use std::time::Duration;

pub const STOP_GRACE: Duration = Duration::from_secs(4);
pub const STOP_WAIT: Duration = Duration::from_secs(2);
pub const STOP_POLL: Duration = Duration::from_millis(20);
pub const RUN_SUFFIX: &str = ".running.lock";
