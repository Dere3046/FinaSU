//! FolkZygisk provider configuration and authoritative module conflict checks.
//! Engine readiness and injection are separate from next-boot configuration.

use anyhow::{Context, Result, bail, ensure};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::os::fd::AsRawFd;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::Path;

const CONFIG: &str = "/data/adb/ksu/zygisk.json";
const LOCK: &str = "/data/adb/ksu/zygisk.lock";
const ENGINE: &str = "/data/adb/ksu/bin/folkzygisk";
const MODULES: &str = "/data/adb/modules";
const UPDATES: &str = "/data/adb/modules_update";
const RUNTIME: &str = "/data/adb/ksu/.zygisk/state.json";
const BOOT_GUARD: &str = "/data/adb/ksu/zygisk-boot.json";
const FAILURE_LIMIT: u32 = 3;

#[derive(Default, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct BootGuard {
    boot_id: String,
    failures: u32,
    latched: bool,
}

impl BootGuard {
    fn attempt(&mut self, boot: &str) -> bool {
        if self.latched || self.boot_id == boot {
            return false;
        }
        if self.failures >= FAILURE_LIMIT {
            boot.clone_into(&mut self.boot_id);
            self.latched = true;
            return false;
        }
        boot.clone_into(&mut self.boot_id);
        self.failures += 1;
        true
    }
}

fn load_guard(path: &Path) -> Result<BootGuard> {
    match fs::read(path) {
        Ok(bytes) => serde_json::from_slice(&bytes).context("Invalid FolkZygisk boot guard"),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(BootGuard::default()),
        Err(error) => Err(error).context("Read FolkZygisk boot guard"),
    }
}

/// Remove leftover runtime artifacts from a previous boot.
///
/// The runtime directory is persistent, but its contents (the monitor socket, lock and status
/// file) are per-boot. Keep the lock inode and refuse cleanup if a monitor owns it,
/// including a manually started monitor not recorded by the boot guard.
fn clear_stale_runtime(runtime_dir: &Path) -> Result<()> {
    let lock = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(runtime_dir.join("monitor.lock"))?;
    // SAFETY: lock owns a valid descriptor; closing it releases the flock.
    ensure!(
        unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } == 0,
        "A FolkZygisk monitor owns the runtime directory"
    );
    for entry in fs::read_dir(runtime_dir).context("Read FolkZygisk runtime directory")? {
        let entry = entry.context("Read FolkZygisk runtime entry")?;
        if entry.file_name() == "monitor.lock" {
            continue;
        }
        let path = entry.path();
        let result = match entry.file_type() {
            Ok(kind) if kind.is_dir() => fs::remove_dir_all(&path),
            _ => fs::remove_file(&path),
        };
        result
            .with_context(|| format!("Clear stale FolkZygisk runtime entry {}", path.display()))?;
    }
    Ok(())
}

/// The kernel starts `ksud post-fs-data` as an init exec (oneshot) service. When that service
/// exits, init SIGKILLs the service's whole cgroup, which would take the monitor down even though
/// `setsid` already detached it from the service's session and process group. Move the monitor into
/// the root cgroup, which init never tears down, so it survives the post-fs-data teardown.
#[cfg(target_os = "android")]
fn detach_monitor_from_service_cgroup() -> std::io::Result<()> {
    // Called after fork: only stack operations and async-signal-safe libc calls.
    unsafe {
        let fd = libc::open(
            c"/sys/fs/cgroup/cgroup.procs".as_ptr(),
            libc::O_WRONLY | libc::O_CLOEXEC,
        );
        if fd == -1 {
            return Err(std::io::Error::last_os_error());
        }
        let mut pid = libc::getpid() as u32;
        let mut bytes = [0u8; 10];
        let mut offset = bytes.len();
        while pid > 0 {
            offset -= 1;
            bytes[offset] = b'0' + (pid % 10) as u8;
            pid /= 10;
        }
        let size = bytes.len() - offset;
        let written = libc::write(fd, bytes[offset..].as_ptr().cast(), size);
        let error = if written == -1 {
            Some(std::io::Error::last_os_error())
        } else if written as usize != size {
            Some(std::io::Error::from_raw_os_error(libc::EIO))
        } else {
            None
        };
        libc::close(fd);
        error.map_or(Ok(()), Err)
    }
}

/// SELinux rules the built-in engine needs in order to inject. The engine's runtime tree lives
/// under `/data/adb`, whose ancestor directories are labelled `adb_data_file`; without search
/// access the zygote cannot even resolve the path, so the injected `openat` for `libzygisk.so`
/// fails with `EACCES`. The rules mirror what a working external provider ships (e.g. ReZygisk)
/// and define the `zygisk_file` label the daemon applies to its daemon socket. The running policy
/// is reset on every reboot, so this must be applied on each boot before the monitor starts.
/// `unlabeled`/`sys_chroot` cover the daemon's mount-namespace fd (an `nsfs` inode) that the
/// zygote opens to switch into the module-free namespace at a module's request.
#[cfg(target_os = "android")]
const SEPOLICY_RULES: &str = "\
type zygisk_file file_type
typeattribute zygisk_file mlstrustedobject
allow zygote zygisk_file sock_file { read write }
allow zygote adb_data_file dir search
allow zygote adb_data_file file { read open getattr map }
allow zygote ksu dir search
allow zygote ksu file { read open getattr }
allow zygote proc file { read open }
allow zygote unlabeled file { read open }
allow zygote zygote capability sys_chroot
allow zygote adb_data_file lnk_file read
allow zygote ksu lnk_file read
allow zygote system_file file { read open getattr map }
allow zygote system_file lnk_file read
allow zygote system_lib_file file { read open execute execmod map }
allow zygote tmpfs file *
allow zygote appdomain_tmpfs file *
allow zygote zygote process execmem
allow system_server system_server process execmem
";

#[cfg(target_os = "android")]
fn apply_sepolicy_rules() -> Result<()> {
    crate::sepolicy::live_patch(SEPOLICY_RULES).context("Apply FolkZygisk SELinux policy rules")
}

