//! Runs plugin callbacks in a separately supervised process.
//!
//! A callback may block, loop or print without limit, so it never runs in the
//! process that started it. The worker is placed in its own process group and
//! the whole group is killed once its deadline passes.

use std::path::{Path, PathBuf};
use std::time::Duration;

use anyhow::{Result, bail};
use mlua::{Lua, LuaOptions, StdLib, Table, Value};

#[cfg(target_os = "android")]
use std::fs::File;
#[cfg(target_os = "android")]
use std::io::Write;
#[cfg(target_os = "android")]
use std::os::fd::AsRawFd;
#[cfg(target_os = "android")]
use std::time::Instant;

#[cfg(target_os = "android")]
use anyhow::Context as _;

use crate::defs;
#[cfg(target_os = "android")]
use crate::module::SigchldBlock;
use crate::plugin::api;
use crate::plugin::manifest;
#[cfg(target_os = "android")]
use crate::utils;

/// Deadline for a callback started by hand.
pub const MANUAL_TIMEOUT: Duration = Duration::from_secs(300);

/// Code reported when the plugin does not define the requested callback.
pub const MISSING_CALLBACK_CODE: i32 = 2;

/// Runs one callback of an installed plugin and returns the exit code to report.
///
/// # Errors
///
/// Returns an error when the plugin is not installed, is disabled, or when the
/// worker cannot be started. A callback that fails is not an error, its code is
/// returned instead.
#[cfg(target_os = "android")]
pub fn run(plugin_dir: &Path, lock_dir: &Path, id: &str, callback: &str) -> Result<i32> {
    let dir = target_dir(plugin_dir, id)?;
    manifest::validate_callback(callback)?;
    let entry = manifest::read_manifest(&dir, id)?.entry;
    let log = api::open_log(&dir)?;
    supervise(
        plugin_dir,
        lock_dir,
        id,
        callback,
        &entry,
        &log,
        MANUAL_TIMEOUT,
    )
}

/// Callbacks need a device; the host build only provides the command surface.
///
/// # Errors
///
/// Always returns an error on the host.
#[cfg(not(target_os = "android"))]
pub fn run(_plugin_dir: &Path, _lock_dir: &Path, _id: &str, _callback: &str) -> Result<i32> {
    bail!("plugin callbacks only run on a device")
}

/// Resolves the package of a plugin that may run now.
fn target_dir(plugin_dir: &Path, id: &str) -> Result<PathBuf> {
    manifest::validate_plugin_id(id)?;
    let dir = plugin_dir.join(id);
    if !dir.is_dir() {
        bail!("plugin '{id}' is not installed");
    }
    if dir.join(defs::PLUGIN_DISABLE_FILE).exists() {
        bail!("plugin '{id}' is disabled");
    }
    Ok(dir)
}

#[cfg(target_os = "android")]
fn supervise(
    plugin_dir: &Path,
    lock_dir: &Path,
    id: &str,
    callback: &str,
    entry: &str,
    log: &File,
    timeout: Duration,
) -> Result<i32> {
    let sigchld = SigchldBlock::block().context("cannot block SIGCHLD")?;
    let descriptor = log.as_raw_fd();
    // SAFETY: the worker only calls code that is safe on its own, and it never
    // returns to this frame.
    let pid = unsafe { libc::fork() };
    if pid < 0 {
        bail!(
            "cannot start the plugin worker: {}",
            std::io::Error::last_os_error()
        );
    }
    if pid == 0 {
        worker(plugin_dir, lock_dir, id, callback, entry, descriptor);
    }

    // The worker also sets its own group; whichever call lands first wins.
    unsafe { libc::setpgid(pid, pid) };
    let deadline = Instant::now() + timeout;
    let status = sigchld
        .wait_status_until(pid, deadline)
        .context("cannot wait for the plugin worker")?;

    let Some(status) = status else {
        // Kill the whole group so helpers cannot outlive their plugin.
        unsafe {
            libc::kill(-pid, libc::SIGKILL);
            libc::kill(pid, libc::SIGKILL);
        }
        let _ = sigchld.wait_status_until(pid, Instant::now() + Duration::from_secs(5));
        let mut sink = log;
        let _ = writeln!(
            sink,
            "[warn] callback '{callback}' exceeded {}s and was stopped",
            timeout.as_secs()
        );
        return Ok(1);
    };

    if libc::WIFEXITED(status) {
        return Ok(libc::WEXITSTATUS(status));
    }
    if libc::WIFSIGNALED(status) {
        let mut sink = log;
        let _ = writeln!(
            sink,
            "[warn] callback '{callback}' was killed by signal {}",
            libc::WTERMSIG(status)
        );
    }
    Ok(1)
}

