//! Plugin packages: manifest, storage and execution.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

pub mod api;
pub mod config;
pub mod install;
pub mod lock;
pub mod log;
pub mod manifest;
pub mod runtime;
pub mod store;

use std::path::{Path, PathBuf};

use anyhow::Result;

use crate::defs;

#[derive(Debug, Clone, clap::Subcommand)]
pub enum Command {
    /// List installed plugins as JSON
    List,
    /// Manage the configuration of one plugin
    Config {
        /// Plugin id
        #[arg(long)]
        id: String,
        #[command(subcommand)]
        command: ConfigCommand,
    },
    /// Print a plugin's output log
    Log {
        /// Plugin id
        id: String,
    },
    /// Remove a plugin's output log
    ClearLog {
        /// Plugin id
        id: String,
    },
    /// Install or update a plugin from a ZIP package
    Install {
        /// Path to the package
        zip: PathBuf,
    },
    /// Remove an installed plugin
    Uninstall {
        /// Plugin id
        id: String,
    },
    /// Allow a plugin to run
    Enable {
        /// Plugin id
        id: String,
    },
    /// Stop a plugin from running
    Disable {
        /// Plugin id
        id: String,
    },
    /// Run one callback of a plugin
    Run {
        /// Plugin id
        id: String,
        /// Callback name
        callback: String,
    },
    /// Run a plugin's action callback
    Action {
        /// Plugin id
        id: String,
    },
}

#[derive(Debug, Clone, clap::Subcommand)]
pub enum ConfigCommand {
    /// Print every value as JSON
    List,
    /// Print one value, empty when it is unset
    Get {
        /// Configuration key
        key: String,
    },
    /// Store a value as a string
    Set {
        /// Configuration key
        key: String,
        /// Value to store
        value: String,
    },
    /// Remove a value
    Delete {
        /// Configuration key
        key: String,
    },
}

/// # Errors
///
/// Returns an error when the requested state cannot be read or written.
pub fn run(command: Command) -> Result<()> {
    let plugin_dir = Path::new(defs::PLUGIN_DIR);
    let lock_dir = Path::new(defs::PLUGIN_LOCK_DIR);
    let stage_dir = Path::new(defs::PLUGIN_STAGE_DIR);
    match command {
        Command::List => store::print_list(),
        Command::Config { id, command } => {
            match command {
                ConfigCommand::List => {
                    let values = config::load(plugin_dir, &id)?;
                    println!("{}", serde_json::to_string(&values)?);
                }
                ConfigCommand::Get { key } => println!("{}", config::get(plugin_dir, &id, &key)?),
                ConfigCommand::Set { key, value } => {
                    config::set(plugin_dir, lock_dir, &id, &key, &value)?;
                }
                ConfigCommand::Delete { key } => {
                    config::delete(plugin_dir, lock_dir, &id, &key)?;
                }
            }
            Ok(())
        }
        Command::Log { id } => {
            print!("{}", log::read(plugin_dir, &id)?);
            Ok(())
        }
        Command::ClearLog { id } => log::clear(plugin_dir, lock_dir, &id),
        Command::Install { zip } => {
            let id = install::install(plugin_dir, lock_dir, stage_dir, &zip)?;
            println!("installed {id}");
            Ok(())
        }
        Command::Uninstall { id } => {
            install::uninstall(plugin_dir, lock_dir, &id)?;
            println!("uninstalled {id}");
            Ok(())
        }
        Command::Enable { id } => {
            install::set_enabled(plugin_dir, lock_dir, &id, true)?;
            println!("enabled {id}");
            Ok(())
        }
        Command::Disable { id } => {
            install::set_enabled(plugin_dir, lock_dir, &id, false)?;
            println!("disabled {id}");
            Ok(())
        }
        Command::Run { id, callback } => {
            let code = runtime::run(plugin_dir, lock_dir, &id, &callback)?;
            report(code)
        }
        Command::Action { id } => {
            let code = runtime::run(plugin_dir, lock_dir, &id, "action")?;
            report(code)
        }
    }
}

/// Turns a callback result into the exit code of this process.
fn report(code: i32) -> Result<()> {
    if code == 0 {
        Ok(())
    } else {
        std::process::exit(code)
    }
}