#[cfg(target_os = "android")]
pub fn post_fs_data(safe_mode: bool) -> Result<()> {
    use std::os::unix::fs::MetadataExt;
    use std::os::unix::process::CommandExt;
    use std::process::{Command as ProcessCommand, Stdio};
    use std::time::{Duration, Instant};
    if safe_mode || crate::utils::is_safe_mode() || crate::utils::has_magisk() {
        return Ok(());
    }
    let _lock = lock()?;
    if !read_config(Path::new(CONFIG))?.enabled {
        return Ok(());
    }
    ensure!(
        providers(Path::new(MODULES), Path::new(UPDATES))?.is_empty(),
        "FOLKZYGISK_CONFLICT: external provider active; skip built-in startup"
    );
    let boot = fs::read_to_string("/proc/sys/kernel/random/boot_id")?;
    let mut guard = load_guard(Path::new(BOOT_GUARD))?;
    let attempt = guard.attempt(boot.trim());
    write_json(Path::new(BOOT_GUARD), &guard)?;
    if !attempt {
        return Ok(());
    }
    ensure!(
        engine_available(Path::new(ENGINE)),
        "FolkZygisk engine missing"
    );
    // The zygote must be able to search the engine's parent directories and read the injected
    // library and daemon socket; without these rules the injected remote `openat` fails with EACCES.
    if let Err(e) = apply_sepolicy_rules() {
        log::warn!("FolkZygisk sepolicy rules failed to apply: {e}");
    }
    let runtime_dir = Path::new(RUNTIME).parent().context("Runtime directory")?;
    fs::create_dir_all(runtime_dir)?;
    let metadata = fs::symlink_metadata(runtime_dir)?;
    ensure!(
        metadata.is_dir() && metadata.uid() == 0,
        "Unsafe FolkZygisk runtime directory"
    );
    // Zygisk clients need socket traversal after specialization, not listing.
    fs::set_permissions(runtime_dir, fs::Permissions::from_mode(0o711))?;
    crate::restorecon::setsyscon(runtime_dir)?;
    clear_stale_runtime(runtime_dir)?;
    let executable = if cfg!(target_pointer_width = "64") {
        "bin/zygisk-ptrace64"
    } else {
        "bin/zygisk-ptrace32"
    };
    let mut command = ProcessCommand::new(Path::new(ENGINE).join(executable));
    command
        .arg("monitor")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    // SAFETY: `pre_exec` runs in the forked child before `exec`. `setsid` is async-signal-safe and
    // gives the monitor its own session. Move it out of init's exec-service cgroup
    // before exec, so any daemon children inherit the surviving cgroup too.
    unsafe {
        command.pre_exec(|| {
            if libc::setsid() == -1 {
                return Err(std::io::Error::last_os_error());
            }
            detach_monitor_from_service_cgroup()
        });
    }
    let mut child = command.spawn()?;
    let deadline = Instant::now() + Duration::from_secs(2);
    let result = loop {
        match child.try_wait() {
            Ok(Some(_)) => {
                break Err(anyhow::anyhow!(
                    "FolkZygisk monitor exited before readiness"
                ));
            }
            Err(error) => break Err(error.into()),
            Ok(None) => {}
        }
        match read_runtime(Path::new(RUNTIME), Path::new("/proc")) {
            Ok(Some(runtime))
                if runtime.monitor_pid == child.id() as i32
                    && runtime.ready
                    && runtime.tracing_state == 0 =>
            {
                break Ok(());
            }
            Err(error) => log::debug!("FolkZygisk awaiting valid status: {error:#}"),
            _ => {}
        }
        if Instant::now() >= deadline {
            break Err(anyhow::anyhow!("FolkZygisk readiness timeout"));
        }
        std::thread::sleep(Duration::from_millis(20));
    };
    if result.is_err() {
        let _ = child.kill();
        let _ = child.wait();
    }
    result
}

#[cfg(target_os = "android")]
pub fn services() -> Result<()> {
    if crate::utils::is_safe_mode() || !read_config(Path::new(CONFIG))?.enabled {
        return Ok(());
    }
    let runtime = read_runtime(Path::new(RUNTIME), Path::new("/proc"))?;
    log::info!(
        "FolkZygisk service health: {}",
        injection_state(runtime.as_ref())
    );
    Ok(())
}

#[cfg(target_os = "android")]
pub fn boot_completed() -> Result<()> {
    if crate::utils::is_safe_mode() {
        return Ok(());
    }
    let _lock = lock()?;
    let runtime = read_runtime(Path::new(RUNTIME), Path::new("/proc"))?;
    // Android boot completion alone does not prove the engine is healthy.
    if injection_state(runtime.as_ref()) != "injected" {
        return Ok(());
    }
    let mut guard = load_guard(Path::new(BOOT_GUARD))?;
    if runtime
        .as_ref()
        .is_some_and(|status| status.boot_id == guard.boot_id)
    {
        guard.failures = 0;
        write_json(Path::new(BOOT_GUARD), &guard)?;
    }
    Ok(())
}

#[derive(Clone, Debug, clap::Subcommand)]
pub enum Command {
    /// Show next-boot selection and validated current-boot injection status
    Status {
        #[arg(long)]
        json: bool,
    },
    /// Enable FolkZygisk for the next boot; refuses active external providers
    Enable {
        /// Explicitly disable recognized external providers before selecting built-in
        #[arg(long)]
        disable_external: bool,
        /// Exact external IDs shown in the confirmation dialog
        #[arg(long)]
        expected_external: Vec<String>,
    },
    /// Disable FolkZygisk for the next boot
    Disable,
    /// Clear the startup failure latch without enabling or starting injection
    ResetFailures,
    /// Install embedded engine assets without enabling or starting injection
    InstallEngine,
    /// Inspect an entire ZIP batch before any installer is executed
    Preflight {
        #[arg(required = true)]
        archives: Vec<String>,
    },
    /// Install a preflighted batch under one provider/configuration lock
    InstallBatch {
        #[arg(long)]
        expected_state: String,
        #[arg(long)]
        switch_external: bool,
        #[arg(required = true)]
        archives: Vec<String>,
    },
    /// Explicitly select an installed external provider for the next boot
    SwitchExternal {
        id: String,
        #[arg(long)]
        undo_uninstall: bool,
    },
}

#[derive(Serialize)]
struct PreflightStatus {
    schema_version: u32,
    builtin_enabled: bool,
    provider_ids: Vec<String>,
    conflict: bool,
    external_providers: Vec<String>,
    blocked: bool,
    state_token: String,
}

