package me.weishu.kernelsu.ui.screen.plugin

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.Plugin
import me.weishu.kernelsu.ui.component.ScrollToTopOnChange
import me.weishu.kernelsu.ui.component.bottombar.LocalFloatingBarReservedHeight
import me.weishu.kernelsu.ui.component.bottombar.LocalFloatingBarVisible
import me.weishu.kernelsu.ui.component.bottombar.LocalIsFloatingNavMode
import me.weishu.kernelsu.ui.component.material.ExpressiveDialog
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.ExpressiveSwitch
import me.weishu.kernelsu.ui.component.material.FolkFilledTonalButton
import me.weishu.kernelsu.ui.component.material.FolkSmallExtendedFloatingActionButton
import me.weishu.kernelsu.ui.component.material.FolkTextButton
import me.weishu.kernelsu.ui.component.material.SearchAppBar
import me.weishu.kernelsu.ui.component.material.SnackBarHost
import me.weishu.kernelsu.ui.component.material.TonalCard
import me.weishu.kernelsu.ui.screen.module.ExpandableDescriptionText
import me.weishu.kernelsu.ui.theme.LocalModuleDescriptionMaxLines

@Composable
fun PluginPagerMaterial(
    uiState: PluginUiState,
    actions: PluginActions,
    bottomInnerPadding: Dp,
) {
    val snackBarHost = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current

    val pullToRefreshState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val refreshTick = remember { mutableIntStateOf(0) }
    var searchText by rememberSaveable { mutableStateOf("") }
    val threshold = with(LocalDensity.current) { 100.dp.toPx() }
    val fabExpanded by remember {
        var lastIndex = 0
        var lastOffset = 0
        var scrollDelta = 0f
        var expanded = true
        derivedStateOf {
            val currentIndex = listState.firstVisibleItemIndex
            val currentOffset = listState.firstVisibleItemScrollOffset
            val delta = if (currentIndex == lastIndex) {
                (currentOffset - lastOffset).toFloat()
            } else if (currentIndex > lastIndex) {
                100f
            } else {
                -100f
            }
            scrollDelta = (scrollDelta + delta).coerceIn(-threshold, threshold)
            lastIndex = currentIndex
            lastOffset = currentOffset
            if (currentIndex == 0) {
                expanded = true
                scrollDelta = 0f
            } else if (expanded && scrollDelta >= threshold) {
                expanded = false
                scrollDelta = 0f
            } else if (!expanded && scrollDelta <= -threshold) {
                expanded = true
                scrollDelta = 0f
            }
            expanded
        }
    }

    LaunchedEffect(uiState.error) {
        val message = uiState.error ?: return@LaunchedEffect
        snackBarHost.showSnackbar(message)
        actions.onErrorShown()
    }

    ExpressiveScaffold(
        topBar = {
            SearchAppBar(
                largeTitle = false,
                topSearch = true,
                title = { Text(stringResource(R.string.plugin)) },
                searchText = searchText,
                onSearchTextChange = { searchText = it },
                onClearClick = { searchText = "" },
                snackbarHostState = snackBarHost,
                searchContent = { bottomPadding, closeSearch ->
                    val results = remember(searchText, uiState.plugins) {
                        uiState.plugins.matching(searchText)
                    }
                    PluginList(
                        bottomInnerPadding = bottomPadding,
                        modifier = Modifier.fillMaxSize(),
                        listState = searchListState,
                        displayPlugins = results,
                        pending = uiState.pending,
                        actions = actions,
                        closeSearch = closeSearch,
                    )
                },
            )
        },
        floatingActionButton = {
            val installLabel = stringResource(id = R.string.plugin_install)
            val packagePicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri ->
                if (uri != null) {
                    actions.onInstallZip(uri)
                }
            }

            val floatingNavMode = LocalIsFloatingNavMode.current
            val floatingBarVisible = LocalFloatingBarVisible.current
            val floatingBarReserved = LocalFloatingBarReservedHeight.current
            val isLandscape =
                LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val fabDrop by animateDpAsState(
                targetValue = if (floatingNavMode && !floatingBarVisible && !isLandscape) {
                    floatingBarReserved
                } else {
                    0.dp
                },
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                label = "fabDrop",
            )
            Box(modifier = Modifier.offset(y = fabDrop)) {
                FolkSmallExtendedFloatingActionButton(
                    modifier = Modifier.padding(bottom = bottomInnerPadding),
                    expanded = fabExpanded,
                    onClick = {
                        packagePicker.launch("application/zip")
                    },
                    icon = { Icon(Icons.Filled.Add, installLabel) },
                    text = { Text(text = installLabel) },
                )
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        snackbarHost = {
            SnackBarHost(
                hostState = snackBarHost,
                modifier = Modifier.padding(bottom = bottomInnerPadding),
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            isRefreshing = uiState.isRefreshing,
            onRefresh = {
                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                actions.onRefresh()
                refreshTick.intValue++
            },
            state = pullToRefreshState,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    modifier = Modifier.align(Alignment.TopCenter),
                    isRefreshing = uiState.isRefreshing,
                    state = pullToRefreshState,
                )
            },
        ) {
            val latestPlugins = rememberUpdatedState(uiState.plugins)
            val latestRefreshing = rememberUpdatedState(uiState.isRefreshing)
            ScrollToTopOnChange(
                listState,
                refreshTick.intValue,
                isBusy = { latestRefreshing.value },
            ) { latestPlugins.value }
            PluginList(
                bottomInnerPadding = bottomInnerPadding,
                listState = listState,
                displayPlugins = uiState.plugins,
                pending = uiState.pending,
                actions = actions,
            )
        }
    }

    PluginLogDialog(uiState = uiState, actions = actions)
    PluginUninstallDialog(uiState = uiState, actions = actions)
}

