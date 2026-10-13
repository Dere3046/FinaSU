package me.weishu.kernelsu.modulebanner

import android.content.Context
import me.weishu.kernelsu.ksuApp
import org.json.JSONObject
import java.io.File

/** User-supplied overrides for a module's card metadata. */
data class CustomModuleInfo(
    val name: String = "",
    val version: String = "",
    val author: String = "",
    val description: String = "",
) {
    fun hasAnyInfo(): Boolean =
        name.isNotBlank() || version.isNotBlank() || author.isNotBlank() || description.isNotBlank()

    fun toJson(): String = JSONObject().apply {
        put("name", name)
        put("version", version)
        put("author", author)
        put("description", description)
    }.toString()

    companion object {
        val Empty = CustomModuleInfo()

        fun fromJson(text: String): CustomModuleInfo = runCatching {
            val json = JSONObject(text)
            CustomModuleInfo(
                name = json.optString("name"),
                version = json.optString("version"),
                author = json.optString("author"),
                description = json.optString("description"),
            )
        }.getOrDefault(Empty)
    }
}

/** Persists [CustomModuleInfo] per module id as `<filesDir>/<customInfoDirName>/<key>.json`. */
class ModuleCustomInfoStorage(
    private val slot: ModuleBannerSlot,
    private val context: Context = ksuApp,
) {
    private val dir: File
        get() = File(context.filesDir, slot.customInfoDirName).apply { mkdirs() }

    private fun fileFor(id: String) = File(dir, "${sanitizeModuleKey(id)}.json")

    fun read(id: String): CustomModuleInfo? {
        val file = fileFor(id)
        if (!file.isFile) return null
        return CustomModuleInfo.fromJson(file.readText()).takeIf { it.hasAnyInfo() }
    }

    fun write(id: String, info: CustomModuleInfo) {
        if (!info.hasAnyInfo()) {
            clear(id)
            return
        }
        fileFor(id).writeText(info.toJson())
    }

    fun clear(id: String) {
        fileFor(id).delete()
    }

    /** Drops stored overrides whose module is no longer installed. */
    fun prune(validIds: Set<String>) {
        val validKeys = validIds.mapTo(HashSet(), ::sanitizeModuleKey)
        dir.listFiles()?.forEach { file ->
            if (file.name.removeSuffix(".json") !in validKeys) file.delete()
        }
    }
}