fn preflight(enabled: bool, ids: &[String], external: &[String]) -> PreflightStatus {
    let provider_ids: Vec<_> = ids
        .iter()
        .filter(|id| is_provider(id))
        .cloned()
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect();
    PreflightStatus {
        schema_version: 2,
        builtin_enabled: enabled,
        conflict: enabled && !provider_ids.is_empty(),
        blocked: provider_ids
            .iter()
            .chain(external)
            .collect::<BTreeSet<_>>()
            .len()
            > 1
            || ids.iter().filter(|id| is_provider(id)).count() > 1,
        state_token: selection_token(enabled, external),
        external_providers: external.to_vec(),
        provider_ids,
    }
}

fn selection_token(enabled: bool, external: &[String]) -> String {
    let mut identity = vec![u8::from(enabled)];
    for id in external {
        identity.extend_from_slice(id.as_bytes());
        identity.push(0);
    }
    sha256::digest(&identity)
}

fn check_confirmed_providers(expected: &[String], actual: &[String]) -> Result<()> {
    let expected: BTreeSet<_> = expected.iter().collect();
    let actual: BTreeSet<_> = actual.iter().collect();
    ensure!(
        expected == actual,
        "FOLKZYGISK_STATE_CHANGED: external providers changed; confirm again"
    );
    Ok(())
}

fn archive_id(archive: &Path) -> Result<String> {
    use java_properties::PropertiesIter;
    use std::io::Cursor;
    use zip_extensions::inflate::zip_extract::zip_extract_file_to_memory;
    let mut bytes = Vec::new();
    zip_extract_file_to_memory(
        &archive.canonicalize()?,
        &std::path::PathBuf::from("module.prop"),
        &mut bytes,
    )?;
    let mut properties = std::collections::HashMap::new();
    PropertiesIter::new_with_encoding(Cursor::new(bytes), encoding_rs::UTF_8).read_into(
        |key, value| {
            properties.insert(key, value);
        },
    )?;
    let id = properties
        .get("id")
        .context("Module ID missing from module.prop")?
        .trim()
        .to_owned();
    #[cfg(target_os = "android")]
    crate::module::validate_module_id(&id)?;
    ensure!(!id.is_empty(), "Empty module ID");
    Ok(id)
}

#[derive(Default, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Config {
    enabled: bool,
}

#[derive(Serialize)]
struct Status {
    schema_version: u32,
    enabled: bool,
    engine_available: bool,
    enable_blockers: Vec<&'static str>,
    external_providers: Vec<String>,
    // Injection requires a current-boot acknowledgement and matching processes.
    injection_state: &'static str,
    // Unknown until boot integration records the effective provider selection.
    reboot_required: Option<bool>,
    runtime: Option<RuntimeStatus>,
    startup_failures: u32,
    startup_blocked: bool,
}

fn enable_blockers(engine: bool, external: bool, blocked: bool) -> Vec<&'static str> {
    let mut blockers = Vec::new();
    if !engine {
        blockers.push("engine_missing");
    }
    if external {
        blockers.push("external_provider_conflict");
    }
    if blocked {
        blockers.push("startup_protection");
    }
    blockers
}

#[derive(Debug, Deserialize, Serialize)]
struct AbiStatus {
    supported: bool,
    daemon_running: bool,
    daemon_pid: i32,
    daemon_start_ticks: u64,
    zygote_pid: i32,
    zygote_start_ticks: u64,
    injected: bool,
    reason: String,
}

#[derive(Debug, Deserialize, Serialize)]
struct RuntimeStatus {
    schema_version: u32,
    boot_id: String,
    monitor_pid: i32,
    monitor_start_ticks: u64,
    ready: bool,
    tracing_state: u32,
    reason: String,
    abis: std::collections::BTreeMap<String, AbiStatus>,
}

fn start_ticks(stat: &str) -> Option<u64> {
    // comm can contain spaces and parentheses; field 3 follows its final ')'.
    stat.rsplit_once(')')?
        .1
        .split_whitespace()
        .nth(19)?
        .parse()
        .ok()
}

fn live_start_ticks(stat: &str) -> Option<u64> {
    let state = stat.rsplit_once(')')?.1.split_whitespace().next()?;
    // A zombie retains its PID/start time but cannot serve injection requests.
    if matches!(state, "Z" | "X" | "x") {
        return None;
    }
    start_ticks(stat)
}

fn same_process(proc: &Path, pid: i32, ticks: u64) -> bool {
    pid > 0
        && ticks > 0
        && fs::read_to_string(proc.join(pid.to_string()).join("stat"))
            .ok()
            .and_then(|stat| live_start_ticks(&stat))
            == Some(ticks)
}

fn read_runtime(path: &Path, proc: &Path) -> Result<Option<RuntimeStatus>> {
    let bytes = match fs::read(path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error).context("Read FolkZygisk runtime status"),
    };
    let mut runtime: RuntimeStatus =
        serde_json::from_slice(&bytes).context("Invalid FolkZygisk runtime status")?;
    ensure!(
        runtime.schema_version == 1,
        "Unsupported FolkZygisk status schema"
    );
    let boot = fs::read_to_string(proc.join("sys/kernel/random/boot_id"))?;
    if runtime.boot_id != boot.trim()
        || !same_process(proc, runtime.monitor_pid, runtime.monitor_start_ticks)
    {
        return Ok(None);
    }
    ensure!(
        runtime.abis.len() == 2
            && runtime.abis.contains_key("64")
            && runtime.abis.contains_key("32"),
        "Invalid FolkZygisk ABI status"
    );
    for abi in runtime.abis.values_mut() {
        abi.daemon_running &= same_process(proc, abi.daemon_pid, abi.daemon_start_ticks);
        abi.injected &= abi.supported
            && abi.daemon_running
            && same_process(proc, abi.zygote_pid, abi.zygote_start_ticks);
    }
    Ok(Some(runtime))
}

fn injection_state(runtime: Option<&RuntimeStatus>) -> &'static str {
    let Some(runtime) = runtime else {
        return "unverified";
    };
    if !runtime.ready || runtime.tracing_state != 0 {
        return "failed";
    }
    let supported: Vec<_> = runtime.abis.values().filter(|abi| abi.supported).collect();
    if supported.is_empty() {
        return "starting";
    }
    if supported.iter().all(|abi| abi.injected) {
        return "injected";
    }
    if supported.iter().any(|abi| abi.injected) {
        return "partial";
    }
    if supported.iter().any(|abi| !abi.daemon_running) {
        return "failed";
    }
    "starting"
}

