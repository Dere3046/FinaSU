package me.weishu.kernelsu.ui.screen.flash

import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.RemoveModerator
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.component.dialog.ConfirmResult
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import androidx.compose.ui.platform.LocalResources
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.backup.BackupKind
import me.weishu.kernelsu.data.backup.PreFlashBackup
import me.weishu.kernelsu.data.backup.backupResult
import me.weishu.kernelsu.data.backup.source.ModuleInstaller
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.component.material.FolkTextButton
import me.weishu.kernelsu.ui.util.FlashResult
import me.weishu.kernelsu.ui.util.LkmSelection
import me.weishu.kernelsu.ui.util.downloadBoot
import me.weishu.kernelsu.ui.util.flashModuleBatch
import me.weishu.kernelsu.ui.util.getFileName
import me.weishu.kernelsu.ui.util.installBoot
import me.weishu.kernelsu.ui.util.restoreBoot
import me.weishu.kernelsu.ui.util.uninstallPermanently
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

enum class FlashingStatus {
    FLASHING,
    SUCCESS,
    FAILED
}

enum class UninstallType(val icon: ImageVector, val title: Int, val message: Int) {
    TEMPORARY(
        Icons.Rounded.RemoveModerator,
        R.string.settings_uninstall_temporary,
        R.string.settings_uninstall_temporary_message
    ),
    PERMANENT(
        Icons.Rounded.DeleteForever,
        R.string.settings_uninstall_permanent,
        R.string.settings_uninstall_permanent_message
    ),
    RESTORE_STOCK_IMAGE(
        Icons.Rounded.RestartAlt,
        R.string.settings_restore_stock_image,
        R.string.settings_restore_stock_image_message
    ),
    NONE(Icons.Rounded.Adb, 0, 0)
}

@Parcelize
sealed class FlashIt : Parcelable {
    @Parcelize
    data class FlashAnyKernel(val uri: Uri) : FlashIt()
    @Parcelize
    data class FlashBoot(
        val boot: Uri? = null,
        val lkm: LkmSelection,
        val ota: Boolean,
        val partition: String? = null,
        val allowShell: Boolean = false,
        val enableAdb: Boolean = false,
        val backup: Boolean = false,
        val blockModules: String = "",
    ) : FlashIt()

    @Parcelize
    data class DownloadBoot(
        val url: String,
        val partition: String,
        val lkm: LkmSelection,
        val allowShell: Boolean = false,
        val enableAdb: Boolean = false,
        val backup: Boolean = false,
        val blockModules: String = "",
    ) : FlashIt()

    @Parcelize
    data class FlashModules(val uris: List<Uri>) : FlashIt()

    @Parcelize
    data object FlashRestore : FlashIt()

    @Parcelize
    data object FlashUninstall : FlashIt()
}

suspend fun flashModulesSequentially(
    uris: List<Uri>,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
    confirmExternal: suspend (List<String>, Boolean) -> Boolean = { _, _ -> false },
): FlashResult {
    return flashModuleBatch(uris, onStdout, onStderr, confirmExternal)
}

suspend fun flashIt(
    flashIt: FlashIt,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
    confirmExternal: suspend (List<String>, Boolean) -> Boolean = { _, _ -> false },
): FlashResult {
    snapshotBeforeFlash(flashIt, onStdout)
    return when (flashIt) {
        is FlashIt.FlashAnyKernel -> me.weishu.kernelsu.ui.util.flashAnyKernel(
            flashIt.uri, onStdout, onStderr
        )
        is FlashIt.FlashBoot -> installBoot(
            flashIt.boot,
            flashIt.lkm,
            flashIt.ota,
            flashIt.partition,
            flashIt.allowShell,
            flashIt.enableAdb,
            flashIt.backup,
            flashIt.blockModules,
            onStdout,
            onStderr
        )

        is FlashIt.DownloadBoot -> downloadBoot(
            flashIt.url,
            flashIt.partition,
            flashIt.lkm,
            flashIt.allowShell,
            flashIt.enableAdb,
            flashIt.backup,
            flashIt.blockModules,
            onStdout,
            onStderr
        )

        is FlashIt.FlashModules -> {
            flashModulesSequentially(flashIt.uris, onStdout, onStderr, confirmExternal)
        }

        FlashIt.FlashRestore -> restoreBoot(onStdout, onStderr)
        FlashIt.FlashUninstall -> uninstallPermanently(onStdout, onStderr)
    }
}

/** Kinds of content a flash may overwrite, and therefore should be snapshotted first. */
private fun FlashIt.preliminaryBackupKinds(): Set<BackupKind> = when (this) {
    is FlashIt.FlashAnyKernel -> setOf(BackupKind.BOOT)
    is FlashIt.FlashBoot -> setOf(BackupKind.BOOT)
    is FlashIt.DownloadBoot -> setOf(BackupKind.BOOT)
    is FlashIt.FlashModules -> setOf(BackupKind.MODULE)
    FlashIt.FlashRestore, FlashIt.FlashUninstall -> emptySet()
}

/** Partitions a flash is about to write, so the snapshot can cover them even if not selected. */
private fun FlashIt.overwrittenPartitions(): Set<String> = when (this) {
    is FlashIt.FlashBoot -> setOfNotNull(partition)
    is FlashIt.DownloadBoot -> setOf(partition)
    else -> emptySet()
}

