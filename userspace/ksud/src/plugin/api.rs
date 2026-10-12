//! The Lua API that plugin callbacks may call.
//!
//! Every function is deliberately small and bounded: a callback can fail, but
//! it cannot make the manager read an unbounded file, run unbounded output or
//! recurse without limit.

// The Lua binding signatures are fixed by what can be converted to and from Lua
// values, so some helpers take owned arguments and return a `Result` they never
// fail with.
#![allow(clippy::needless_pass_by_value, clippy::unnecessary_wraps)]

use std::collections::BTreeSet;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::os::unix::process::ExitStatusExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};

use anyhow::{Context as _, Result};
use mlua::{Lua, Table, Value, Variadic};

use crate::defs;
use crate::plugin::{config, manifest};

/// Largest amount of command output kept for one call.
pub const MAX_COMMAND_OUTPUT: usize = 4 * 1024 * 1024;
/// Largest file the API will read or write in one call.
pub const MAX_FILE_SIZE: u64 = 4 * 1024 * 1024;
/// Largest number of directory entries returned by one call.
pub const MAX_DIRECTORY_ENTRIES: usize = 10_000;
/// Largest JSON document accepted or produced by one call.
pub const MAX_JSON_SIZE: usize = 4 * 1024 * 1024;
/// Largest nesting depth accepted in a Lua value or JSON document.
pub const MAX_JSON_DEPTH: usize = 32;
/// Largest number of values accepted in a Lua value or JSON document.
pub const MAX_JSON_NODES: usize = 10_000;
/// Longest property name accepted.
pub const MAX_PROPERTY_NAME: usize = 256;
/// Largest number of arguments passed to one program.
pub const MAX_COMMAND_ARGUMENTS: usize = 256;
/// Memory the Lua state of one callback may use.
pub const MAX_MEMORY: usize = 64 * 1024 * 1024;

const TRUNCATION_MARKER: &str = "\n[output truncated]\n";

/// The state one callback runs against: which plugin it belongs to and where
/// its output is written.
pub struct Context {
    id: String,
    plugin_dir: PathBuf,
    lock_dir: PathBuf,
    log: Mutex<File>,
}

impl Context {
    /// Opens the plugin directory and its output log.
    ///
    /// # Errors
    /// Returns an error when the id is invalid or the log cannot be opened.
    pub fn new(plugin_dir: &Path, lock_dir: &Path, id: &str) -> Result<Arc<Self>> {
        manifest::validate_plugin_id(id)?;
        let dir = plugin_dir.join(id);
        let log = open_log(&dir)?;
        Ok(Arc::new(Self {
            id: id.to_owned(),
            plugin_dir: plugin_dir.to_path_buf(),
            lock_dir: lock_dir.to_path_buf(),
            log: Mutex::new(log),
        }))
    }

    /// Directory holding the running plugin.
    #[must_use]
    pub fn dir(&self) -> PathBuf {
        self.plugin_dir.join(&self.id)
    }

    /// Appends one line to the plugin log.
    pub fn log(&self, level: &str, message: &str) {
        let text = message.replace('\n', " ");
        if let Ok(mut file) = self.log.lock() {
            let _ = writeln!(file, "[{level}] {text}");
        }
    }
}

/// Opens the log for appending, dropping an oversized log first.
pub fn open_log(dir: &Path) -> Result<File> {
    let path = dir.join(defs::PLUGIN_LOG_FILE);
    let file = OpenOptions::new()
        .create(true)
        .append(true)
        .mode(0o600)
        .open(&path)
        .with_context(|| format!("cannot open {}", path.display()))?;
    if file.metadata().map_or(0, |data| data.len()) > defs::PLUGIN_MAX_LOG_SIZE {
        file.set_len(0)
            .with_context(|| format!("cannot truncate {}", path.display()))?;
    }
    Ok(file)
}

