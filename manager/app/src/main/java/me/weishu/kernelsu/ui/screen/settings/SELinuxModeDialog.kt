package me.weishu.kernelsu.ui.screen.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.ConfirmResult
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.component.material.ExpressiveDialog
import me.weishu.kernelsu.ui.component.material.FolkTextButton
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedRadioItem

/**
 * Picks the SELinux mode, then confirms it: permissive weakens the whole device and back to
 * enforcing can break apps that rely on permissive. OK stays disabled while the selection equals
 * the current mode, so the dialog cannot issue a no-op write.
 */
@Composable
internal fun SELinuxModeDialog(
    currentMode: String,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    val resources = LocalResources.current
    val confirmDialog = rememberConfirmDialog()
    val scope = rememberCoroutineScope()
    val currentEnforcing = currentMode == "Enforcing"
    var selectedEnforcing by remember(currentMode) { mutableStateOf(currentEnforcing) }

    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_selinux_mode)) },
        text = {
            SegmentedColumn(
                content = listOf(
                    {
                        SegmentedRadioItem(
                            title = stringResource(R.string.settings_selinux_mode_enforcing),
                            selected = selectedEnforcing,
                            onClick = { selectedEnforcing = true },
                        )
                    },
                    {
                        SegmentedRadioItem(
                            title = stringResource(R.string.settings_selinux_mode_permissive),
                            selected = !selectedEnforcing,
                            onClick = { selectedEnforcing = false },
                        )
                    },
                )
            )
        },
        confirmButton = {
            FolkTextButton(
                enabled = selectedEnforcing != currentEnforcing,
                onClick = {
                    val message = resources.getString(
                        if (selectedEnforcing) R.string.msg_selinux_enforcing_confirm
                        else R.string.msg_selinux_permissive_warning
                    )
                    scope.launch {
                        val result = confirmDialog.awaitConfirm(
                            title = resources.getString(R.string.settings_selinux_mode),
                            content = message,
                        )
                        if (result == ConfirmResult.Confirmed) {
                            onConfirm(selectedEnforcing)
                            onDismiss()
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            FolkTextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
