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
    val error: String? = null,
)

@Immutable
data class PluginActions(
    val onRefresh: () -> Unit,
    val onToggleEnabled: (Plugin, Boolean) -> Unit,
    val onOpenLog: (Plugin) -> Unit,
    val onDismissLog: () -> Unit,
    val onInstallZip: (Uri) -> Unit,
    val onRequestUninstall: (Plugin) -> Unit,
    val onConfirmUninstall: () -> Unit,
    val onDismissUninstall: () -> Unit,
    val onErrorShown: () -> Unit,
)
