package me.weishu.kernelsu.ui.component.rebootlistpopup

import android.content.Context
import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.security.SecurityTarget
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.component.security.LocalAppAuthenticator
import me.weishu.kernelsu.ui.util.reboot

data class RebootListOption(
    @param:StringRes val labelRes: Int,
    val reason: String,
)

@Composable
fun getRebootListOption(): List<RebootListOption> {
    val pm = LocalContext.current.getSystemService(Context.POWER_SERVICE) as PowerManager?

    @Suppress("DEPRECATION")
    val isRebootingUserspaceSupported = pm?.isRebootingUserspaceSupported == true

    return buildList {
        add(RebootListOption(R.string.reboot, ""))
        if (isRebootingUserspaceSupported) {
            add(RebootListOption(R.string.reboot_userspace, "userspace"))
        }
        add(RebootListOption(R.string.reboot_soft, "soft_reboot"))
        add(RebootListOption(R.string.reboot_recovery, "recovery"))
        add(RebootListOption(R.string.reboot_bootloader, "bootloader"))
        add(RebootListOption(R.string.reboot_download, "download"))
        add(RebootListOption(R.string.reboot_edl, "edl"))
    }
}

/** Reboots on selection, but confirms first in jailbreak mode where a plain reboot drops root. */
@Composable
fun rememberRebootAction(): (String) -> Unit {
    val title = stringResource(R.string.reboot)
    val message = stringResource(R.string.jailbreak_reboot_warning)
    val scope = rememberCoroutineScope()
    val authenticator = LocalAppAuthenticator.current
    val confirmDialog = rememberConfirmDialog(onConfirm = {
        scope.launch {
            if (authenticator.require(SecurityTarget.REBOOT)) reboot()
        }
    })

    return remember(title, message, confirmDialog, authenticator) {
        { reason ->
            if (Natives.isLateLoadMode && reason.isEmpty()) {
                confirmDialog.showConfirm(title = title, content = message)
            } else {
                scope.launch {
                    if (authenticator.require(SecurityTarget.REBOOT)) reboot(reason)
                }
            }
        }
    }
}

@Composable
fun RebootListPopup() {
    RebootListPopupMaterial()
}
