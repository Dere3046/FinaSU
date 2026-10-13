package me.weishu.kernelsu.ui.screen.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.pluginstore.StorePlugin
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.FolkFilledTonalButton
import me.weishu.kernelsu.ui.component.material.SearchAppBar
import me.weishu.kernelsu.ui.component.material.SnackBarHost
import me.weishu.kernelsu.ui.component.material.TonalCard
import me.weishu.kernelsu.ui.component.material.TopBarBackButton

/**
 * The online catalogue. It only lists packages and installs them as they come,
 * because the plugin identity is whatever the package carries itself.
 */
@Composable
fun PluginStoreMaterial(
    uiState: PluginStoreUiState,
    actions: PluginStoreActions,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    LaunchedEffect(uiState.error) {
        val message = uiState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        actions.onErrorShown()
    }

    LaunchedEffect(uiState.notice, uiState.noticeArg) {
        val notice = uiState.notice ?: return@LaunchedEffect
        val arg = uiState.noticeArg
        val message = if (arg == null) context.getString(notice) else context.getString(notice, arg)
        snackbarHostState.showSnackbar(message)
        actions.onNoticeShown()
    }

    ExpressiveScaffold(
        topBar = {
            SearchAppBar(
                snackbarHostState = snackbarHostState,
                title = { Text(text = stringResource(R.string.plugin_store)) },
                searchText = uiState.searchText,
                onSearchTextChange = actions.onSearchTextChange,
                onClearClick = actions.onClearSearch,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarBackButton(onClick = actions.onBack)
                },
                searchContent = { bottomPadding, closeSearch ->
                    if (uiState.searchResults.isEmpty()) {
                        StoreMessageState(
                            icon = Icons.Outlined.SearchOff,
                            title = stringResource(R.string.plugin_store_no_matches),
                        )
                    } else {
                        StorePluginList(
                            plugins = uiState.searchResults,
                            installing = uiState.installing,
                            listState = searchListState,
                            bottomPadding = bottomPadding,
                            modifier = Modifier.fillMaxSize(),
                            onInstall = actions.onInstall,
                            closeSearch = closeSearch,
                        )
                    }
                },
            )
        },
        snackbarHost = {
            SnackBarHost(hostState = snackbarHostState)
        },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        when {
            uiState.isLoading -> Box(modifier = contentModifier, contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }

            uiState.error != null && uiState.plugins.isEmpty() -> StoreMessageState(
                icon = Icons.Outlined.CloudOff,
                title = uiState.error,
                modifier = contentModifier,
            )

            else -> PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = {
                    haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    actions.onRefresh()
                },
                state = pullToRefreshState,
                modifier = contentModifier,
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(
                        modifier = Modifier.align(Alignment.TopCenter),
                        isRefreshing = uiState.isRefreshing,
                        state = pullToRefreshState,
                    )
                },
            ) {
                StorePluginList(
                    plugins = uiState.plugins,
                    installing = uiState.installing,
                    listState = listState,
                    bottomPadding = 0.dp,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    onInstall = actions.onInstall,
                )
            }
        }
    }
}

@Composable
private fun StorePluginList(
    plugins: List<StorePlugin>,
    installing: Set<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    bottomPadding: Dp,
    onInstall: (StorePlugin) -> Unit,
    modifier: Modifier = Modifier,
    closeSearch: () -> Unit = {},
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(13.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 16.dp + bottomPadding,
        ),
    ) {
        if (plugins.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.plugin_store_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(plugins, key = { it.downloadUrl }, contentType = { "store-plugin" }) { entry ->
            StorePluginItem(
                plugin = entry,
                installing = entry.name in installing,
                onInstall = {
                    closeSearch()
                    onInstall(entry)
                },
            )
        }
    }
}

@Composable
private fun StorePluginItem(
    plugin: StorePlugin,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = plugin.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (plugin.version.isNotBlank()) {
                    Text(
                        text = plugin.version,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (plugin.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = plugin.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            FolkFilledTonalButton(enabled = !installing, onClick = onInstall) {
                if (installing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.install),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StoreMessageState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