/// Registers every public function and global on a fresh Lua state.
///
/// # Errors
/// Returns an error when the Lua state cannot be extended.
pub fn bind(lua: &Lua, state: &Arc<Context>) -> mlua::Result<()> {
    let globals = lua.globals();
    globals.set("PLUGIN_ID", state.id.as_str())?;
    globals.set("PLUGIN_DIR", state.dir().to_string_lossy().as_ref())?;

    let sink = Arc::clone(state);
    globals.set(
        "info",
        lua.create_function(move |_, message: String| {
            sink.log("info", &message);
            Ok(())
        })?,
    )?;

    let sink = Arc::clone(state);
    globals.set(
        "warn",
        lua.create_function(move |_, message: String| {
            sink.log("warn", &message);
            Ok(())
        })?,
    )?;

    globals.set("getprop", lua.create_function(lua_getprop)?)?;
    globals.set("setprop", lua.create_function(lua_setprop)?)?;
    globals.set("exec", lua.create_function(lua_exec)?)?;
    globals.set("read_file", lua.create_function(lua_read_file)?)?;
    globals.set("write_file", lua.create_function(lua_write_file)?)?;
    globals.set("sysctl", lua.create_function(lua_sysctl)?)?;
    globals.set("chmod", lua.create_function(lua_chmod)?)?;
    globals.set("mkdir", lua.create_function(lua_mkdir)?)?;
    globals.set("rm", lua.create_function(lua_rm)?)?;
    globals.set("list_dir", lua.create_function(lua_list_dir)?)?;
    globals.set("file_exists", lua.create_function(lua_file_exists)?)?;
    globals.set("json_decode", lua.create_function(lua_json_decode)?)?;
    globals.set("json_encode", lua.create_function(lua_json_encode)?)?;

    let sink = Arc::clone(state);
    globals.set(
        "get_config",
        lua.create_function(move |_, key: String| {
            let value = config::get(&sink.plugin_dir, &sink.id, &key)
                .map_err(|error| mlua::Error::RuntimeError(error.to_string()))?;
            Ok(value)
        })?,
    )?;

    let sink = Arc::clone(state);
    globals.set(
        "set_config",
        lua.create_function(move |_, (key, value): (String, String)| {
            config::set(&sink.plugin_dir, &sink.lock_dir, &sink.id, &key, &value)
                .map_err(|error| mlua::Error::RuntimeError(error.to_string()))?;
            Ok(true)
        })?,
    )?;

    let sink = Arc::clone(state);
    globals.set(
        "start_daemon",
        lua.create_function(move |_, (_callback, _interval): (String, i64)| {
            sink.log("warn", "start_daemon is not available");
            Ok(false)
        })?,
    )?;

    Ok(())
}

fn runtime_error(message: impl Into<String>) -> mlua::Error {
    mlua::Error::RuntimeError(message.into())
}

fn lua_getprop(_lua: &Lua, name: String) -> mlua::Result<String> {
    check_property(&name)?;
    let output = Command::new("/system/bin/getprop")
        .arg(&name)
        .output()
        .map_err(|error| runtime_error(format!("cannot read property '{name}': {error}")))?;
    Ok(String::from_utf8_lossy(&output.stdout)
        .trim_end()
        .to_owned())
}

fn lua_setprop(_lua: &Lua, (name, value): (String, String)) -> mlua::Result<bool> {
    check_property(&name)?;
    if value.len() > MAX_PROPERTY_NAME || value.contains('\0') {
        return Err(runtime_error("invalid property value"));
    }
    let status = Command::new("/system/bin/setprop")
        .arg(&name)
        .arg(&value)
        .status()
        .map_err(|error| runtime_error(format!("cannot set property '{name}': {error}")))?;
    Ok(status.success())
}

fn check_property(name: &str) -> mlua::Result<()> {
    if name.is_empty() || name.len() > MAX_PROPERTY_NAME || name.contains('\0') {
        return Err(runtime_error(format!("invalid property name '{name}'")));
    }
    Ok(())
}

/// Reads at most `limit` bytes, reporting whether more was available.
fn read_capped(mut stream: impl Read, limit: usize) -> std::io::Result<(Vec<u8>, bool)> {
    let mut buffer = Vec::new();
    let mut chunk = [0_u8; 8192];
    let mut truncated = false;
    loop {
        let read = stream.read(&mut chunk)?;
        if read == 0 {
            break;
        }
        let remaining = limit.saturating_sub(buffer.len());
        if read > remaining {
            buffer.extend_from_slice(&chunk[..remaining]);
            truncated = true;
            break;
        }
        buffer.extend_from_slice(&chunk[..read]);
        if buffer.len() == limit {
            truncated = true;
            break;
        }
    }
    Ok((buffer, truncated))
}

