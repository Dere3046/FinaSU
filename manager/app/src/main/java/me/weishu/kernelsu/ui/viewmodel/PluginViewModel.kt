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
                .onSuccess { plugins -> applyList(plugins) }
                .onFailure { error -> fail(error) }
        }
    }

    fun setEnabled(plugin: Plugin, enabled: Boolean) {
        if (plugin.id in _uiState.value.pending) return
        _uiState.update { it.copy(pending = it.pending + plugin.id) }
        viewModelScope.launch {
            repo.setEnabled(plugin.id, enabled)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(plugins = state.plugins.withEnabled(plugin.id, enabled))
                    }
                }
                .onFailure { error -> fail(error) }
            _uiState.update { it.copy(pending = it.pending - plugin.id) }
        }
    }

    fun install(path: String) {
        if (_uiState.value.isInstalling) return
        _uiState.update { it.copy(isInstalling = true) }
        viewModelScope.launch {
            repo.install(path)
                .onSuccess { reload() }
                .onFailure { error -> fail(error) }
            _uiState.update { it.copy(isInstalling = false) }
        }
    }

    fun requestUninstall(plugin: Plugin) {
        _uiState.update { it.copy(confirmUninstall = plugin) }
    }

    fun dismissUninstall() {
        _uiState.update { it.copy(confirmUninstall = null) }
    }

    fun confirmUninstall() {
        val plugin = _uiState.value.confirmUninstall ?: return
        _uiState.update { it.copy(confirmUninstall = null, pending = it.pending + plugin.id) }
        viewModelScope.launch {
            repo.uninstall(plugin.id)
                .onSuccess { reload() }
                .onFailure { error -> fail(error) }
            _uiState.update { it.copy(pending = it.pending - plugin.id) }
        }
    }

    fun loadLog(plugin: Plugin) {
        viewModelScope.launch {
            repo.getLog(plugin.id)
                .onSuccess { text ->
                    _uiState.update { it.copy(logPlugin = plugin.id, logText = text) }
                }
                .onFailure { error -> fail(error) }
        }
    }

    fun dismissLog() {
        _uiState.update { it.copy(logPlugin = null, logText = "") }
    }

    fun reportError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private suspend fun reload() {
        loadJob?.join()
        repo.getPlugins()
            .onSuccess { plugins -> applyList(plugins) }
            .onFailure { error -> fail(error) }
    }

    private fun applyList(plugins: List<Plugin>) {
        _uiState.update {
            it.copy(isRefreshing = false, hasLoaded = true, plugins = plugins)
        }
    }

    private fun fail(error: Throwable) {
        _uiState.update {
            it.copy(
                isRefreshing = false,
                isInstalling = false,
                error = error.message ?: error.toString(),
            )
        }
    }
}

private fun List<Plugin>.withEnabled(id: String, enabled: Boolean): List<Plugin> =
    map { plugin -> if (plugin.id == id) plugin.copy(enabled = enabled) else plugin }
