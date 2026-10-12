package me.weishu.kernelsu.ui.component.security

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.security.CredentialKind
import me.weishu.kernelsu.data.security.CredentialResult
import me.weishu.kernelsu.data.security.Credentials
import me.weishu.kernelsu.data.security.SecurityRepository

/** Both sides are capped so the prompt stays a compact card instead of a full-height sheet. */
private val DialogMaxWidth = 360.dp
private val PatternMaxWidth = 200.dp

/** Every credential dialog shares one secure, screen-sized surface; a credential is never stored. */
@Composable
private fun CredentialDialogFrame(content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnClickOutside = false,
            securePolicy = SecureFlagPolicy.SecureOn,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f).widthIn(max = DialogMaxWidth),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp).imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) { content() }
        }
    }
}

@Composable
private fun CredentialHeader(title: String, subtitle: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CredentialInput(
    kind: CredentialKind,
    pin: String,
    nodes: List<Int>,
    enabled: Boolean,
    onPin: (String) -> Unit,
    onNodes: (List<Int>) -> Unit,
    onComplete: () -> Unit,
) {
    when (kind) {
        CredentialKind.PIN -> PinPad(
            value = pin,
            length = Credentials.PIN_LENGTH_LONG,
            enabled = enabled,
            isError = false,
            onValueChange = onPin,
            onComplete = onComplete,
        )

        // The gesture reports its final nodes here so the caller never reads a stale list.
        CredentialKind.PATTERN -> PatternGrid(
            nodes = nodes,
            enabled = enabled,
            isError = false,
            modifier = Modifier.widthIn(max = PatternMaxWidth),
            onNodesChange = onNodes,
            onComplete = { final -> onNodes(final); onComplete() },
        )
    }
}

@Composable
private fun CredentialPrompt(
    title: String,
    subtitle: String,
    confirmLabel: String,
    confirmEnabled: Boolean,
    error: String?,
    input: @Composable () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    CredentialDialogFrame {
        CredentialHeader(title, subtitle)
        input()
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled,
                modifier = Modifier.weight(1f).height(48.dp),
                shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
            ) { Text(confirmLabel) }
        }
    }
}

@StringRes
private fun subtitleOf(kind: CredentialKind): Int = when (kind) {
    CredentialKind.PIN -> R.string.security_pin_subtitle
    CredentialKind.PATTERN -> R.string.security_pattern_subtitle
}

private fun secretOfPin(pin: String): CharArray? = pin.toCharArray().takeIf { Credentials.isValidPin(it) }

private fun secretOfPattern(nodes: List<Int>): CharArray? = Credentials.patternSecret(nodes)

private fun secretOf(kind: CredentialKind, pin: String, nodes: List<Int>): CharArray? = when (kind) {
    CredentialKind.PIN -> secretOfPin(pin)
    CredentialKind.PATTERN -> secretOfPattern(nodes)
}

private fun sameSecret(first: CharArray, second: CharArray) = first.contentEquals(second)

