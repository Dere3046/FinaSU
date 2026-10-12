package me.weishu.kernelsu.ui.component.security

import android.os.SystemClock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.security.AuthMethod
import me.weishu.kernelsu.data.security.CredentialKind
import me.weishu.kernelsu.data.security.CredentialStatus
import me.weishu.kernelsu.data.security.SecurityRepository
import me.weishu.kernelsu.data.security.SecurityTarget
import me.weishu.kernelsu.ui.component.material.ExpressiveDialog
import me.weishu.kernelsu.ui.component.material.FolkTextButton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private object StartupAuthentication : AbstractCoroutineContextElement(StartupAuthenticationKey)
private object StartupAuthenticationKey : CoroutineContext.Key<StartupAuthentication>

/** Activity-owned requests are never restored across recreation or kept while backgrounded. */
class AuthenticationSession(
    private val activity: FragmentActivity,
    val repository: SecurityRepository,
) {
    /** [kind] null means "this method is unavailable": the host shows an explanation first. */
    data class Request(
        val kind: CredentialKind?,
        val fallback: Boolean,
        val fullScreen: Boolean = false,
        val answer: CompletableDeferred<Boolean>,
    )

    private val mutableRequest = MutableStateFlow<Request?>(null)
    val request = mutableRequest.asStateFlow()
    private val mutableForeground = MutableStateFlow(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    val foregroundState = mutableForeground.asStateFlow()
    private val mutableStopGeneration = MutableStateFlow(0L)
    val stopGeneration = mutableStopGeneration.asStateFlow()
    private var activeJob: Job? = null
    private val biometric = BiometricAuthenticator(activity)
    var userCancelled: Boolean = false
        private set

    val authenticator: AppAuthenticator = AppAuthenticatorImpl(
        repository,
        SystemClock::elapsedRealtime,
        { mutableForeground.value },
        { BiometricAuthenticator.isAvailable(activity) },
        ::authenticate,
    )

    fun started() {
        mutableForeground.value = true
    }

    fun stopped() {
        mutableForeground.value = false
        mutableStopGeneration.value++
        authenticator.invalidate()
        mutableRequest.value?.answer?.complete(false)
        mutableRequest.value = null
        activeJob?.cancel()
    }

    fun resetCancellation() {
        userCancelled = false
    }

    fun cancel(request: Request) {
        if (mutableForeground.value) userCancelled = true
        request.answer.complete(false)
    }

    suspend fun unlock(): Boolean = withContext(StartupAuthentication) {
        authenticator.require(SecurityTarget.APP_START, fresh = true)
    }

    private suspend fun authenticate(method: AuthMethod, fallback: Boolean): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (!mutableForeground.value) return@withContext false
            userCancelled = false
            activeJob = currentCoroutineContext()[Job]
            try {
                when (method) {
                    AuthMethod.NONE -> true
                    AuthMethod.PIN -> ask(CredentialKind.PIN)
                    AuthMethod.PATTERN -> ask(CredentialKind.PATTERN)
                    AuthMethod.BIOMETRIC -> when (val result = biometric.authenticate(fallback)) {
                        BiometricResult.Success -> true
                        BiometricResult.Cancelled -> {
                            userCancelled = true
                            false
                        }

                        BiometricResult.CredentialRequested ->
                            availableCredential()?.let { ask(it) } ?: explainUnavailable(fallback)

                        is BiometricResult.Error -> explainUnavailable(fallback)
                    }
                }
            } finally {
                activeJob = null
            }
        }

    /** Explains an unusable biometric, then optionally falls through to a credential. */
    private suspend fun explainUnavailable(fallback: Boolean): Boolean {
        if (!ask(kind = null, fallback = fallback)) return false
        if (!fallback) return false
        return availableCredential()?.let { ask(it) } ?: false
    }

    private fun availableCredential(): CredentialKind? {
        val state = repository.state.value
        return CredentialKind.entries.firstOrNull { state.statusOf(it) == CredentialStatus.READY }
    }

    private suspend fun ask(kind: CredentialKind?, fallback: Boolean = false): Boolean {
        if (!mutableForeground.value) return false
        val pending = Request(kind, fallback, currentCoroutineContext()[StartupAuthenticationKey] != null, CompletableDeferred())
        mutableRequest.value = pending
        return try {
            pending.answer.await()
        } finally {
            if (mutableRequest.value === pending) mutableRequest.value = null
        }
    }
}

@Composable
fun rememberAuthenticationSession(activity: FragmentActivity, repository: SecurityRepository): AuthenticationSession {
    val session = remember(activity, repository) { AuthenticationSession(activity, repository) }
    DisposableEffect(activity, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> session.started()
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> session.stopped()
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            session.stopped()
        }
    }
    LaunchedEffect(session) {
        repository.initialize()
        repository.state.collect { session.authenticator.invalidate() }
    }
    return session
}

/** Non-startup prompts only; the startup lock renders its own full-screen surface. */
@Composable
fun AuthenticationHost(session: AuthenticationSession) {
    val request by session.request.collectAsStateWithLifecycle()
    val pending = request?.takeUnless { it.fullScreen } ?: return
    val kind = pending.kind
    key(pending) {
        if (kind != null) {
            CredentialEntryDialog(
                kind = kind,
                repository = session.repository,
                onDismiss = { session.cancel(pending) },
                onVerified = { pending.answer.complete(true) },
            )
        } else {
            ExpressiveDialog(
                onDismissRequest = { session.cancel(pending) },
                title = { Text(stringResource(R.string.security_biometric_title)) },
                text = { Text(stringResource(R.string.security_biometric_unavailable)) },
                confirmButton = {
                    FolkTextButton(onClick = { pending.answer.complete(pending.fallback) }) {
                        Text(
                            stringResource(
                                if (pending.fallback) R.string.security_use_credential else android.R.string.ok
                            )
                        )
                    }
                },
                dismissButton = if (pending.fallback) ({
                    FolkTextButton(onClick = { session.cancel(pending) }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }) else null,
            )
        }
    }
}