fn lua_exec(lua: &Lua, args: Variadic<String>) -> mlua::Result<Table> {
    let Some((program, rest)) = args.split_first() else {
        return Err(runtime_error("exec needs a program"));
    };
    if rest.len() + 1 > MAX_COMMAND_ARGUMENTS {
        return Err(runtime_error("exec received too many arguments"));
    }
    if program.contains('\0') || rest.iter().any(|argument| argument.contains('\0')) {
        return Err(runtime_error("exec arguments must not contain a NUL byte"));
    }

    let mut child = Command::new(program)
        .args(rest)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| runtime_error(format!("cannot run '{program}': {error}")))?;

    let stdout = child.stdout.take();
    let stderr = child.stderr.take();
    let (out, err) = std::thread::scope(|scope| {
        let out = scope.spawn(|| stdout.map(|pipe| read_capped(pipe, MAX_COMMAND_OUTPUT)));
        let err = scope.spawn(|| stderr.map(|pipe| read_capped(pipe, MAX_COMMAND_OUTPUT)));
        (out.join(), err.join())
    });

    let (out, out_truncated) = flatten(out)?;
    let (err, err_truncated) = flatten(err)?;
    if out_truncated || err_truncated {
        // The pipes are no longer drained, so stop the program before waiting.
        let _ = child.kill();
    }
    let status = child
        .wait()
        .map_err(|error| runtime_error(format!("cannot wait for '{program}': {error}")))?;

    let (code, ok) = status.code().map_or_else(
        || (128 + status.signal().unwrap_or(0), false),
        |code| (code, status.success()),
    );

    let table = lua.create_table()?;
    table.set("ok", ok)?;
    table.set("code", code)?;
    table.set("stdout", with_marker(&out, out_truncated))?;
    table.set("stderr", with_marker(&err, err_truncated))?;
    Ok(table)
}

type Capped = std::thread::Result<Option<std::io::Result<(Vec<u8>, bool)>>>;

fn flatten(result: Capped) -> mlua::Result<(Vec<u8>, bool)> {
    let joined = result
        .map_err(|_| runtime_error("cannot read command output"))?
        .transpose()
        .map_err(|error| runtime_error(format!("cannot read command output: {error}")))?;
    Ok(joined.unwrap_or_default())
}

fn with_marker(bytes: &[u8], truncated: bool) -> String {
    let mut text = String::from_utf8_lossy(bytes).into_owned();
    if truncated {
        text.push_str(TRUNCATION_MARKER);
    }
    text
}

