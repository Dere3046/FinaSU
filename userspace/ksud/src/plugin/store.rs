//! Plugin discovery and listing.
//!
//! Listing only reads the package metadata. It never loads or runs plugin
//! code, so a broken or hostile entry cannot affect the listing itself.

use std::collections::{BTreeMap, BTreeSet};
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

/// The order in which the enabled plugins should run.
#[derive(Debug, Default, Serialize)]
pub struct Plan {
    /// Plugin ids that can run, dependencies first.
    pub runnable: Vec<String>,
    /// Plugins that are left out, with the reason.
    pub skipped: Vec<(String, String)>,
}

/// Orders the enabled plugins so that every dependency runs before the plugins
/// that need it. Ties are broken by id, so the order only depends on the set of
/// installed plugins. A plugin whose dependency is missing, disabled or invalid
/// is left out with the reason, and so is every plugin that needs it; a
/// dependency cycle leaves all of its members out rather than picking one.
pub fn execution_order(plugins: &[PluginInfo]) -> Plan {
    let enabled: BTreeMap<&str, &PluginInfo> = plugins
        .iter()
        .filter(|plugin| plugin.enabled && plugin.error.is_none())
        .map(|plugin| (plugin.id.as_str(), plugin))
        .collect();

    let mut skipped: BTreeMap<String, String> = BTreeMap::new();
    loop {
        let mut changed = false;
        for (id, plugin) in &enabled {
            if skipped.contains_key(*id) {
                continue;
            }
            for dependency in &plugin.depends {
                let reason = if enabled.contains_key(dependency.as_str()) {
                    skipped.get(dependency).map(|reason| {
                        format!("requires '{dependency}', which is skipped ({reason})")
                    })
                } else {
                    Some(describe_absent(plugins, dependency))
                };
                if let Some(reason) = reason {
                    skipped.insert((*id).to_owned(), reason);
                    changed = true;
                    break;
                }
            }
        }
        if !changed {
            break;
        }
    }

    let mut runnable = Vec::new();
    let mut done: BTreeSet<&str> = BTreeSet::new();
    loop {
        let mut next = None;
        for (id, plugin) in &enabled {
            if done.contains(*id) || skipped.contains_key(*id) {
                continue;
            }
            if plugin
                .depends
                .iter()
                .all(|dependency| done.contains(dependency.as_str()))
            {
                next = Some(*id);
                break;
            }
        }
        match next {
            Some(id) => {
                done.insert(id);
                runnable.push(id.to_owned());
            }
            None => break,
        }
    }

    for id in enabled.keys() {
        if !done.contains(*id) {
            skipped
                .entry((*id).to_owned())
                .or_insert_with(|| "dependency cycle".to_owned());
        }
    }

    Plan {
        runnable,
        skipped: skipped.into_iter().collect(),
    }
}

fn describe_absent(plugins: &[PluginInfo], dependency: &str) -> String {
    match plugins.iter().find(|plugin| plugin.id == dependency) {
        Some(plugin) if plugin.error.is_some() => {
            format!("requires '{dependency}', whose manifest is invalid")
        }
        Some(_) => format!("requires '{dependency}', which is disabled"),
        None => format!("requires '{dependency}', which is not installed"),
    }
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

    fn info(id: &str, enabled: bool, error: Option<&str>, depends: &[&str]) -> PluginInfo {
        PluginInfo {
            id: id.to_owned(),
            name: String::new(),
            author: String::new(),
            version: String::new(),
            description: String::new(),
            descriptions: BTreeMap::new(),
            license: String::new(),
            enabled,
            has_manifest: error.is_none(),
            has_action: false,
            entry: defs::PLUGIN_ENTRY.to_owned(),
            quick_action: None,
            config: Vec::new(),
            depends: depends.iter().map(|dep| (*dep).to_owned()).collect(),
            callbacks: Vec::new(),
            error: error.map(str::to_owned),
        }
    }

    #[test]
    fn orders_dependencies_before_dependents() {
        let plugins = [
            info("top", true, None, &["middle"]),
            info("middle", true, None, &["base"]),
            info("base", true, None, &[]),
        ];
        let plan = execution_order(&plugins);
        assert_eq!(plan.runnable, ["base", "middle", "top"]);
        assert!(plan.skipped.is_empty());
    }

    #[test]
    fn breaks_ties_by_id() {
        let plugins = [
            info("zeta", true, None, &[]),
            info("alpha", true, None, &[]),
        ];
        assert_eq!(execution_order(&plugins).runnable, ["alpha", "zeta"]);
    }

    #[test]
    fn leaves_out_plugins_whose_dependencies_cannot_run() {
        let plugins = [
            info("absent", true, None, &["nowhere"]),
            info("disabled", true, None, &["off"]),
            info("off", false, None, &[]),
            info("child", true, None, &["absent"]),
            info("alone", true, None, &[]),
        ];
        let plan = execution_order(&plugins);
        assert_eq!(plan.runnable, ["alone"]);
        let reasons: BTreeMap<_, _> = plan.skipped.iter().cloned().collect();
        assert_eq!(
            reasons.get("absent").map(String::as_str),
            Some("requires 'nowhere', which is not installed")
        );
        assert_eq!(
            reasons.get("disabled").map(String::as_str),
            Some("requires 'off', which is disabled")
        );
        assert!(reasons.contains_key("child"));
    }

    #[test]
    fn leaves_out_plugins_that_depend_on_an_invalid_manifest() {
        let plugins = [
            info("broken", true, Some("plugin.json is not valid JSON"), &[]),
            info("child", true, None, &["broken"]),
        ];
        let plan = execution_order(&plugins);
        assert!(plan.runnable.is_empty());
        let reasons: BTreeMap<_, _> = plan.skipped.iter().cloned().collect();
        assert_eq!(
            reasons.get("child").map(String::as_str),
            Some("requires 'broken', whose manifest is invalid")
        );
    }

    #[test]
    fn leaves_out_every_plugin_of_a_dependency_cycle() {
        let plugins = [
            info("left", true, None, &["right"]),
            info("right", true, None, &["left"]),
        ];
        let plan = execution_order(&plugins);
        assert!(plan.runnable.is_empty());
        let reasons: BTreeMap<_, _> = plan.skipped.iter().cloned().collect();
        assert_eq!(
            reasons.get("left").map(String::as_str),
            Some("dependency cycle")
        );
        assert_eq!(
            reasons.get("right").map(String::as_str),
            Some("dependency cycle")
        );
    }

    #[test]
    fn never_runs_a_disabled_plugin() {
        let plugins = [info("off", false, None, &[])];
        let plan = execution_order(&plugins);
        assert!(plan.runnable.is_empty());
        assert!(plan.skipped.is_empty());
    }

    #[test]
    fn a_dependency_runs_before_an_earlier_id() {
        let plugins = [
            info("zz-base", true, None, &[]),
            info("aa-top", true, None, &["zz-base"]),
        ];
        let plan = execution_order(&plugins);
        assert_eq!(plan.runnable, ["zz-base", "aa-top"]);
        assert!(plan.skipped.is_empty());
    }
}
