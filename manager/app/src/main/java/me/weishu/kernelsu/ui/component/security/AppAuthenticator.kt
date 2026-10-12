package me.weishu.kernelsu.ui.component.security

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.weishu.kernelsu.data.security.AuthMethod
import me.weishu.kernelsu.data.security.CredentialStatus
import me.weishu.kernelsu.data.security.SecurityConfig
import me.weishu.kernelsu.data.security.SecurityRepository
import me.weishu.kernelsu.data.security.SecurityState
import me.weishu.kernelsu.data.security.SecurityTarget

interface AppAuthenticator {
    fun isProtected(target: SecurityTarget): Boolean
    suspend fun require(target: SecurityTarget, fresh: Boolean = false): Boolean
    suspend fun changeDefault(method: AuthMethod): Boolean
    fun invalidate()
}

val LocalAppAuthenticator = staticCompositionLocalOf<AppAuthenticator> {
    error("Authentication host not provided")
}

/** Platform-free policy coordinator, owned and called on the host's main dispatcher. */
class AppAuthenticatorImpl(
    private val repository: SecurityRepository,
    private val elapsedRealtime: () -> Long,
    private val isForeground: () -> Boolean,
    private val biometricEnrolled: () -> Boolean = { true },
    private val authenticate: suspend (AuthMethod, Boolean) -> Boolean,
) : AppAuthenticator {
    private val mutex = Mutex()
    private val verifiedAt = mutableMapOf<SecurityTarget, Long>()
    private var revision = -1L
    private var generation = 0L

    override fun invalidate() {
        generation++
        verifiedAt.clear()
    }

    override fun isProtected(target: SecurityTarget): Boolean {
        val state = repository.state.value
        return !state.loaded || state.storageError || state.config.methodFor(target) != AuthMethod.NONE
    }

    override suspend fun require(target: SecurityTarget, fresh: Boolean): Boolean = mutex.withLock {
        repository.initialize()
        syncRevision()
        val state = repository.state.value
        if (state.storageError || !isForeground()) return@withLock false
        val method = state.config.methodFor(target)
        if (method == AuthMethod.NONE) return@withLock true
        val now = elapsedRealtime()
        val cached = verifiedAt[target]
        if (!fresh && cached != null && now >= cached && now - cached < CACHE_MILLIS) return@withLock true
        // A failed fresh attempt must not fall back onto an older grant for the same target.
        verifiedAt.remove(target)
        val token = generation
        val success = authenticate(method, fallbackGranted(state))
        if (!success || !unchanged(token, state.revision)) return@withLock false
        verifiedAt[target] = elapsedRealtime()
        true
    }

    /** Verifies the current policy once; a new method is checked for enrollment, never re-prompted. */
    override suspend fun changeDefault(method: AuthMethod): Boolean = mutex.withLock {
        repository.initialize()
        syncRevision()
        val state = repository.state.value
        if (state.storageError || !isForeground()) return@withLock false
        if (method == state.config.defaultMethod) return@withLock true
        if (!enrollable(method, state)) return@withLock false
        val token = generation
        val current = state.config.methodFor(SecurityTarget.SETTINGS).takeUnless { it == AuthMethod.NONE }
            ?: state.config.defaultMethod
        if (current != AuthMethod.NONE && !authenticate(current, fallbackGranted(state))) return@withLock false
        if (!unchanged(token, state.revision)) return@withLock false
        val saved = repository.updateConfig(
            SecurityConfig.create(method, state.config.targetOverrides, state.config.allowCredentialFallback)
        )
        invalidate()
        saved
    }

    /** A fallback is offered only while it is enabled and some credential can actually answer it. */
    private fun fallbackGranted(state: SecurityState) =
        state.config.allowCredentialFallback && state.hasAnyCredential()

    private fun enrollable(method: AuthMethod, state: SecurityState): Boolean {
        val kind = method.credential
        return when {
            kind != null -> state.statusOf(kind) == CredentialStatus.READY
            method == AuthMethod.BIOMETRIC -> biometricEnrolled()
            else -> true
        }
    }

    private fun unchanged(token: Long, expectedRevision: Long) = isForeground() &&
        token == generation && repository.state.value.revision == expectedRevision &&
        !repository.state.value.storageError

    private fun syncRevision() {
        if (revision != repository.state.value.revision) {
            invalidate()
            revision = repository.state.value.revision
        }
    }

    companion object {
        const val CACHE_MILLIS = 30_000L
    }
}
