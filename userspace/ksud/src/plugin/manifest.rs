//! Plugin manifest parsing and validation.

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::Path;

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};

use crate::defs;

/// A user-configurable field declared by a plugin.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ConfigField {
    pub key: String,
    #[serde(default)]
    pub label: String,
    #[serde(default)]
    pub labels: BTreeMap<String, String>,
    #[serde(default = "default_field_type", rename = "type")]
    pub field_type: String,
    #[serde(default)]
    pub default: Option<serde_json::Value>,
    #[serde(default)]
    pub options: Vec<String>,
}

fn default_field_type() -> String {
    "text".to_owned()
}

/// A quick action exposed by a plugin.
#[derive(Debug, Clone, Serialize)]
pub struct QuickAction {
    pub function: String,
    pub label: String,
    pub labels: BTreeMap<String, String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
enum RawQuickAction {
    Function(String),
    Object {
        function: String,
        #[serde(default)]
        label: String,
        #[serde(default)]
        labels: BTreeMap<String, String>,
    },
}

#[derive(Debug, Clone, Deserialize)]
struct RawManifest {
    id: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    author: String,
    #[serde(default)]
    version: String,
    #[serde(default)]
    description: String,
    #[serde(default)]
    descriptions: BTreeMap<String, String>,
    #[serde(default)]
    license: String,
    #[serde(default)]
    min_version: Option<u32>,
    #[serde(default)]
    depends: Vec<String>,
    #[serde(default)]
    entry: Option<String>,
    #[serde(default)]
    config: Vec<ConfigField>,
    #[serde(default)]
    quick_action: Option<RawQuickAction>,
    #[serde(default)]
    has_action: Option<bool>,
    #[serde(default)]
    callbacks: Vec<String>,
}

/// A validated manifest with defaults applied.
#[derive(Debug, Clone)]
pub struct PluginManifest {
    pub id: String,
    pub name: String,
    pub author: String,
    pub version: String,
    pub description: String,
    pub descriptions: BTreeMap<String, String>,
    pub license: String,
    pub depends: Vec<String>,
    pub entry: String,
    pub config: Vec<ConfigField>,
    pub quick_action: Option<QuickAction>,
    pub has_action: bool,
    pub callbacks: Vec<String>,
}

/// Parses and validates manifest bytes.
///
/// # Errors
/// Returns an error when the bytes are not valid JSON, exceed the size limit,
/// or fail any field validation.
pub fn parse_manifest(bytes: &[u8], expected_id: Option<&str>) -> Result<PluginManifest> {
    if bytes.len() as u64 > defs::PLUGIN_MAX_MANIFEST_SIZE {
        bail!("manifest exceeds {} bytes", defs::PLUGIN_MAX_MANIFEST_SIZE);
    }
    let raw: RawManifest =
        serde_json::from_slice(bytes).context("failed to parse plugin manifest")?;

    validate_plugin_id(&raw.id)?;
    if let Some(expected) = expected_id
        && raw.id != expected
    {
        bail!("manifest id '{}' does not match '{expected}'", raw.id);
    }

    let entry = raw.entry.unwrap_or_else(|| defs::PLUGIN_ENTRY.to_owned());
    validate_entry(&entry)?;

    for dependency in &raw.depends {
        validate_plugin_id(dependency)?;
        if dependency == &raw.id {
            bail!("plugin '{}' depends on itself", raw.id);
        }
    }
    ensure_distinct(&raw.depends, "depends")?;

    for callback in &raw.callbacks {
        validate_callback(callback)?;
    }

    for field in &raw.config {
        validate_config_key(&field.key)?;
        if !matches!(
            field.field_type.as_str(),
            "text" | "number" | "bool" | "select"
        ) {
            bail!(
                "config field '{}' has unsupported type '{}'",
                field.key,
                field.field_type
            );
        }
        if field.field_type == "select" && field.options.is_empty() {
            bail!("select config field '{}' declares no options", field.key);
        }
    }
    let keys: Vec<String> = raw.config.iter().map(|field| field.key.clone()).collect();
    ensure_distinct(&keys, "config field")?;

    let quick_action = match raw.quick_action {
        None => None,
        Some(RawQuickAction::Function(function)) => {
            validate_callback(&function)?;
            Some(QuickAction {
                label: function.clone(),
                function,
                labels: BTreeMap::new(),
            })
        }
        Some(RawQuickAction::Object {
            function,
            label,
            labels,
        }) => {
            validate_callback(&function)?;
            let label = if label.is_empty() {
                function.clone()
            } else {
                label
            };
            Some(QuickAction {
                function,
                label,
                labels,
            })
        }
    };

    if let Some(min) = raw.min_version {
        let host = host_version_code();
        if min > host {
            bail!(
                "plugin '{}' requires version code {min}, host is {host}",
                raw.id
            );
        }
    }

    let declares_action = raw.callbacks.iter().any(|callback| callback == "action")
        || quick_action
            .as_ref()
            .is_some_and(|quick| quick.function == "action");
    let has_action = raw.has_action.unwrap_or(false) || declares_action;

    Ok(PluginManifest {
        id: raw.id,
        name: raw.name,
        author: raw.author,
        version: raw.version,
        description: raw.description,
        descriptions: raw.descriptions,
        license: raw.license,
        depends: raw.depends,
        entry,
        config: raw.config,
        quick_action,
        has_action,
        callbacks: raw.callbacks,
    })
}

/// Reads and validates the manifest of the plugin in `dir`.
///
/// # Errors
/// Returns an error when the manifest is missing, unreadable, too large or
/// invalid.
pub fn read_manifest(dir: &Path, expected_id: &str) -> Result<PluginManifest> {
    let path = dir.join(defs::PLUGIN_MANIFEST);
    let metadata =
        fs::metadata(&path).with_context(|| format!("cannot stat {}", path.display()))?;
    if metadata.len() > defs::PLUGIN_MAX_MANIFEST_SIZE {
        bail!("manifest exceeds {} bytes", defs::PLUGIN_MAX_MANIFEST_SIZE);
    }
    let bytes = fs::read(&path).with_context(|| format!("cannot read {}", path.display()))?;
    parse_manifest(&bytes, Some(expected_id))
}

/// Checks that `id` is a single safe path component.
///
/// # Errors
/// Returns an error when `id` is empty, too long, or not a plain ASCII name.
pub fn validate_plugin_id(id: &str) -> Result<()> {
    if id.is_empty() {
        bail!("plugin id is empty");
    }
    if id.len() > defs::PLUGIN_MAX_ID_LEN {
        bail!("plugin id exceeds {} characters", defs::PLUGIN_MAX_ID_LEN);
    }
    if id == "." || id == ".." {
        bail!("plugin id must not be a path component");
    }
    let first = id.as_bytes()[0];
    if !first.is_ascii_alphanumeric() {
        bail!("plugin id must start with an ASCII letter or digit");
    }
    if !id
        .bytes()
        .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.'))
    {
        bail!("plugin id contains an unsupported character");
    }
    Ok(())
}

/// Checks that `entry` is a plain file name inside the plugin directory.
///
/// # Errors
/// Returns an error when `entry` is empty, too long, or contains a path
/// separator or a parent component.
pub fn validate_entry(entry: &str) -> Result<()> {
    if entry.is_empty() {
        bail!("plugin entry is empty");
    }
    if entry.len() > defs::PLUGIN_MAX_ENTRY_LEN {
        bail!(
            "plugin entry exceeds {} characters",
            defs::PLUGIN_MAX_ENTRY_LEN
        );
    }
    if entry == "." || entry == ".." {
        bail!("plugin entry must be a file name");
    }
    if !entry
        .bytes()
        .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.'))
    {
        bail!("plugin entry must be a plain file name without path separators");
    }
    Ok(())
}

/// Checks that `name` is a callable Lua function name.
///
/// # Errors
/// Returns an error when `name` is empty, too long, or not an ASCII
/// identifier.
pub fn validate_callback(name: &str) -> Result<()> {
    if name.is_empty() {
        bail!("callback name is empty");
    }
    if name.len() > defs::PLUGIN_MAX_CALLBACK_LEN {
        bail!(
            "callback name exceeds {} characters",
            defs::PLUGIN_MAX_CALLBACK_LEN
        );
    }
    let first = name.as_bytes()[0];
    if !(first.is_ascii_alphanumeric() || first == b'_') {
        bail!("callback name must start with a letter, digit or underscore");
    }
    if !name
        .bytes()
        .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
    {
        bail!("callback name contains an unsupported character");
    }
    Ok(())
}

/// Checks that `key` is a usable config key.
///
/// # Errors
/// Returns an error when `key` is empty, too long, or contains a path
/// separator.
pub fn validate_config_key(key: &str) -> Result<()> {
    if key.is_empty() {
        bail!("config key is empty");
    }
    if key.len() > defs::PLUGIN_MAX_CONFIG_KEY_LEN {
        bail!(
            "config key exceeds {} characters",
            defs::PLUGIN_MAX_CONFIG_KEY_LEN
        );
    }
    if key.contains('/') || key.contains('\\') {
        bail!("config key must not contain a path separator");
    }
    Ok(())
}

/// Returns the version code of the running host.
pub fn host_version_code() -> u32 {
    defs::VERSION_CODE.parse().unwrap_or(0)
}

fn ensure_distinct(values: &[String], what: &str) -> Result<()> {
    let mut seen = BTreeSet::new();
    for value in values {
        if !seen.insert(value.as_str()) {
            bail!("duplicate {what} '{value}'");
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{parse_manifest, validate_callback, validate_entry, validate_plugin_id};

    fn manifest(body: &str) -> anyhow::Result<super::PluginManifest> {
        parse_manifest(body.as_bytes(), None)
    }

    #[test]
    fn accepts_minimal_manifest() {
        let parsed = manifest(r#"{"id":"demo"}"#).expect("valid");
        assert_eq!(parsed.entry, "main.lua");
        assert!(parsed.depends.is_empty());
        assert!(!parsed.has_action);
    }

    #[test]
    fn rejects_id_mismatch() {
        let error = parse_manifest(br#"{"id":"demo"}"#, Some("other")).expect_err("mismatch");
        assert!(error.to_string().contains("does not match"));
    }

    #[test]
    fn validates_plugin_ids() {
        assert!(validate_plugin_id("hello-plugin").is_ok());
        assert!(validate_plugin_id("a.b_c-1").is_ok());
        assert!(validate_plugin_id("").is_err());
        assert!(validate_plugin_id(".").is_err());
        assert!(validate_plugin_id("..").is_err());
        assert!(validate_plugin_id("../etc").is_err());
        assert!(validate_plugin_id("a/b").is_err());
        assert!(validate_plugin_id("-leading").is_err());
        assert!(validate_plugin_id("星空").is_err());
        assert!(validate_plugin_id(&"a".repeat(65)).is_err());
    }

    #[test]
    fn validates_entries_and_callbacks() {
        assert!(validate_entry("main.lua").is_ok());
        assert!(validate_entry("sub/main.lua").is_err());
        assert!(validate_entry("..").is_err());
        assert!(validate_callback("post_fs_data").is_ok());
        assert!(validate_callback("_hidden").is_ok());
        assert!(validate_callback("has-dash").is_err());
        assert!(validate_callback("has.dot").is_err());
        assert!(validate_callback("").is_err());
    }

    #[test]
    fn rejects_self_and_duplicate_dependencies() {
        assert!(manifest(r#"{"id":"a","depends":["a"]}"#).is_err());
        assert!(manifest(r#"{"id":"a","depends":["b","b"]}"#).is_err());
        assert!(manifest(r#"{"id":"a","depends":["b"]}"#).is_ok());
    }

    #[test]
    fn normalizes_quick_action() {
        let parsed = manifest(r#"{"id":"a","quick_action":"clean_now"}"#).expect("valid");
        let quick = parsed.quick_action.expect("present");
        assert_eq!(quick.function, "clean_now");
        assert_eq!(quick.label, "clean_now");

        let parsed = manifest(r#"{"id":"a","quick_action":{"function":"action","label":"Run"}}"#)
            .expect("valid");
        assert!(parsed.has_action);
    }

    #[test]
    fn validates_config_fields() {
        assert!(manifest(r#"{"id":"a","config":[{"key":"n","type":"number"}]}"#).is_ok());
        assert!(manifest(r#"{"id":"a","config":[{"key":"n","type":"mystery"}]}"#).is_err());
        assert!(manifest(r#"{"id":"a","config":[{"key":"s","type":"select"}]}"#).is_err());
        assert!(
            manifest(r#"{"id":"a","config":[{"key":"s","type":"select","options":["x"]}]}"#)
                .is_ok()
        );
        assert!(manifest(r#"{"id":"a","config":[{"key":"k"},{"key":"k"}]}"#).is_err());
        assert!(manifest(r#"{"id":"a","config":[{"key":"a/b"}]}"#).is_err());
    }

    #[test]
    fn enforces_min_version() {
        assert!(manifest(r#"{"id":"a","min_version":0}"#).is_ok());
        assert!(manifest(&format!(r#"{{"id":"a","min_version":{}}}"#, u32::MAX)).is_err());
    }

    #[test]
    fn derives_has_action_from_callbacks() {
        let parsed = manifest(r#"{"id":"a","callbacks":["action"]}"#).expect("valid");
        assert!(parsed.has_action);
        let parsed = manifest(r#"{"id":"a","has_action":true}"#).expect("valid");
        assert!(parsed.has_action);
    }

    #[test]
    fn rejects_oversized_and_malformed_manifests() {
        let huge = vec![b' '; (super::defs::PLUGIN_MAX_MANIFEST_SIZE + 1) as usize];
        assert!(parse_manifest(&huge, None).is_err());
        assert!(manifest("{").is_err());
        assert!(manifest("[]").is_err());
        assert!(manifest("{}").is_err());
    }
}
