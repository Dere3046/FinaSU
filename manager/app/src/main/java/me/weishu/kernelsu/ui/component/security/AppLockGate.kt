package me.weishu.kernelsu.ui.component.security

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.security.AuthMethod
import me.weishu.kernelsu.data.security.SecurityTarget

val LocalAppUnlocked = staticCompositionLocalOf { false }

/** Business content starts only after authentication, then stays mounted beneath the lock. */
@Composable
fun AppLockGate(session: AuthenticationSession, onCancel: () -> Unit, content: @Composable () -> Unit) {
    val state by session.repository.state.collectAsStateWithLifecycle()
    // Must observe STOP immediately, rather than defer its emission until the next START.
    val foreground by session.foregroundState.collectAsState()
    val request by session.request.collectAsState()
    var granted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var entered by remember { mutableStateOf(false) }
    val protected = !state.loaded || state.storageError || state.config.methodFor(SecurityTarget.APP_START) != AuthMethod.NONE
    // Authentication is per app launch: returning from the background must not prompt again.
    val unlocked = state.loaded && !state.storageError && (!protected || granted)
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(unlocked) { if (!unlocked) focusManager.clearFocus(force = true) }
    DisposableEffect(context, protected, unlocked) {
        var host: Context = context
        while (host is ContextWrapper && host !is FragmentActivity) host = host.baseContext
        val window = (host as? FragmentActivity)?.window
        val originallySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) == WindowManager.LayoutParams.FLAG_SECURE
        // Only the locked screen blocks captures; an unlocked session keeps its normal behaviour.
        if (protected && !unlocked) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!originallySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(foreground, state.loaded, attempt) {
        if (!foreground || !state.loaded || unlocked) return@LaunchedEffect
        busy = true
        failed = false
        try {
            session.resetCancellation()
            if (session.unlock()) {
                granted = true
            } else if (session.userCancelled) {
                onCancel()
            } else {
                failed = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            busy = false
        }
    }
    LaunchedEffect(unlocked) { if (unlocked) entered = true }
    Box(Modifier.fillMaxSize()) {
    if (entered || unlocked) {
        // Keep in-flight flash work mounted: relocking must never restart its LaunchedEffect.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (unlocked) 1f else 0f }
            .then(if (unlocked) Modifier else Modifier.clearAndSetSemantics {})) {
            CompositionLocalProvider(LocalAppUnlocked provides unlocked) { content() }
        }
    }
    if (!unlocked) {
        BackHandler(onBack = onCancel)
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }.safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            val pending = request?.takeIf { it.fullScreen }
            val pendingKind = pending?.kind
            if (pending != null && pendingKind != null) {
                StartupCredentialScreen(
                    kind = pendingKind,
                    repository = session.repository,
                    onVerified = { pending.answer.complete(true) },
                    onCancel = { session.cancel(pending) },
                )
            } else {
                AppAuthenticationScreen(
                    state = if (pending != null || failed || state.storageError) AuthenticationVisualState.ERROR
                        else if (busy || !state.loaded) AuthenticationVisualState.BUSY else AuthenticationVisualState.BIOMETRIC,
                    error = when {
                        state.storageError -> stringResource(R.string.security_storage_error)
                        pending != null -> stringResource(R.string.security_biometric_unavailable)
                        failed -> stringResource(R.string.security_unlock_failed)
                        else -> null
                    },
                    enabled = state.loaded && foreground && (!busy || pending != null),
                    actionEnabled = true,
                    onVerify = {
                        if (pending != null) pending.answer.complete(false)
                        attempt++
                    },
                    onCancel = { if (pending != null) session.cancel(pending) else onCancel() },
                    fallbackAction = if (pending?.fallback == true) ({
                        androidx.compose.material3.TextButton(onClick = { pending.answer.complete(true) }) {
                            Text(stringResource(R.string.security_use_credential))
                        }
                    }) else null,
                )
            }
        }
    }
    }
}
