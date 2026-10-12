//! Long-running plugin callbacks.
//!
//! A daemon repeatedly runs one callback of one plugin. The interpreter is
//! rebuilt every round, so a callback cannot carry state across rounds, and the
//! instance stops as soon as its plugin disappears or is disabled. Only one
//! daemon exists per plugin and callback.

use std::path::{Path, PathBuf};
use std::time::Duration;

use anyhow::{Result, bail};

use crate::defs;
use crate::plugin::api;
use crate::plugin::lock;
use crate::plugin::manifest;
use crate::plugin::runtime::{self, Failure};
#[cfg(target_os = "android")]
use crate::utils;

/// Byte a ready daemon writes to the descriptor it was handed.
pub const READY_BYTE: u8 = b'1';

/// Runs one callback of one plugin until it is stopped.
///
/// # Errors
/// Returns an error when the plugin is absent, the callback name is invalid or
/// another daemon already holds the same plugin and callback.
pub fn run(
    plugin_dir: &Path,
    lock_dir: &Path,
    id: &str,
    callback: &str,
    interval: u64,
    ready_fd: Option<i32>,
) -> Result<i32> {
    manifest::validate_plugin_id(id)?;
    manifest::validate_callback(callback)?;
    let dir = plugin_dir.join(id);
    if !dir.is_dir() {
        bail!("plugin '{id}' is not installed");
    }

    let _held = lock::exclusive(&lock_path(lock_dir, id, callback))?;
    #[cfg(target_os = "android")]
    utils::switch_cgroups();
    let entry = manifest::read_manifest(&dir, id)?.entry;
    let mut sink = api::open_log(&dir)?;
    let interval = Duration::from_secs(interval.max(1));
    let mut ready = ready_fd;
    let mut round = 0_u64;

    loop {
        if stopped(plugin_dir, id) {
            break;
        }
        match runtime::prepare(plugin_dir, lock_dir, id, callback, &entry) {
            Ok(prepared) => {
                if let Some(descriptor) = ready.take() {
                    announce(descriptor)?;
                }
                if let Err(Failure::Other(message)) = prepared.invoke() {
                    prepared.log("warn", &format!("callback '{callback}' failed: {message}"));
                }
            }
            Err(Failure::MissingCallback) => {
                if round == 0 {
                    bail!("plugin '{id}' does not define the '{callback}' callback");
                }
                api::append_log(
                    &mut sink,
                    "warn",
                    &format!("callback '{callback}' is gone; stopping"),
                );
                break;
            }
            Err(Failure::Other(message)) => {
                if round == 0 {
                    bail!("plugin '{id}' callback '{callback}' cannot start: {message}");
                }
                api::append_log(
                    &mut sink,
                    "warn",
                    &format!("cannot reload the package: {message}"),
                );
            }
        }
        round += 1;
        std::thread::sleep(interval);
    }
    Ok(0)
}

fn lock_path(lock_dir: &Path, id: &str, callback: &str) -> PathBuf {
    lock_dir.join(format!("{id}.{callback}.daemon.lock"))
}

fn stopped(plugin_dir: &Path, id: &str) -> bool {
    let dir = plugin_dir.join(id);
    !dir.is_dir() || dir.join(defs::PLUGIN_DISABLE_FILE).exists()
}

/// Tells the launcher that the callback has been loaded and can be called.
fn announce(descriptor: i32) -> Result<()> {
    if descriptor <= libc::STDERR_FILENO {
        bail!("ready descriptor must be an open file descriptor");
    }
    let byte = READY_BYTE;
    // SAFETY: the descriptor was handed over by the launcher and the one-byte
    // buffer outlives the call.
    let written = unsafe { libc::write(descriptor, (&raw const byte).cast(), 1) };
    if written != 1 {
        bail!(
            "cannot report readiness: {}",
            std::io::Error::last_os_error()
        );
    }
    Ok(())
}

/// How long a launcher waits for a started daemon to report readiness.
#[cfg(target_os = "android")]
const READY_TIMEOUT_MS: i32 = 10_000;

