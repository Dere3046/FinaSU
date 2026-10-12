package me.weishu.kernelsu.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.backup.BackupEngine
import me.weishu.kernelsu.data.backup.BackupEntry
import me.weishu.kernelsu.data.backup.BackupPaths
import me.weishu.kernelsu.data.backup.BackupSourceRegistry
import me.weishu.kernelsu.data.backup.BackupStorage
import me.weishu.kernelsu.data.backup.RemoteEntry
import me.weishu.kernelsu.data.backup.SecretStore
import me.weishu.kernelsu.data.backup.webDavBackupStorage
import me.weishu.kernelsu.data.backup.device.NativesAllowlistAccess
import me.weishu.kernelsu.data.backup.device.RootModuleAccess
import me.weishu.kernelsu.data.backup.device.RootPartitionAccess
import me.weishu.kernelsu.data.backup.local.LocalBackupStorage
import me.weishu.kernelsu.data.backup.source.AllowlistAccess
import me.weishu.kernelsu.data.backup.source.AllowlistBackupSource
import me.weishu.kernelsu.data.backup.source.ModuleAccess
import me.weishu.kernelsu.data.backup.source.ModuleBackupSource
import me.weishu.kernelsu.data.backup.source.PartitionAccess
import me.weishu.kernelsu.data.backup.source.PartitionBackupSource
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ksuApp

enum class BackupTarget { LOCAL, WEBDAV }

data class BackupListItem(
    val entry: BackupEntry,
    val target: BackupTarget,
    val sizeBytes: Long,
)

sealed interface BackupNotice {
    data class Restored(val name: String) : BackupNotice
    data class Deleted(val name: String) : BackupNotice
    data class Failed(val message: String) : BackupNotice
}

data class BackupListUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val items: List<BackupListItem> = emptyList(),
    val notice: BackupNotice? = null,
)

/**
 * Lists backups across the local folder and WebDAV, and restores or deletes them.
 *
 * Restore re-reads the payload from the backend it was listed from, so a remote entry is streamed
 * back through the same source that produced it.
 */
class BackupListViewModel(
    private val settings: SettingsRepository = SettingsRepositoryImpl(),
    private val secrets: SecretStore = ksuApp.backupSecrets,
    private val partitionAccess: PartitionAccess = RootPartitionAccess(),
    private val moduleAccess: ModuleAccess = RootModuleAccess(),
    private val allowlistAccess: AllowlistAccess = NativesAllowlistAccess(),
    private val local: BackupStorage = LocalBackupStorage(ksuApp),
) : ViewModel() {

    private val webDav = webDavBackupStorage(
        ksuApp.okhttpClient, secrets, settings.webDavUrl, settings.webDavUsername, settings.webDavPath,
    )

    private val mutable = MutableStateFlow(BackupListUiState())
    val state = mutable.asStateFlow()

    /** Reloads both backends; a backend that fails simply contributes no entries. */
    fun refresh() {
        viewModelScope.launch {
            mutable.update { it.copy(loading = true) }
            val items = withContext(Dispatchers.IO) {
                buildList {
                    addAll(local.list("").getOrDefault(emptyList()).toItems(BackupTarget.LOCAL))
                    webDav?.let { addAll(it.list("").getOrDefault(emptyList()).toItems(BackupTarget.WEBDAV)) }
                }
            }
            mutable.update { it.copy(loading = false, items = items) }
        }
    }

    /** Restores [item] through the source registered for its kind. */
    fun restore(item: BackupListItem) {
        if (mutable.value.busy) return
        val storage = storageFor(item.target) ?: return
        mutable.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                val engine = BackupEngine(sources(), listOf(storage))
                withContext(Dispatchers.IO) {
                    engine.restore(item.entry.kind, item.entry, storage).getOrThrow()
                }
                mutable.update { it.copy(busy = false, notice = BackupNotice.Restored(item.entry.displayName)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(busy = false, notice = BackupNotice.Failed(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    /** Deletes [item] and its metadata sidecar from its backend, then reloads the list. */
    fun delete(item: BackupListItem) {
        if (mutable.value.busy) return
        val storage = storageFor(item.target) ?: return
        mutable.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    storage.delete(item.entry.relativePath).getOrThrow()
                    BackupPaths.metaPathOf(item.entry.relativePath)?.let { storage.delete(it).getOrThrow() }
                }
                mutable.update { it.copy(busy = false, notice = BackupNotice.Deleted(item.entry.displayName)) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(busy = false, notice = BackupNotice.Failed(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun clearNotice() {
        mutable.update { it.copy(notice = null) }
    }

    private fun storageFor(target: BackupTarget): BackupStorage? = when (target) {
        BackupTarget.LOCAL -> local
        BackupTarget.WEBDAV -> webDav
    }

    private fun sources(): BackupSourceRegistry {
        val tempDir = File(ksuApp.cacheDir, "backup-restore").apply { mkdirs() }
        return BackupSourceRegistry(
            listOf(
                ModuleBackupSource(moduleAccess, tempDir),
                AllowlistBackupSource(allowlistAccess),
                PartitionBackupSource(partitionAccess, settings.backupPartitions.toList(), tempDir),
            ),
        )
    }

    private fun List<RemoteEntry>.toItems(target: BackupTarget): List<BackupListItem> =
        mapNotNull { remote ->
            val entry = BackupPaths.parse(
                relativePath = remote.relativePath,
                sizeBytes = remote.sizeBytes,
                sha256 = remote.sha256.orEmpty(),
                createdAtEpochMs = remote.lastModifiedEpochMs ?: 0L,
            ) ?: return@mapNotNull null
            BackupListItem(
                entry = entry,
                target = target,
                sizeBytes = remote.sizeBytes,
            )
        }
}
