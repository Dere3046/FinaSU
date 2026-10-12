package me.weishu.kernelsu.data.backup

/**
 * A persistence backend for backup artifacts.
 *
 * Implementations address content by a storage-relative path (see [remotePathFor]) and are expected
 * to be stateless between calls. All operations report failures through [Result] rather than
 * throwing, so a failing backend never aborts a batch.
 */
interface BackupStorage {

    /** Verifies that the backend is reachable and writable. */
    suspend fun test(): Result<Unit>

    /** Writes [bytes] at [relativePath], replacing any existing content. */
    suspend fun put(relativePath: String, bytes: ByteArray): Result<Unit>

    /**
     * Writes the stream produced by [content] at [relativePath], replacing any existing content.
     *
     * The default implementation buffers the whole payload; backends that can stream (e.g. an
     * HTTP `PUT` of a partition image) should override this to avoid loading it into memory.
     */
    suspend fun put(relativePath: String, content: BackupContent): Result<Unit> =
        put(relativePath, content.readBytes())

    /** Reads the content stored at [relativePath]. */
    suspend fun get(relativePath: String): Result<ByteArray>

    /**
     * Opens the content stored at [relativePath] as a stream.
     *
     * The default implementation buffers the whole payload through [get]; backends able to stream
     * (e.g. a local file or a partition image) should override it to avoid loading it into memory.
     */
    suspend fun open(relativePath: String): Result<BackupContent> =
        get(relativePath).map { BackupContent.Bytes(it) }

    /** Lists entries whose path starts with [prefix]. */
    suspend fun list(prefix: String): Result<List<RemoteEntry>>

    /** Deletes the content stored at [relativePath]. */
    suspend fun delete(relativePath: String): Result<Unit>
}
