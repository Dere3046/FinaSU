package me.weishu.kernelsu.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.weishu.kernelsu.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import me.weishu.kernelsu.ui.component.dialog.ConfirmResult
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.component.material.SegmentedSwitchItem

@Composable
internal fun FolkZygiskStatusItem(state: SettingsUiState, actions: SettingsScreenActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dialog = rememberConfirmDialog()
    val status = state.folkZygiskStatus
    val summary = when {
        state.isFolkZygiskLoading -> stringResource(R.string.folkzygisk_loading)
        state.folkZygiskReadError || status == null -> stringResource(R.string.folkzygisk_read_error)
        else -> {
            val injection = stringResource(when (status.injectionState) {
                "injected" -> R.string.folkzygisk_injected
                "partial" -> R.string.folkzygisk_partial
                "starting" -> R.string.folkzygisk_starting
                "failed" -> R.string.folkzygisk_failed
                else -> R.string.folkzygisk_unverified
            })
            val selection = stringResource(if (status.enabled) R.string.folkzygisk_next_enabled
                else R.string.folkzygisk_next_disabled)
            val blockers = status.enableBlockers.map { reason ->
                stringResource(when (reason) {
                    "license_review_required" -> R.string.folkzygisk_license_gate
                    "device_acceptance_required" -> R.string.folkzygisk_device_gate
                    "engine_missing" -> R.string.folkzygisk_engine_missing
                    "external_provider_conflict" -> R.string.folkzygisk_external_conflict
                    "startup_protection" -> R.string.folkzygisk_startup_protection
                    else -> R.string.folkzygisk_unknown_gate
                })
            }.distinct()
            buildList {
                add(stringResource(R.string.folkzygisk_current, injection))
                add(selection)
                if (status.rebootRequired == true || state.folkZygiskRebootPending) add(stringResource(R.string.folkzygisk_reboot_required))
                if (status.externalProviders.isNotEmpty()) {
                    add(stringResource(R.string.folkzygisk_external, status.externalProviders.joinToString(", ")))
                }
                if (blockers.isNotEmpty()) add(stringResource(R.string.folkzygisk_blockers, blockers.joinToString("; ")))
            }.joinToString("\n")
        }
    }
    SegmentedSwitchItem(
        icon = Icons.Filled.Code,
        title = "FolkZygisk",
        summary = summary,
        checked = status?.enabled == true,
        enabled = status != null && !state.folkZygiskReadError && !state.isFolkZygiskLoading && !state.isFolkZygiskWriting,
        onCheckedChange = { enabled ->
            if (enabled && status != null && status.externalProviders.isNotEmpty()) {
                if (!dialog.isShown) scope.launch {
                    if (dialog.awaitConfirm(
                        title = context.getString(R.string.folkzygisk_switch_title),
                        content = context.getString(R.string.folkzygisk_builtin_switch_message, status.externalProviders.joinToString(", ")),
                    ) == ConfirmResult.Confirmed) actions.onSetFolkZygiskEnabled(true, true)
                }
            } else actions.onSetFolkZygiskEnabled(enabled, false)
        },
    )
}
