//! Probes the plugin Lua engine.

use anyhow::Result;
use mlua::{Function, Lua, Table, Value, Variadic};

/// The 18-name plugin public API.
const API_NAMES: [&str; 18] = [
    "info",
    "warn",
    "getprop",
    "setprop",
    "exec",
    "read_file",
    "write_file",
    "sysctl",
    "chmod",
    "mkdir",
    "rm",
    "list_dir",
    "file_exists",
    "get_config",
    "set_config",
    "json_decode",
    "json_encode",
    "start_daemon",
];

/// Entry source returning a callback table.
const ENTRY: &str = r#"
local M = {}
function M.probe()
  local names = {}
  for _, n in ipairs({
    "info","warn","getprop","setprop","exec","read_file","write_file","sysctl",
    "chmod","mkdir","rm","list_dir","file_exists","get_config","set_config",
    "json_decode","json_encode","start_daemon" }) do
    names[#names+1] = n .. "=" .. type(_G[n])
  end
  return table.concat(names, "\n")
end
return M
"#;

/// `mlua::Error` is not `Send`, so it cannot flow through `anyhow` via `?`.
pub fn run() -> Result<()> {
    probe().map_err(|e| anyhow::anyhow!("lua probe failed: {e}"))
}

fn probe() -> mlua::Result<()> {
    let lua = Lua::new();
    let globals = lua.globals();

    for name in API_NAMES {
        let stub = lua.create_function(|_, _args: Variadic<Value>| Ok(Value::Nil))?;
        globals.set(name, stub)?;
    }
    globals.set("PLUGIN_ID", "lua-probe")?;
    globals.set("PLUGIN_DIR", "/data/adb/plugins/lua-probe")?;

    let version: String = lua.load("return _VERSION").eval()?;
    println!("lua_engine={version}");

    let entry: Table = lua.load(ENTRY).eval()?;
    let callback: Function = entry.get("probe")?;
    let bindings: String = callback.call(())?;
    println!("api_bindings:\n{bindings}");

    let contract: String = lua
        .load(r#"return "PLUGIN_ID=" .. PLUGIN_ID .. "|dir=" .. PLUGIN_DIR"#)
        .eval()?;
    println!("globals={contract}");

    Ok(())
}