/// Recognize provider packages, not modules which merely use the Zygisk API.
pub fn is_provider(id: &str) -> bool {
    // Zygisk Next, NeoZygisk and HSSkyBoy/NyaZygisk share the module ID zygisksu.
    matches!(id, "zygisksu" | "rezygisk" | "onyxzygisk")
}

fn read_config(path: &Path) -> Result<Config> {
    match fs::read(path) {
        Ok(bytes) => serde_json::from_slice(&bytes).context("Invalid FolkZygisk configuration"),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(Config::default()),
        Err(error) => Err(error).context("Read FolkZygisk configuration"),
    }
}

fn active(path: &Path) -> bool {
    path.is_dir() && !path.join("disable").exists() && !path.join("remove").exists()
}

fn providers(modules: &Path, updates: &Path) -> Result<Vec<String>> {
    let mut ids = BTreeSet::new();
    for root in [modules, updates] {
        let entries = match fs::read_dir(root) {
            Ok(entries) => entries,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error).context("Discover Zygisk providers"),
        };
        for entry in entries {
            let entry = entry?;
            let Some(id) = entry.file_name().to_str().map(str::to_owned) else {
                continue;
            };
            if !is_provider(&id) {
                continue;
            }
            let installed = modules.join(&id);
            let pending = updates.join(&id);
            // Boot promotion inherits these markers from the installed copy.
            if installed.join("disable").exists() || installed.join("remove").exists() {
                continue;
            }
            let effective = if pending.is_dir() { pending } else { installed };
            if active(&effective) {
                ids.insert(id);
            }
        }
    }
    Ok(ids.into_iter().collect())
}

/// Hold across the full operation, including installer execution and rc refresh.
/// flock is released by closing the descriptor, including on error/unwind.
pub fn lock() -> Result<File> {
    fs::create_dir_all("/data/adb/ksu")?;
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .open(LOCK)?;
    // SAFETY: file owns a valid descriptor for the duration of this call.
    if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
        return Err(std::io::Error::last_os_error())
            .context("Zygisk configuration/module operation busy; retry after it finishes");
    }
    Ok(file)
}

/// Called before extracting a provider ZIP, removing disable, or undoing removal.
pub fn check_activation(id: &str) -> Result<()> {
    if !is_provider(id) {
        return Ok(());
    }
    if read_config(Path::new(CONFIG))?.enabled {
        bail!(
            "FOLKZYGISK_CONFLICT: {id} cannot be installed or activated while FolkZygisk is enabled; disable FolkZygisk first (changes apply on reboot)"
        );
    }
    ensure_single_external(id, &providers(Path::new(MODULES), Path::new(UPDATES))?)?;
    Ok(())
}

fn ensure_single_external(id: &str, external: &[String]) -> Result<()> {
    ensure!(
        external.iter().all(|provider| provider == id),
        "FOLKZYGISK_EXTERNAL_CONFLICT: other external providers are active: {}",
        external.join(", ")
    );
    Ok(())
}

