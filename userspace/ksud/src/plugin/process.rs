//! Identity-checked plugin process ownership and termination.

use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::os::fd::{AsRawFd, FromRawFd};
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::time::Instant;

use anyhow::{Context, Result, bail, ensure};
use serde::{Deserialize, Serialize};

use super::{defs, lock};

#[derive(Deserialize, Serialize)]
struct Identity {
    pid: libc::pid_t,
    start: String,
    boot: String,
}

pub struct Running {
    path: PathBuf,
    _lock: lock::Lock,
}

impl Drop for Running {
    fn drop(&mut self) {
        // Keep the inode: removing it would let a new owner bypass its lock.
        if let Ok(file) = OpenOptions::new()
            .write(true)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&self.path)
        {
            let _ = file.set_len(0);
        }
    }
}

fn start_time(pid: libc::pid_t) -> Result<String> {
    let stat = fs::read_to_string(format!("/proc/{pid}/stat"))?;
    let (_, fields) = stat.rsplit_once(')').context("invalid process stat")?;
    Ok(fields
        .split_whitespace()
        .nth(19)
        .context("missing start time")?
        .to_owned())
}

/// Registers a process while the caller holds the plugin installation lock.
pub fn register(lock_dir: &Path, id: &str, name: &str, pid: libc::pid_t) -> Result<Running> {
    ensure!(pid > 1, "invalid plugin process id");
    let path = lock_dir.join(format!("{id}.{name}{}", defs::RUN_SUFFIX));
    let held = lock::exclusive(&path)?;
    let running = Running { path, _lock: held };
    let identity = Identity {
        pid,
        start: start_time(pid)?,
        boot: fs::read_to_string("/proc/sys/kernel/random/boot_id")?,
    };
    let mut file = OpenOptions::new()
        .write(true)
        .truncate(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(&running.path)?;
    file.write_all(&serde_json::to_vec(&identity)?)?;
    file.sync_all()?;
    Ok(running)
}

fn records(lock_dir: &Path, id: &str) -> Result<Vec<PathBuf>> {
    let entries = match fs::read_dir(lock_dir) {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(error.into()),
    };
    let prefix = format!("{id}.");
    let mut paths = Vec::new();
    for entry in entries {
        let entry = entry?;
        let name = entry.file_name();
        if let Some(name) = name.to_str()
            && name.starts_with(&prefix)
            && name.ends_with(defs::RUN_SUFFIX)
        {
            paths.push(entry.path());
        }
    }
    Ok(paths)
}

fn open_pid(pid: libc::pid_t) -> Result<Option<File>> {
    // SAFETY: pidfd_open takes scalar arguments and returns an owned descriptor.
    let fd = unsafe { libc::syscall(libc::SYS_pidfd_open, pid, 0) };
    if fd < 0 {
        let error = std::io::Error::last_os_error();
        if error.raw_os_error() == Some(libc::ESRCH) {
            return Ok(None);
        }
        return Err(error).context("cannot pin plugin process identity");
    }
    // SAFETY: the successful syscall returned a new descriptor.
    Ok(Some(unsafe { File::from_raw_fd(fd as i32) }))
}

fn exited(pidfd: &File) -> Result<bool> {
    let mut poll = libc::pollfd {
        fd: pidfd.as_raw_fd(),
        events: libc::POLLIN,
        revents: 0,
    };
    // SAFETY: poll points to one initialized entry.
    let result = unsafe { libc::poll(&raw mut poll, 1, 0) };
    if result < 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    Ok(result > 0 && poll.revents & libc::POLLIN != 0)
}

fn signal(pidfd: &File, signal: i32) -> Result<()> {
    // SAFETY: the pidfd is owned and the optional siginfo pointer is null.
    let result = unsafe {
        libc::syscall(
            libc::SYS_pidfd_send_signal,
            pidfd.as_raw_fd(),
            signal,
            std::ptr::null::<libc::siginfo_t>(),
            0,
        )
    };
    if result < 0 {
        let error = std::io::Error::last_os_error();
        if error.raw_os_error() != Some(libc::ESRCH) {
            return Err(error.into());
        }
    }
    Ok(())
}

fn group_members(leader: libc::pid_t) -> Result<Vec<File>> {
    let mut members = Vec::new();
    // Only dedicated plugin groups are eligible; never inspect the caller's
    // shared shell or test-runner group.
    if unsafe { libc::getpgid(leader) } != leader {
        return Ok(members);
    }
    for entry in fs::read_dir("/proc")? {
        let entry = entry?;
        let Some(pid) = entry
            .file_name()
            .to_str()
            .and_then(|name| name.parse::<libc::pid_t>().ok())
        else {
            continue;
        };
        if pid <= 1 || pid == leader {
            continue;
        }
        let Ok(start) = start_time(pid) else {
            continue;
        };
        let Some(fd) = open_pid(pid)? else {
            continue;
        };
        let Ok(contents) = fs::read_to_string(entry.path().join("stat")) else {
            continue;
        };
        let Some((_, fields)) = contents.rsplit_once(')') else {
            continue;
        };
        let fields: Vec<_> = fields.split_whitespace().collect();
        if fields
            .get(2)
            .and_then(|group| group.parse::<libc::pid_t>().ok())
            == Some(leader)
            && fields.get(19).copied() == Some(start.as_str())
            && !exited(&fd)?
        {
            members.push(fd);
        }
    }
    Ok(members)
}

fn wait(targets: &[&File], deadline: Instant) -> Result<bool> {
    loop {
        let mut done = true;
        for target in targets {
            done &= exited(target)?;
        }
        if done {
            return Ok(true);
        }
        if Instant::now() >= deadline {
            return Ok(false);
        }
        std::thread::sleep(defs::STOP_POLL);
    }
}

/// Stops registered instances without relying on callback cooperation.
/// The caller must prevent new registrations with the installation lock.
pub fn stop(lock_dir: &Path, id: &str) -> Result<()> {
    let paths = records(lock_dir, id)?;
    let boot = fs::read_to_string("/proc/sys/kernel/random/boot_id")?;
    let mut targets = Vec::new();
    for path in &paths {
        if let Ok(_held) = lock::exclusive(path) {
            OpenOptions::new()
                .write(true)
                .custom_flags(libc::O_NOFOLLOW)
                .open(path)?
                .set_len(0)?;
            continue;
        }
        let mut bytes = Vec::new();
        OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NOFOLLOW)
            .open(path)?
            .take(4096)
            .read_to_end(&mut bytes)?;
        let identity: Identity = if let Ok(identity) = serde_json::from_slice(&bytes) {
            identity
        } else {
            OpenOptions::new()
                .write(true)
                .custom_flags(libc::O_NOFOLLOW)
                .open(path)?
                .set_len(0)?;
            continue;
        };
        ensure!(identity.pid > 1, "invalid plugin process id");
        if identity.boot != boot {
            continue;
        }
        if let Some(fd) = open_pid(identity.pid)? {
            if exited(&fd)? {
                continue;
            }
            ensure!(
                start_time(identity.pid)? == identity.start,
                "plugin process identity changed"
            );
            targets.extend(group_members(identity.pid)?);
            targets.push(fd);
        }
    }
    for target in &targets {
        signal(target, libc::SIGTERM)?;
    }
    if !wait(
        &targets.iter().collect::<Vec<_>>(),
        Instant::now() + defs::STOP_GRACE,
    )? {
        for target in &targets {
            signal(target, libc::SIGKILL)?;
        }
        ensure!(
            wait(
                &targets.iter().collect::<Vec<_>>(),
                Instant::now() + defs::STOP_WAIT
            )?,
            "plugin processes did not exit"
        );
    }
    let deadline = Instant::now() + defs::STOP_WAIT;
    for path in paths {
        loop {
            if let Ok(_held) = lock::exclusive(&path) {
                OpenOptions::new()
                    .write(true)
                    .custom_flags(libc::O_NOFOLLOW)
                    .open(&path)?
                    .set_len(0)?;
                break;
            }
            if Instant::now() >= deadline {
                bail!("plugin '{id}' still holds its running lock");
            }
            std::thread::sleep(defs::STOP_POLL);
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader};
    use std::process::{Command, Stdio};

    #[test]
    fn fake_worker() {
        let Ok(dir) = std::env::var("KSUD_TEST_PROCESS_DIR") else {
            return;
        };
        if std::env::var_os("KSUD_TEST_IGNORE_TERM").is_some() {
            unsafe {
                libc::signal(libc::SIGTERM, libc::SIG_IGN);
            }
        }
        let _running =
            register(Path::new(&dir), "demo", "worker", std::process::id() as i32).unwrap();
        println!("ready");
        std::io::stdout().flush().unwrap();
        loop {
            std::thread::sleep(defs::STOP_POLL);
        }
    }

    fn exercise(ignore_term: bool) {
        let root = tempfile::tempdir().unwrap();
        let mut command = Command::new(std::env::current_exe().unwrap());
        command
            .args([
                "--exact",
                "plugin::process::tests::fake_worker",
                "--nocapture",
            ])
            .env("KSUD_TEST_PROCESS_DIR", root.path())
            .stdout(Stdio::piped());
        if ignore_term {
            command.env("KSUD_TEST_IGNORE_TERM", "1");
        }
        let mut child = command.spawn().unwrap();
        let mut output = BufReader::new(child.stdout.take().unwrap());
        let mut line = String::new();
        loop {
            line.clear();
            assert!(output.read_line(&mut line).unwrap() > 0);
            if line.trim() == "ready" {
                break;
            }
        }
        let record = root.path().join(format!("demo.worker{}", defs::RUN_SUFFIX));
        assert!(lock::exclusive(&record).is_err());
        let started = Instant::now();
        stop(root.path(), "demo").unwrap();
        if ignore_term {
            assert!(started.elapsed() >= defs::STOP_GRACE);
        }
        assert!(!child.wait().unwrap().success());
        assert!(fs::read(&record).unwrap().is_empty());
        let _held = lock::exclusive(&record).unwrap();
        stop(root.path(), "other").unwrap();
    }

    #[test]
    fn stops_a_registered_process_and_clears_its_record() {
        exercise(false);
    }

    #[test]
    fn kills_a_worker_that_ignores_term() {
        exercise(true);
    }

    #[test]
    fn clears_stale_records_without_signalling() {
        let root = tempfile::tempdir().unwrap();
        let record = root.path().join(format!("demo.old{}", defs::RUN_SUFFIX));
        fs::write(&record, "invalid stale record").unwrap();
        stop(root.path(), "demo").unwrap();
        assert!(fs::read(record).unwrap().is_empty());
    }
}
