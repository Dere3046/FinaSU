package me.weishu.kernelsu.ui.screen.plugin

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import me.weishu.kernelsu.data.model.Plugin

@Stable
data class PluginUiState(
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val plugins: List<Plugin> = emptyList(),
    val pending: Set<String> = emptySet(),
    val isInstalling: Boolean = false,
    val confirmUninstall: Plugin? = null,
    val logPlugin: String? = null,
    val logText: String = "",
    val configPlugin: Plugin? = null,
    val configValues: Map<String, String> = emptyMap(),
    val savedConfigValues: Map<String, String> = emptyMap(),
    /** Keys whose stored value is dropped so the package default applies again. */
    val configDeleted: Set<String> = emptySet(),
    val isLoadingConfig: Boolean = false,
    val running: Set<String> = emptySet(),
    /** A string resource shown once the operation it reports finished. */
    val notice: Int? = null,
    val error: String? = null,
)

@Immutable
data class PluginActions(
    val onRefresh: () -> Unit,
    val onToggleEnabled: (Plugin, Boolean) -> Unit,
    val onOpenLog: (Plugin) -> Unit,
    val onDismissLog: () -> Unit,
    val onInstallZip: (Uri) -> Unit,
    val onOpenStore: () -> Unit,
    val onRequestUninstall: (Plugin) -> Unit,
    val onConfirmUninstall: () -> Unit,
    val onDismissUninstall: () -> Unit,
    val onOpenConfig: (Plugin) -> Unit,
    val onChangeConfigValue: (String, String) -> Unit,
    val onResetConfigValue: (String) -> Unit,
    val onSaveConfig: () -> Unit,
    val onDismissConfig: () -> Unit,
    val onRun: (Plugin) -> Unit,
    val onNoticeShown: () -> Unit,
    val onErrorShown: () -> Unit,
)
