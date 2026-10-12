package me.weishu.kernelsu.data.backup

/**
 * Classifies a stored relative path back into a [BackupEntry].
 *
 * This is the inverse of [remotePathFor]; it is pure so both the WebDAV and the local backends can
 * turn a raw file listing into a restorable entry without knowing where the bytes live.
 */
object BackupPaths {

    internal const val MODULES_DIR = "modules"
    internal const val ALLOWLIST_DIR = "allowlist"
    internal const val BOOT_DIR = "boot"

    /** Suffix of the metadata sidecar written next to an artifact. */
    const val META_SUFFIX = ".meta.json"

    /** Capture markers that may appear in a boot image file name, e.g. `stock` in `boot-stock-<sha1>.img`. */
    private val BOOT_MARKERS = listOf(BOOT_MARKER_STOCK, BOOT_MARKER_PATCHED, BOOT_MARKER_CURRENT)

    /** Origin markers that may appear in a module archive file name, e.g. `installer` in `<id>-installer-…zip`. */
    private val MODULE_MARKERS = listOf(MODULE_MARKER_INSTALLER, MODULE_MARKER_SNAPSHOT)

    /** `true` when [relativePath] points at a metadata sidecar rather than a restorable artifact. */
    fun isMeta(relativePath: String): Boolean = relativePath.endsWith(META_SUFFIX)

    /**
     * The sidecar path belonging to [relativePath], or `null` when the artifact has no metadata.
     *
     * Mirrors `bootMetaFileName` / `moduleMetaFileName`: the extension is replaced rather than
     * appended, so `boot/boot-stock-<sha1>.img` maps to `boot/boot-stock-<sha1>.meta.json`.
     */
    fun metaPathOf(relativePath: String): String? {
        val directory = relativePath.substringBeforeLast('/', "")
        val fileName = relativePath.substringAfterLast('/')
        val metaName = when {
            fileName.endsWith(".img") -> fileName.removeSuffix(".img") + META_SUFFIX
            fileName.endsWith(".zip") -> fileName.removeSuffix(".zip") + META_SUFFIX
            else -> return null
        }
        return if (directory.isEmpty()) metaName else "$directory/$metaName"
    }

    /**
     * Builds a [BackupEntry] for a listed file, or `null` when the path is not a recognised artifact
     * (for example the manifest or a sidecar).
     */
    fun parse(
        relativePath: String,
        sizeBytes: Long = 0,
        sha256: String = "",
        createdAtEpochMs: Long = 0,
    ): BackupEntry? {
        if (isMeta(relativePath)) return null
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        val fileName = segments.last()
        return when (segments.first()) {
            MODULES_DIR -> {
                if (segments.size < 3) return null
                val moduleId = segments[1]
                val marker = moduleMarkerOf(moduleId, fileName).orEmpty()
                BackupEntry(
                    id = moduleId,
                    kind = BackupKind.MODULE,
                    moduleId = moduleId,
                    displayName = moduleId,
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                    createdAtEpochMs = createdAtEpochMs,
                    source = if (marker == MODULE_MARKER_INSTALLER) {
                        BackupOrigin.ARCHIVED_INSTALLER
                    } else {
                        BackupOrigin.SNAPSHOT
                    },
                    marker = marker,
                    relativePath = relativePath,
                )
            }

            ALLOWLIST_DIR -> BackupEntry(
                id = fileName,
                kind = BackupKind.ALLOWLIST,
                displayName = fileName,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                createdAtEpochMs = createdAtEpochMs,
                source = BackupOrigin.EXPORTED,
                relativePath = relativePath,
            )

            BOOT_DIR -> {
                val name = bootImageParts(fileName) ?: return null
                BackupEntry(
                    id = name.partition,
                    kind = BackupKind.BOOT,
                    displayName = name.partition,
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                    createdAtEpochMs = createdAtEpochMs,
                    source = BackupOrigin.EXPORTED,
                    marker = name.marker,
                    relativePath = relativePath,
                )
            }

            else -> null
        }
    }

    /** Matches a plausible partition name, e.g. `boot`, `init_boot`, `vendor_boot`. */
    private val PARTITION = Regex("^[a-z][a-z0-9_]*$")

    /** The three components of a boot image file name: partition, capture marker and SHA-1. */
    data class BootImageName(val partition: String, val marker: String, val sha1: String)

    /**
     * Decomposes a boot image file name `<partition>-<marker>-<sha1>.img`.
     *
     * Returns `null` when the name does not carry a recognised [BOOT_MARKERS] entry preceded by a
     * plausible partition name, so unrelated `.img` files are never mistaken for boot captures.
     */
    fun bootImageParts(fileName: String): BootImageName? {
        val stem = fileName.removeSuffix(".img")
        for (marker in BOOT_MARKERS) {
            val token = "-$marker-"
            val index = stem.indexOf(token)
            if (index <= 0) continue
            val partition = stem.substring(0, index)
            if (!PARTITION.matches(partition)) continue
            return BootImageName(
                partition = partition,
                marker = marker,
                sha1 = stem.substring(index + token.length),
            )
        }
        return null
    }

    /** The partition name encoded in a boot image file name, or `null` when it is not one. */
    fun partitionOf(fileName: String): String? = bootImageParts(fileName)?.partition

    /** The capture marker (`stock`/`patched`/`current`) encoded in a boot image file name. */
    fun markerOf(fileName: String): String? = bootImageParts(fileName)?.marker

    /** The SHA-1 encoded in a boot image file name, or `null` when it is not one. */
    fun bootSha1Of(fileName: String): String? = bootImageParts(fileName)?.sha1

    /**
     * The origin marker (`installer`/`snapshot`) encoded in a module archive file name.
     *
     * The module id is carried by the directory segment, so the marker is read from what follows the
     * `<id>-` prefix; `null` means an archive written before markers existed (a snapshot).
     */
    fun moduleMarkerOf(moduleId: String, fileName: String): String? {
        val rest = fileName.removePrefix("${sanitizeSegment(moduleId)}-")
        if (rest == fileName) return null
        return MODULE_MARKERS.firstOrNull { rest.startsWith("$it-") }
    }
}
