package me.weishu.kernelsu.data.backup.webdav

import java.io.IOException
import me.weishu.kernelsu.data.backup.backupResult
import me.weishu.kernelsu.data.backup.WebDavPaths

/** One child of a WebDAV collection, as reported by a `PROPFIND Depth: 1`. */
data class WebDavEntry(
    /** Final path segment, e.g. `boot-stock-ab12.img`. */
    val name: String,
    /** Storage-relative path below the configured base, e.g. `boot/boot-stock-ab12.img`. */
    val relativePath: String,
    val isCollection: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long?,
)

/**
 * Read-only browsing over the configured WebDAV base.
 *
 * This is deliberately separate from [WebDavBackupStorage]: the storage speaks in artifacts and a
 * manifest, while a person browsing the server wants to see collections, arbitrary file names and
 * previews. Both share the same [WebDavTransport] and href handling.
 */
class WebDavBrowser(
    private val transport: WebDavTransport,
    private val baseUrl: String,
) {

    private val basePath: String = WebDavHref.toPath(baseUrl)

    /** The absolute URL of a storage-relative path. */
    fun resolve(relativePath: String): String = WebDavPaths.joinBase(baseUrl, relativePath)

    /** Lists the immediate children of [path] (a storage-relative directory; empty for the base). */
    suspend fun list(path: String): Result<List<WebDavEntry>> = backupResult {
        val response = transport.propfind(resolve(path), depth = 1)
        if (response.code == 404 || response.code == 409) return@backupResult emptyList()
        if (!response.isSuccessful) {
            throw IOException("PROPFIND $path failed: HTTP ${response.code}")
        }
        val xml = response.body ?: return@backupResult emptyList()
        val prefix = path.trim('/')
        WebDavMultiStatus.parse(xml.inputStream())
            .mapNotNull { resource -> toEntry(resource, prefix) }
            .sortedWith(compareByDescending<WebDavEntry> { it.isCollection }.thenBy { it.name.lowercase() })
    }

    /** Reads at most [maxBytes] of [relativePath] into memory (a preview, not a full download). */
    suspend fun read(relativePath: String, maxBytes: Long? = null): Result<ByteArray> = backupResult {
        val response = transport.get(resolve(relativePath), maxBytes)
        if (!response.isSuccessful) {
            throw IOException("GET $relativePath failed: HTTP ${response.code}")
        }
        response.body ?: ByteArray(0)
    }

    /** Streams the whole [relativePath] into [target] (a full download of any size). */
    suspend fun downloadTo(relativePath: String, target: java.io.File): Result<Unit> = backupResult {
        val response = transport.getToFile(resolve(relativePath), target)
        if (!response.isSuccessful) {
            target.delete()
            throw IOException("GET $relativePath failed: HTTP ${response.code}")
        }
    }

    /** Deletes [relativePath]. */
    suspend fun delete(relativePath: String): Result<Unit> = backupResult {
        val response = transport.delete(resolve(relativePath))
        if (!response.isSuccessful && response.code != 404) {
            throw IOException("DELETE $relativePath failed: HTTP ${response.code}")
        }
    }

    private fun toEntry(resource: WebDavResource, prefix: String): WebDavEntry? {
        val relativePath = WebDavHref.relativize(basePath, resource.href)?.trim('/') ?: return null
        // The depth-1 response includes the collection itself; drop it and anything outside [prefix].
        if (relativePath.isEmpty() || relativePath == prefix) return null
        if (prefix.isNotEmpty() && !relativePath.startsWith("$prefix/")) return null
        val name = relativePath.substringAfterLast('/')
        if (name.isEmpty()) return null
        return WebDavEntry(
            name = name,
            relativePath = relativePath,
            isCollection = resource.isCollection,
            sizeBytes = resource.contentLength,
            lastModifiedEpochMs = resource.lastModifiedEpochMs,
        )
    }
}