/** Backup failures must not abort flashing, but coroutine cancellation must still propagate. */
private suspend fun snapshotBeforeFlash(flashIt: FlashIt, onStdout: (String) -> Unit) {
    val kinds = flashIt.preliminaryBackupKinds()
    val settings = SettingsRepositoryImpl()
    if (kinds.isEmpty() || !settings.backupBeforeFlash) return
    if (!settings.backupLocalEnabled && !settings.backupWebDavEnabled) return
    var installers = emptyList<ModuleInstaller>()
    try {
        if (settings.backupModulesEnabled) installers = stageModuleInstallers(flashIt)
        PreFlashBackup(settings = settings).run(kinds, flashIt.overwrittenPartitions(), installers, onStdout)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onStdout("Pre-flash backup failed: ${e.message ?: e.toString()}")
    } finally {
        installers.forEach { it.file.delete() }
    }
}

/** Copies selected ZIPs without changing the user's documents; unreadable picks are skipped. */
private suspend fun stageModuleInstallers(flashIt: FlashIt): List<ModuleInstaller> {
    val modules = flashIt as? FlashIt.FlashModules ?: return emptyList()
    if (modules.uris.isEmpty()) return emptyList()
    val created = mutableListOf<File>()
    return try {
        withContext(Dispatchers.IO) {
            modules.uris.mapNotNull { uri ->
                backupResult {
                    val name = uri.getFileName(ksuApp) ?: "module.zip"
                    val file = File.createTempFile("module-installer-", ".zip", ksuApp.cacheDir)
                    created += file
                    try {
                        requireNotNull(ksuApp.contentResolver.openInputStream(uri)) {
                            "Cannot read module archive"
                        }.use { input -> file.outputStream().use { input.copyTo(it) } }
                        ModuleInstaller(file, originalName = name)
                    } catch (t: Throwable) {
                        file.delete()
                        throw t
                    }
                }.getOrNull()
            }
        }
    } catch (t: Throwable) {
        created.forEach { it.delete() }
        throw t
    }
}

@Composable
fun FlashEffect(
    flashIt: FlashIt,
    text: String,
    logContent: StringBuilder,
    onTextUpdate: (String) -> Unit,
    onShowRebootChange: (Boolean) -> Unit,
    onFlashingStatusChange: (FlashingStatus) -> Unit,
    enabled: Boolean = true
) {
    val dialog = rememberConfirmDialog()
    val resources = LocalResources.current
    LaunchedEffect(enabled) {
        if (!enabled || text.isNotEmpty()) {
            return@LaunchedEffect
        }
        var currentText = text
        val mainHandler = Handler(Looper.getMainLooper())
        withContext(Dispatchers.IO) {
            flashIt(flashIt, onStdout = {
                val tempText = "$it\n"
                if (tempText.startsWith("[H[J")) { // clear command
                    currentText = tempText.substring(6)
                } else {
                    currentText += tempText
                }
                mainHandler.post {
                    onTextUpdate(currentText)
                }
                logContent.append(it).append("\n")
            }, onStderr = {
                logContent.append(it).append("\n")
            }, confirmExternal = { ids, blocked ->
                // Suspend without holding the backend lock or blocking an IO worker.
                withContext(Dispatchers.Main) {
                    dialog.awaitConfirm(
                        title = resources.getString(R.string.folkzygisk_switch_title),
                        content = resources.getString(if (blocked) R.string.folkzygisk_multiple_external
                            else R.string.folkzygisk_install_switch_message, ids.joinToString(", ")),
                        confirm = resources.getString(if (blocked) android.R.string.ok else R.string.folkzygisk_switch_install),
                    ) == ConfirmResult.Confirmed && !blocked
                }
            }).apply {
                if (code != 0) {
                    currentText += "Error code: $code.\n $err Please save and check the log.\n"
                    mainHandler.post {
                        onTextUpdate(currentText)
                    }
                }
                if (showReboot) {
                    currentText += "\n\n\n"
                    mainHandler.post {
                        onTextUpdate(currentText)
                        onShowRebootChange(true)
                    }
                }
                mainHandler.post {
                    onFlashingStatusChange(if (code == 0) FlashingStatus.SUCCESS else FlashingStatus.FAILED)
                }
            }
        }
    }
}

fun saveLog(
    logContent: StringBuilder,
    scope: CoroutineScope,
    showMessage: (String) -> Unit
): () -> Unit {
    return {
        scope.launch {
            val format = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
            val date = format.format(Date())
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "KernelSU_install_log_${date}.log"
            )
            file.writeText(logContent.toString())
            showMessage("Log saved to ${file.absolutePath}")
        }
    }
}

private const val JAILBREAK_WARNING_COUNTDOWN = 10

@Composable
fun JailbreakFlashWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var countdown by remember { mutableIntStateOf(JAILBREAK_WARNING_COUNTDOWN) }

    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000.milliseconds)
            countdown--
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(android.R.string.dialog_alert_title)) },
        text = {
            Text(
                stringResource(R.string.jailbreak_flash_warning),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            FolkTextButton(
                onClick = onConfirm,
                enabled = countdown == 0
            ) {
                Text(
                    if (countdown > 0)
                        stringResource(R.string.jailbreak_flash_warning_countdown, countdown)
                    else
                        stringResource(R.string.install_next)
                )
            }
        },
        dismissButton = {
            FolkTextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
