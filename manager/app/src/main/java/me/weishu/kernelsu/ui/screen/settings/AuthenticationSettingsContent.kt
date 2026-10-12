package me.weishu.kernelsu.ui.screen.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Password
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.security.AuthMethod
import me.weishu.kernelsu.data.security.CredentialKind
import me.weishu.kernelsu.data.security.CredentialStatus
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedDropdownItem
import me.weishu.kernelsu.ui.component.material.SegmentedListItem
import me.weishu.kernelsu.ui.component.security.BiometricAuthenticator
import me.weishu.kernelsu.ui.component.security.CredentialRemovalDialog
import me.weishu.kernelsu.ui.component.security.CredentialSetupDialog
import me.weishu.kernelsu.ui.component.security.LocalAppAuthenticator

/** App authentication settings, kept separate from kernel security controls. */
@Composable
fun AuthenticationSettingsContent() {
    val repository = ksuApp.securityRepository
    val state by repository.state.collectAsStateWithLifecycle()
    val authenticator = LocalAppAuthenticator.current
    val scope = rememberCoroutineScope()
    var setup by remember { mutableStateOf<CredentialKind?>(null) }
    var removal by remember { mutableStateOf<CredentialKind?>(null) }
    var writing by remember { mutableStateOf(false) }
    var changeFailed by remember { mutableStateOf(false) }
    LaunchedEffect(repository) { repository.initialize() }

    val editable = state.loaded && !state.storageError && !writing
    // A fingerprint option only exists where the hardware and enrolment do.
    val biometricSupported = remember { BiometricAuthenticator.isSupported(ksuApp) }
    val methods = remember(biometricSupported) {
        AuthMethod.entries.filter { it != AuthMethod.BIOMETRIC || biometricSupported }
    }
    val methodLabels = methods.map { method ->
        stringResource(
            when (method) {
                AuthMethod.NONE -> R.string.security_method_none
                AuthMethod.BIOMETRIC -> R.string.security_method_biometric
                AuthMethod.PIN -> R.string.security_method_pin
                AuthMethod.PATTERN -> R.string.security_method_pattern
            },
        )
    }
    val pinReady = state.pinStatus == CredentialStatus.READY
    val patternReady = state.patternStatus == CredentialStatus.READY

    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        content = buildList {
            add {
                SegmentedDropdownItem(
                    icon = Icons.Filled.Fingerprint,
                    title = stringResource(R.string.security_default_method),
                    summary = stringResource(
                        if (changeFailed) R.string.security_method_change_failed
                        else R.string.security_default_method_summary
                    ),
                    items = methodLabels,
                    selectedIndex = methods.indexOf(state.config.defaultMethod).coerceAtLeast(0),
                    enabled = editable,
                    onItemSelected = { index ->
                        if (!writing) {
                            writing = true
                            changeFailed = false
                            scope.launch {
                                try {
                                    changeFailed = !authenticator.changeDefault(methods[index])
                                } finally {
                                    writing = false
                                }
                            }
                        }
                    },
                )
            }
            add {
                SegmentedListItem(
                    onClick = { if (editable) setup = CredentialKind.PIN },
                    enabled = editable,
                    headlineContent = {
                        Text(stringResource(if (pinReady) R.string.security_pin_change else R.string.security_pin_set))
                    },
                    supportingContent = {
                        Text(stringResource(if (pinReady) R.string.security_pin_configured else R.string.security_pin_absent))
                    },
                    leadingContent = { Icon(Icons.Filled.Password, null) },
                )
            }
            add {
                SegmentedListItem(
                    onClick = { if (editable) setup = CredentialKind.PATTERN },
                    enabled = editable,
                    headlineContent = {
                        Text(stringResource(if (patternReady) R.string.security_pattern_change else R.string.security_pattern_set))
                    },
                    supportingContent = {
                        Text(stringResource(if (patternReady) R.string.security_pattern_configured else R.string.security_pattern_absent))
                    },
                    leadingContent = { Icon(Icons.Filled.Gesture, null) },
                )
            }
            if (pinReady) add {
                SegmentedListItem(
                    onClick = { if (editable) removal = CredentialKind.PIN },
                    enabled = editable,
                    headlineContent = { Text(stringResource(R.string.security_pin_clear)) },
                )
            }
            if (patternReady) add {
                SegmentedListItem(
                    onClick = { if (editable) removal = CredentialKind.PATTERN },
                    enabled = editable,
                    headlineContent = { Text(stringResource(R.string.security_pattern_clear)) },
                )
            }
        },
    )

    setup?.let { kind ->
        CredentialSetupDialog(
            kind = kind,
            repository = repository,
            changing = state.statusOf(kind) == CredentialStatus.READY,
            onDismiss = { setup = null },
            onDone = { setup = null },
        )
    }
    removal?.let { kind ->
        CredentialRemovalDialog(
            kind = kind,
            repository = repository,
            onDismiss = { removal = null },
            onDone = { removal = null },
        )
    }
}
