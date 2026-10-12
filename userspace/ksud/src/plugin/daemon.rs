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
