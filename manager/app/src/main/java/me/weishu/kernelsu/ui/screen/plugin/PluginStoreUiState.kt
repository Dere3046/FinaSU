package me.weishu.kernelsu.ui.screen.plugin

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import me.weishu.kernelsu.data.pluginstore.StorePlugin

@Stable
data class PluginStoreUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val plugins: List<StorePlugin> = emptyList(),
    val searchText: String = "",
    val searchResults: List<StorePlugin> = emptyList(),
    /** Entries whose package is being downloaded and installed right now. */
    val installing: Set<String> = emptySet(),
    /** A string resource shown once the operation it reports finished. */
    val notice: Int? = null,
    val noticeArg: String? = null,
    val error: String? = null,
)

@Immutable
data class PluginStoreActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onSearchTextChange: (String) -> Unit,
    val onClearSearch: () -> Unit,
    val onInstall: (StorePlugin) -> Unit,
    val onNoticeShown: () -> Unit,
    val onErrorShown: () -> Unit,
)
