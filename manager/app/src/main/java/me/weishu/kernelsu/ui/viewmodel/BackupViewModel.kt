package me.weishu.kernelsu.ui.viewmodel

import android.util.Log
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
import me.weishu.kernelsu.data.backup.BackupPlan
import me.weishu.kernelsu.data.backup.BackupSource
import me.weishu.kernelsu.data.backup.BackupSourceRegistry
import me.weishu.kernelsu.data.backup.BackupStorage
import me.weishu.kernelsu.data.backup.SecretStore
import me.weishu.kernelsu.data.backup.WEBDAV_PASSWORD_SECRET_KEY
import me.weishu.kernelsu.data.backup.backupResult
import me.weishu.kernelsu.data.backup.webDavBackupStorage
import me.weishu.kernelsu.data.backup.device.NativesAllowlistAccess
import me.weishu.kernelsu.data.backup.device.RootPartitionAccess
import me.weishu.kernelsu.data.backup.local.LocalBackupStorage
import me.weishu.kernelsu.data.backup.source.AllowlistAccess
import me.weishu.kernelsu.data.backup.source.AllowlistBackupSource
import me.weishu.kernelsu.data.backup.source.PartitionAccess
import me.weishu.kernelsu.data.backup.source.PartitionBackupSource
import me.weishu.kernelsu.data.backup.source.collectKnownStockSha1
import me.weishu.kernelsu.data.backup.webdav.WebDavBackupStorage
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ksuApp

data class BackupOutcome(
    val written: Int,
    val skipped: Int,
    val failed: Int,
)

enum class WebDavTestState { IDLE, RUNNING, SUCCESS, FAILED }

data class BackupUiState(
    val localEnabled: Boolean = true,
    val webDavEnabled: Boolean = false,
    val webDavUrl: String = "",
    val webDavUsername: String = "",
    val webDavPasswordSet: Boolean = false,
    val webDavPath: String = "",
    val modulesEnabled: Boolean = true,
    val allowlistEnabled: Boolean = true,
    val selectedPartitions: Set<String> = emptySet(),
    val availablePartitions: List<String> = emptyList(),
    val backupBeforeFlash: Boolean = false,
    val busy: Boolean = false,
    val testState: WebDavTestState = WebDavTestState.IDLE,
    val outcome: BackupOutcome? = null,
    val error: String? = null,
) {
    val canBackup: Boolean get() = (localEnabled || webDavEnabled) &&
        (allowlistEnabled || selectedPartitions.isNotEmpty())
}

