package me.weishu.kernelsu.ui.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.weishu.kernelsu.data.model.Plugin
import me.weishu.kernelsu.data.repository.PluginRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun preloadIsSilentAndTabActivationReusesEmptyList() = runTest(dispatcher) {
        val repo = Repository()
        val model = PluginViewModel(repo)
        model.ensureLoaded()
        model.ensureLoaded()
        assertFalse(model.uiState.value.isRefreshing)
        advanceUntilIdle()
        assertTrue(model.uiState.value.hasLoaded)
        model.ensureLoaded()
        advanceUntilIdle()
        assertEquals(1, repo.loads)
    }

    @Test fun invalidationReloadsSilentlyAndPullRefreshShowsIndicator() = runTest(dispatcher) {
        val repo = Repository()
        val model = PluginViewModel(repo)
        model.ensureLoaded()
        advanceUntilIdle()
        model.markNeedRefresh()
        model.ensureLoaded()
        assertFalse(model.uiState.value.isRefreshing)
        advanceUntilIdle()
        assertEquals(2, repo.loads)
        model.refresh()
        assertTrue(model.uiState.value.isRefreshing)
        advanceUntilIdle()
        assertFalse(model.uiState.value.isRefreshing)
        assertEquals(3, repo.loads)
    }

    @Test fun failedPreloadCanRetryAndFailedRefreshKeepsLastList() = runTest(dispatcher) {
        val repo = Repository()
        val model = PluginViewModel(repo)
        repo.result = Result.failure(IllegalStateException("unavailable"))
        model.ensureLoaded()
        advanceUntilIdle()
        assertFalse(model.uiState.value.hasLoaded)
        repo.result = Result.success(listOf(plugin))
        model.ensureLoaded()
        advanceUntilIdle()
        repo.result = Result.failure(IllegalStateException("unavailable"))
        model.refresh()
        advanceUntilIdle()
        assertEquals(listOf(plugin), model.uiState.value.plugins)
        assertFalse(model.uiState.value.isRefreshing)
        assertEquals(3, repo.loads)
    }

    @Test fun repeatedActivationDoesNotStartAnotherScanWhileLoading() = runTest(dispatcher) {
        val repo = Repository()
        repo.gate = CompletableDeferred()
        val model = PluginViewModel(repo)
        model.ensureLoaded()
        runCurrent()
        model.ensureLoaded()
        model.refresh()
        runCurrent()
        assertEquals(1, repo.loads)
        repo.gate?.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.uiState.value.hasLoaded)
    }

    @Test fun invalidationDuringPreloadIsNotLost() = runTest(dispatcher) {
        val repo = Repository()
        repo.gate = CompletableDeferred()
        val model = PluginViewModel(repo)
        model.ensureLoaded()
        runCurrent()
        model.markNeedRefresh()
        model.ensureLoaded()
        repo.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, repo.loads)
        model.ensureLoaded()
        advanceUntilIdle()
        assertEquals(2, repo.loads)
    }

    private class Repository : PluginRepository {
        var loads = 0
        var result: Result<List<Plugin>> = Result.success(emptyList())
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun getPlugins(): Result<List<Plugin>> {
            loads++
            gate?.await()
            return result
        }
        override suspend fun setEnabled(id: String, enabled: Boolean) = Result.success(Unit)
        override suspend fun install(path: String) = Result.success(Unit)
        override suspend fun uninstall(id: String) = Result.success(Unit)
        override suspend fun getLog(id: String) = Result.success("")
        override suspend fun getConfig(id: String) = Result.success(emptyMap<String, String>())
        override suspend fun setConfig(id: String, key: String, value: String) = Result.success(Unit)
        override suspend fun deleteConfig(id: String, key: String) = Result.success(Unit)
        override suspend fun run(id: String, callback: String) = Result.success(Unit)
    }

    private val plugin = Plugin(
        id = "test", name = "Test", author = "", version = "1", description = "",
        license = "", enabled = true, entry = "main.lua", quickAction = null,
        config = emptyList(), depends = emptyList(), callbacks = emptyList(), error = null,
    )
}
