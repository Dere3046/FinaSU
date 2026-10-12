//! Transactional plugin installation, removal and enablement.
//!
//! A package is unpacked into a private staging directory, checked again, and
//! only then moved into place with a single rename. The current version is never
//! removed first, so a package that fails any check leaves the installed plugin
//! untouched. Nothing in a package is interpreted, only its manifest is read.

use std::collections::BTreeSet;
use std::ffi::CString;
use std::fs::{self, DirBuilder, File};
use std::io::Read;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::{DirBuilderExt, MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, bail, ensure};

use crate::defs;
use crate::plugin::{lock, manifest, store};

/// Files that belong to the user. A package never provides them, and an update
/// carries the installed copies over instead of letting the archive replace
/// them.
const RESERVED: [&str; 3] = [
    defs::PLUGIN_CONFIG_FILE,
    defs::PLUGIN_DISABLE_FILE,
    defs::PLUGIN_LOG_FILE,
];

/// One package member selected for extraction.
struct Planned {
    index: usize,
    relative: PathBuf,
    size: u64,
}

/// Installs or updates a plugin from a ZIP package and returns its id.
///
/// # Errors
/// Fails when the archive is unreadable or malformed, when it contains unsafe,
/// duplicated or oversized members, or when the staged package cannot be
/// activated without discarding the installed version.
pub fn install(
    plugin_dir: &Path,
    lock_dir: &Path,
    stage_root: &Path,
    archive: &Path,
) -> Result<String> {
    let file = File::open(archive).with_context(|| format!("cannot open {}", archive.display()))?;
    let mut zip = zip::ZipArchive::new(file)
        .with_context(|| format!("{} is not a ZIP archive", archive.display()))?;

    let (manifest_index, prefix) = locate_manifest(&mut zip)?;
    let bytes = read_entry(&mut zip, manifest_index, defs::PLUGIN_MAX_MANIFEST_SIZE)?;
    let parsed = manifest::parse_manifest(&bytes, None)?;
    let id = parsed.id.clone();

    let _guard = lock::exclusive(&lock_dir.join(format!("{id}.install.lock")))?;
    let plan = plan_entries(&mut zip, &prefix, &parsed.entry)?;

    fs::create_dir_all(plugin_dir)
        .with_context(|| format!("cannot create {}", plugin_dir.display()))?;
    fs::create_dir_all(stage_root)
        .with_context(|| format!("cannot create {}", stage_root.display()))?;
    ensure!(
        device_of(stage_root)? == device_of(plugin_dir)?,
        "staging directory is on another filesystem than the plugin directory"
    );

    let target = plugin_dir.join(&id);
    let stage = stage_path(stage_root, &id);
    create_stage(&stage)?;

    if let Err(error) = prepare(&mut zip, &plan, &parsed.entry, &target, &stage, &id) {
        let _ = fs::remove_dir_all(&stage);
        return Err(error);
    }
    match activate(&stage, &target, plugin_dir) {
        Ok(()) => Ok(id),
        Err(error) => {
            // A failed activation never moved anything into place, so the
            // staged copy is the only thing to clean up.
            let _ = fs::remove_dir_all(&stage);
            Err(error)
        }
    }
}

/// Removes an installed plugin.
///
/// # Errors
/// Fails when the plugin is not installed, when its manifest is unreadable, or
/// when another enabled plugin depends on it.
pub fn uninstall(plugin_dir: &Path, lock_dir: &Path, id: &str) -> Result<()> {
    manifest::validate_plugin_id(id)?;
    let _guard = lock::exclusive(&lock_dir.join(format!("{id}.install.lock")))?;
    let target = plugin_dir.join(id);
    ensure!(target.is_dir(), "plugin '{id}' is not installed");
    ensure_no_dependents(plugin_dir, id)?;
    fs::remove_dir_all(&target).with_context(|| format!("cannot remove {}", target.display()))?;
    sync_dir(plugin_dir)?;
    Ok(())
}

