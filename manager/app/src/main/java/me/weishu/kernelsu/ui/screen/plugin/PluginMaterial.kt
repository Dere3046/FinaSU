package me.weishu.kernelsu.ui.screen.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.Plugin
import me.weishu.kernelsu.ui.component.material.ExpressiveDialog
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.ExpressiveSwitch
import me.weishu.kernelsu.ui.component.material.FolkIconButton
import me.weishu.kernelsu.ui.component.material.FolkTextButton
import me.weishu.kernelsu.ui.component.material.SearchAppBar
import me.weishu.kernelsu.ui.component.material.SnackBarHost
import me.weishu.kernelsu.ui.component.material.TonalCard

@Composable
fun PluginPagerMaterial(
    uiState: PluginUiState,
    actions: PluginActions,
    bottomInnerPadding: Dp,
) {
    val snackBarHost = remember { SnackbarHostState() }
    var searchText by rememberSaveable { mutableStateOf("") }
    val matches = remember(uiState.plugins, searchText) { uiState.plugins.matching(searchText) }

    ExpressiveScaffold(
        topBar = {
            SearchAppBar(
                title = { Text(stringResource(R.string.plugin)) },
                searchText = searchText,
                onSearchTextChange = { searchText = it },
                onClearClick = { searchText = "" },
                snackbarHostState = snackBarHost,
                actions = {
                    FolkIconButton(onClick = actions.onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                },
                largeTitle = false,
                topSearch = true,
                searchContent = { bottomPadding, _ ->
                    PluginList(
                        plugins = matches,
                        pending = uiState.pending,
                        actions = actions,
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = bottomPadding + 16.dp,
                        ),
                    )
                },
            )
        },
        snackbarHost = { SnackBarHost(hostState = snackBarHost) },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            uiState.error?.let { message ->
                item(key = "error") { PluginMessageCard(message = message, isError = true) }
            }
            if (matches.isEmpty()) {
                item(key = "empty") { PluginMessageCard(message = stringResource(R.string.plugin_empty)) }
            }
            items(matches, key = { it.id }) { plugin ->
                PluginCard(plugin = plugin, pending = plugin.id in uiState.pending, actions = actions)
            }
        }
    }

    PluginLogDialog(uiState = uiState, actions = actions)
}

private fun List<Plugin>.matching(query: String): List<Plugin> {
    val text = query.trim()
    if (text.isEmpty()) return this
    return filter { plugin ->
        plugin.id.contains(text, ignoreCase = true) ||
            plugin.name.contains(text, ignoreCase = true) ||
            plugin.description.contains(text, ignoreCase = true)
    }
}

@Composable
private fun PluginList(
    plugins: List<Plugin>,
    pending: Set<String>,
    actions: PluginActions,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(plugins, key = { it.id }) { plugin ->
            PluginCard(plugin = plugin, pending = plugin.id in pending, actions = actions)
        }
    }
}

@Composable
private fun PluginCard(
    plugin: Plugin,
    pending: Boolean,
    actions: PluginActions,
) {
    val actionable = plugin.error == null && !pending
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.name.ifBlank { plugin.id },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val subtitle = listOf(plugin.version, plugin.author)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ExpressiveSwitch(
                    checked = plugin.enabled,
                    onCheckedChange = if (actionable) {
                        { value -> actions.onToggleEnabled(plugin, value) }
                    } else {
                        null
                    },
                    enabled = actionable,
                )
            }
            if (plugin.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = plugin.description, style = MaterialTheme.typography.bodyMedium)
            }
            if (plugin.depends.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.plugin_dependencies) + ": " + plugin.depends.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            plugin.error?.let { message ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            FolkTextButton(onClick = { actions.onOpenLog(plugin) }) {
                Text(stringResource(R.string.plugin_log), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun PluginMessageCard(message: String, isError: Boolean = false) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun PluginLogDialog(uiState: PluginUiState, actions: PluginActions) {
    val plugin = uiState.logPlugin ?: return
    ExpressiveDialog(
        onDismissRequest = actions.onDismissLog,
        confirmButton = {
            FolkTextButton(onClick = actions.onDismissLog) {
                Text(stringResource(android.R.string.ok))
            }
        },
        title = { Text(plugin) },
        text = {
            val scroll = rememberScrollState()
            Text(
                text = uiState.logText.trimEnd().ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(scroll),
            )
        },
    )
}
