package me.weishu.kernelsu.modulebanner

/**
 * Describes where one module family keeps its banners and user metadata.
 *
 * Everything that stores or resolves a banner works against this seam instead of a hardcoded
 * directory, so a new family (script, plugin, ...) only adds an instance. FolkSU ships the APM
 * slot today.
 */
interface ModuleBannerSlot {
    val key: String
    val bannerDirName: String
    val customInfoDirName: String

    /** Module roots searched in order; the installed root first, pending updates second. */
    val moduleRoots: List<String>
    val supportsModulePropBanner: Boolean
}

object ApmBannerSlot : ModuleBannerSlot {
    override val key = "apm"
    override val bannerDirName = "module_banners"
    override val customInfoDirName = "module_custom_info"
    override val moduleRoots = listOf("/data/adb/modules", "/data/adb/modules_update")
    override val supportsModulePropBanner = true
}

/** Only real module ids reach the shell, so the id can never inject arguments. */
private val ModuleIdPattern = Regex("[A-Za-z0-9._-]+")

internal fun isSafeModuleId(id: String): Boolean = ModuleIdPattern.matches(id)

/** Maps an arbitrary module id onto a filesystem-safe key. */
fun sanitizeModuleKey(raw: String): String = raw.replace(Regex("[^a-zA-Z0-9._-]"), "_")