fn lua_read_file(_lua: &Lua, path: String) -> mlua::Result<String> {
    let path = PathBuf::from(path);
    let file = File::open(&path)
        .map_err(|error| runtime_error(format!("cannot read {}: {error}", path.display())))?;
    let mut bytes = Vec::new();
    file.take(MAX_FILE_SIZE + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| runtime_error(format!("cannot read {}: {error}", path.display())))?;
    if bytes.len() as u64 > MAX_FILE_SIZE {
        return Err(runtime_error(format!(
            "{} is larger than {MAX_FILE_SIZE} bytes",
            path.display()
        )));
    }
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

fn lua_write_file(_lua: &Lua, (path, content): (String, String)) -> mlua::Result<bool> {
    if content.len() as u64 > MAX_FILE_SIZE {
        return Err(runtime_error(format!(
            "content is larger than {MAX_FILE_SIZE} bytes"
        )));
    }
    let path = PathBuf::from(path);
    if let Some(parent) = path.parent()
        && !parent.as_os_str().is_empty()
        && fs::create_dir_all(parent).is_err()
    {
        return Ok(false);
    }
    let written = File::create(&path)
        .and_then(|mut file| {
            file.write_all(content.as_bytes())?;
            fs::set_permissions(&path, fs::Permissions::from_mode(0o644))
        })
        .is_ok();
    Ok(written)
}

fn lua_sysctl(_lua: &Lua, (name, value): (String, String)) -> mlua::Result<bool> {
    let path = sysctl_path(&name)?;
    if value.len() > MAX_PROPERTY_NAME || value.contains('\0') {
        return Err(runtime_error("invalid sysctl value"));
    }
    Ok(fs::write(&path, value.as_bytes()).is_ok())
}

fn sysctl_path(name: &str) -> mlua::Result<PathBuf> {
    if name.is_empty() || name.starts_with('/') || name.starts_with('.') {
        return Err(runtime_error(format!("invalid sysctl name '{name}'")));
    }
    let mut path = PathBuf::from("/proc/sys");
    for part in name.split('.') {
        if part.is_empty()
            || !part.chars().all(|character| {
                character.is_ascii_alphanumeric() || character == '_' || character == '-'
            })
        {
            return Err(runtime_error(format!("invalid sysctl name '{name}'")));
        }
        path.push(part);
    }
    Ok(path)
}

fn lua_chmod(_lua: &Lua, (path, mode): (String, Value)) -> mlua::Result<bool> {
    let mode = parse_mode(&mode)?;
    let path = PathBuf::from(path);
    Ok(fs::set_permissions(&path, fs::Permissions::from_mode(mode)).is_ok())
}

fn parse_mode(mode: &Value) -> mlua::Result<u32> {
    match mode {
        Value::Integer(value) => {
            if (0..=0o7777).contains(value) {
                Ok(*value as u32)
            } else {
                Err(runtime_error(format!("mode {value} is out of range")))
            }
        }
        Value::String(text) => {
            let text = text.to_str().map_err(|_| runtime_error("invalid mode"))?;
            let text = text.trim();
            if text.is_empty() || text.len() > 16 {
                return Err(runtime_error("invalid mode"));
            }
            // Lua has no octal literals, so a mode is normally written as a
            // quoted string such as "0755".
            u32::from_str_radix(text, 8)
                .ok()
                .filter(|mode| *mode <= 0o7777)
                .ok_or_else(|| runtime_error(format!("mode '{text}' is not a valid octal mode")))
        }
        other => Err(runtime_error(format!(
            "mode must be an integer or a string, not {}",
            other.type_name()
        ))),
    }
}

fn lua_mkdir(_lua: &Lua, (path, recursive): (String, bool)) -> mlua::Result<bool> {
    let path = PathBuf::from(path);
    let created = if recursive {
        fs::create_dir_all(&path)
    } else {
        fs::create_dir(&path)
    };
    match created {
        Ok(()) => Ok(true),
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => Ok(path.is_dir()),
        Err(_) => Ok(false),
    }
}

fn lua_rm(_lua: &Lua, path: String) -> mlua::Result<bool> {
    let path = PathBuf::from(path);
    Ok(fs::remove_file(&path)
        .or_else(|_| fs::remove_dir(&path))
        .is_ok())
}

fn lua_list_dir(lua: &Lua, path: String) -> mlua::Result<Table> {
    let path = PathBuf::from(path);
    let entries = fs::read_dir(&path)
        .map_err(|error| runtime_error(format!("cannot list {}: {error}", path.display())))?;
    let mut names = Vec::new();
    for entry in entries.flatten() {
        if names.len() == MAX_DIRECTORY_ENTRIES {
            return Err(runtime_error(format!(
                "{} has more than {MAX_DIRECTORY_ENTRIES} entries",
                path.display()
            )));
        }
        if let Some(name) = entry.file_name().to_str() {
            names.push(name.to_owned());
        }
    }
    names.sort();
    let table = lua.create_table()?;
    for (index, name) in names.iter().enumerate() {
        table.set(index + 1, name.as_str())?;
    }
    Ok(table)
}

fn lua_file_exists(_lua: &Lua, path: String) -> mlua::Result<bool> {
    Ok(Path::new(&path).exists())
}

fn lua_json_decode(lua: &Lua, text: String) -> mlua::Result<Value> {
    if text.len() > MAX_JSON_SIZE {
        return Err(runtime_error("json input is too large"));
    }
    let parsed: serde_json::Value = serde_json::from_str(&text)
        .map_err(|error| runtime_error(format!("cannot decode json: {error}")))?;
    json_to_lua(lua, &parsed)
}

fn json_to_lua(lua: &Lua, value: &serde_json::Value) -> mlua::Result<Value> {
    Ok(match value {
        serde_json::Value::Null => Value::Nil,
        serde_json::Value::Bool(flag) => Value::Boolean(*flag),
        serde_json::Value::Number(number) => number.as_i64().map_or_else(
            || Value::Number(number.as_f64().unwrap_or(0.0)),
            Value::Integer,
        ),
        serde_json::Value::String(text) => Value::String(lua.create_string(text)?),
        serde_json::Value::Array(items) => {
            let table = lua.create_table()?;
            for (index, item) in items.iter().enumerate() {
                table.set(index + 1, json_to_lua(lua, item)?)?;
            }
            Value::Table(table)
        }
        serde_json::Value::Object(map) => {
            let table = lua.create_table()?;
            for (key, item) in map {
                table.set(key.as_str(), json_to_lua(lua, item)?)?;
            }
            Value::Table(table)
        }
    })
}

fn lua_json_encode(_lua: &Lua, value: Value) -> mlua::Result<String> {
    let mut seen = BTreeSet::new();
    let mut nodes = 0_usize;
    let json = lua_to_json(&value, 0, &mut nodes, &mut seen)?;
    let text = serde_json::to_string(&json)
        .map_err(|error| runtime_error(format!("cannot encode json: {error}")))?;
    if text.len() > MAX_JSON_SIZE {
        return Err(runtime_error("encoded json is too large"));
    }
    Ok(text)
}

fn lua_to_json(
    value: &Value,
    depth: usize,
    nodes: &mut usize,
    seen: &mut BTreeSet<usize>,
) -> mlua::Result<serde_json::Value> {
    *nodes += 1;
    if *nodes > MAX_JSON_NODES {
        return Err(runtime_error("value has too many entries"));
    }
    if depth > MAX_JSON_DEPTH {
        return Err(runtime_error("value is nested too deeply"));
    }
    Ok(match value {
        Value::Nil => serde_json::Value::Null,
        Value::Boolean(flag) => serde_json::Value::Bool(*flag),
        Value::Integer(integer) => serde_json::Value::from(*integer),
        Value::Number(number) => {
            if number.is_finite() {
                serde_json::Value::from(*number)
            } else {
                return Err(runtime_error("cannot encode a non-finite number"));
            }
        }
        Value::String(text) => serde_json::Value::String(text.to_string_lossy()),
        Value::Table(table) => {
            let pointer = table.to_pointer() as usize;
            if !seen.insert(pointer) {
                return Err(runtime_error("value contains a cycle"));
            }
            let json = table_to_json(table, depth, nodes, seen)?;
            seen.remove(&pointer);
            json
        }
        other => {
            return Err(runtime_error(format!(
                "cannot encode a {} value",
                other.type_name()
            )));
        }
    })
}

fn table_to_json(
    table: &Table,
    depth: usize,
    nodes: &mut usize,
    seen: &mut BTreeSet<usize>,
) -> mlua::Result<serde_json::Value> {
    let mut entries = Vec::new();
    for pair in table.pairs::<Value, Value>() {
        let (key, value) = pair.map_err(|error| runtime_error(error.to_string()))?;
        entries.push((key, value));
    }

    let array = !entries.is_empty()
        && entries.iter().all(|(key, _)| {
            matches!(key, Value::Integer(index) if *index >= 1 && *index <= entries.len() as i64)
        });
    if array {
        let mut items = vec![serde_json::Value::Null; entries.len()];
        for (key, value) in entries {
            if let Value::Integer(index) = key {
                let slot = (index - 1) as usize;
                items[slot] = lua_to_json(&value, depth + 1, nodes, seen)?;
            }
        }
        return Ok(serde_json::Value::Array(items));
    }

    let mut map = serde_json::Map::new();
    for (key, value) in entries {
        let name = match key {
            Value::String(text) => text.to_string_lossy(),
            Value::Integer(integer) => integer.to_string(),
            other => {
                return Err(runtime_error(format!(
                    "cannot use a {} as an object key",
                    other.type_name()
                )));
            }
        };
        if map.contains_key(&name) {
            return Err(runtime_error(format!("duplicate key '{name}'")));
        }
        map.insert(name, lua_to_json(&value, depth + 1, nodes, seen)?);
    }
    Ok(serde_json::Value::Object(map))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn state() -> (tempfile::TempDir, Arc<Context>) {
        let root = tempfile::tempdir().unwrap();
        let plugins = root.path().join("plugins");
        let locks = root.path().join("locks");
        fs::create_dir_all(plugins.join("demo")).unwrap();
        fs::create_dir_all(&locks).unwrap();
        let state = Context::new(&plugins, &locks, "demo").unwrap();
        (root, state)
    }

    fn lua_with(state: &Arc<Context>) -> Lua {
        let lua = Lua::new_with(mlua::StdLib::ALL_SAFE, mlua::LuaOptions::default()).unwrap();
        bind(&lua, state).unwrap();
        lua
    }

    fn eval<T: mlua::FromLuaMulti>(lua: &Lua, source: &str) -> T {
        lua.load(source).eval().unwrap()
    }

    #[test]
    fn binds_every_public_function_and_global() {
        let (_root, state) = state();
        let lua = lua_with(&state);
        let names = eval::<Vec<String>>(
            &lua,
            r#"
            local result = {}
            for _, name in ipairs({
                "info", "warn", "getprop", "setprop", "exec", "read_file", "write_file",
                "sysctl", "chmod", "mkdir", "rm", "list_dir", "file_exists",
                "get_config", "set_config", "json_decode", "json_encode", "start_daemon",
            }) do
                result[#result + 1] = name .. "=" .. type(_G[name])
            end
            return result
            "#,
        );
        assert_eq!(names.len(), 18);
        assert!(names.iter().all(|entry| entry.ends_with("=function")));
        assert_eq!(eval::<String>(&lua, "return PLUGIN_ID"), "demo");
        assert!(eval::<String>(&lua, "return PLUGIN_DIR").ends_with("plugins/demo"));
    }

    #[test]
    fn exec_reports_status_and_output() {
        let (_root, state) = state();
        let lua = lua_with(&state);
        let result = eval::<Table>(
            &lua,
            r#"return exec("/bin/sh", "-c", "echo out; echo err 1>&2; exit 3")"#,
        );
        assert_eq!(result.get::<bool>("ok").unwrap(), false);
        assert_eq!(result.get::<i64>("code").unwrap(), 3);
        assert_eq!(result.get::<String>("stdout").unwrap(), "out\n");
        assert_eq!(result.get::<String>("stderr").unwrap(), "err\n");
    }

    #[test]
    fn exec_marks_truncated_output_and_reports_signals() {
        let (_root, state) = state();
        let lua = lua_with(&state);
        let result = eval::<Table>(&lua, r#"return exec("/bin/sh", "-c", "kill -9 $$")"#);
        assert_eq!(result.get::<bool>("ok").unwrap(), false);
        assert!(result.get::<i64>("code").unwrap() > 128);
    }

    #[test]
    fn exec_rejects_a_missing_program() {
        let (_root, state) = state();
        let lua = lua_with(&state);
        let failed = eval::<bool>(
            &lua,
            r#"local ok = pcall(exec, "__nonexistent_program_xyz__"); return ok"#,
        );
        assert!(!failed);
    }

    #[test]
    fn files_round_trip_within_the_plugin_directory() {
        let (root, state) = state();
        let lua = lua_with(&state);
        let path = root.path().join("scratch").join("note.txt");
        let path = path.to_string_lossy().to_string();
        assert!(eval::<bool>(
            &lua,
            &format!(r#"return write_file("{path}", "hello")"#)
        ));
        assert!(eval::<bool>(
            &lua,
            &format!(r#"return file_exists("{path}")"#)
        ));
        assert_eq!(
            eval::<String>(&lua, &format!(r#"return read_file("{path}")"#)),
            "hello"
        );
        assert!(eval::<bool>(&lua, &format!(r#"return rm("{path}")"#)));
        assert!(!eval::<bool>(
            &lua,
            &format!(r#"return file_exists("{path}")"#)
        ));
    }

    #[test]
    fn chmod_accepts_octal_text_and_rejects_a_bad_mode() {
        let (root, state) = state();
        let lua = lua_with(&state);
        let path = root.path().join("scratch").join("mode.txt");
        let path = path.to_string_lossy().to_string();
        assert!(eval::<bool>(
            &lua,
            &format!(r#"return write_file("{path}", "x")"#)
        ));
        assert!(eval::<bool>(
            &lua,
            &format!(r#"return chmod("{path}", "644")"#)
        ));
        let mode = fs::metadata(root.path().join("scratch").join("mode.txt"))
            .unwrap()
            .permissions()
            .mode();
        assert_eq!(mode & 0o777, 0o644);
        assert!(!eval::<bool>(
            &lua,
            &format!(r#"return pcall(chmod, "{path}", "999")"#)
        ));
    }

    #[test]
    fn mkdir_is_idempotent_and_list_dir_is_sorted() {
        let (root, state) = state();
        let lua = lua_with(&state);
        let dir = root.path().join("scratch").join("nested");
        let dir = dir.to_string_lossy().to_string();
        assert!(eval::<bool>(
            &lua,
            &format!(r#"return mkdir("{dir}", true)"#)
        ));
        assert!(eval::<bool>(
            &lua,
            &format!(r#"return mkdir("{dir}", true)"#)
        ));
        for name in ["b.txt", "a.txt"] {
            eval::<bool>(&lua, &format!(r#"return write_file("{dir}/{name}", "x")"#));
        }
        let names = eval::<Vec<String>>(&lua, &format!(r#"return list_dir("{dir}")"#));
        assert_eq!(names, vec!["a.txt", "b.txt"]);
    }

    #[test]
    fn list_dir_rejects_a_missing_directory() {
        let (_root, state) = state();
        let lua = lua_with(&state);
        let failed = eval::<bool>(&lua, r#"return pcall(list_dir, "/nonexistent-dir-xyz")"#);
        assert!(!failed, "a missing directory must raise");
    }

    #[test]
    fn sysctl_rejects_malformed_names() {
        let (_root, state) = state();
        let lua = lua_with(&state);
        for name in ["", "/kernel.panic", "kernel..panic", "kernel/p anic"] {
            let succeeded = eval::<bool>(&lua, &format!(r#"return pcall(sysctl, "{name}", "0")"#));
            assert!(!succeeded, "expected '{name}' to be rejected");
        }
    }

    #[test]
    fn json_round_trips_documents() {
        let (_root, state) = state();
        let lua = lua_with(&state);
        let text = eval::<String>(
            &lua,
            r#"return json_encode({ a = 1, b = { 1, 2, 3 }, c = "héllo" })"#,
        );
        let parsed: serde_json::Value = serde_json::from_str(&text).unwrap();
        assert_eq!(parsed["b"], serde_json::json!([1, 2, 3]));

        let decoded = eval::<Table>(
            &lua,
            r#"return json_decode('{"a":1,"b":[1,2,3],"c":null,"d":"héllo"}')"#,
        );
        assert_eq!(decoded.get::<i64>("a").unwrap(), 1);
        assert_eq!(decoded.get::<Value>("c").unwrap(), Value::Nil);
        assert_eq!(decoded.get::<String>("d").unwrap(), "héllo");
    }

    #[test]
    fn json_encoding_rejects_cycles_and_bad_values() {
        let (_root, state) = state();
        let lua = lua_with(&state);
        assert!(!eval::<bool>(
            &lua,
            "local t = {}; t.self = t; return pcall(json_encode, t)"
        ));
        assert!(!eval::<bool>(
            &lua,
            r#"return pcall(json_encode, { [true] = 1 })"#
        ));
    }

    #[test]
    fn config_functions_use_the_plugin_directory() {
        let (_root, state) = state();
        let lua = lua_with(&state);
        assert_eq!(
            eval::<String>(&lua, r#"return get_config("mode")"#),
            String::new()
        );
        assert!(eval::<bool>(&lua, r#"return set_config("mode", "fast")"#));
        assert_eq!(eval::<String>(&lua, r#"return get_config("mode")"#), "fast");
    }

    #[test]
    fn logs_are_written_next_to_the_package() {
        let (root, state) = state();
        let lua = lua_with(&state);
        eval::<()>(&lua, r#"info("started")"#);
        eval::<()>(&lua, r#"warn("careful")"#);
        let text = fs::read_to_string(root.path().join("plugins/demo").join(defs::PLUGIN_LOG_FILE))
            .unwrap();
        assert!(text.contains("[info] started"));
        assert!(text.contains("[warn] careful"));
    }

    #[test]
    fn start_daemon_reports_that_it_is_unavailable() {
        let (_root, state) = state();
        let lua = lua_with(&state);
        assert!(!eval::<bool>(&lua, r#"return start_daemon("tick", 5)"#));
    }
}