/// Enables or disables a plugin by creating or removing its disable marker.
///
/// # Errors
/// Fails when the plugin is not installed, when enabling finds an invalid
/// package or an absent dependency, or when disabling would leave an enabled
/// plugin without one of its dependencies.
pub fn set_enabled(plugin_dir: &Path, lock_dir: &Path, id: &str, enabled: bool) -> Result<()> {
    manifest::validate_plugin_id(id)?;
    let _guard = lock::exclusive(&lock_dir.join(format!("{id}.install.lock")))?;
    let target = plugin_dir.join(id);
    ensure!(target.is_dir(), "plugin '{id}' is not installed");
    let marker = target.join(defs::PLUGIN_DISABLE_FILE);

    if enabled {
        let package = manifest::read_manifest(&target, id)?;
        for dependency in &package.depends {
            ensure!(
                plugin_dir.join(dependency).is_dir(),
                "plugin '{id}' requires '{dependency}', which is not installed"
            );
        }
        if let Err(error) = fs::remove_file(&marker)
            && error.kind() != std::io::ErrorKind::NotFound
        {
            return Err(error).with_context(|| format!("cannot remove {}", marker.display()));
        }
    } else {
        ensure_no_dependents(plugin_dir, id)?;
        File::create(&marker).with_context(|| format!("cannot create {}", marker.display()))?;
    }
    sync_dir(&target)?;
    Ok(())
}

/// Stages the package, carries the installed user state over and checks the
/// result the same way it will be read back later.
fn prepare(
    zip: &mut zip::ZipArchive<File>,
    plan: &[Planned],
    entry: &str,
    target: &Path,
    stage: &Path,
    id: &str,
) -> Result<()> {
    extract(zip, plan, stage)?;
    carry_over_state(target, stage)?;
    manifest::read_manifest(stage, id)?;
    ensure!(
        stage.join(entry).is_file(),
        "package does not contain its '{entry}' entry"
    );
    sync_dir(stage)?;
    Ok(())
}

/// Moves the staged tree into place. Anything that fails afterwards is reported
/// as a warning, because the new version is already live by then.
fn activate(stage: &Path, target: &Path, plugin_dir: &Path) -> Result<()> {
    let updating = target.exists();
    if updating {
        exchange(stage, target)?;
    } else {
        fs::rename(stage, target)
            .with_context(|| format!("cannot move {} to {}", stage.display(), target.display()))?;
    }

    if updating && let Err(error) = fs::remove_dir_all(stage) {
        eprintln!(
            "warning: cannot remove the previous version at {}: {error}",
            stage.display()
        );
    }
    if let Err(error) = sync_dir(plugin_dir) {
        eprintln!("warning: plugin is installed but persistence is unconfirmed: {error}");
    }
    Ok(())
}

/// Swaps two directories atomically. There is deliberately no fallback: a
/// remove-then-move would leave no working version if it failed halfway. The C
/// library only exports `renameat2` from API 30, so the syscall is issued
/// directly; an unsupported kernel reports `ENOSYS`.
fn exchange(left: &Path, right: &Path) -> Result<()> {
    let left = CString::new(left.as_os_str().as_bytes()).context("path contains a NUL byte")?;
    let right = CString::new(right.as_os_str().as_bytes()).context("path contains a NUL byte")?;
    // SAFETY: both pointers are valid NUL-terminated strings for the call.
    let result = unsafe {
        libc::syscall(
            libc::SYS_renameat2,
            libc::AT_FDCWD,
            left.as_ptr(),
            libc::AT_FDCWD,
            right.as_ptr(),
            libc::RENAME_EXCHANGE as libc::c_uint,
        )
    };
    if result != 0 {
        let error = std::io::Error::last_os_error();
        bail!(
            "cannot swap the package into place atomically ({error}); the installed version is unchanged"
        );
    }
    Ok(())
}

