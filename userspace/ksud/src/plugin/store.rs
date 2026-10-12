//! Plugin discovery and listing.
//!
//! Listing only reads the package metadata. It never loads or runs plugin
//! code, so a broken or hostile entry cannot affect the listing itself.

use std::collections::BTreeMap;
use std::fs;
use std::path::Path;

use anyhow::{Context, Result};
use serde::Serialize;

use super::manifest::{self, ConfigField, QuickAction};
use crate::defs;

/// One discovered plugin. Invalid packages are reported instead of skipped so
/// the user can see why a plugin does not work.
#[derive(Debug, Clone, Serialize)]
pub struct PluginInfo {
    pub id: String,
    pub name: String,
    pub author: String,
    pub version: String,
    pub description: String,
    pub descriptions: BTreeMap<String, String>,
    pub license: String,
    pub enabled: bool,
    pub has_manifest: bool,
    pub has_action: bool,
    pub entry: String,
    pub quick_action: Option<QuickAction>,
    pub config: Vec<ConfigField>,
    pub depends: Vec<String>,
    pub callbacks: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

/// Lists the plugins installed under `dir`, sorted by id.
///
/// A missing directory is not an error: it simply means no plugin is
/// installed yet.
///
/// # Errors
/// Returns an error when `dir` exists but its contents cannot be read.
pub fn discover(dir: &Path) -> Result<Vec<PluginInfo>> {
    let entries = match fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(error).with_context(|| format!("cannot read {}", dir.display())),
    };

    let mut plugins = Vec::new();
    for entry in entries {
        let entry = entry.with_context(|| format!("cannot read {}", dir.display()))?;
        let Some(id) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        plugins.push(inspect(&path, &id));
    }
    plugins.sort_by(|left, right| left.id.cmp(&right.id));
    Ok(plugins)
}

/// Prints the plugin list as a JSON array on stdout.
///
/// # Errors
/// Returns an error when the plugin directory or the manifest of no plugin can
/// be read; an unreadable manifest is reported per item instead.
pub fn print_list() -> Result<()> {
    let plugins = discover(Path::new(defs::PLUGIN_DIR))?;
    println!("{}", serde_json::to_string(&plugins)?);
    Ok(())
}

fn inspect(path: &Path, id: &str) -> PluginInfo {
    let enabled = !path.join(defs::PLUGIN_DISABLE_FILE).exists();
    let has_manifest = path.join(defs::PLUGIN_MANIFEST).exists();
    match manifest::read_manifest(path, id) {
        Ok(manifest) => PluginInfo {
            id: manifest.id,
            name: manifest.name,
            author: manifest.author,
            version: manifest.version,
            description: manifest.description,
            descriptions: manifest.descriptions,
            license: manifest.license,
            enabled,
            has_manifest: true,
            has_action: manifest.has_action,
            entry: manifest.entry,
            quick_action: manifest.quick_action,
            config: manifest.config,
            depends: manifest.depends,
            callbacks: manifest.callbacks,
            error: None,
        },
        Err(error) => PluginInfo {
            id: id.to_owned(),
            name: String::new(),
            author: String::new(),
            version: String::new(),
            description: String::new(),
            descriptions: BTreeMap::new(),
            license: String::new(),
            enabled,
            has_manifest,
            has_action: false,
            entry: String::new(),
            quick_action: None,
            config: Vec::new(),
            depends: Vec::new(),
            callbacks: Vec::new(),
            error: Some(error.to_string()),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn write_manifest(dir: &Path, manifest: &str) {
        fs::create_dir_all(dir).unwrap();
        fs::write(dir.join(defs::PLUGIN_MANIFEST), manifest).unwrap();
    }

    #[test]
    fn reports_missing_directory_as_empty() {
        let root = tempfile::tempdir().unwrap();
        let plugins = discover(&root.path().join("absent")).unwrap();
        assert!(plugins.is_empty());
    }

    #[test]
    fn lists_valid_plugins_sorted_by_id() {
        let root = tempfile::tempdir().unwrap();
        for id in ["zeta", "alpha"] {
            write_manifest(
                &root.path().join(id),
                &format!(r#"{{"id":"{id}","name":"{id}"}}"#),
            );
        }
        let plugins = discover(root.path()).unwrap();
        let ids: Vec<_> = plugins.iter().map(|p| p.id.as_str()).collect();
        assert_eq!(ids, ["alpha", "zeta"]);
        assert!(plugins.iter().all(|p| p.error.is_none() && p.has_manifest));
    }

    #[test]
    fn skips_files_and_reports_invalid_packages() {
        let root = tempfile::tempdir().unwrap();
        fs::write(root.path().join("stray.txt"), "not a plugin").unwrap();
        fs::create_dir_all(root.path().join("broken")).unwrap();
        let hidden = root.path().join("quiet");
        write_manifest(&hidden, r#"{"id":"quiet","name":"Quiet"}"#);
        fs::write(hidden.join(defs::PLUGIN_DISABLE_FILE), "").unwrap();

        let plugins = discover(root.path()).unwrap();
        let ids: Vec<_> = plugins.iter().map(|p| p.id.as_str()).collect();
        assert_eq!(ids, ["broken", "quiet"]);

        let broken = &plugins[0];
        assert!(!broken.has_manifest);
        assert!(broken.error.is_some());

        let quiet = &plugins[1];
        assert!(!quiet.enabled);
        assert!(quiet.error.is_none());
    }

    #[test]
    fn exposes_declared_action_and_config() {
        let root = tempfile::tempdir().unwrap();
        write_manifest(
            &root.path().join("worker"),
            r#"{"id":"worker","quick_action":{"function":"clean_now","label":"Clean"},
               "config":[{"key":"days","type":"number","default":7}]}"#,
        );
        let plugins = discover(root.path()).unwrap();
        let worker = &plugins[0];
        assert!(!worker.has_action);
        assert_eq!(
            worker.quick_action.as_ref().map(|q| q.function.as_str()),
            Some("clean_now")
        );
        assert_eq!(worker.config.len(), 1);
        assert_eq!(worker.config[0].field_type, "number");
    }
}
