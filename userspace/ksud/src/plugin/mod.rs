//! Plugin packages: manifest, storage and execution.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

pub mod manifest;
pub mod store;

use anyhow::Result;

#[derive(Debug, Clone, Copy, clap::Subcommand)]
pub enum Command {
    /// List installed plugins as JSON
    List,
}

pub fn run(command: Command) -> Result<()> {
    match command {
        Command::List => store::print_list(),
    }
}
