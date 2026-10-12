//! Advisory locks for plugin state files.

use std::fs::{File, OpenOptions};
use std::os::unix::fs::OpenOptionsExt;
use std::os::unix::io::AsRawFd;
use std::path::Path;

use anyhow::{Context, Result, bail};

/// An exclusive lock held until the value is dropped.
pub struct Lock(File);

impl Drop for Lock {
    fn drop(&mut self) {
        // Closing the descriptor also releases the lock; unlocking keeps the
        // intent explicit.
        unsafe {
            libc::flock(self.0.as_raw_fd(), libc::LOCK_UN);
        }
    }
}

/// Take a non-blocking exclusive lock on `path`, creating missing parents.
///
/// A lock that is already held is reported instead of waiting, so an
/// interactive command never blocks behind a running plugin.
///
/// # Errors
///
/// Returns an error when the lock file cannot be opened or is already held.
pub fn exclusive(path: &Path) -> Result<Lock> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("cannot create {}", parent.display()))?;
    }
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path)
        .with_context(|| format!("cannot open {}", path.display()))?;
    // SAFETY: the descriptor is owned by `file` and stays open for the call.
    if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
        bail!("plugin state is busy; retry later");
    }
    Ok(Lock(file))
}
