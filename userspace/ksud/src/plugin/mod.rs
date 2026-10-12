//! Plugin packages: manifest, storage and execution.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

pub mod config;
pub mod lock;
pub mod manifest;
pub mod store;

use std::path::Path;

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
    }
}