/// Child side of [`supervise`]. Never returns.
#[cfg(target_os = "android")]
fn worker(
    plugin_dir: &Path,
    lock_dir: &Path,
    id: &str,
    callback: &str,
    entry: &str,
    descriptor: libc::c_int,
) -> ! {
    // SAFETY: the worker owns its own process here.
    unsafe {
        libc::setpgid(0, 0);
        libc::dup2(descriptor, libc::STDOUT_FILENO);
        libc::dup2(descriptor, libc::STDERR_FILENO);
    }
    utils::switch_cgroups();

    let code = match evaluate(plugin_dir, lock_dir, id, callback, entry) {
        Ok(()) => 0,
        Err(Failure::MissingCallback) => {
            eprintln!("plugin '{id}' does not define the '{callback}' callback");
            MISSING_CALLBACK_CODE
        }
        Err(Failure::Other(message)) => {
            eprintln!("plugin '{id}' callback '{callback}' failed: {message}");
            1
        }
    };
    unsafe { libc::_exit(code) }
}

#[derive(Debug, PartialEq, Eq)]
enum Failure {
    MissingCallback,
    Other(String),
}

impl Failure {
    fn other(error: impl std::fmt::Display) -> Self {
        Self::Other(error.to_string())
    }
}

/// Loads the package in a fresh interpreter and calls the named callback.
fn evaluate(
    plugin_dir: &Path,
    lock_dir: &Path,
    id: &str,
    callback: &str,
    entry: &str,
) -> Result<(), Failure> {
    let state = api::Context::new(plugin_dir, lock_dir, id).map_err(Failure::other)?;
    let lua = Lua::new_with(StdLib::ALL_SAFE, LuaOptions::default()).map_err(Failure::other)?;
    lua.set_memory_limit(api::MAX_MEMORY)
        .map_err(Failure::other)?;
    api::bind(&lua, &state).map_err(Failure::other)?;

    let path = plugin_dir.join(id).join(entry);
    let source = std::fs::read_to_string(&path).map_err(Failure::other)?;
    let module: Table = lua
        .load(source.as_str())
        .set_name(entry)
        .eval()
        .map_err(Failure::other)?;

    match module.get::<Value>(callback).map_err(Failure::other)? {
        Value::Function(function) => function.call(()).map_err(Failure::other),
        Value::Nil => Err(Failure::MissingCallback),
        other => Err(Failure::Other(format!(
            "callback '{callback}' is {}, not a function",
            other.type_name()
        ))),
    }
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::os::unix::fs::DirBuilderExt;

    use super::*;

    const MANIFEST: &str = r#"{"id":"demo","entry":"main.lua"}"#;

    struct Fixture {
        root: tempfile::TempDir,
    }

    impl Fixture {
        fn new(script: &str) -> Self {
            let root = tempfile::tempdir().unwrap();
            fs::create_dir_all(root.path().join("plugins")).unwrap();
            let dir = root.path().join("plugins/demo");
            std::fs::DirBuilder::new().mode(0o700).create(&dir).unwrap();
            fs::write(dir.join("plugin.json"), MANIFEST).unwrap();
            fs::write(dir.join("main.lua"), script).unwrap();
            fs::create_dir_all(root.path().join("locks")).unwrap();
            Self { root }
        }

        fn plugins(&self) -> PathBuf {
            self.root.path().join("plugins")
        }

        fn locks(&self) -> PathBuf {
            self.root.path().join("locks")
        }

        fn call(&self, callback: &str) -> Result<(), Failure> {
            evaluate(&self.plugins(), &self.locks(), "demo", callback, "main.lua")
        }
    }

    #[test]
    fn runs_a_callback_that_returns() {
        let fixture = Fixture::new("return { probe = function() return 1 end }");
        assert_eq!(fixture.call("probe"), Ok(()));
    }

    #[test]
    fn reports_a_missing_callback() {
        let fixture = Fixture::new("return { other = function() end }");
        assert_eq!(fixture.call("probe"), Err(Failure::MissingCallback));
    }

    #[test]
    fn reports_a_failing_callback() {
        let fixture = Fixture::new("return { probe = function() error('boom') end }");
        let failure = fixture.call("probe").unwrap_err();
        assert!(matches!(failure, Failure::Other(message) if message.contains("boom")));
    }

    #[test]
    fn reports_a_callback_that_is_not_a_function() {
        let fixture = Fixture::new("return { probe = 1 }");
        let failure = fixture.call("probe").unwrap_err();
        assert!(matches!(failure, Failure::Other(message) if message.contains("not a function")));
    }

    #[test]
    fn reports_a_missing_entry_file() {
        let fixture = Fixture::new("return {}");
        fs::remove_file(fixture.plugins().join("demo/main.lua")).unwrap();
        let failure = fixture.call("probe").unwrap_err();
        assert!(matches!(failure, Failure::Other(_)));
    }

    #[test]
    fn rejects_plugins_that_are_absent_or_disabled() {
        let fixture = Fixture::new("return {}");
        let error = target_dir(&fixture.plugins(), "ghost").unwrap_err();
        assert!(error.to_string().contains("not installed"));
        fs::write(fixture.plugins().join("demo/disable"), "").unwrap();
        let error = target_dir(&fixture.plugins(), "demo").unwrap_err();
        assert!(error.to_string().contains("disabled"));
    }

    #[test]
    fn rejects_an_unsafe_callback_name() {
        let error = manifest::validate_callback("../main").unwrap_err();
        assert!(error.to_string().contains("callback"));
    }
}
