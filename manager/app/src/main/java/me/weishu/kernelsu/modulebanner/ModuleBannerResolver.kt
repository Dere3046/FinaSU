package me.weishu.kernelsu.modulebanner

import com.topjohnwu.superuser.ShellUtils
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream

/**
 * Resolves banners that live on the device: the module directory itself and the `banner=` field of
 * its `module.prop`. Reads run through a root shell because `/data/adb/modules` is not readable by
 * the app process.
 */
object ModuleBannerResolver {

    private val BannerFileNames =
        listOf("banner", "banner.png", "banner.jpg", "banner.jpeg", "banner.webp")

    /** On-disk directory for [moduleId], or null when the module has no directory. */
    fun resolveModuleDir(slot: ModuleBannerSlot, moduleId: String): String? {
        if (!isSafeModuleId(moduleId)) return null
        for (root in slot.moduleRoots) {
            val dir = "$root/$moduleId"
            if (ShellUtils.fastCmd("test -d '$dir' && echo 1").trim() == "1") return dir
        }
        return null
    }

    /** The raw `banner=` value from the module's `module.prop`, if the slot exposes one. */
    fun readModulePropBanner(slot: ModuleBannerSlot, moduleId: String): String? {
        if (!slot.supportsModulePropBanner) return null
        val dir = resolveModuleDir(slot, moduleId) ?: return null
        val prop = ShellUtils.fastCmd("cat '$dir/module.prop' 2>/dev/null")
        return prop.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("banner=") }
            ?.substringAfter("banner=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /** A banner file shipped inside the module directory, read through a root shell. */
    fun readModuleFileBanner(slot: ModuleBannerSlot, moduleId: String): ByteArray? {
        val dir = resolveModuleDir(slot, moduleId) ?: return null
        for (name in BannerFileNames) {
            readBannerFile("$dir/$name")?.let { return it }
        }
        return null
    }

    /** Reads [path] (absolute, or relative to the module directory) as banner bytes. */
    fun readBannerFileAt(slot: ModuleBannerSlot, moduleId: String, path: String): ByteArray? {
        if (path.startsWith("/")) return readBannerFile(path)
        val dir = resolveModuleDir(slot, moduleId) ?: return null
        return readBannerFile("$dir/$path")
    }

    fun isRemoteSource(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

    private fun readBannerFile(path: String): ByteArray? {
        val file = SuFile(path)
        if (!file.isFile) return null
        return runCatching { SuFileInputStream.open(file).use { it.readBytes() } }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }
}
