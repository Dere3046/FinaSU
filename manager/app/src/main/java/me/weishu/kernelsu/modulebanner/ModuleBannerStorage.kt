package me.weishu.kernelsu.modulebanner

import android.content.Context
import android.net.Uri
import me.weishu.kernelsu.ksuApp
import java.io.File

/**
 * Stores one user-selected banner image per module id as a file under `filesDir`.
 *
 * Bytes are kept (not the source Uri) so the banner survives the picker grant being revoked.
 */
class ModuleBannerStorage(
    private val slot: ModuleBannerSlot,
    private val context: Context = ksuApp,
) {
    private val dir: File
        get() = File(context.filesDir, slot.bannerDirName).apply { mkdirs() }

    private fun fileFor(key: String) = File(dir, sanitizeModuleKey(key))

    fun exists(key: String): Boolean = fileFor(key).isFile

    fun read(key: String): ByteArray? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return file.readBytes().takeIf { it.isNotEmpty() }
    }

    fun write(key: String, uri: Uri): ByteArray? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty()) return null
        fileFor(key).writeBytes(bytes)
        return bytes
    }

    fun clear(key: String): Boolean {
        val file = fileFor(key)
        return !file.exists() || file.delete()
    }
}
