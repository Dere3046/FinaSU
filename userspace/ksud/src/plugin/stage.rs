//! Stage dispatch for plugins.
//!
//! Plugins run in dependency order and each callback is supervised exactly like
//! a manual run, so a plugin that blocks is stopped here as well. A stage never
//! fails as a whole: a plugin that cannot start is reported and the remaining
//! plugins still run.

use std::path::Path;
use std::time::Duration;

use anyhow::Result;

use super::store;

#[cfg(target_os = "android")]
use std::time::Instant;

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
    stage: &str,
    deadline: Option<Instant>,
) -> Result<()> {
    if !STAGES.contains(&stage) {
        anyhow::bail!("'{stage}' is not a plugin stage");
    }

    let callback = callback_for(stage);
    let plan = store::execution_order(&store::discover(plugin_dir)?);

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
    _stage: &str,
    _deadline: Option<std::time::Instant>,
) -> Result<()> {
    anyhow::bail!("plugin stages only run on a device")
}

/// Records why a plugin was left out, without turning it into a stage failure.
#[cfg(target_os = "android")]
fn warn(plugin_dir: &Path, id: &str, message: &str) {
    if let Ok(mut sink) = api::open_log(&plugin_dir.join(id)) {
        api::append_log(&mut sink, "warn", message);
    }
}
