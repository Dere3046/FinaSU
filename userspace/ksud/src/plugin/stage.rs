//! Stage dispatch for plugins.
//!
//! Plugins run in dependency order and each callback is supervised exactly like
//! a manual run, so a plugin that blocks is stopped here as well. A stage never
//! fails as a whole: a plugin that cannot start is reported and the remaining
//! plugins still run.

use std::path::Path;
use std::time::Duration;

use anyhow::Result;

#[cfg(target_os = "android")]
use super::store;

#[cfg(target_os = "android")]
use std::path::PathBuf;

#[cfg(target_os = "android")]
use std::time::Instant;

#[cfg(target_os = "android")]
use anyhow::Context;

#[cfg(target_os = "android")]
use crate::defs;

#[cfg(target_os = "android")]
use super::{api, manifest, runtime};

/// Seconds a single stage callback may run before it is stopped.
pub const CALLBACK_TIMEOUT: Duration = Duration::from_secs(120);

/// The stages a plugin may hook automatically.
pub const STAGES: [&str; 3] = ["post-fs-data", "service", "boot-completed"];

/// Maps a stage name to the callback name used inside the package.
pub fn callback_for(stage: &str) -> String {
    stage.replace('-', "_")
}

/// Runs `stage` for every enabled plugin, dependencies first.
///
/// `deadline` bounds the whole dispatch when the caller has a shared budget: a
/// plugin that no longer fits is left for the next boot instead of extending the
/// wait. Without a deadline every callback gets [`CALLBACK_TIMEOUT`].
///
/// # Errors
/// Returns an error when the plugin directory cannot be read or `stage` is not a
/// stage a plugin may hook.
#[cfg(target_os = "android")]
pub fn run(
    plugin_dir: &Path,
    lock_dir: &Path,
    state_dir: &Path,
    stage: &str,
    deadline: Option<Instant>,
) -> Result<()> {
    if !STAGES.contains(&stage) {
        anyhow::bail!("'{stage}' is not a plugin stage");
    }

    let callback = callback_for(stage);
    let plugins = store::discover(plugin_dir)?;
    let admitted = admitted_ids(plugin_dir, state_dir, &plugins);
    let plan = store::execution_order(&plugins, |id| admitted.contains(id));

    for (id, reason) in &plan.skipped {
        warn(plugin_dir, id, &format!("not run at {stage}: {reason}"));
    }

    for id in &plan.runnable {
        let timeout = match deadline {
            Some(deadline) => {
                let remaining = deadline.saturating_duration_since(Instant::now());
                if remaining.is_zero() {
                    warn(
                        plugin_dir,
                        id,
                        &format!("not run at {stage}: the stage budget is exhausted"),
                    );
                    continue;
                }
                remaining.min(CALLBACK_TIMEOUT)
            }
            None => CALLBACK_TIMEOUT,
        };

        let dir = plugin_dir.join(id);
        let entry = match manifest::read_manifest(&dir, id) {
            Ok(manifest) => manifest.entry,
            Err(error) => {
                warn(plugin_dir, id, &format!("not run at {stage}: {error}"));
                continue;
            }
        };

        let log = api::open_log(&dir)?;
        match runtime::supervise(plugin_dir, lock_dir, id, &callback, &entry, &log, timeout) {
            // The stage callback is optional, and so is a plugin that does not
            // define it.
            Ok(0 | runtime::MISSING_CALLBACK_CODE) => {}
            Ok(code) => warn(
                plugin_dir,
                id,
                &format!("callback '{callback}' exited with {code}"),
            ),
            Err(error) => warn(
                plugin_dir,
                id,
                &format!("cannot run callback '{callback}': {error}"),
            ),
        }
    }

    Ok(())
}

/// # Errors
/// Always fails on a host: plugin callbacks only run on a device.
#[cfg(not(target_os = "android"))]
pub fn run(
    _plugin_dir: &Path,
    _lock_dir: &Path,
    _state_dir: &Path,
    _stage: &str,
    _deadline: Option<std::time::Instant>,
) -> Result<()> {
    anyhow::bail!("plugin stages only run on a device")
}

/// Collects the plugins that were confirmed for the automatic stages.
#[cfg(target_os = "android")]
fn admitted_ids(
    plugin_dir: &Path,
    state_dir: &Path,
    plugins: &[store::PluginInfo],
) -> std::collections::BTreeSet<String> {
    let mut admitted = std::collections::BTreeSet::new();
    for plugin in plugins {
        if !plugin.enabled || plugin.error.is_some() {
            continue;
        }
        let Ok(fingerprint) = super::admit::fingerprint(&plugin_dir.join(&plugin.id)) else {
            // An unreadable package is simply not admitted.
            continue;
        };
        if super::admit::is_admitted(state_dir, &plugin.id, &fingerprint) {
            admitted.insert(plugin.id.clone());
        }
    }
    admitted
}

/// Records why a plugin was left out, without turning it into a stage failure.
#[cfg(target_os = "android")]
fn warn(plugin_dir: &Path, id: &str, message: &str) {
    if let Ok(mut sink) = api::open_log(&plugin_dir.join(id)) {
        api::append_log(&mut sink, "warn", message);
    }
}

/// Dispatches `stage` in the background, at most once per boot.
///
/// A boot stage must not delay the boot, so the dispatch happens in a separate
/// process that outlives the caller. The run is claimed before it starts: a
/// callback that would not survive a repeated boot stage is never run twice for
/// the same boot.
///
/// # Errors
/// Returns an error when `stage` is not a plugin stage or the run cannot be
/// claimed.
#[cfg(target_os = "android")]
pub fn background(stage: &str) -> Result<()> {
    if !STAGES.contains(&stage) {
        anyhow::bail!("'{stage}' is not a plugin stage");
    }

    if !claim(stage)? {
        return Ok(());
    }

    spawn(stage)
}

/// # Errors
/// Always fails on a host: plugin callbacks only run on a device.
#[cfg(not(target_os = "android"))]
pub fn background(_stage: &str) -> Result<()> {
    anyhow::bail!("plugin stages only run on a device")
}

/// Re-executes this binary so the dispatch outlives the boot event.
#[cfg(target_os = "android")]
fn spawn(stage: &str) -> Result<()> {
    use std::os::unix::process::CommandExt;
    use std::process::{Command, Stdio};

    let program = std::env::current_exe().unwrap_or_else(|_| PathBuf::from(defs::DAEMON_PATH));
    let mut command = Command::new(program);
    command
        .arg("plugin")
        .arg("stage")
        .arg(stage)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    // SAFETY: only async-signal-safe calls are made before the exec.
    unsafe {
        command.pre_exec(|| {
            libc::setsid();
            Ok(())
        });
    }
    command
        .spawn()
        .map(|_| ())
        .with_context(|| format!("cannot start the {stage} dispatcher"))
}

/// Claims the one automatic run of `stage` for this boot, if it is still free.
#[cfg(target_os = "android")]
fn claim(stage: &str) -> Result<bool> {
    let boot = std::fs::read_to_string("/proc/sys/kernel/random/boot_id")
        .context("cannot read the boot id")?;
    let boot = boot.trim();
    let record = Path::new(defs::PLUGIN_STATE_DIR).join(format!("{stage}.ran"));

    match std::fs::read_to_string(&record) {
        Ok(previous) if previous.trim() == boot => return Ok(false),
        Ok(_) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error).context("cannot read the stage record"),
    }

    std::fs::create_dir_all(defs::PLUGIN_STATE_DIR)
        .context("cannot create the plugin state directory")?;
    std::fs::write(&record, boot).context("cannot record the stage run")?;
    Ok(true)
}
