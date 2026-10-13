package me.weishu.kernelsu.modulebanner

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp
import okhttp3.Request

private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
private const val IMAGE_ACCEPT = "image/jpeg,image/png,image/webp,image/gif,image/bmp"
private val ImageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

/**
 * Resolves a banner from an "API source": either a remote endpoint that serves one image per module
 * or a local directory of images. Fetched bytes are cached on disk for [CACHE_TTL_MS] and the
 * endpoint is always queried with a per-module seed, so a shared API returns a stable image.
 */
object BannerApiClient {

    private const val CACHE_DIR = "api_banners"

    fun isLocalDirectory(source: String): Boolean =
        source.startsWith("/") || source.startsWith("file://")

    /** Deterministic per-module seed, so an endpoint that ignores it still returns a stable pick. */
    fun apiSeed(moduleId: String): String = md5Hex(moduleId).take(4)

    suspend fun load(context: Context, moduleId: String, source: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val src = source.trim()
            if (src.isEmpty()) return@withContext null
            val cache = cacheFile(context, src, moduleId)
            readFresh(cache)?.let { return@withContext it }
            val bytes = if (isLocalDirectory(src)) {
                readLocalDirectory(moduleId, src)
            } else {
                fetchRemote(moduleId, src)
            }
            if (bytes != null && bytes.isNotEmpty()) cache.writeBytes(bytes)
            bytes
        }

    /** Synchronous cached read for the initial value of a banner state holder. */
    fun loadSync(context: Context, moduleId: String, source: String): ByteArray? {
        val src = source.trim()
        if (src.isEmpty()) return null
        return readFresh(cacheFile(context, src, moduleId))
    }

    fun clearAll(context: Context) {
        cacheDir(context).listFiles()?.forEach { it.delete() }
    }

    fun clearModule(context: Context, moduleId: String) {
        val suffix = "_${sanitizeModuleKey(moduleId)}"
        cacheDir(context).listFiles()?.forEach { if (it.name.endsWith(suffix)) it.delete() }
    }

    private fun cacheDir(context: Context): File =
        File(context.filesDir, CACHE_DIR).apply { mkdirs() }

    private fun cacheFile(context: Context, source: String, moduleId: String): File =
        File(cacheDir(context), "${md5Hex(source).take(8)}_${sanitizeModuleKey(moduleId)}")

    private fun readFresh(file: File): ByteArray? {
        if (!file.isFile) return null
        if (System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS) {
            file.delete()
            return null
        }
        return file.readBytes().takeIf { it.isNotEmpty() }
    }

    private fun readLocalDirectory(moduleId: String, source: String): ByteArray? {
        val dir = File(Uri.parse(source).path ?: source)
        val images = dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in ImageExtensions }
            ?.sortedBy { it.name }
            .orEmpty()
        if (images.isEmpty()) return null
        val index = (moduleId.hashCode() % images.size + images.size) % images.size
        return images[index].readBytes().takeIf { it.isNotEmpty() }
    }

    private fun fetchRemote(moduleId: String, source: String): ByteArray? {
        val url = appendSeed(source, apiSeed(moduleId))
        val request = Request.Builder().url(url).header("Accept", IMAGE_ACCEPT).build()
        return try {
            ksuApp.okhttpClient.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty()
                    .substringBefore(';').trim().lowercase()
                if (!response.isSuccessful || !contentType.startsWith("image/")) {
                    null
                } else {
                    response.body.bytes().takeIf { it.isNotEmpty() }
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun appendSeed(source: String, seed: String): String =
        source + if (source.contains('?')) "&seed=$seed" else "?seed=$seed"

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