/// Starts a daemon for one callback and reports whether it became ready.
///
/// `dir` is the directory of the plugin itself. Readiness is reported by the
/// daemon only once the callback has been loaded, so a successful start means
/// the callback can be called.
///
/// # Errors
/// Returns an error when the callback name is invalid or the daemon cannot be
/// started at all. A daemon that starts but never becomes ready is reported as
/// `false` rather than an error.
#[cfg(target_os = "android")]
pub fn launch(dir: &Path, id: &str, callback: &str, interval: i64) -> Result<bool> {
    use std::os::unix::process::CommandExt;
    use std::process::{Command, Stdio};

    manifest::validate_callback(callback)?;
    if dir.join(defs::PLUGIN_DISABLE_FILE).exists() {
        note(dir, "warn", "start_daemon refused: the plugin is disabled");
        return Ok(false);
    }

    let mut fds = [0_i32; 2];
    // SAFETY: the array is writable and holds exactly two descriptors.
    if unsafe { libc::pipe2(fds.as_mut_ptr(), 0) } != 0 {
        note(
            dir,
            "warn",
            &format!(
                "cannot create the readiness pipe: {}",
                std::io::Error::last_os_error()
            ),
        );
        return Ok(false);
    }
    let [read_fd, write_fd] = fds;

    // Re-execute this very binary so the daemon always matches the running
    // build, and the fixed daemon path stays only as a fallback.
    let program = std::env::current_exe().unwrap_or_else(|_| PathBuf::from(defs::DAEMON_PATH));
    let mut command = Command::new(program);
    command
        .arg("plugin")
        .arg("daemon")
        .arg(id)
        .arg(callback)
        .arg(interval.max(1).to_string())
        .arg("--ready-fd")
        .arg(write_fd.to_string())
        .stdin(Stdio::null());
    if let Ok(handle) = api::open_log(dir) {
        let stdout = handle.try_clone();
        let stderr = handle.try_clone();
        if let Ok(stdout) = stdout {
            let _ = command.stdout(Stdio::from(stdout));
        }
        if let Ok(stderr) = stderr {
            let _ = command.stderr(Stdio::from(stderr));
        }
    }
    // SAFETY: the closure only calls async-signal-safe functions, and the
    // readiness descriptor stays open across the exec so it can be written to.
    unsafe {
        command.pre_exec(move || {
            libc::close(read_fd);
            libc::fcntl(write_fd, libc::F_SETFD, 0);
            libc::setsid();
            Ok(())
        });
    }

    let spawned = command.spawn();
    // The reader never sees the end of the pipe while this process still holds
    // the write end, so it is closed here rather than in the child path above.
    // SAFETY: the descriptor is owned by this process.
    unsafe { libc::close(write_fd) };

    let ready = match spawned {
        Ok(_) => await_ready(read_fd),
        Err(error) => {
            note(dir, "warn", &format!("cannot start a daemon: {error}"));
            false
        }
    };
    // SAFETY: the descriptor is owned by this process.
    unsafe { libc::close(read_fd) };

    if ready {
        note(dir, "info", &format!("daemon '{callback}' is running"));
    } else {
        note(
            dir,
            "warn",
            &format!("daemon '{callback}' did not become ready"),
        );
    }
    Ok(ready)
}

/// Appends one line to the log of the plugin the daemon belongs to.
#[cfg(target_os = "android")]
fn note(dir: &Path, level: &str, message: &str) {
    if let Ok(mut sink) = api::open_log(dir) {
        api::append_log(&mut sink, level, message);
    }
}

/// Waits for the readiness byte a started daemon writes once it is running.
#[cfg(target_os = "android")]
fn await_ready(descriptor: i32) -> bool {
    let mut entry = libc::pollfd {
        fd: descriptor,
        events: libc::POLLIN,
        revents: 0,
    };
    // SAFETY: the descriptor and the structure are valid for the call.
    if unsafe { libc::poll(&raw mut entry, 1, READY_TIMEOUT_MS) } != 1 {
        return false;
    }
    let mut byte = 0_u8;
    // SAFETY: the descriptor is open and the one-byte buffer is writable.
    let read = unsafe { libc::read(descriptor, (&raw mut byte).cast(), 1) };
    read == 1 && byte == READY_BYTE
}
