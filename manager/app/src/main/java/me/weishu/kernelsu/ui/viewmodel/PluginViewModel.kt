package me.weishu.kernelsu.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.weishu.kernelsu.data.model.Plugin
import me.weishu.kernelsu.data.repository.PluginRepository
import me.weishu.kernelsu.data.repository.PluginRepositoryImpl
import me.weishu.kernelsu.ui.screen.plugin.PluginUiState

class PluginViewModel(
    private val repo: PluginRepository = PluginRepositoryImpl(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(PluginUiState())
    val uiState: StateFlow<PluginUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            repo.getPlugins()
                .onSuccess { plugins ->
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            hasLoaded = true,
                            plugins = plugins,
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            hasLoaded = true,
                            error = error.message ?: error.toString(),
                        )
                    }
                }
        }
    }

    fun setEnabled(plugin: Plugin, enabled: Boolean) {
        if (plugin.id in _uiState.value.pending) return
        viewModelScope.launch {
            _uiState.update { it.copy(pending = it.pending + plugin.id, error = null) }
            repo.setEnabled(plugin.id, enabled)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            plugins = state.plugins.map { existing ->
                                if (existing.id == plugin.id) existing.copy(enabled = enabled) else existing
                            },
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message ?: error.toString()) }
                }
            _uiState.update { it.copy(pending = it.pending - plugin.id) }
        }
    }

    fun loadLog(plugin: Plugin) {
        viewModelScope.launch {
            repo.getLog(plugin.id)
                .onSuccess { text ->
                    _uiState.update { it.copy(logPlugin = plugin.id, logText = text, error = null) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message ?: error.toString()) }
                }
        }
    }

    fun dismissLog() {
        _uiState.update { it.copy(logPlugin = null, logText = "") }
    }
}
