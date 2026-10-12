package me.weishu.kernelsu.data.backup.webdav

import me.weishu.kernelsu.data.backup.BackupContent
import me.weishu.kernelsu.data.backup.readBytes

/**
 * In-memory [WebDavTransport] shared by the WebDAV tests.
 *
 * It speaks just enough DAV to drive both [WebDavBackupStorage] and [WebDavBrowser]: files and
 * collections live in maps, `PROPFIND` renders a `multistatus` document, and every call is recorded
 * in [methods] so a test can assert the verbs that were issued.
 */
class TestWebDavTransport(private val basePath: String) : WebDavTransport {

    val files = LinkedHashMap<String, ByteArray>()
    val collections = linkedSetOf(basePath.trimEnd('/').ifEmpty { "/" })
    val methods = mutableListOf<String>()

    /** Adds a collection so `PROPFIND` reports it as a child. */
    fun addCollection(path: String) {
        collections += path.trimEnd('/')
    }

    override suspend fun propfind(url: String, depth: Int): WebDavResponse {
        val path = WebDavHref.toPath(url).trimEnd('/').ifEmpty { "/" }
        methods += "PROPFIND $path $depth"
        val exists = collections.contains(path) || files.keys.any { it == path || it.startsWith("$path/") }
        if (!exists) return WebDavResponse(404)
        if (depth < 1) return WebDavResponse(207, multistatus(path, emptyList(), emptyList()).toByteArray())
        val childDirs = collections.filter { it != path && isDirectChild(path, it) }
        val childFiles = files.keys.filter { isDirectChild(path, it) }
        return WebDavResponse(207, multistatus(path, childDirs, childFiles).toByteArray())
    }

    override suspend fun get(url: String, maxBytes: Long?): WebDavResponse {
        val bytes = files[WebDavHref.toPath(url)] ?: return WebDavResponse(404)
        return WebDavResponse(200, if (maxBytes == null) bytes else bytes.copyOf(minOf(bytes.size.toLong(), maxBytes).toInt()))
    }

    override suspend fun getToFile(url: String, target: java.io.File): WebDavResponse {
        val bytes = files[WebDavHref.toPath(url)] ?: return WebDavResponse(404)
        target.writeBytes(bytes)
        return WebDavResponse(200)
    }

    override suspend fun put(url: String, content: BackupContent): WebDavResponse {
        files[WebDavHref.toPath(url)] = content.readBytes()
        return WebDavResponse(201)
    }

    override suspend fun delete(url: String): WebDavResponse {
        files.remove(WebDavHref.toPath(url))
        return WebDavResponse(204)
    }

    override suspend fun mkcol(url: String): WebDavResponse {
        val path = WebDavHref.toPath(url).trimEnd('/')
        return if (collections.add(path)) WebDavResponse(201) else WebDavResponse(405)
    }

    private fun isDirectChild(parent: String, child: String): Boolean =
        child.startsWith("$parent/") && !child.removePrefix("$parent/").contains('/')

    private fun multistatus(dir: String, directories: List<String>, filePaths: List<String>): String {
        val builder = StringBuilder()
        builder.append("<?xml version=\"1.0\" encoding=\"utf-8\"?><d:multistatus xmlns:d=\"DAV:\">")
        builder.append(entry("$dir/", collection = true, size = 0L))
        for (collection in directories) builder.append(entry("$collection/", collection = true, size = 0L))
        for (file in filePaths) builder.append(entry(file, collection = false, size = files.getValue(file).size.toLong()))
        builder.append("</d:multistatus>")
        return builder.toString()
    }

    private fun entry(href: String, collection: Boolean, size: Long): String =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop>" +
            "<d:resourcetype>${if (collection) "<d:collection/>" else ""}</d:resourcetype>" +
            (if (collection) "" else "<d:getcontentlength>$size</d:getcontentlength>") +
            "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
}
