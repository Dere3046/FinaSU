package me.weishu.kernelsu.data.backup

import java.io.File
import kotlinx.coroutines.CancellationException
import me.weishu.kernelsu.data.backup.device.NativesAllowlistAccess
import me.weishu.kernelsu.data.backup.device.RootModuleAccess
import me.weishu.kernelsu.data.backup.device.RootPartitionAccess
import me.weishu.kernelsu.data.backup.local.LocalBackupStorage
import me.weishu.kernelsu.data.backup.source.AllowlistAccess
import me.weishu.kernelsu.data.backup.source.AllowlistBackupSource
import me.weishu.kernelsu.data.backup.source.ModuleAccess
import me.weishu.kernelsu.data.backup.source.ModuleBackupSource
import me.weishu.kernelsu.data.backup.source.ModuleInstaller
import me.weishu.kernelsu.data.backup.source.PartitionAccess
import me.weishu.kernelsu.data.backup.source.PartitionBackupSource
import me.weishu.kernelsu.data.backup.source.collectKnownStockSha1
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ksuApp

/** Best-effort snapshots before flashing; failures are logged without aborting the flash. */
class PreFlashBackup(
    private val settings: SettingsRepository = SettingsRepositoryImpl(),
    private val secrets: SecretStore = ksuApp.backupSecrets,
    private val partitionAccess: PartitionAccess = RootPartitionAccess(),
    private val moduleAccess: ModuleAccess = RootModuleAccess(),
    private val allowlistAccess: AllowlistAccess = NativesAllowlistAccess(),
    private val local: BackupStorage = LocalBackupStorage(ksuApp),
) {
    /**
     * [extraPartitions] includes partitions outside the general selection. [moduleInstallers] are
     * archived as-is alongside snapshots of the installed modules they replace.
     */
    suspend fun run(
        kinds: Set<BackupKind>,
        extraPartitions: Set<String> = emptySet(),
        moduleInstallers: List<ModuleInstaller> = emptyList(),
        onLog: (String) -> Unit = {},
    ): Boolean {
        if (kinds.isEmpty() || !settings.backupBeforeFlash) return false

        val plan = BackupPlan.from(settings)
        var tempDir: File? = null
        return try {
            val storages = buildStorages(plan)
            if (storages.isEmpty()) {
                onLog("Pre-flash backup skipped: no destination enabled")
                return false
            }
            val workspace = File.createTempFile("backup-preflash-", "", ksuApp.cacheDir)
            tempDir = workspace
            check(workspace.delete() && workspace.mkdir()) { "Could not create backup workspace" }
            val knownStockSha1 = collectKnownStockSha1(storages)
            val selectedKinds = kinds.intersect(plan.kinds).toMutableSet()
            if (BackupKind.BOOT in kinds && extraPartitions.isNotEmpty()) selectedKinds += BackupKind.BOOT
            val sources = buildSources(
                selectedKinds,
                plan.partitions + extraPartitions,
                workspace,
                knownStockSha1,
                moduleInstallers,
            )
            if (sources.kinds.isEmpty()) return false

            val engine = BackupEngine(sources, storages)
            for (kind in sources.kinds) {
                onLog("Backing up ${kind.value} before flashing…")
                val result = engine.backup(kind)
                result.failures.forEach { onLog("Backup failed for ${it.path}: ${it.cause.message}") }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onLog("Pre-flash backup failed: ${e.message ?: e.toString()}")
            false
        } finally {
            tempDir?.deleteRecursively()
        }
    }

    private fun buildStorages(plan: BackupPlan): List<BackupStorage> = buildList {
        if (plan.localEnabled) add(local)
        if (plan.webDavEnabled) {
            webDavBackupStorage(ksuApp.okhttpClient, secrets, settings.webDavUrl, settings.webDavUsername, settings.webDavPath)
                ?.let { add(it) }
        }
    }

    private suspend fun buildSources(
        kinds: Set<BackupKind>,
        partitions: Set<String>,
        tempDir: File,
        knownStockSha1: Set<String>,
        moduleInstallers: List<ModuleInstaller>,
    ): BackupSourceRegistry {
        val sources = mutableListOf<BackupSource>()
        if (BackupKind.MODULE in kinds && moduleInstallers.isNotEmpty()) {
            sources += ModuleBackupSource(moduleAccess, tempDir, moduleInstallers)
        }
        if (BackupKind.ALLOWLIST in kinds) sources += AllowlistBackupSource(allowlistAccess)
        if (BackupKind.BOOT in kinds) {
            val available = partitionAccess.availablePartitions().getOrDefault(emptyList()).toSet()
            val selected = partitions.intersect(available)
            if (selected.isNotEmpty()) {
                sources += PartitionBackupSource(
                    access = partitionAccess,
                    partitions = selected.toList(),
                    tempDir = tempDir,
                    trigger = BackupTrigger.PRE_FLASH,
                    knownStockSha1 = knownStockSha1,
                )
            }
        }
        return BackupSourceRegistry(sources)
    }
}