/// Finds the manifest and the directory that holds the package, so an archive
/// that wraps everything in one folder installs the same way as a flat one.
fn locate_manifest(zip: &mut zip::ZipArchive<File>) -> Result<(usize, String)> {
    let attribute = format!("/{}", defs::PLUGIN_MANIFEST);
    let mut best: Option<(usize, String)> = None;
    for index in 0..zip.len() {
        let name = entry_name(zip, index)?;
        let prefix = if name == defs::PLUGIN_MANIFEST {
            String::new()
        } else if let Some(head) = name.strip_suffix(&attribute) {
            head.to_owned()
        } else {
            continue;
        };
        let shallower = best
            .as_ref()
            .is_none_or(|(_, current)| prefix.len() < current.len());
        if shallower {
            best = Some((index, prefix));
        }
    }
    best.ok_or_else(|| anyhow::anyhow!("package has no {}", defs::PLUGIN_MANIFEST))
}

/// Decides whether a member may be extracted. Many writers store only permission
/// bits without a file type, so a missing type is accepted while a declared link
/// or device entry is refused.
const fn is_regular_member(mode: Option<u32>) -> bool {
    let Some(mode) = mode else {
        return true;
    };
    let kind = mode & libc::S_IFMT;
    kind == 0 || kind == libc::S_IFREG
}

/// Selects and checks every member before anything is written to disk.
fn plan_entries(
    zip: &mut zip::ZipArchive<File>,
    prefix: &str,
    entry: &str,
) -> Result<Vec<Planned>> {
    let mut plan = Vec::new();
    let mut seen = BTreeSet::new();
    let mut total = 0_u64;

    for index in 0..zip.len() {
        let (name, size, compressed, mode) = {
            let member = zip
                .by_index(index)
                .with_context(|| format!("cannot read ZIP member {index}"))?;
            if member.is_dir() {
                continue;
            }
            (
                member.name().to_owned(),
                member.size(),
                member.compressed_size(),
                member.unix_mode(),
            )
        };

        let Some(relative) = strip_prefix(&name, prefix) else {
            continue;
        };
        if !relative.contains('/') && RESERVED.contains(&relative) {
            continue;
        }
        let path = safe_relative(relative)?;
        ensure!(
            seen.insert(path.clone()),
            "package contains a duplicate member: '{name}'"
        );
        ensure!(
            is_regular_member(mode),
            "package contains a member that is not a regular file: '{name}'"
        );
        ensure!(
            size <= defs::PLUGIN_MAX_FILE_SIZE,
            "'{name}' exceeds {} bytes",
            defs::PLUGIN_MAX_FILE_SIZE
        );
        check_ratio(&name, size, compressed)?;
        total += size;
        ensure!(
            total <= defs::PLUGIN_MAX_TOTAL_SIZE,
            "package expands beyond {} bytes",
            defs::PLUGIN_MAX_TOTAL_SIZE
        );
        ensure!(
            plan.len() < defs::PLUGIN_MAX_ARCHIVE_FILES,
            "package contains more than {} files",
            defs::PLUGIN_MAX_ARCHIVE_FILES
        );
        plan.push(Planned {
            index,
            relative: path,
            size,
        });
    }

    ensure!(
        plan.iter()
            .any(|planned| planned.relative == Path::new(entry)),
        "package does not contain its '{entry}' entry"
    );
    Ok(plan)
}

/// Rejects members whose expansion is far larger than their stored size.
fn check_ratio(name: &str, size: u64, compressed: u64) -> Result<()> {
    if size == 0 {
        return Ok(());
    }
    ensure!(compressed > 0, "'{name}' has an invalid compressed size");
    ensure!(
        size / compressed <= defs::PLUGIN_MAX_COMPRESSION_RATIO,
        "'{name}' expands {} times its stored size",
        size / compressed
    );
    Ok(())
}

