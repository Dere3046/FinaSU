package me.weishu.kernelsu.data.backup.source

import java.io.File
import me.weishu.kernelsu.data.backup.backupResult
import me.weishu.kernelsu.data.backup.BackupArtifact
import me.weishu.kernelsu.data.backup.BackupContent
import me.weishu.kernelsu.data.backup.BackupEntry
import me.weishu.kernelsu.data.backup.BackupKind
import me.weishu.kernelsu.data.backup.BackupOrigin
import me.weishu.kernelsu.data.backup.BackupSource
import me.weishu.kernelsu.data.backup.MODULE_MARKER_INSTALLER
import me.weishu.kernelsu.data.backup.MODULE_MARKER_SNAPSHOT
import me.weishu.kernelsu.data.backup.ModuleBackupMeta
import me.weishu.kernelsu.data.backup.backupIsoTimestamp
import me.weishu.kernelsu.data.backup.moduleArchiveName
import me.weishu.kernelsu.data.backup.moduleMetaFileName
import me.weishu.kernelsu.data.backup.sanitizeSegment
import me.weishu.kernelsu.data.backup.sha256

/** Identity of an installed module, as needed to describe a backup. */
data class InstalledModule(
    val id: String,
    val name: String,
    val versionName: String,
    val versionCode: Int,
    val author: String,
    val metamodule: Boolean,
)

/** A module installer ZIP the user picked to flash, paired with the name it was chosen under. */
data class ModuleInstaller(
    val file: File,
    val originalName: String = file.name,
)

/**
 * Packs and installs modules on behalf of a [ModuleBackupSource].
 *
 * The real implementation reads `/data/adb/modules/<id>`, inspects installer ZIPs and drives
 * `ksud module install`; keeping it behind this interface lets the source be tested without root.
 */
interface ModuleAccess {

    /** Modules currently installed. */
    suspend fun installedModules(): Result<List<InstalledModule>>

    /** Reads `module.prop` from an installer ZIP without installing it. */
    suspend fun inspectInstaller(zip: File): Result<InstalledModule>

    /** Packs the installed module [moduleId] into a flashable ZIP at [target]. */
    suspend fun packModule(moduleId: String, target: File): Result<Unit>

    /** Installs the module ZIP at [zip], replacing any module with the same id. */
    suspend fun installModule(zip: File): Result<Unit>
}

/** Archives staged installers and snapshots replaced modules. Exported files transfer to the engine. */
class ModuleBackupSource(
    private val access: ModuleAccess,
    private val tempDir: File,
    private val installers: List<ModuleInstaller> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
) : BackupSource {

    override val kind: BackupKind = BackupKind.MODULE

    override suspend fun list(): Result<List<BackupEntry>> = access.installedModules().map { modules ->
        modules.map { module ->
            BackupEntry(
                id = module.id,
                kind = kind,
                moduleId = module.id,
                displayName = module.name.ifBlank { module.id },
                versionName = module.versionName,
                versionCode = module.versionCode,
                source = BackupOrigin.SNAPSHOT,
            )
        }
    }

    override suspend fun export(): Result<List<BackupArtifact>> = backupResult {
        val createdAt = backupIsoTimestamp(clock())
        val created = mutableListOf<File>()
        try {
            val artifacts = mutableListOf<BackupArtifact>()
            val installerIds = mutableSetOf<String>()
            for (installer in installers) {
                val module = access.inspectInstaller(installer.file).getOrThrow()
                installerIds += module.id
                artifacts += installerArtifact(module, installer, createdAt)
            }
            // Snapshot the installed tree of every module this flash replaces, so an overwrite can
            // be rolled back even though the new installer is the only thing we would otherwise have.
            if (installerIds.isNotEmpty()) {
                val installed = access.installedModules().getOrThrow()
                for (module in installed.filter { it.id in installerIds }) {
                    val target = File(tempDir, "module-${sanitizeSegment(module.id)}.zip")
                    created += target
                    access.packModule(module.id, target).getOrThrow()
                    artifacts += snapshotArtifact(module, target, createdAt)
                }
            }
            artifacts
        } catch (e: Throwable) {
            // A failed export never hands its artifacts to the engine, so snapshots already produced
            // would leak; drop them before surfacing the error.
            created.forEach { it.delete() }
            throw e
        }
    }

    override suspend fun restore(entry: BackupEntry, content: BackupContent): Result<Unit> = backupResult {
        val temp = File.createTempFile("restore-", ".zip", tempDir)
        try {
            content.open().use { input -> temp.outputStream().use { input.copyTo(it) } }
            access.installModule(temp).getOrThrow()
        } finally {
            temp.delete()
        }
    }

    override suspend fun delete(entry: BackupEntry): Result<Unit> = Result.success(Unit)

    private suspend fun installerArtifact(
        module: InstalledModule,
        installer: ModuleInstaller,
        createdAt: String,
    ): BackupArtifact {
        val sha256 = installer.file.sha256()
        val fileName = moduleArchiveName(
            moduleId = module.id,
            versionName = module.versionName,
            versionCode = module.versionCode,
            epochMs = clock(),
            marker = MODULE_MARKER_INSTALLER,
        )
        return BackupArtifact(
            kind = kind,
            moduleId = module.id,
            fileName = fileName,
            metaFileName = moduleMetaFileName(fileName),
            sha256 = sha256,
            metaJson = meta(
                module = module,
                originalFileName = installer.originalName,
                sizeBytes = installer.file.length(),
                sha256 = sha256,
                source = BackupOrigin.ARCHIVED_INSTALLER,
                createdAt = createdAt,
            ).toJson(),
            file = installer.file,
        )
    }

    private suspend fun snapshotArtifact(
        module: InstalledModule,
        target: File,
        createdAt: String,
    ): BackupArtifact {
        val sha256 = target.sha256()
        val fileName = moduleArchiveName(
            moduleId = module.id,
            versionName = module.versionName,
            versionCode = module.versionCode,
            epochMs = clock(),
            marker = MODULE_MARKER_SNAPSHOT,
        )
        return BackupArtifact(
            kind = kind,
            moduleId = module.id,
            fileName = fileName,
            metaFileName = moduleMetaFileName(fileName),
            sha256 = sha256,
            metaJson = meta(
                module = module,
                originalFileName = fileName,
                sizeBytes = target.length(),
                sha256 = sha256,
                source = BackupOrigin.SNAPSHOT,
                createdAt = createdAt,
            ).toJson(),
            file = target,
        )
    }

    private fun meta(
        module: InstalledModule,
        originalFileName: String,
        sizeBytes: Long,
        sha256: String,
        source: BackupOrigin,
        createdAt: String,
    ): ModuleBackupMeta = ModuleBackupMeta(
        moduleId = module.id,
        name = module.name,
        versionName = module.versionName,
        versionCode = module.versionCode,
        author = module.author,
        metamodule = module.metamodule,
        originalFileName = originalFileName,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        source = source,
        createdAt = createdAt,
    )
}
