package me.weishu.kernelsu.data.backup.webdav

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.backup.BackupContent
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody

/** Minimal HTTP result used by the WebDAV layer. */
data class WebDavResponse(val code: Int, val body: ByteArray? = null) {

    /** `true` for any 2xx status, including the `207 Multi-Status` of `PROPFIND`. */
    val isSuccessful: Boolean get() = code in 200..299

    /** The body decoded as UTF-8 text. */
    fun bodyText(): String? = body?.toString(Charsets.UTF_8)
}

/**
 * The HTTP verbs the WebDAV storage needs.
 *
 * Implementations throw [java.io.IOException] on transport failure; the storage wraps calls in
 * [runCatching] so a single failed request never escapes as an untyped crash.
 */
interface WebDavTransport {

    /** Issues a `PROPFIND` with the given [depth]. */
    suspend fun propfind(url: String, depth: Int): WebDavResponse

    /** Issues a `GET`, optionally bounded to the first [maxBytes] bytes via a `Range` request. */
    suspend fun get(url: String, maxBytes: Long? = null): WebDavResponse

    /**
     * Issues a `GET`, streaming the body into [target].
     *
     * Used when a payload may be larger than the in-memory body limit (e.g. a partition image): the
     * bytes never accumulate in memory.
     */
    suspend fun getToFile(url: String, target: File): WebDavResponse

    /** Issues a `PUT` with [content] as the body, streaming file-backed payloads. */
    suspend fun put(url: String, content: BackupContent): WebDavResponse

    /** Issues a `DELETE`. */
    suspend fun delete(url: String): WebDavResponse

    /** Issues a `MKCOL` to create a collection. */
    suspend fun mkcol(url: String): WebDavResponse
}

/** [WebDavTransport] implemented with OkHttp and HTTP Basic authentication. */
class OkHttpWebDavTransport(
    private val client: OkHttpClient,
    username: String,
    password: String,
) : WebDavTransport {

    private val authorization: String = Credentials.basic(username, password, Charsets.UTF_8)

    override suspend fun propfind(url: String, depth: Int): WebDavResponse = execute(
        Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .header("Depth", depth.toString())
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .build(),
    )

    override suspend fun get(url: String, maxBytes: Long?): WebDavResponse = execute(
        Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .apply { if (maxBytes != null) header("Range", "bytes=0-${maxBytes - 1}") }
            .get()
            .build(),
        maxBytes = maxBytes ?: MAX_BODY_BYTES,
    )

    override suspend fun getToFile(url: String, target: File): WebDavResponse = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(url).header("Authorization", authorization).get().build(),
        ).execute().use { response ->
            if (response.isSuccessful) {
                response.body.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            WebDavResponse(code = response.code)
        }
    }

    override suspend fun put(url: String, content: BackupContent): WebDavResponse {
        val body = when (content) {
            is BackupContent.Bytes -> content.bytes().toRequestBody(BINARY)
            is BackupContent.FileRef -> content.file().asRequestBody(BINARY)
        }
        return execute(
            Request.Builder().url(url).header("Authorization", authorization).put(body).build(),
        )
    }

    override suspend fun delete(url: String): WebDavResponse = execute(
        Request.Builder().url(url).header("Authorization", authorization).delete().build(),
    )

    override suspend fun mkcol(url: String): WebDavResponse = execute(
        Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .method("MKCOL", null)
            .build(),
    )

    private suspend fun execute(request: Request, maxBytes: Long = MAX_BODY_BYTES): WebDavResponse = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            WebDavResponse(code = response.code, body = response.readBoundedBody(maxBytes))
        }
    }

    private fun Response.readBoundedBody(maxBytes: Long): ByteArray? {
        val body: ResponseBody = this.body
        if (body.contentLength() == 0L) return null
        return body.byteStream().use { it.readAtMost(minOf(maxBytes, MAX_BODY_BYTES)) }
    }

    private fun InputStream.readAtMost(limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        return output.toByteArray()
    }

    private companion object {
        val BINARY = "application/octet-stream".toMediaType()
        val XML = "application/xml; charset=utf-8".toMediaType()
        const val MAX_BODY_BYTES = 8L * 1024 * 1024
        const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:allprop/></d:propfind>"
    }
}
