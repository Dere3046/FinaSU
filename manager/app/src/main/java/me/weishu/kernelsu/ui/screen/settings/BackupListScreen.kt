package me.weishu.kernelsu.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.backup.BackupKind
import me.weishu.kernelsu.ui.component.material.ExpressiveDialog
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedListItem
import me.weishu.kernelsu.ui.component.material.TopBarBackButton
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors
import me.weishu.kernelsu.ui.component.settings.SettingsContentMaxWidth
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.viewmodel.BackupListItem
import me.weishu.kernelsu.ui.viewmodel.BackupListViewModel
import me.weishu.kernelsu.ui.viewmodel.BackupNotice
import me.weishu.kernelsu.ui.viewmodel.BackupTarget

private val ListGroupModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)

/**
 * Lists backups from the local folder and WebDAV together, grouped by kind, and offers restore and
 * delete for each entry. Entries are read from whichever backend they were listed from.
 */
@Composable
fun BackupListScreen() {
    val navigator = LocalNavigator.current
    val viewModel = viewModel<BackupListViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    var pendingDelete by remember { mutableStateOf<BackupListItem?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        val text = when (notice) {
            is BackupNotice.Restored -> resources.getString(R.string.backup_restored, notice.name)
            is BackupNotice.Deleted -> resources.getString(R.string.backup_deleted, notice.name)
            is BackupNotice.Failed -> notice.message
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        viewModel.clearNotice()
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.backup_restore_title)) },
                navigationIcon = { TopBarBackButton(onClick = { navigator.pop() }) },
                colors = expressiveTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(innerPadding)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = SettingsContentMaxWidth)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            when {
                state.loading -> SegmentedColumn(modifier = ListGroupModifier) {
                    item {
                        SegmentedListItem(headlineContent = { Text(stringResource(R.string.backup_loading)) })
                    }
                }

                state.items.isEmpty() -> SegmentedColumn(modifier = ListGroupModifier) {
                    item {
                        SegmentedListItem(headlineContent = { Text(stringResource(R.string.backup_empty_list)) })
                    }
                }

                else -> BackupKind.entries.forEach { kind ->
                    val group = state.items.filter { it.entry.kind == kind }
                    if (group.isEmpty()) return@forEach
                    SegmentedColumn(
                        modifier = ListGroupModifier,
                        title = stringResource(kindLabel(kind)),
                    ) {
                        group.forEach { item ->
                            item(key = "${item.target}:${item.entry.relativePath}") {
                                SegmentedListItem(
                                    onClick = { viewModel.restore(item) },
                                    enabled = !state.busy,
                                    headlineContent = { Text(item.entry.displayName) },
                                    supportingContent = { Text(supportingText(item)) },
                                    leadingContent = {
                                        Icon(Icons.Rounded.Restore, contentDescription = null)
                                    },
                                    trailingContent = {
                                        TextButton(
                                            onClick = { pendingDelete = item },
                                            enabled = !state.busy,
                                        ) {
                                            Icon(
                                                Icons.Rounded.Delete,
                                                contentDescription = stringResource(R.string.backup_delete),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingDelete?.let { item ->
        ExpressiveDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.backup_delete)) },
            text = { Text(stringResource(R.string.backup_delete_confirm, item.entry.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.delete(item)
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

private fun kindLabel(kind: BackupKind): Int = when (kind) {
    BackupKind.MODULE -> R.string.backup_modules
    BackupKind.ALLOWLIST -> R.string.backup_allowlist
    BackupKind.BOOT -> R.string.backup_partitions
}

/** Builds the secondary line of a row: the backend and the artifact size. */
private fun supportingText(item: BackupListItem): String {
    val target = if (item.target == BackupTarget.WEBDAV) "WebDAV" else "Local"
    return "$target · ${formatBytes(item.sizeBytes)}"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GiB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MiB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KiB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
