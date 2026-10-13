package me.weishu.kernelsu.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.pluginstore.OfficialPluginStoreSource
import me.weishu.kernelsu.data.pluginstore.PluginStoreSource
import me.weishu.kernelsu.data.pluginstore.StorePlugin
import me.weishu.kernelsu.data.pluginstore.filterByQuery
import me.weishu.kernelsu.data.repository.PluginRepository
import me.weishu.kernelsu.data.repository.PluginRepositoryImpl
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.screen.plugin.PluginStoreUiState
import me.weishu.kernelsu.ui.screen.plugin.stagePluginPackage
import me.weishu.kernelsu.ui.util.download

/**
 * Lists the packages the online catalogue offers and installs a whole package
 * through the daemon, so the plugin identity always comes from its own manifest.
 */
class PluginStoreViewModel(
    private val repo: PluginRepository = PluginRepositoryImpl(),
    private val source: PluginStoreSource = OfficialPluginStoreSource(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(PluginStoreUiState())
    val uiState: StateFlow<PluginStoreUiState> = _uiState.asStateFlow()

    /** Fetches the catalogue the first time the screen is shown. */
    fun ensureLoaded() {
        val state = _uiState.value
        if (state.hasLoaded || state.isLoading) return
        load(refreshing = false)
    }

    fun refresh() = load(refreshing = true)

    private fun load(refreshing: Boolean) {
        _uiState.update { it.copy(isLoading = !refreshing, isRefreshing = refreshing, error = null) }
        viewModelScope.launch {
            source.list(null)
                .onSuccess { plugins ->
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            isRefreshing = false,
                            hasLoaded = true,
                            plugins = plugins,
                            searchResults = plugins.filterByQuery(state.searchText),
                        )
                    }
                }
                .onFailure { fail(RuntimeException(ksuApp.getString(R.string.plugin_store_error), it)) }
        }
    }

    fun updateSearchText(text: String) {
        _uiState.update { it.copy(searchText = text, searchResults = it.plugins.filterByQuery(text)) }
    }

    fun clearSearch() = updateSearchText("")

    fun install(entry: StorePlugin) {
        if (entry.name in _uiState.value.installing) return
        _uiState.update { it.copy(installing = it.installing + entry.name, error = null) }
        viewModelScope.launch {
            var failure: String? = null
            val uri = runCatching {
                download(
                    url = entry.downloadUrl,
                    fileName = entry.fileName,
                    onFailed = { failure = it },
                )
            }.getOrNull()
            val result = if (uri == null) {
                Result.failure<Unit>(IllegalStateException(downloadFailureMessage(failure)))
            } else {
                val path = withContext(Dispatchers.IO) { stagePluginPackage(ksuApp, uri) }
                if (path == null) {
                    Result.failure<Unit>(
                        IllegalStateException(downloadFailureMessage(failure))
                    )
                } else {
                    repo.install(path)
                }
            }
            _uiState.update { it.copy(installing = it.installing - entry.name) }
            result
                .onSuccess {
                    _uiState.update {
                        it.copy(notice = R.string.plugin_store_installed, noticeArg = entry.name)
                    }
                }
                .onFailure { fail(it, entry.name) }
        }
    }

    private fun downloadFailureMessage(detail: String?): String {
        val message = ksuApp.getString(R.string.plugin_store_download_failed)
        return detail?.takeIf { it.isNotBlank() }?.let { "$message: $it" } ?: message
    }

    fun clearNotice() {
        _uiState.update { it.copy(notice = null, noticeArg = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun fail(error: Throwable, entry: String? = null) {
        val reason = error.message ?: error.toString()
        _uiState.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                error = if (entry == null) reason else "$entry: $reason",
            )
        }
    }
}
