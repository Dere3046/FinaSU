package me.weishu.kernelsu.ui.screen.settings

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.material.SegmentedCheckboxItem
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedListItem
import me.weishu.kernelsu.ui.component.material.SegmentedSwitchItem
import me.weishu.kernelsu.ui.component.material.SegmentedTextField
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.viewmodel.BackupViewModel
import me.weishu.kernelsu.ui.viewmodel.WebDavTestState

private val BackupGroupModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)

/**
 * Settings content for the on-demand backup feature: where to write (local / WebDAV) and what to
 * include (modules, allowlist, partitions).
 */
@Composable
fun BackupSettingsContent() {
    val viewModel = viewModel<BackupViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val navigator = LocalNavigator.current

    LaunchedEffect(state.outcome) {
        val outcome = state.outcome ?: return@LaunchedEffect
        val text = resources.getString(
            R.string.backup_result,
            outcome.written,
            outcome.skipped,
            outcome.failed,
        )
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        viewModel.clearOutcome()
    }
    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        viewModel.clearError()
    }
    LaunchedEffect(state.testState) {
        when (state.testState) {
            WebDavTestState.SUCCESS -> {
                Toast.makeText(context, R.string.backup_webdav_test_success, Toast.LENGTH_SHORT).show()
                viewModel.clearTestResult()
            }
            WebDavTestState.FAILED -> {
                Toast.makeText(context, R.string.backup_webdav_test_failed, Toast.LENGTH_SHORT).show()
                viewModel.clearTestResult()
            }
            else -> Unit
        }
    }

    SegmentedColumn(modifier = BackupGroupModifier, title = stringResource(R.string.backup_section_targets)) {
        item {
            SegmentedSwitchItem(
                title = stringResource(R.string.backup_local_enabled),
                summary = stringResource(R.string.backup_local_summary),
                checked = state.localEnabled,
                onCheckedChange = viewModel::setLocalEnabled,
            )
        }
        item {
            SegmentedSwitchItem(
                title = stringResource(R.string.backup_webdav_enabled),
                checked = state.webDavEnabled,
                onCheckedChange = viewModel::setWebDavEnabled,
            )
        }
        item {
            SegmentedListItem(
                onClick = { openDownloads(context) },
                headlineContent = { Text(stringResource(R.string.backup_open_folder)) },
            )
        }
        item {
            SegmentedSwitchItem(
                title = stringResource(R.string.backup_before_flash),
                summary = stringResource(R.string.backup_before_flash_summary),
                checked = state.backupBeforeFlash,
                onCheckedChange = viewModel::setBackupBeforeFlash,
            )
        }
    }

    SegmentedColumn(modifier = BackupGroupModifier, title = stringResource(R.string.backup_section_content)) {
        item {
            SegmentedSwitchItem(
                title = stringResource(R.string.backup_modules),
                summary = stringResource(R.string.backup_modules_summary),
                checked = state.modulesEnabled,
                onCheckedChange = viewModel::setModulesEnabled,
            )
        }
        item {
            SegmentedSwitchItem(
                title = stringResource(R.string.backup_allowlist),
                checked = state.allowlistEnabled,
                onCheckedChange = viewModel::setAllowlistEnabled,
            )
        }
        item(visible = state.availablePartitions.isEmpty()) {
            SegmentedListItem(
                headlineContent = { Text(stringResource(R.string.backup_no_partitions)) },
            )
        }
        state.availablePartitions.forEach { partition ->
            item(key = partition) {
                SegmentedCheckboxItem(
                    title = partition,
                    checked = partition in state.selectedPartitions,
                    onCheckedChange = { viewModel.setPartitionEnabled(partition, it) },
                )
            }
        }
        item {
            SegmentedListItem(
                onClick = viewModel::backupNow,
                enabled = state.canBackup && !state.busy,
                headlineContent = {
                    Text(
                        stringResource(
                            if (state.busy) R.string.backup_running else R.string.backup_now
                        )
                    )
                },
            )
        }
        item {
            SegmentedListItem(
                onClick = { navigator.push(Route.BackupList) },
                headlineContent = { Text(stringResource(R.string.backup_open_list)) },
            )
        }
    }

    if (state.webDavEnabled) {
        WebDavSettingsSection(viewModel, state.webDavUrl, state.webDavUsername, state.webDavPath, state.webDavPasswordSet)
    }
}

@Composable
private fun WebDavSettingsSection(
    viewModel: BackupViewModel,
    initialUrl: String,
    initialUsername: String,
    initialPath: String,
    passwordSet: Boolean,
) {
    val navigator = LocalNavigator.current
    var url by remember { mutableStateOf(initialUrl) }
    var username by remember { mutableStateOf(initialUsername) }
    var path by remember { mutableStateOf(initialPath) }
    var password by remember { mutableStateOf("") }

    SegmentedColumn(modifier = BackupGroupModifier, title = stringResource(R.string.backup_section_webdav)) {
        item {
            SegmentedTextField(
                value = url,
                onValueChange = { url = it; viewModel.setWebDavUrl(it) },
                label = stringResource(R.string.backup_webdav_url),
                singleLine = true,
            )
        }
        item {
            SegmentedTextField(
                value = username,
                onValueChange = { username = it; viewModel.setWebDavUsername(it) },
                label = stringResource(R.string.backup_webdav_username),
                singleLine = true,
            )
        }
        item {
            SegmentedTextField(
                value = password,
                onValueChange = { password = it; viewModel.setWebDavPassword(it) },
                label = stringResource(R.string.backup_webdav_password),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingContent = if (passwordSet && password.isEmpty()) {
                    { Text(stringResource(R.string.backup_webdav_password_set)) }
                } else null,
            )
        }
        item {
            SegmentedTextField(
                value = path,
                onValueChange = { path = it; viewModel.setWebDavPath(it) },
                label = stringResource(R.string.backup_webdav_path),
                singleLine = true,
            )
        }
        item {
            SegmentedListItem(
                onClick = viewModel::testWebDav,
                enabled = url.isNotBlank(),
                headlineContent = { Text(stringResource(R.string.backup_webdav_test)) },
            )
        }
        item {
            SegmentedListItem(
                onClick = { navigator.push(Route.WebDavBrowser()) },
                enabled = url.isNotBlank(),
                headlineContent = { Text(stringResource(R.string.backup_webdav_browse)) },
            )
        }
    }
}

private fun openDownloads(context: Context) {
    val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