@Composable
private fun PluginList(
    bottomInnerPadding: Dp,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    displayPlugins: List<Plugin>,
    pending: Set<String>,
    actions: PluginActions,
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
            bottom = 16.dp + bottomInnerPadding + 56.dp + 16.dp,
        ),
    ) {
        if (displayPlugins.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.plugin_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(displayPlugins, key = { it.id }, contentType = { "plugin" }) { plugin ->
            PluginItem(
                plugin = plugin,
                pending = plugin.id in pending,
                actions = actions,
                closeSearch = closeSearch,
            )
        }
    }
}

@Composable
private fun PluginItem(
    plugin: Plugin,
    pending: Boolean,
    actions: PluginActions,
    closeSearch: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val hasDescription = plugin.description.isNotBlank()
    val maxLinesLimit = LocalModuleDescriptionMaxLines.current
    var expanded by rememberSaveable(plugin.id) { mutableStateOf(false) }
    val actionable = plugin.error == null && !pending

    TonalCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (hasDescription) {
            { expanded = !expanded }
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val versionLabel = stringResource(id = R.string.module_version)
                val authorLabel = stringResource(id = R.string.module_author)

                Column(
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text(
                        text = plugin.name.ifBlank { plugin.id },
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                    )

                    Text(
                        text = "$versionLabel: ${plugin.version}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    Text(
                        text = "$authorLabel: ${plugin.author}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ExpressiveSwitch(
                        enabled = actionable,
                        checked = plugin.enabled,
                        onCheckedChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            actions.onToggleEnabled(plugin, it)
                        },
                    )
                }
            }

            if (hasDescription) {
                Spacer(modifier = Modifier.height(8.dp))

                ExpandableDescriptionText(
                    text = plugin.description,
                    expanded = expanded,
                    maxLinesLimit = maxLinesLimit,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            val dependencies = plugin.depends.joinToString(", ")
            if (dependencies.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${stringResource(R.string.plugin_dependencies)}: $dependencies",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            plugin.error?.let { message ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(top = 10.dp),
                thickness = Dp.Hairline,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FolkFilledTonalButton(
                    modifier = Modifier.defaultMinSize(52.dp, 32.dp),
                    onClick = {
                        actions.onOpenLog(plugin)
                        closeSearch()
                    },
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Icon(
                        modifier = Modifier.size(20.dp),
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                    )
                    Text(
                        modifier = Modifier.padding(start = 7.dp),
                        fontFamily = MaterialTheme.typography.labelMedium.fontFamily,
                        fontSize = MaterialTheme.typography.labelMedium.fontSize,
                        text = stringResource(R.string.plugin_log),
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                FolkFilledTonalButton(
                    modifier = Modifier.defaultMinSize(52.dp, 32.dp),
                    enabled = actionable,
                    onClick = {
                        actions.onRequestUninstall(plugin)
                        closeSearch()
                    },
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Icon(
                        modifier = Modifier.size(20.dp),
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                    )
                    Text(
                        modifier = Modifier.padding(start = 7.dp),
                        fontFamily = MaterialTheme.typography.labelMedium.fontFamily,
                        fontSize = MaterialTheme.typography.labelMedium.fontSize,
                        text = stringResource(R.string.uninstall),
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginLogDialog(
    uiState: PluginUiState,
    actions: PluginActions,
) {
    val pluginId = uiState.logPlugin ?: return
    val emptyLog = stringResource(R.string.plugin_log_empty)

    ExpressiveDialog(
        onDismissRequest = actions.onDismissLog,
        title = { Text(pluginId) },
        text = {
            Text(
                text = uiState.logText.ifBlank { emptyLog },
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            FolkTextButton(onClick = actions.onDismissLog) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
private fun PluginUninstallDialog(
    uiState: PluginUiState,
    actions: PluginActions,
) {
    val plugin = uiState.confirmUninstall ?: return

    ExpressiveDialog(
        onDismissRequest = actions.onDismissUninstall,
        title = { Text(stringResource(R.string.uninstall)) },
        text = {
            Text(
                text = stringResource(R.string.plugin_uninstall_confirm).format(
                    plugin.name.ifBlank { plugin.id }
                ),
            )
        },
        confirmButton = {
            FolkTextButton(
                onClick = actions.onConfirmUninstall,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.uninstall))
            }
        },
        dismissButton = {
            FolkTextButton(onClick = actions.onDismissUninstall) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

private fun List<Plugin>.matching(query: String): List<Plugin> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this
    return filter { plugin ->
        plugin.id.contains(trimmed, ignoreCase = true) ||
            plugin.name.contains(trimmed, ignoreCase = true) ||
            plugin.description.contains(trimmed, ignoreCase = true)
    }
}