/** Non-startup credential prompt: verifies once and stays a compact dialog. */
@Composable
fun CredentialEntryDialog(
    kind: CredentialKind,
    repository: SecurityRepository,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var nodes by remember { mutableStateOf(emptyList<Int>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<CredentialResult?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        val secret = secretOf(kind, pin, nodes) ?: return
        busy = true
        error = null
        scope.launch {
            val outcome = try {
                repository.verify(kind, secret)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CredentialResult.StorageError
            } finally {
                secret.fill('\u0000')
            }
            busy = false
            if (outcome == CredentialResult.Success) {
                pin = ""
                nodes = emptyList()
                onVerified()
            } else {
                error = outcome
                pin = ""
                nodes = emptyList()
            }
        }
    }

    CredentialPrompt(
        title = stringResource(R.string.security_unlock_title),
        subtitle = stringResource(subtitleOf(kind)),
        confirmLabel = stringResource(R.string.security_unlock_action),
        confirmEnabled = !busy && secretOf(kind, pin, nodes) != null,
        error = error?.let { credentialErrorText(it) },
        input = {
            CredentialInput(kind, pin, nodes, !busy, { pin = it; error = null }, { nodes = it; error = null }, onComplete = { submit() })
        },
        onCancel = onDismiss,
        onConfirm = { submit() },
    )
}

/** Set a new credential, or change one, always re-entering it so a typo cannot lock the user out. */
@Composable
fun CredentialSetupDialog(
    kind: CredentialKind,
    repository: SecurityRepository,
    changing: Boolean,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    var stage by remember { mutableStateOf(if (changing) Stage.VerifyOld else Stage.New) }
    var pin by remember { mutableStateOf("") }
    var nodes by remember { mutableStateOf(emptyList<Int>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<CredentialResult?>(null) }
    val scope = rememberCoroutineScope()
    val stashed = remember { arrayOfNulls<CharArray>(1) }
    val pending = remember { arrayOfNulls<CharArray>(1) }
    DisposableEffect(Unit) {
        onDispose {
            stashed[0]?.fill('\u0000')
            pending[0]?.fill('\u0000')
        }
    }

    fun advance() {
        if (busy) return
        val secret = secretOf(kind, pin, nodes) ?: return
        when (stage) {
            Stage.VerifyOld -> {
                busy = true
                scope.launch {
                    var retained = false
                    try {
                        val outcome = try {
                            repository.verify(kind, secret)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            CredentialResult.StorageError
                        }
                        if (outcome == CredentialResult.Success) {
                            stashed[0] = secret
                            retained = true
                            pin = ""
                            nodes = emptyList()
                            error = null
                            stage = Stage.New
                        } else {
                            error = outcome
                        }
                    } finally {
                        if (!retained) secret.fill('\u0000')
                        busy = false
                    }
                }
            }

            Stage.New -> {
                pending[0]?.fill('\u0000')
                pending[0] = secret
                pin = ""
                nodes = emptyList()
                error = null
                stage = Stage.Confirm
            }

            Stage.Confirm -> {
                val first = pending[0]
                if (first == null || !sameSecret(first, secret)) {
                    secret.fill('\u0000')
                    error = CredentialResult.Incorrect
                    return
                }
                busy = true
                scope.launch {
                    val outcome = try {
                        if (changing) repository.change(kind, stashed[0] ?: secret, secret) else repository.set(kind, secret)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        CredentialResult.StorageError
                    } finally {
                        secret.fill('\u0000')
                        pending[0]?.fill('\u0000')
                        pending[0] = null
                        busy = false
                    }
                    if (outcome == CredentialResult.Success) {
                        onDone()
                    } else {
                        error = outcome
                        stage = Stage.New
                        pin = ""
                        nodes = emptyList()
                    }
                }
            }
        }
    }

    val titleRes = when (stage) {
        Stage.VerifyOld -> R.string.security_credential_verify_title
        Stage.New -> R.string.security_credential_new_title
        Stage.Confirm -> R.string.security_credential_confirm_title
    }

    CredentialPrompt(
        title = stringResource(titleRes),
        subtitle = stringResource(subtitleOf(kind)),
        confirmLabel = stringResource(
            if (stage == Stage.Confirm) R.string.security_credential_save else R.string.security_credential_next,
        ),
        confirmEnabled = !busy && secretOf(kind, pin, nodes) != null,
        error = error?.let { credentialErrorText(it) },
        input = {
            CredentialInput(kind, pin, nodes, !busy, { pin = it; error = null }, { nodes = it; error = null }, onComplete = { advance() })
        },
        onCancel = onDismiss,
        onConfirm = { advance() },
    )
}

/** Removal verifies the credential first, so a cleared credential is never an accidental lockout. */
@Composable
fun CredentialRemovalDialog(
    kind: CredentialKind,
    repository: SecurityRepository,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var nodes by remember { mutableStateOf(emptyList<Int>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<CredentialResult?>(null) }
    val scope = rememberCoroutineScope()

    fun remove() {
        if (busy) return
        val secret = secretOf(kind, pin, nodes) ?: return
        busy = true
        error = null
        scope.launch {
            val outcome = try {
                repository.clear(kind, secret)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CredentialResult.StorageError
            } finally {
                secret.fill('\u0000')
            }
            busy = false
            if (outcome == CredentialResult.Success) {
                onDone()
            } else {
                error = outcome
                pin = ""
                nodes = emptyList()
            }
        }
    }

    CredentialPrompt(
        title = stringResource(R.string.security_credential_remove_title),
        subtitle = stringResource(
            when (kind) {
                CredentialKind.PIN -> R.string.security_pin_clear
                CredentialKind.PATTERN -> R.string.security_pattern_clear
            },
        ),
        confirmLabel = stringResource(R.string.security_credential_remove_confirm),
        confirmEnabled = !busy && secretOf(kind, pin, nodes) != null,
        error = error?.let { credentialErrorText(it) },
        input = {
            CredentialInput(kind, pin, nodes, !busy, { pin = it; error = null }, { nodes = it; error = null }, onComplete = { remove() })
        },
        onCancel = onDismiss,
        onConfirm = { remove() },
    )
}

/** Maps a failed credential result to its message, so no composable builds text from a repository type. */
@Composable
private fun credentialErrorText(result: CredentialResult): String = when (result) {
    is CredentialResult.RetryAfter -> stringResource(R.string.security_password_retry, (result.millis + 999) / 1000)
    CredentialResult.StorageError -> stringResource(R.string.security_storage_error)
    CredentialResult.InvalidFormat -> stringResource(R.string.security_pin_subtitle)
    CredentialResult.Unavailable, CredentialResult.AlreadySet -> stringResource(R.string.security_password_unavailable)
    CredentialResult.Incorrect, CredentialResult.Success -> stringResource(R.string.security_password_incorrect)
}

private enum class Stage { VerifyOld, New, Confirm }
