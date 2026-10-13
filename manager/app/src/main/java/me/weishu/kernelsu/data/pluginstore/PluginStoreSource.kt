package me.weishu.kernelsu.data.pluginstore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.modulestore.RepoHttp
import me.weishu.kernelsu.data.modulestore.isHttpUrl
import me.weishu.kernelsu.data.modulestore.selectDescription
import me.weishu.kernelsu.data.modulestore.storeLanguage
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray

private const val INDEX_URL = "https://folk.mysqil.com/api/modules"

/** Lists the packages of the built-in plugin index. */
interface PluginStoreSource {
    suspend fun list(query: String?): Result<List<StorePlugin>>
}

class OfficialPluginStoreSource(
    private val language: String = storeLanguage(),
    private val token: String = "",
    private val http: RepoHttp = RepoHttp,
) : PluginStoreSource {

    override suspend fun list(query: String?): Result<List<StorePlugin>> =
        withContext(Dispatchers.IO) {
            http.get(buildIndexUrl()).mapCatching { body ->
                parseStorePlugins(body, language).filterByQuery(query)
            }
        }

    private fun buildIndexUrl(): String = INDEX_URL.toHttpUrlOrNull()
        ?.newBuilder()
        ?.addQueryParameter("type", "plugin")
        ?.addQueryParameter("lang", language)
        ?.addQueryParameter("token", token)
        ?.build()
        ?.toString()
        ?: INDEX_URL
}

/** Reads the index payload, dropping entries that cannot be installed. */
internal fun parseStorePlugins(json: String, language: String): List<StorePlugin> {
    val array = JSONArray(json)
    val plugins = mutableListOf<StorePlugin>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val name = item.optString("name")
        val url = item.optString("url")
        if (name.isBlank() || !url.isHttpUrl()) continue
        plugins += StorePlugin(
            name = name,
            version = item.optString("version"),
            description = selectDescription(item, language),
            downloadUrl = url,
        )
    }
    return plugins
}

internal fun List<StorePlugin>.filterByQuery(query: String?): List<StorePlugin> {
    if (query.isNullOrBlank()) return this
    val needle = query.trim()
    return filter {
        it.name.contains(needle, ignoreCase = true) || it.description.contains(needle, ignoreCase = true)
    }
}
