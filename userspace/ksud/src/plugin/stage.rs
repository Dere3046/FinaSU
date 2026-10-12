//! Stage dispatch for plugins.
//!
//! Plugins run in dependency order and each callback is supervised exactly like
//! a manual run, so a plugin that blocks is stopped here as well. A stage never
//! fails as a whole: a plugin that cannot start is reported and the remaining
//! plugins still run.

use std::path::Path;
use std::time::{Duration, Instant};

use anyhow::Result;

use crate::plugin::lock;

#[cfg(target_os = "android")]
use super::store;

#[cfg(target_os = "android")]
use std::path::PathBuf;

use anyhow::Context;

#[cfg(target_os = "android")]
use crate::defs;

#[cfg(target_os = "android")]
use super::{api, daemon, manifest, runtime};

/// Seconds a single stage callback may run before it is stopped.
pub const CALLBACK_TIMEOUT: Duration = Duration::from_secs(120);

/// Interval of the long-running callback started together with the service stage.
#[cfg(target_os = "android")]
const MAIN_INTERVAL: i64 = 1;

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
        let Some(timeout) = remaining_timeout(deadline, CALLBACK_TIMEOUT, Instant::now()) else {
            warn(
                plugin_dir,
                id,
                &format!("not run at {stage}: the stage budget is exhausted"),
            );
            continue;
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

        // A package may declare one callback that keeps running for the rest of
        // the boot. It is started here and never probed.
        if stage == "service"
            && declares_main(&plugins, id)
            && let Err(error) = daemon::launch(&dir, id, "main", MAIN_INTERVAL)
        {
            warn(
                plugin_dir,
                id,
                &format!("cannot start the main callback: {error}"),
            );
        }
    }

    Ok(())
}

/// Whether a plugin declares the long-running callback in its manifest.
#[cfg(target_os = "android")]
fn declares_main(plugins: &[store::PluginInfo], id: &str) -> bool {
    plugins
        .iter()
        .any(|plugin| plugin.id == id && plugin.callbacks.iter().any(|callback| callback == "main"))
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
    claim_for(
        Path::new(defs::PLUGIN_STATE_DIR),
        Path::new(defs::PLUGIN_LOCK_DIR),
        stage,
        boot.trim(),
    )
}

/// Claims the one automatic run of `stage` for `boot`.
///
/// The record only guards anything if two callers cannot both claim it, so the
/// check and the write happen under one lock.
fn claim_for(state_dir: &Path, lock_dir: &Path, stage: &str, boot: &str) -> Result<bool> {
    let _guard = lock::exclusive(&lock_dir.join(format!("{stage}.claim.lock")))?;
    let record = state_dir.join(format!("{stage}.ran"));

    match std::fs::read_to_string(&record) {
        Ok(previous) if previous.trim() == boot => return Ok(false),
        Ok(_) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error).context("cannot read the stage record"),
    }

    std::fs::create_dir_all(state_dir).context("cannot create the plugin state directory")?;
    std::fs::write(&record, boot).context("cannot record the stage run")?;
    Ok(true)
}

/// The timeout for one callback of a dispatch that is bounded by `deadline`.
///
/// Returns `None` when the budget is already spent, so the plugin is left out
/// instead of being started with no time left.
fn remaining_timeout(deadline: Option<Instant>, cap: Duration, now: Instant) -> Option<Duration> {
    deadline.map_or(Some(cap), |deadline| {
        let remaining = deadline.saturating_duration_since(now);
        if remaining.is_zero() {
            None
        } else {
            Some(remaining.min(cap))
        }
    })
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::path::PathBuf;

    use super::*;

    struct Fixture {
        root: tempfile::TempDir,
    }

    impl Fixture {
        fn new() -> Self {
            let root = tempfile::tempdir().unwrap();
            fs::create_dir_all(root.path().join("state")).unwrap();
            fs::create_dir_all(root.path().join("locks")).unwrap();
            Self { root }
        }

        fn state(&self) -> PathBuf {
            self.root.path().join("state")
        }

        fn locks(&self) -> PathBuf {
            self.root.path().join("locks")
        }
    }

    #[test]
    fn a_stage_callback_is_capped_by_the_shared_budget() {
        let cap = Duration::from_secs(120);
        let now = Instant::now();

        assert_eq!(remaining_timeout(None, cap, now), Some(cap));
        assert_eq!(
            remaining_timeout(Some(now + Duration::from_secs(5)), cap, now),
            Some(Duration::from_secs(5))
        );
        assert_eq!(
            remaining_timeout(Some(now + Duration::from_secs(600)), cap, now),
            Some(cap)
        );
        assert_eq!(
            remaining_timeout(Some(now - Duration::from_secs(1)), cap, now),
            None
        );
    }

    #[test]
    fn a_stage_is_only_claimed_once_per_boot() {
        let fixture = Fixture::new();
        let (state, locks) = (fixture.state(), fixture.locks());

        assert!(claim_for(&state, &locks, "service", "boot-a").unwrap());
        assert!(!claim_for(&state, &locks, "service", "boot-a").unwrap());
        assert!(claim_for(&state, &locks, "boot-completed", "boot-a").unwrap());
        assert!(claim_for(&state, &locks, "service", "boot-b").unwrap());
        assert_eq!(
            fs::read_to_string(state.join("service.ran")).unwrap(),
            "boot-b"
        );
    }

    #[test]
    fn a_damaged_stage_record_is_reported() {
        let fixture = Fixture::new();
        fs::create_dir_all(fixture.state().join("service.ran")).unwrap();

        assert!(claim_for(&fixture.state(), &fixture.locks(), "service", "boot-a").is_err());
    }
}
