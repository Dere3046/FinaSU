package me.weishu.kernelsu.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.model.Plugin
import me.weishu.kernelsu.data.model.PluginConfigField
import me.weishu.kernelsu.data.model.PluginQuickAction
import me.weishu.kernelsu.ui.util.execKsudResult
import org.json.JSONArray
import org.json.JSONObject

/** Raised when the manager daemon reports that a plugin command failed. */
class PluginCommandException(val code: Int, message: String) : Exception(message)

class PluginRepositoryImpl : PluginRepository {

    override suspend fun getPlugins(): Result<List<Plugin>> = withContext(Dispatchers.IO) {
        runCatching {
            val plugins = JSONArray(run("plugin list"))
            (0 until plugins.length()).map { index -> plugins.getJSONObject(index).toPlugin() }
        }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val action = if (enabled) "enable" else "disable"
                run("plugin $action ${quote(id)}")
                Unit
            }
        }

    override suspend fun getLog(id: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching { run("plugin log ${quote(id)}") }
    }

    private suspend fun run(arguments: String): String {
        val result = execKsudResult(arguments)
        if (!result.isSuccess) {
            val reason = result.err.joinToString("\n").trim()
            throw PluginCommandException(
                result.code,
                reason.ifBlank { "plugin command failed with code ${result.code}" },
            )
        }
        return result.out.joinToString("\n")
    }

    /** Returns the token quoted for a POSIX shell, so it cannot split into more arguments. */
    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}

private fun JSONObject.toPlugin(): Plugin = Plugin(
    id = optString("id"),
    name = optString("name"),
    author = optString("author"),
    version = optString("version"),
    description = optString("description"),
    license = optString("license"),
    enabled = optBoolean("enabled"),
    entry = optString("entry"),
    quickAction = optJSONObject("quick_action")?.let { quick ->
        PluginQuickAction(function = quick.optString("function"), label = quick.optString("label"))
    },
    config = optJSONArray("config").toConfigFields(),
    depends = optJSONArray("depends").toStringList(),
    callbacks = optJSONArray("callbacks").toStringList(),
    error = if (isNull("error")) null else optString("error").ifBlank { null },
)

private fun JSONArray?.toConfigFields(): List<PluginConfigField> {
    if (this == null) return emptyList()
    return (0 until length()).map { index ->
        val field = getJSONObject(index)
        PluginConfigField(
            key = field.optString("key"),
            label = field.optString("label"),
            type = field.optString("type"),
            default = if (field.isNull("default")) null else field.opt("default")?.toString(),
            options = field.optJSONArray("options").toStringList(),
        )
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { index -> optString(index) }
}