/// Clear only the requested marker on both boot-promotion copies. Roll back on rc failure.
pub fn clear_activation_markers(
    installed: &Path,
    pending: &Path,
    undo_uninstall: bool,
    mut refresh: impl FnMut() -> Result<()>,
) -> Result<()> {
    let marker = if undo_uninstall { "remove" } else { "disable" };
    let mut previous = Vec::new();
    for directory in [installed, pending] {
        let path = directory.join(marker);
        match fs::read(&path) {
            Ok(bytes) => previous.push((path, bytes)),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
    }
    let result = (|| {
        for (path, _) in &previous {
            fs::remove_file(path)?;
        }
        refresh()
    })();
    if let Err(error) = result {
        for (path, bytes) in previous {
            fs::write(path, bytes).context("Restore activation marker")?;
        }
        refresh().context("Restore activation rc")?;
        return Err(error);
    }
    Ok(())
}

fn engine_available(root: &Path) -> bool {
    let (monitor, daemon, library) = if cfg!(target_pointer_width = "64") {
        ("bin/zygisk-ptrace64", "bin/zygiskd64", "lib64/libzygisk.so")
    } else {
        ("bin/zygisk-ptrace32", "bin/zygiskd32", "lib/libzygisk.so")
    };
    [monitor, daemon].iter().all(|name| {
        fs::metadata(root.join(name))
            .is_ok_and(|metadata| metadata.is_file() && metadata.permissions().mode() & 0o111 != 0)
    }) && root.join(library).is_file()
}

fn write_config(path: &Path, config: &Config) -> Result<()> {
    write_json(path, config)
}

/// Caller holds the shared operation lock. Never execute external installers.
fn select_builtin(
    config: &Path,
    modules: &Path,
    updates: &Path,
    external: &[String],
    mut refresh_rc: impl FnMut() -> Result<()>,
) -> Result<()> {
    let previous = read_config(config)?;
    let mut created = Vec::new();
    let result = (|| {
        for id in external {
            ensure!(is_provider(id), "Unrecognized Zygisk provider");
            for root in [modules, updates] {
                let directory = root.join(id);
                if directory.is_dir() && !directory.join("disable").exists() {
                    let path = directory.join("disable");
                    File::options()
                        .write(true)
                        .create_new(true)
                        .mode(0o600)
                        .open(&path)?
                        .sync_all()?;
                    created.push(path);
                }
            }
        }
        refresh_rc()?;
        write_config(config, &Config { enabled: true })
    })();
    if let Err(error) = result {
        for path in created {
            fs::remove_file(path).context("Restore external provider marker")?;
        }
        write_config(config, &previous).context("Restore FolkZygisk selection")?;
        refresh_rc().context("Restore external provider init rc")?;
        return Err(error);
    }
    Ok(())
}

/// Caller holds the provider lock. Restore selection on ordinary operation errors.
/// This is not crash recovery and never changes the running zygote.
fn switch_external(config: &Path, id: &str, activate: impl FnOnce() -> Result<()>) -> Result<()> {
    ensure!(
        is_provider(id),
        "Not a recognized external Zygisk provider: {id}"
    );
    let previous = read_config(config)?;
    write_config(config, &Config { enabled: false })?;
    if let Err(error) = activate() {
        write_config(config, &previous)
            .context("Restore provider selection after activation failure")?;
        return Err(error);
    }
    Ok(())
}

/// Restore built-in selection only after a failed installer's provider copies are quarantined.
fn install_external(
    config: &Path,
    modules: &Path,
    updates: &Path,
    id: &str,
    install: impl FnOnce() -> Result<()>,
    mut refresh: impl FnMut() -> Result<()>,
) -> Result<()> {
    let previous = read_config(config)?;
    for root in [modules, updates] {
        ensure!(
            !root.join(id).join("remove").exists(),
            "Provider is marked for removal; undo removal before installing"
        );
    }
    write_config(config, &Config { enabled: false })?;
    let result = install().and_then(|()| {
        clear_activation_markers(&modules.join(id), &updates.join(id), false, &mut refresh)?;
        ensure_single_external(id, &providers(modules, updates)?)
    });
    if let Err(error) = result {
        for root in [modules, updates] {
            let path = root.join(id);
            if path.is_dir() {
                File::create(path.join("disable"))?.sync_all()?;
            }
        }
        refresh()?;
        ensure!(
            providers(modules, updates)?.is_empty(),
            "Provider quarantine failed; built-in remains disabled"
        );
        write_config(config, &previous)?;
        return Err(error);
    }
    Ok(())
}

fn write_json(path: &Path, value: &impl Serialize) -> Result<()> {
    let parent = path.parent().context("Missing configuration directory")?;
    fs::create_dir_all(parent)?;
    let mut file = tempfile::NamedTempFile::new_in(parent)?;
    file.as_file()
        .set_permissions(fs::Permissions::from_mode(0o600))?;
    serde_json::to_writer(&mut file, value)?;
    file.write_all(b"\n")?;
    file.as_file().sync_all()?;
    file.persist(path)
        .context("Persist FolkZygisk configuration")?;
    File::open(parent)?.sync_all()?;
    Ok(())
}

pub fn run(command: Command) -> Result<()> {
    let _lock = lock()?;
    match command {
        Command::SwitchExternal { id, undo_uninstall } => {
            #[cfg(target_os = "android")]
            {
                crate::module::validate_module_id(&id)?;
                let external = providers(Path::new(MODULES), Path::new(UPDATES))?;
                ensure_single_external(&id, &external)?;
                switch_external(Path::new(CONFIG), &id, || {
                    crate::module::activate_external_provider_locked(&id, undo_uninstall)
                })?;
                println!("External provider {id} selected for the next boot; reboot required");
            }
            #[cfg(not(target_os = "android"))]
            {
                let _ = (id, undo_uninstall);
                bail!("External provider activation requires Android");
            }
        }
        Command::Preflight { archives } => {
            let ids = archives
                .iter()
                .map(|archive| archive_id(Path::new(archive)))
                .collect::<Result<Vec<_>>>()?;
            let status = preflight(
                read_config(Path::new(CONFIG))?.enabled,
                &ids,
                &providers(Path::new(MODULES), Path::new(UPDATES))?,
            );
            println!("{}", serde_json::to_string(&status)?);
        }
        Command::InstallBatch {
            expected_state,
            switch_external: confirmed,
            archives,
        } => {
            let ids = archives
                .iter()
                .map(|archive| archive_id(Path::new(archive)))
                .collect::<Result<Vec<_>>>()?;
            let config = read_config(Path::new(CONFIG))?;
            let status = preflight(
                config.enabled,
                &ids,
                &providers(Path::new(MODULES), Path::new(UPDATES))?,
            );
            ensure!(
                status.state_token == expected_state,
                "FOLKZYGISK_STATE_CHANGED: preflight again"
            );
            ensure!(
                !status.blocked,
                "FOLKZYGISK_EXTERNAL_CONFLICT: multiple external providers"
            );
            ensure!(
                ids.iter().filter(|id| is_provider(id)).count() <= 1,
                "FOLKZYGISK_EXTERNAL_CONFLICT: choose only one provider archive per batch"
            );
            ensure!(
                !status.conflict || confirmed,
                "FOLKZYGISK_CONFLICT: confirmation required"
            );
            #[cfg(target_os = "android")]
            {
                // Ordinary modules first: a later unrelated failure cannot leave a
                // successfully selected provider with restored built-in configuration.
                let mut batch: Vec<_> = archives.iter().zip(&ids).collect();
                batch.sort_by_key(|(_, id)| is_provider(id));
                for (archive, id) in batch {
                    let install = || crate::module::install_module_locked(archive);
                    if is_provider(id) && config.enabled {
                        install_external(
                            Path::new(CONFIG),
                            Path::new(MODULES),
                            Path::new(UPDATES),
                            id,
                            install,
                            crate::module::regenerate_preinit_rc,
                        )?;
                    } else {
                        install()?;
                    }
                    println!("FOLKZYGISK_BATCH_CHANGED");
                }
            }
            #[cfg(not(target_os = "android"))]
            bail!("Batch installation requires Android");
        }
        Command::Status { json } => {
            let runtime = read_runtime(Path::new(RUNTIME), Path::new("/proc"))?;
            let guard = load_guard(Path::new(BOOT_GUARD))?;
            let engine = engine_available(Path::new(ENGINE));
            let external = providers(Path::new(MODULES), Path::new(UPDATES))?;
            let blocked = guard.latched || guard.failures >= FAILURE_LIMIT;
            let blockers = enable_blockers(engine, !external.is_empty(), blocked);
            let status = Status {
                schema_version: 1,
                enabled: read_config(Path::new(CONFIG))?.enabled,
                engine_available: engine,
                enable_blockers: blockers,
                external_providers: external,
                injection_state: injection_state(runtime.as_ref()),
                reboot_required: None,
                runtime,
                startup_failures: guard.failures,
                startup_blocked: blocked,
            };
            if json {
                println!("{}", serde_json::to_string(&status)?);
            } else {
                println!("{}", serde_json::to_string_pretty(&status)?);
            }
        }
        Command::Enable {
            disable_external,
            expected_external,
        } => {
            let external = providers(Path::new(MODULES), Path::new(UPDATES))?;
            if disable_external {
                check_confirmed_providers(&expected_external, &external)?;
            }
            ensure!(
                disable_external || external.is_empty(),
                "FOLKZYGISK_CONFLICT: {}",
                external.join(", ")
            );
            let guard = load_guard(Path::new(BOOT_GUARD))?;
            let engine = engine_available(Path::new(ENGINE));
            let blocked = guard.latched || guard.failures >= FAILURE_LIMIT;
            let blockers = enable_blockers(engine, false, blocked);
            ensure!(
                blockers.is_empty(),
                "FOLKZYGISK_UNAVAILABLE: {}",
                blockers.join(", ")
            );
            select_builtin(
                Path::new(CONFIG),
                Path::new(MODULES),
                Path::new(UPDATES),
                &external,
                || {
                    #[cfg(target_os = "android")]
                    crate::module::regenerate_preinit_rc()?;
                    Ok(())
                },
            )?;
            println!("FolkZygisk enabled for the next boot; reboot required");
        }
        Command::Disable => {
            write_config(Path::new(CONFIG), &Config { enabled: false })?;
            println!("FolkZygisk disabled for the next boot");
        }
        Command::ResetFailures => {
            let mut guard = load_guard(Path::new(BOOT_GUARD))?;
            guard.failures = 0;
            guard.latched = false;
            // Recovery never permits another attempt during the current boot.
            let boot = fs::read_to_string("/proc/sys/kernel/random/boot_id")?;
            boot.trim().clone_into(&mut guard.boot_id);
            write_json(Path::new(BOOT_GUARD), &guard)?;
            println!("FolkZygisk startup protection reset for the next boot");
        }
        Command::InstallEngine => {
            ensure!(
                read_runtime(Path::new(RUNTIME), Path::new("/proc"))?.is_none(),
                "Cannot update a live FolkZygisk engine; disable and reboot first"
            );
            let boot = fs::read_to_string("/proc/sys/kernel/random/boot_id")?;
            ensure!(
                load_guard(Path::new(BOOT_GUARD))?.boot_id != boot.trim(),
                "Engine startup was attempted this boot; reboot before installing"
            );
            let files = crate::zygisk_assets::FILES
                .iter()
                .map(|name| {
                    let bytes = crate::assets::get_asset_data(&format!("folkzygisk/{name}"))?;
                    Ok(((*name).to_owned(), bytes.into_owned()))
                })
                .collect::<Result<Vec<_>>>()?;
            let version = crate::zygisk_assets::install(
                Path::new(ENGINE).parent().context("Engine parent")?,
                &files,
                |path| {
                    #[cfg(target_os = "android")]
                    crate::restorecon::restore_syscon(path)?;
                    #[cfg(not(target_os = "android"))]
                    let _ = path;
                    Ok(())
                },
            )?;
            println!(
                "FolkZygisk engine installed: {version}; provider selection unchanged, no injection started"
            );
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn confirmation_cannot_disable_an_unseen_provider() -> anyhow::Result<()> {
        super::check_confirmed_providers(&["rezygisk".into()], &["rezygisk".into()])?;
        assert!(
            super::check_confirmed_providers(
                &["rezygisk".into()],
                &["onyxzygisk".into(), "rezygisk".into()]
            )
            .is_err()
        );
        assert!(super::check_confirmed_providers(&["rezygisk".into()], &[]).is_err());
        assert!(super::check_confirmed_providers(&[], &["rezygisk".into()]).is_err());
        Ok(())
    }
    #[test]
    fn failed_provider_install_is_quarantined_before_selection_restore() -> anyhow::Result<()> {
        let dir = tempfile::tempdir()?;
        let config = dir.path().join("config.json");
        let modules = dir.path().join("modules");
        let updates = dir.path().join("updates");
        super::write_config(&config, &super::Config { enabled: true })?;
        let result = super::install_external(
            &config,
            &modules,
            &updates,
            "rezygisk",
            || {
                std::fs::create_dir_all(updates.join("rezygisk"))?;
                anyhow::bail!("installer failed after extraction")
            },
            || Ok(()),
        );
        assert!(result.is_err());
        assert!(super::read_config(&config)?.enabled);
        assert!(updates.join("rezygisk/disable").exists());
        assert!(super::providers(&modules, &updates)?.is_empty());
        super::install_external(
            &config,
            &modules,
            &updates,
            "rezygisk",
            || {
                std::fs::create_dir_all(modules.join("rezygisk"))?;
                Ok(())
            },
            || Ok(()),
        )?;
        assert!(!super::read_config(&config)?.enabled);
        assert!(!updates.join("rezygisk/disable").exists());
        assert_eq!(super::providers(&modules, &updates)?, vec!["rezygisk"]);
        // Fail closed: never restore the built-in if quarantine/rc regeneration fails.
        super::write_config(&config, &super::Config { enabled: true })?;
        assert!(
            super::install_external(
                &config,
                &modules,
                &updates,
                "rezygisk",
                || anyhow::bail!("installer failed"),
                || anyhow::bail!("rc failed")
            )
            .is_err()
        );
        assert!(!super::read_config(&config)?.enabled);
        Ok(())
    }
    #[test]
    fn preflight_blocks_multiple_providers_and_tracks_selection() {
        let ids = vec!["rezygisk".to_owned()];
        let one = super::preflight(true, &ids, &[]);
        assert!(!one.blocked);
        assert!(one.conflict);
        assert!(super::preflight(false, &ids, &["onyxzygisk".to_owned()]).blocked);
        assert!(super::preflight(false, &["rezygisk".into(), "rezygisk".into()], &[]).blocked);
        assert!(!super::preflight(false, &ids, &ids).blocked);
        assert_ne!(
            one.state_token,
            super::preflight(false, &ids, &[]).state_token
        );
        assert_ne!(
            one.state_token,
            super::preflight(true, &ids, &ids).state_token
        );
        assert!(super::ensure_single_external("rezygisk", &["onyxzygisk".into()]).is_err());
        assert!(super::ensure_single_external("rezygisk", &ids).is_ok());
    }

    #[test]
    fn pending_activation_markers_clear_together_and_restore_on_failure() -> anyhow::Result<()> {
        let dir = tempfile::tempdir()?;
        let installed = dir.path().join("installed");
        let pending = dir.path().join("pending");
        std::fs::create_dir_all(&installed)?;
        std::fs::create_dir_all(&pending)?;
        for name in ["disable", "remove"] {
            for path in [&installed, &pending] {
                std::fs::write(path.join(name), b"original")?;
            }
            let mut calls = 0;
            assert!(
                super::clear_activation_markers(&installed, &pending, name == "remove", || {
                    calls += 1;
                    if calls == 1 {
                        anyhow::bail!("rc failure");
                    }
                    Ok(())
                })
                .is_err()
            );
            for path in [&installed, &pending] {
                assert_eq!(std::fs::read(path.join(name))?, b"original");
            }
            super::clear_activation_markers(&installed, &pending, name == "remove", || Ok(()))?;
            for path in [&installed, &pending] {
                assert!(!path.join(name).exists());
            }
        }
        Ok(())
    }
    #[test]
    fn builtin_selection_disables_pending_providers_and_rolls_back() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let modules = dir.path().join("modules");
        let updates = dir.path().join("updates");
        for root in [&modules, &updates] {
            fs::create_dir_all(root.join("rezygisk"))?;
        }
        let config = dir.path().join("config.json");
        let external = vec!["rezygisk".to_owned()];
        let mut calls = 0;
        assert!(
            select_builtin(&config, &modules, &updates, &external, || {
                calls += 1;
                if calls == 1 {
                    bail!("rc failed")
                }
                Ok(())
            })
            .is_err()
        );
        assert!(!modules.join("rezygisk/disable").exists());
        assert!(!read_config(&config)?.enabled);
        select_builtin(&config, &modules, &updates, &external, || Ok(()))?;
        assert!(read_config(&config)?.enabled);
        assert!(providers(&modules, &updates)?.is_empty());
        Ok(())
    }

    #[test]
    fn runtime_cleanup_refuses_live_monitor_lock() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let owner = File::create(dir.path().join("monitor.lock"))?;
        assert_eq!(
            unsafe { libc::flock(owner.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) },
            0
        );
        fs::write(dir.path().join("state.json"), "keep")?;
        assert!(clear_stale_runtime(dir.path()).is_err());
        assert!(dir.path().join("state.json").exists());
        Ok(())
    }
    #[test]
    fn external_switch_restores_selection_on_failure() {
        let dir = tempfile::tempdir().unwrap();
        let config = dir.path().join("config.json");
        super::write_config(&config, &super::Config { enabled: true }).unwrap();
        assert!(
            super::switch_external(&config, "rezygisk", || anyhow::bail!("activation failed"))
                .is_err()
        );
        assert!(super::read_config(&config).unwrap().enabled);
        assert!(super::switch_external(&config, "plugin", || Ok(())).is_err());
        assert!(super::read_config(&config).unwrap().enabled);
        super::switch_external(&config, "rezygisk", || {
            assert!(!super::read_config(&config)?.enabled);
            Ok(())
        })
        .unwrap();
        assert!(!super::read_config(&config).unwrap().enabled);
    }
    #[test]
    fn archive_preflight_reads_root_properties_and_rejects_missing_id() {
        use std::io::Write;
        let file = tempfile::NamedTempFile::new().unwrap();
        let mut zip = zip::ZipWriter::new(std::fs::File::create(file.path()).unwrap());
        zip.start_file("module.prop", zip::write::SimpleFileOptions::default())
            .unwrap();
        zip.write_all(b"id = rezygisk\nname=ReZygisk\n").unwrap();
        zip.finish().unwrap();
        assert_eq!(super::archive_id(file.path()).unwrap(), "rezygisk");
        let mut zip = zip::ZipWriter::new(std::fs::File::create(file.path()).unwrap());
        zip.start_file("module.prop", zip::write::SimpleFileOptions::default())
            .unwrap();
        zip.write_all(b"name=No ID\n").unwrap();
        zip.finish().unwrap();
        assert!(super::archive_id(file.path()).is_err());
    }
    #[test]
    fn batch_preflight_recognizes_only_providers_without_mutating_selection() {
        let ids = vec![
            "lsposed".to_owned(),
            "rezygisk".to_owned(),
            "onyxzygisk".to_owned(),
            "rezygisk".to_owned(),
        ];
        let status = super::preflight(true, &ids, &[]);
        assert!(status.conflict);
        assert_eq!(status.provider_ids, vec!["onyxzygisk", "rezygisk"]);
        assert!(!super::preflight(false, &ids, &[]).conflict);
        assert!(!super::preflight(true, &["lsposed".to_owned()], &[]).conflict);
    }
    use super::*;

    #[test]
    fn activation_keeps_operational_protection() {
        assert!(enable_blockers(true, false, false).is_empty());
        assert_eq!(
            enable_blockers(false, true, true),
            vec![
                "engine_missing",
                "external_provider_conflict",
                "startup_protection"
            ]
        );
    }

    #[test]
    fn boot_guard_counts_once_and_latches_after_three_failed_boots() -> Result<()> {
        let mut guard = BootGuard::default();
        for boot in ["one", "two", "three"] {
            assert!(guard.attempt(boot));
            let count = guard.failures;
            assert!(!guard.attempt(boot));
            assert_eq!(guard.failures, count);
        }
        assert!(!guard.attempt("four"));
        assert!(guard.latched);
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("guard.json");
        write_json(&path, &guard)?;
        assert!(load_guard(&path)?.latched);
        fs::write(&path, "broken")?;
        assert!(load_guard(&path).is_err());
        Ok(())
    }

    fn fake_process(proc: &Path, pid: i32, ticks: u64) -> Result<()> {
        fs::create_dir_all(proc.join(pid.to_string()))?;
        let fields = std::iter::once("S")
            .chain(std::iter::repeat_n("0", 18))
            .collect::<Vec<_>>()
            .join(" ");
        fs::write(
            proc.join(pid.to_string()).join("stat"),
            format!("{pid} (zygote (worker)) {fields} {ticks} 0\n"),
        )?;
        Ok(())
    }

    #[test]
    fn runtime_requires_current_boot_and_process_identity() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let proc = dir.path().join("proc");
        fs::create_dir_all(proc.join("sys/kernel/random"))?;
        fs::write(proc.join("sys/kernel/random/boot_id"), "current\n")?;
        fake_process(&proc, 10, 100)?;
        fake_process(&proc, 20, 200)?;
        fake_process(&proc, 30, 300)?;
        let path = dir.path().join("state.json");
        let mut value = serde_json::json!({
            "schema_version": 1, "boot_id": "current", "monitor_pid": 10,
            "monitor_start_ticks": 100, "ready": true, "tracing_state": 0, "reason": "",
            "abis": {
                "64": {"supported": true, "daemon_running": true, "daemon_pid": 20,
                    "daemon_start_ticks": 200, "zygote_pid": 30, "zygote_start_ticks": 300,
                    "injected": true, "reason": ""},
                "32": {"supported": false, "daemon_running": false, "daemon_pid": -1,
                    "daemon_start_ticks": 0, "zygote_pid": 0, "zygote_start_ticks": 0,
                    "injected": false, "reason": ""}
            }
        });
        fs::write(&path, serde_json::to_vec(&value)?)?;
        let runtime = read_runtime(&path, &proc)?;
        assert_eq!(injection_state(runtime.as_ref()), "injected");
        fake_process(&proc, 30, 301)?;
        assert_eq!(
            injection_state(read_runtime(&path, &proc)?.as_ref()),
            "starting"
        );
        fs::remove_file(proc.join("20/stat"))?;
        assert_eq!(
            injection_state(read_runtime(&path, &proc)?.as_ref()),
            "failed"
        );
        value["boot_id"] = serde_json::json!("old");
        fs::write(&path, serde_json::to_vec(&value)?)?;
        assert!(read_runtime(&path, &proc)?.is_none());
        value["boot_id"] = serde_json::json!("current");
        value["monitor_start_ticks"] = serde_json::json!(101);
        fs::write(&path, serde_json::to_vec(&value)?)?;
        assert!(read_runtime(&path, &proc)?.is_none());
        fs::write(&path, "broken")?;
        assert!(read_runtime(&path, &proc).is_err());
        Ok(())
    }

    #[test]
    fn stat_parser_handles_parentheses_and_rejects_truncation() {
        assert_eq!(start_ticks("1 (odd) name) S 0"), None);
        assert!(!same_process(Path::new("/proc"), 0, 1));
        assert!(!same_process(Path::new("/proc"), 1, 0));
    }

    #[test]
    fn zombies_do_not_validate_as_live_injection_processes() -> Result<()> {
        let dir = tempfile::tempdir()?;
        fake_process(dir.path(), 10, 100)?;
        assert!(same_process(dir.path(), 10, 100));
        let path = dir.path().join("10/stat");
        let stat = fs::read_to_string(&path)?;
        for state in ["Z", "X", "x"] {
            fs::write(&path, stat.replace(") S ", &format!(") {state} ")))?;
            assert!(!same_process(dir.path(), 10, 100));
        }
        Ok(())
    }

    #[test]
    fn recognizes_only_verified_providers() {
        for id in ["zygisksu", "rezygisk", "onyxzygisk"] {
            assert!(is_provider(id));
        }
        for id in [
            "lsposed",
            "neozygisk",
            "nyazygisk",
            "nyxzygisk",
            "zygisk_plugin",
        ] {
            assert!(!is_provider(id));
        }
    }

    #[test]
    fn pending_updates_inherit_installed_markers() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let modules = dir.path().join("modules");
        let updates = dir.path().join("modules_update");
        fs::create_dir_all(modules.join("rezygisk"))?;
        fs::create_dir_all(updates.join("rezygisk"))?;
        assert_eq!(providers(&modules, &updates)?, vec!["rezygisk"]);
        for marker in ["disable", "remove"] {
            fs::write(modules.join("rezygisk").join(marker), "")?;
            assert!(providers(&modules, &updates)?.is_empty());
            fs::remove_file(modules.join("rezygisk").join(marker))?;
        }
        fs::write(updates.join("rezygisk/disable"), "")?;
        assert!(providers(&modules, &updates)?.is_empty());
        Ok(())
    }

    #[test]
    fn config_is_default_off_and_rejects_corruption() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("zygisk.json");
        assert!(!read_config(&path)?.enabled);
        write_config(&path, &Config { enabled: true })?;
        assert!(read_config(&path)?.enabled);
        fs::write(&path, "broken")?;
        assert!(read_config(&path).is_err());
        Ok(())
    }

    #[test]
    fn stale_runtime_artifacts_are_cleared_before_startup() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let runtime = dir.path().join("runtime");
        fs::create_dir_all(runtime.join("nested"))?;
        fs::write(runtime.join("state.json"), "{}")?;
        fs::write(runtime.join("monitor.lock"), "")?;
        fs::write(runtime.join("nested/leftover"), "")?;
        clear_stale_runtime(&runtime)?;
        assert_eq!(fs::read_dir(&runtime)?.count(), 1);
        assert!(runtime.join("monitor.lock").exists());
        Ok(())
    }

    #[test]
    fn missing_directories_do_not_imply_an_external_provider() -> Result<()> {
        let dir = tempfile::tempdir()?;
        assert!(providers(&dir.path().join("modules"), &dir.path().join("updates"))?.is_empty());
        assert!(!engine_available(dir.path()));
        Ok(())
    }

    #[test]
    fn pending_only_providers_are_discovered_but_plugins_are_not() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let modules = dir.path().join("modules");
        let updates = dir.path().join("updates");
        fs::create_dir_all(updates.join("zygisksu"))?;
        fs::create_dir_all(modules.join("lsposed/zygisk"))?;
        assert_eq!(providers(&modules, &updates)?, vec!["zygisksu"]);
        fs::write(updates.join("zygisksu/remove"), "")?;
        assert!(providers(&modules, &updates)?.is_empty());
        Ok(())
    }

    #[test]
    fn provider_results_are_unique_and_sorted() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let modules = dir.path().join("modules");
        let updates = dir.path().join("updates");
        for id in ["zygisksu", "rezygisk", "onyxzygisk"] {
            fs::create_dir_all(modules.join(id))?;
            fs::create_dir_all(updates.join(id))?;
        }
        assert_eq!(
            providers(&modules, &updates)?,
            vec!["onyxzygisk", "rezygisk", "zygisksu"]
        );
        Ok(())
    }
}