/// Removes the package prefix and rejects anything that leaves the package.
fn strip_prefix<'a>(name: &'a str, prefix: &str) -> Option<&'a str> {
    if prefix.is_empty() {
        return Some(name);
    }
    name.strip_prefix(prefix)?.strip_prefix('/')
}

/// Turns a package-relative name into a path below the target directory.
fn safe_relative(relative: &str) -> Result<PathBuf> {
    ensure!(
        !relative.is_empty(),
        "package contains an empty member name"
    );
    ensure!(
        !relative.starts_with('/'),
        "package contains an absolute path: '{relative}'"
    );
    let mut path = PathBuf::new();
    for part in relative.split('/') {
        ensure!(
            !part.is_empty(),
            "package contains an empty path segment: '{relative}'"
        );
        ensure!(
            part != "." && part != "..",
            "package contains an unsafe path: '{relative}'"
        );
        path.push(part);
    }
    Ok(path)
}

/// Writes the selected members below the staging directory.
fn extract(zip: &mut zip::ZipArchive<File>, plan: &[Planned], stage: &Path) -> Result<()> {
    for planned in plan {
        let destination = stage.join(&planned.relative);
        if let Some(parent) = destination.parent() {
            DirBuilder::new()
                .recursive(true)
                .mode(0o755)
                .create(parent)
                .with_context(|| format!("cannot create {}", parent.display()))?;
        }
        let mut member = zip
            .by_index(planned.index)
            .with_context(|| format!("cannot read ZIP member {index}", index = planned.index))?;
        let mut output = File::create(&destination)
            .with_context(|| format!("cannot create {}", destination.display()))?;
        output
            .set_permissions(fs::Permissions::from_mode(0o644))
            .with_context(|| format!("cannot set permissions on {}", destination.display()))?;
        let copied = std::io::copy(&mut (&mut member).take(planned.size + 1), &mut output)
            .with_context(|| format!("cannot write {}", destination.display()))?;
        ensure!(
            copied == planned.size,
            "'{}' does not match its declared size",
            planned.relative.display()
        );
        output
            .sync_all()
            .with_context(|| format!("cannot sync {}", destination.display()))?;
    }
    Ok(())
}

/// Copies the installed user state into the staged package, so an update keeps
/// the configuration, the disabled marker and the previous log.
fn carry_over_state(target: &Path, stage: &Path) -> Result<()> {
    for name in RESERVED {
        let source = target.join(name);
        let metadata = match fs::symlink_metadata(&source) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
            Err(error) => {
                return Err(error).with_context(|| format!("cannot read {}", source.display()));
            }
        };
        ensure!(
            metadata.is_file(),
            "{} is not a regular file",
            source.display()
        );
        fs::copy(&source, stage.join(name))
            .with_context(|| format!("cannot preserve {}", source.display()))?;
    }
    Ok(())
}

/// Refuses to remove a plugin that another enabled plugin still needs.
fn ensure_no_dependents(plugin_dir: &Path, id: &str) -> Result<()> {
    let mut dependents = Vec::new();
    for info in store::discover(plugin_dir)? {
        if info.enabled && info.depends.iter().any(|dependency| dependency == id) {
            dependents.push(info.id);
        }
    }
    ensure!(
        dependents.is_empty(),
        "plugin '{id}' is required by: {}",
        dependents.join(", ")
    );
    Ok(())
}

/// Reads one member with a hard upper bound.
fn read_entry(zip: &mut zip::ZipArchive<File>, index: usize, limit: u64) -> Result<Vec<u8>> {
    let mut member = zip
        .by_index(index)
        .with_context(|| format!("cannot read ZIP member {index}"))?;
    ensure!(
        member.size() <= limit,
        "plugin manifest exceeds {limit} bytes"
    );
    let mut buffer = Vec::new();
    (&mut member)
        .take(limit + 1)
        .read_to_end(&mut buffer)
        .context("cannot read plugin manifest")?;
    ensure!(
        u64::try_from(buffer.len()).unwrap_or(u64::MAX) <= limit,
        "plugin manifest exceeds {limit} bytes"
    );
    Ok(buffer)
}