/** Manual runs export allowlists and partitions; module installers are archived only before flashing. */
class BackupViewModel(
    private val settings: SettingsRepository = SettingsRepositoryImpl(),
    private val secrets: SecretStore = ksuApp.backupSecrets,
    private val partitionAccess: PartitionAccess = RootPartitionAccess(),
    private val allowlistAccess: AllowlistAccess = NativesAllowlistAccess(),
) : ViewModel() {

    private val mutable = MutableStateFlow(
        BackupUiState(
            localEnabled = settings.backupLocalEnabled,
            webDavEnabled = settings.backupWebDavEnabled,
            webDavUrl = settings.webDavUrl,
            webDavUsername = settings.webDavUsername,
            webDavPasswordSet = secrets.has(WEBDAV_PASSWORD_SECRET_KEY),
            webDavPath = settings.webDavPath,
            modulesEnabled = settings.backupModulesEnabled,
            allowlistEnabled = settings.backupAllowlistEnabled,
            selectedPartitions = settings.backupPartitions,
            backupBeforeFlash = settings.backupBeforeFlash,
        )
    )
    val state = mutable.asStateFlow()

    init {
        refreshPartitions()
    }

    private fun refreshPartitions() {
        viewModelScope.launch {
            val partitions = withContext(Dispatchers.IO) {
                partitionAccess.availablePartitions().getOrDefault(emptyList())
            }
            mutable.update { it.copy(availablePartitions = partitions) }
        }
    }

    fun setLocalEnabled(value: Boolean) {
        settings.backupLocalEnabled = value
        mutable.update { it.copy(localEnabled = value) }
    }

    fun setWebDavEnabled(value: Boolean) {
        settings.backupWebDavEnabled = value
        mutable.update { it.copy(webDavEnabled = value) }
    }

    fun setWebDavUrl(value: String) {
        settings.webDavUrl = value
        mutable.update { it.copy(webDavUrl = value) }
    }

    fun setWebDavUsername(value: String) {
        settings.webDavUsername = value
        mutable.update { it.copy(webDavUsername = value) }
    }

    fun setWebDavPath(value: String) {
        settings.webDavPath = value
        mutable.update { it.copy(webDavPath = value) }
    }

    /** Stores the WebDAV password (encrypted). A blank value removes any stored secret. */
    fun setWebDavPassword(value: String) {
        if (value.isBlank()) {
            secrets.remove(WEBDAV_PASSWORD_SECRET_KEY)
        } else {
            secrets.put(WEBDAV_PASSWORD_SECRET_KEY, value)
        }
        mutable.update { it.copy(webDavPasswordSet = secrets.has(WEBDAV_PASSWORD_SECRET_KEY)) }
    }

    fun setModulesEnabled(value: Boolean) {
        settings.backupModulesEnabled = value
        mutable.update { it.copy(modulesEnabled = value) }
    }

    fun setAllowlistEnabled(value: Boolean) {
        settings.backupAllowlistEnabled = value
        mutable.update { it.copy(allowlistEnabled = value) }
    }

    fun setPartitionEnabled(partition: String, enabled: Boolean) {
        val updated = mutable.value.selectedPartitions.toMutableSet().apply {
            if (enabled) add(partition) else remove(partition)
        }
        settings.backupPartitions = updated
        mutable.update { it.copy(selectedPartitions = updated) }
    }

    fun setBackupBeforeFlash(value: Boolean) {
        settings.backupBeforeFlash = value
        mutable.update { it.copy(backupBeforeFlash = value) }
    }

    fun clearOutcome() {
        mutable.update { it.copy(outcome = null) }
    }

    fun clearError() {
        mutable.update { it.copy(error = null) }
    }

    fun clearTestResult() {
        mutable.update { it.copy(testState = WebDavTestState.IDLE) }
    }

    fun testWebDav() {
        val snapshot = mutable.value
        if (snapshot.testState == WebDavTestState.RUNNING) return
        mutable.update { it.copy(testState = WebDavTestState.RUNNING, error = null) }
        viewModelScope.launch {
            val succeeded = withContext(Dispatchers.IO) {
                backupResult {
                    webDavStorageOrNull(snapshot)?.test()?.getOrThrow()
                        ?: throw IllegalArgumentException("WebDAV URL is empty")
                }.isSuccess
            }
            mutable.update {
                it.copy(testState = if (succeeded) WebDavTestState.SUCCESS else WebDavTestState.FAILED)
            }
        }
    }

    fun backupNow() {
        val snapshot = mutable.value
        if (snapshot.busy || !snapshot.canBackup) return
        mutable.update { it.copy(busy = true, outcome = null, error = null) }
        viewModelScope.launch {
            try {
                val storages = buildStorages(snapshot)
                check(storages.isNotEmpty()) { "No backup destination configured" }
                val tempDir = File(ksuApp.cacheDir, "backup").apply { mkdirs() }
                val knownStockSha1 = withContext(Dispatchers.IO) { collectKnownStockSha1(storages) }
                val sources = buildSources(snapshot, tempDir, knownStockSha1)
                check(sources.kinds.isNotEmpty()) { "No backup source available" }
                val engine = BackupEngine(sources, storages)
                val outcome = withContext(Dispatchers.IO) {
                    val plan = BackupPlan(
                        localEnabled = snapshot.localEnabled,
                        webDavEnabled = snapshot.webDavEnabled,
                        modules = false,
                        allowlist = snapshot.allowlistEnabled,
                        partitions = snapshot.selectedPartitions,
                    )
                    var written = 0
                    var skipped = 0
                    var failed = 0
                    for (kind in plan.kinds) {
                        if (sources.sourceFor(kind) == null) continue
                        val result = engine.backup(kind)
                        result.failures.forEach { failure ->
                            Log.w(
                                "FinaSUBackup",
                                "backup $kind ${failure.operation} failed at ${failure.path} (${failure.storage})",
                                failure.cause,
                            )
                        }
                        written += result.written.size
                        skipped += result.skipped.size
                        failed += result.failures.size
                    }
                    BackupOutcome(written = written, skipped = skipped, failed = failed)
                }
                mutable.update { it.copy(busy = false, outcome = outcome) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("FinaSUBackup", "backup run failed", e)
                mutable.update { it.copy(busy = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun buildStorages(state: BackupUiState): List<BackupStorage> = buildList {
        if (state.localEnabled) add(LocalBackupStorage(ksuApp))
        if (state.webDavEnabled) {
            webDavStorageOrNull(state)?.let { add(it) }
        }
    }

    private fun buildSources(
        state: BackupUiState,
        tempDir: File,
        knownStockSha1: Set<String>,
    ): BackupSourceRegistry {
        val sources = mutableListOf<BackupSource>()
        if (state.allowlistEnabled) sources += AllowlistBackupSource(allowlistAccess)
        val partitions = state.availablePartitions.filter { it in state.selectedPartitions }
        if (partitions.isNotEmpty()) {
            sources += PartitionBackupSource(
                access = partitionAccess,
                partitions = partitions,
                tempDir = tempDir,
                knownStockSha1 = knownStockSha1,
            )
        }
        return BackupSourceRegistry(sources)
    }

    private fun webDavStorageOrNull(state: BackupUiState): WebDavBackupStorage? = webDavBackupStorage(
        ksuApp.okhttpClient, secrets, state.webDavUrl, state.webDavUsername, state.webDavPath,
    )
}
