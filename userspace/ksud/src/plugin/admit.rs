//! Boot admission records.
//!
//! A package is only run by the automatic stages once this manager has been told
//! to run it. The shared plugin directory can hold packages installed by another
//! manager, so a package that has never been confirmed here is left alone until
//! the user installs or enables it.
//!
//! A record binds the plugin id to a fingerprint of its code, so replacing the
//! package files invalidates the record and the plugin stops running at boot
//! until it is confirmed again. The record never touches the package itself.

use std::collections::BTreeSet;
use std::fs::{self, File};
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};

use crate::defs;

/// Files that hold runtime state rather than package code.
const RUNTIME_STATE: [&str; 3] = [
    defs::PLUGIN_CONFIG_FILE,
    defs::PLUGIN_DISABLE_FILE,
    defs::PLUGIN_LOG_FILE,
];

/// Hashes the code of a package so a record can be invalidated by a change.
///
/// Only regular files count, and the runtime state files are left out so that
/// changing a setting does not look like new code.
///
/// # Errors
/// Returns an error when the package cannot be walked or read.
pub fn fingerprint(dir: &Path) -> Result<String> {
    let mut files = BTreeSet::new();
    collect(dir, dir, &mut files)?;

    let mut summary = String::new();
    for relative in files {
        let path = dir.join(&relative);
        let content = fs::read(&path).with_context(|| format!("cannot read {}", path.display()))?;
        summary.push_str(&relative.to_string_lossy());
        summary.push(':');
        summary.push_str(&sha256::digest(&content));
        summary.push('\n');
    }
    Ok(sha256::digest(summary.as_bytes()))
}

/// Reports whether the package behind `fingerprint` was confirmed for boot.
pub fn is_admitted(state_dir: &Path, id: &str, fingerprint: &str) -> bool {
    fs::read_to_string(record_path(state_dir, id)).is_ok_and(|record| record.trim() == fingerprint)
}

/// Confirms a package for the automatic stages.
///
/// # Errors
/// Returns an error when the record cannot be written.
pub fn admit(state_dir: &Path, id: &str, fingerprint: &str) -> Result<()> {
    fs::create_dir_all(state_dir).context("cannot create the plugin state directory")?;
    fs::write(record_path(state_dir, id), fingerprint)
        .with_context(|| format!("cannot record the admission of '{id}'"))?;
    File::open(state_dir)
        .and_then(|directory| directory.sync_all())
        .context("cannot sync the plugin state directory")?;
    Ok(())
}

/// Drops the record of a package that is gone.
///
/// # Errors
/// Returns an error when the record exists but cannot be removed.
pub fn forget(state_dir: &Path, id: &str) -> Result<()> {
    match fs::remove_file(record_path(state_dir, id)) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error).with_context(|| format!("cannot drop the record of '{id}'")),
    }
}

fn record_path(state_dir: &Path, id: &str) -> PathBuf {
    state_dir.join(format!("{id}.admitted"))
}

/// Gathers the package files relative to `root`, without following links.
fn collect(root: &Path, dir: &Path, out: &mut BTreeSet<PathBuf>) -> Result<()> {
    let entries = fs::read_dir(dir).with_context(|| format!("cannot read {}", dir.display()))?;
    for entry in entries {
        let entry = entry?;
        let path = entry.path();
        let metadata = fs::symlink_metadata(&path)
            .with_context(|| format!("cannot inspect {}", path.display()))?;
        if metadata.is_dir() {
            collect(root, &path, out)?;
        } else if metadata.is_file() {
            let relative = path.strip_prefix(root).unwrap_or(path.as_path()).to_owned();
            if RUNTIME_STATE.iter().any(|name| relative == Path::new(name)) {
                continue;
            }
            out.insert(relative);
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::os::unix::fs::DirBuilderExt;

    struct Fixture {
        root: tempfile::TempDir,
    }

    impl Fixture {
        fn new() -> Self {
            let root = tempfile::tempdir().unwrap();
            let mut builder = fs::DirBuilder::new();
            builder.mode(0o700);
            builder.create(root.path().join("plugins")).unwrap();
            builder.create(root.path().join("state")).unwrap();
            Self { root }
        }

        fn plugin(&self, id: &str, files: &[(&str, &str)]) -> PathBuf {
            let dir = self.root.path().join("plugins").join(id);
            let mut builder = fs::DirBuilder::new();
            builder.mode(0o700);
            builder.create(&dir).unwrap();
            for (name, body) in files {
                let path = dir.join(name);
                let mut file = File::create(&path).unwrap();
                file.write_all(body.as_bytes()).unwrap();
            }
            dir
        }

        fn state(&self) -> PathBuf {
            self.root.path().join("state")
        }
    }

    #[test]
    fn a_confirmed_package_stays_admitted_until_its_code_changes() {
        let fixture = Fixture::new();
        let dir = fixture.plugin(
            "demo",
            &[
                ("plugin.json", "{\"id\":\"demo\"}"),
                ("main.lua", "return {}"),
            ],
        );
        let state = fixture.state();

        let initial = fingerprint(&dir).unwrap();
        assert!(!is_admitted(&state, "demo", &initial));

        admit(&state, "demo", &initial).unwrap();
        assert!(is_admitted(&state, "demo", &initial));

        // A changed package is no longer the one that was confirmed.
        let mut file = File::create(dir.join("main.lua")).unwrap();
        file.write_all(b"return { changed = true }").unwrap();
        let changed = fingerprint(&dir).unwrap();
        assert_ne!(changed, initial);
        assert!(!is_admitted(&state, "demo", &changed));
    }

    #[test]
    fn runtime_state_does_not_change_the_fingerprint() {
        let fixture = Fixture::new();
        let dir = fixture.plugin(
            "demo",
            &[
                ("plugin.json", "{\"id\":\"demo\"}"),
                ("main.lua", "return {}"),
            ],
        );
        let before = fingerprint(&dir).unwrap();

        for name in RUNTIME_STATE {
            let mut file = File::create(dir.join(name)).unwrap();
            file.write_all(b"changed").unwrap();
        }
        assert_eq!(fingerprint(&dir).unwrap(), before);
    }

    #[test]
    fn nested_files_change_the_fingerprint() {
        let fixture = Fixture::new();
        let dir = fixture.plugin(
            "demo",
            &[
                ("plugin.json", "{\"id\":\"demo\"}"),
                ("main.lua", "return {}"),
            ],
        );
        let before = fingerprint(&dir).unwrap();

        fs::create_dir(dir.join("lib")).unwrap();
        let mut file = File::create(dir.join("lib/helper.lua")).unwrap();
        file.write_all(b"return 1").unwrap();
        assert_ne!(fingerprint(&dir).unwrap(), before);
    }

    #[test]
    fn forgetting_a_package_removes_its_record() {
        let fixture = Fixture::new();
        let dir = fixture.plugin("demo", &[("plugin.json", "{\"id\":\"demo\"}")]);
        let state = fixture.state();
        let fingerprint = fingerprint(&dir).unwrap();
        admit(&state, "demo", &fingerprint).unwrap();

        forget(&state, "demo").unwrap();
        assert!(!is_admitted(&state, "demo", &fingerprint));
        // Dropping a record that is already gone is not an error.
        forget(&state, "demo").unwrap();
    }
}