fn entry_name(zip: &mut zip::ZipArchive<File>, index: usize) -> Result<String> {
    let member = zip
        .by_index(index)
        .with_context(|| format!("cannot read ZIP member {index}"))?;
    Ok(member.name().to_owned())
}

fn device_of(path: &Path) -> Result<u64> {
    Ok(fs::metadata(path)
        .with_context(|| format!("cannot read {}", path.display()))?
        .dev())
}

fn stage_path(root: &Path, id: &str) -> PathBuf {
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |elapsed| elapsed.as_nanos());
    root.join(format!(".{id}.{}.{stamp}", std::process::id()))
}

fn create_stage(stage: &Path) -> Result<()> {
    DirBuilder::new()
        .mode(0o755)
        .create(stage)
        .with_context(|| format!("cannot create {}", stage.display()))
}

fn sync_dir(path: &Path) -> Result<()> {
    File::open(path)
        .and_then(|directory| directory.sync_all())
        .with_context(|| format!("cannot sync {}", path.display()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    struct Fixture {
        root: tempfile::TempDir,
    }

    impl Fixture {
        fn new() -> Self {
            let root = tempfile::tempdir().unwrap();
            fs::create_dir_all(root.path().join("plugins")).unwrap();
            fs::create_dir_all(root.path().join("locks")).unwrap();
            Self { root }
        }

        fn plugins(&self) -> PathBuf {
            self.root.path().join("plugins")
        }

        fn locks(&self) -> PathBuf {
            self.root.path().join("locks")
        }

        fn stage(&self) -> PathBuf {
            self.root.path().join("stage")
        }

        fn install(&self, entries: &[(&str, &str, Option<u32>)]) -> Result<String> {
            let archive = self.root.path().join("package.zip");
            write_zip(&archive, entries);
            install(&self.plugins(), &self.locks(), &self.stage(), &archive)
        }
    }

    fn write_zip(path: &Path, entries: &[(&str, &str, Option<u32>)]) {
        let file = File::create(path).unwrap();
        let mut writer = zip::ZipWriter::new(file);
        for (name, body, mode) in entries {
            let options =
                zip::write::SimpleFileOptions::default().unix_permissions(mode.unwrap_or(0o644));
            writer.start_file(*name, options).unwrap();
            writer.write_all(body.as_bytes()).unwrap();
        }
        writer.finish().unwrap();
    }

    fn manifest_for(id: &str) -> String {
        format!(r#"{{"id":"{id}","entry":"main.lua"}}"#)
    }

    fn package(id: &str, body: &str) -> Vec<(String, String, Option<u32>)> {
        vec![
            ("plugin.json".to_owned(), manifest_for(id), None),
            ("main.lua".to_owned(), body.to_owned(), None),
        ]
    }

    fn install_package(fixture: &Fixture, id: &str, body: &str) -> Result<String> {
        let owned = package(id, body);
        let entries: Vec<(&str, &str, Option<u32>)> = owned
            .iter()
            .map(|(name, body, mode)| (name.as_str(), body.as_str(), *mode))
            .collect();
        fixture.install(&entries)
    }

    #[test]
    fn installs_a_flat_package() {
        let fixture = Fixture::new();
        let id = install_package(&fixture, "demo", "return {}").unwrap();
        assert_eq!(id, "demo");
        assert_eq!(
            fs::read_to_string(fixture.plugins().join("demo/main.lua")).unwrap(),
            "return {}"
        );
        assert!(!fixture.stage().join("demo").exists());
    }

    #[test]
    fn installs_a_wrapped_package() {
        let fixture = Fixture::new();
        let id = fixture
            .install(&[
                ("wrap/plugin.json", &manifest_for("wrapped"), None),
                ("wrap/main.lua", "return 1", None),
                ("wrap/lib/helper.lua", "return 2", None),
            ])
            .unwrap();
        assert_eq!(id, "wrapped");
        assert!(fixture.plugins().join("wrapped/lib/helper.lua").is_file());
        assert!(!fixture.plugins().join("wrapped/wrap").exists());
    }

    #[test]
    fn keeps_the_installed_version_when_a_check_fails() {
        let fixture = Fixture::new();
        install_package(&fixture, "demo", "return 'first'").unwrap();
        let archive = fixture.root.path().join("bad.zip");
        write_zip(
            &archive,
            &[
                ("plugin.json", &manifest_for("demo"), None),
                ("extra.txt", "no entry file", None),
            ],
        );
        let error = install(
            &fixture.plugins(),
            &fixture.locks(),
            &fixture.stage(),
            &archive,
        )
        .unwrap_err();
        assert!(error.to_string().contains("main.lua"));
        assert_eq!(
            fs::read_to_string(fixture.plugins().join("demo/main.lua")).unwrap(),
            "return 'first'"
        );
    }

    #[test]
    fn update_keeps_user_state_and_replaces_code() {
        let fixture = Fixture::new();
        install_package(&fixture, "demo", "return 'first'").unwrap();
        fs::write(fixture.plugins().join("demo/config.json"), r#"{"k":"v"}"#).unwrap();
        fs::write(fixture.plugins().join("demo/disable"), "").unwrap();
        fs::write(fixture.plugins().join("demo/last_output.log"), "old\n").unwrap();

        let id = install_package(&fixture, "demo", "return 'second'").unwrap();
        assert_eq!(id, "demo");
        let target = fixture.plugins().join("demo");
        assert_eq!(
            fs::read_to_string(target.join("main.lua")).unwrap(),
            "return 'second'"
        );
        assert_eq!(
            fs::read_to_string(target.join("config.json")).unwrap(),
            r#"{"k":"v"}"#
        );
        assert!(target.join("disable").exists());
        assert_eq!(
            fs::read_to_string(target.join("last_output.log")).unwrap(),
            "old\n"
        );
    }

    #[test]
    fn a_package_cannot_ship_runtime_state() {
        let fixture = Fixture::new();
        fixture
            .install(&[
                ("plugin.json", &manifest_for("demo"), None),
                ("main.lua", "return {}", None),
                ("config.json", r#"{"stolen":"yes"}"#, None),
                ("disable", "", None),
            ])
            .unwrap();
        let target = fixture.plugins().join("demo");
        assert!(!target.join("config.json").exists());
        assert!(!target.join("disable").exists());
    }

    #[test]
    fn rejects_unsafe_member_paths() {
        let fixture = Fixture::new();
        for name in ["../escape.lua", "a/../../escape.lua", "/absolute.lua"] {
            let error = fixture
                .install(&[
                    ("plugin.json", &manifest_for("demo"), None),
                    ("main.lua", "return {}", None),
                    (name, "x", None),
                ])
                .unwrap_err();
            assert!(
                error.to_string().contains("unsafe path") || error.to_string().contains("absolute"),
                "unexpected error for {name}: {error}"
            );
            assert!(!fixture.plugins().join("demo").exists());
        }
    }

    #[test]
    fn rejects_symlink_members() {
        let fixture = Fixture::new();
        let archive = fixture.root.path().join("link.zip");
        let file = File::create(&archive).unwrap();
        let mut writer = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default();
        writer.start_file("plugin.json", options).unwrap();
        writer.write_all(manifest_for("demo").as_bytes()).unwrap();
        writer.start_file("main.lua", options).unwrap();
        writer.write_all(b"return {}").unwrap();
        writer
            .add_symlink("elsewhere", "/system/bin/sh", options)
            .unwrap();
        writer.finish().unwrap();

        let error = install(
            &fixture.plugins(),
            &fixture.locks(),
            &fixture.stage(),
            &archive,
        )
        .unwrap_err();
        assert!(error.to_string().contains("regular file"), "{error}");
        assert!(!fixture.plugins().join("demo").exists());
    }

    #[test]
    fn validates_member_names_and_ratios() {
        assert!(safe_relative("lib/helper.lua").is_ok());
        for name in ["", "/abs", "a//b", "./a", "a/../../b", ".."] {
            assert!(safe_relative(name).is_err(), "{name} should be rejected");
        }
        assert_eq!(strip_prefix("a/b/main.lua", "a/b"), Some("main.lua"));
        assert_eq!(strip_prefix("main.lua", ""), Some("main.lua"));
        assert_eq!(strip_prefix("b/main.lua", "a"), None);
        assert!(check_ratio("bomb", 100 * 1024 * 1024, 1024).is_err());
        assert!(check_ratio("empty", 0, 0).is_ok());
        assert!(check_ratio("normal", 4096, 2048).is_ok());
    }

    #[test]
    fn accepts_members_without_a_declared_file_type() {
        assert!(is_regular_member(None));
        assert!(is_regular_member(Some(0o600)));
        assert!(is_regular_member(Some(libc::S_IFREG | 0o644)));
        assert!(!is_regular_member(Some(libc::S_IFLNK | 0o777)));
        assert!(!is_regular_member(Some(libc::S_IFCHR | 0o666)));
    }

    #[test]
    fn enables_and_disables_a_plugin() {
        let fixture = Fixture::new();
        install_package(&fixture, "demo", "return {}").unwrap();
        let marker = fixture.plugins().join("demo/disable");

        set_enabled(&fixture.plugins(), &fixture.locks(), "demo", false).unwrap();
        assert!(marker.exists());
        set_enabled(&fixture.plugins(), &fixture.locks(), "demo", true).unwrap();
        assert!(!marker.exists());

        let error = set_enabled(&fixture.plugins(), &fixture.locks(), "missing", true).unwrap_err();
        assert!(error.to_string().contains("not installed"), "{error}");
    }

    #[test]
    fn refuses_to_break_dependencies() {
        let fixture = Fixture::new();
        let base = manifest_for("base");
        fixture
            .install(&[
                ("plugin.json", &base, None),
                ("main.lua", "return {}", None),
            ])
            .unwrap();
        let dependent = r#"{"id":"dependent","entry":"main.lua","depends":["base"]}"#;
        fixture
            .install(&[
                ("plugin.json", dependent, None),
                ("main.lua", "return {}", None),
            ])
            .unwrap();

        let error = uninstall(&fixture.plugins(), &fixture.locks(), "base").unwrap_err();
        assert!(error.to_string().contains("dependent"), "{error}");
        let error = set_enabled(&fixture.plugins(), &fixture.locks(), "base", false).unwrap_err();
        assert!(error.to_string().contains("dependent"), "{error}");

        uninstall(&fixture.plugins(), &fixture.locks(), "dependent").unwrap();
        uninstall(&fixture.plugins(), &fixture.locks(), "base").unwrap();
        assert!(!fixture.plugins().join("base").exists());
    }

    #[test]
    fn refuses_enabling_with_an_absent_dependency() {
        let fixture = Fixture::new();
        let manifest = r#"{"id":"dependent","entry":"main.lua","depends":["ghost"]}"#;
        fixture
            .install(&[
                ("plugin.json", manifest, None),
                ("main.lua", "return {}", None),
            ])
            .unwrap();
        let error =
            set_enabled(&fixture.plugins(), &fixture.locks(), "dependent", true).unwrap_err();
        assert!(error.to_string().contains("ghost"), "{error}");
    }

    #[test]
    fn rejects_packages_without_a_manifest() {
        let fixture = Fixture::new();
        let error = fixture
            .install(&[("main.lua", "return {}", None)])
            .unwrap_err();
        assert!(error.to_string().contains("plugin.json"), "{error}");
    }
}
