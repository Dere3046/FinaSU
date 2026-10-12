package me.weishu.kernelsu.data.backup.local

/**
 * Pure mapping between a backup storage-relative path (the `remotePathFor` form) and the MediaStore
 * columns used to address the same file under the public Downloads directory.
 *
 * The layout is `Download/FinaSU/<relative path>`, so a module artifact at
 * `modules/example/file.zip` is written to `Download/FinaSU/modules/example/file.zip`.
 */
object LocalBackupLayout {

    /** Folder created under the public Downloads directory that holds every local backup. */
    const val ROOT_DIR_NAME = "FinaSU"

    /** MediaStore `RELATIVE_PATH` of the backup root, e.g. `Download/FinaSU`. */
    const val MEDIA_RELATIVE_ROOT = "Download/$ROOT_DIR_NAME"

    /** The display name (last path segment) of [relativePath]. */
    fun displayName(relativePath: String): String = relativePath.substringAfterLast('/')

    /**
     * The MediaStore `RELATIVE_PATH` (always with a trailing slash) of the directory that holds
     * [relativePath].
     */
    fun mediaRelativeDir(relativePath: String): String {
        val dir = relativePath.trim('/').substringBeforeLast('/', "")
        val base = if (dir.isEmpty()) MEDIA_RELATIVE_ROOT else "$MEDIA_RELATIVE_ROOT/$dir"
        return "$base/"
    }

    /**
     * Converts a stored `RELATIVE_PATH` + `DISPLAY_NAME` pair back to a storage-relative path.
     *
     * Returns `null` when the pair lives outside the backup root, so unrelated Downloads files are
     * never surfaced as backups.
     */
    fun toRelativePath(mediaRelativePath: String, displayName: String): String? {
        val normalized = mediaRelativePath.trim().trim('/')
        val root = MEDIA_RELATIVE_ROOT
        if (normalized != root && !normalized.startsWith("$root/")) return null
        val remainder = if (normalized == root) "" else normalized.removePrefix("$root/")
        return if (remainder.isEmpty()) displayName else "$remainder/$displayName"
    }
}
