package me.weishu.kernelsu.ui.screen.plugin

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import me.weishu.kernelsu.data.model.Plugin

@Stable
data class PluginUiState(
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val plugins: List<Plugin> = emptyList(),
    /** Ids whose enable state is being changed right now. */
    val pending: Set<String> = emptySet(),
    /** Id of the plugin whose log is open, or null when no log is shown. */
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
)
