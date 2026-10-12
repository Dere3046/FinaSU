package me.weishu.kernelsu.ui.component.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.weishu.kernelsu.data.security.AuthMethod
import me.weishu.kernelsu.data.security.CredentialKind
import me.weishu.kernelsu.data.security.CredentialResult
import me.weishu.kernelsu.data.security.CredentialStatus
import me.weishu.kernelsu.data.security.SecurityConfig
import me.weishu.kernelsu.data.security.SecurityRepository
import me.weishu.kernelsu.data.security.SecurityState
import me.weishu.kernelsu.data.security.SecurityTarget
import org.junit.Assert.*
import org.junit.Test

class AppAuthenticatorTest {
    private class Repository(state: SecurityState) : SecurityRepository {
        override val state = MutableStateFlow(state)
        override suspend fun initialize() = Unit
        override suspend fun updateConfig(config: SecurityConfig): Boolean {
            state.value = state.value.copy(config = config, revision = state.value.revision + 1)
            return true
        }
        override suspend fun verify(kind: CredentialKind, secret: CharArray) = CredentialResult.Success
        override suspend fun set(kind: CredentialKind, secret: CharArray) = CredentialResult.Success
        override suspend fun change(kind: CredentialKind, oldSecret: CharArray, newSecret: CharArray) =
            CredentialResult.Success
        override suspend fun clear(kind: CredentialKind, secret: CharArray) = CredentialResult.Success
    }

    private fun repository(
        config: SecurityConfig = SecurityConfig.create(AuthMethod.PIN),
        pinStatus: CredentialStatus = CredentialStatus.READY,
    ) = Repository(SecurityState(loaded = true, config = config, pinStatus = pinStatus))

    @Test fun cacheIsTargetScopedExpiresAndFreshIgnoresIt() = runBlocking {
        var now = 0L
        var calls = 0
        val auth = AppAuthenticatorImpl(repository(), { now }, { true }) { _, _ -> calls++; true }
        assertTrue(auth.require(SecurityTarget.APP_START))
        assertTrue(auth.require(SecurityTarget.APP_START))
        assertEquals(1, calls)
        assertTrue(auth.require(SecurityTarget.MODULE_INSTALL))
        assertEquals(2, calls)
        assertTrue(auth.require(SecurityTarget.APP_START, fresh = true))
        assertEquals(3, calls)
        now = 30_000L
        assertTrue(auth.require(SecurityTarget.APP_START))
        assertEquals(4, calls)
        auth.invalidate()
        assertTrue(auth.require(SecurityTarget.APP_START))
        assertEquals(5, calls)
    }

    @Test fun failureBackgroundAndStorageErrorNeverGrantAccess() = runBlocking {
        val repo = repository()
        var foreground = true
        var accepted = false
        val auth = AppAuthenticatorImpl(repo, { 0 }, { foreground }) { _, _ -> accepted }
        assertFalse(auth.require(SecurityTarget.APP_START))
        accepted = true
        assertTrue(auth.require(SecurityTarget.APP_START))
        foreground = false
        assertFalse(auth.require(SecurityTarget.APP_START, fresh = true))
        foreground = true
        repo.state.value = repo.state.value.copy(storageError = true, revision = 1)
        assertFalse(auth.require(SecurityTarget.APP_START, fresh = true))
        assertTrue(auth.isProtected(SecurityTarget.APP_START))
    }

    @Test fun invalidationDuringPromptRejectsSuccess() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Boolean>()
        val auth = AppAuthenticatorImpl(repository(), { 0 }, { true }) { _, _ ->
            started.complete(Unit)
            answer.await()
        }
        val pending = async { auth.require(SecurityTarget.APP_START) }
        started.await()
        auth.invalidate()
        answer.complete(true)
        assertFalse(pending.await())
    }

    @Test fun policyChangeAndTargetSwitchesNeverPrompt() = runBlocking {
        val repo = repository()
        var calls = 0
        val auth = AppAuthenticatorImpl(repo, { 0 }, { true }) { _, _ -> calls++; true }
        assertTrue(auth.require(SecurityTarget.THEME_IMPORT))
        assertEquals(1, calls)
        assertTrue(auth.changeDefault(AuthMethod.BIOMETRIC))
        assertEquals(AuthMethod.BIOMETRIC, repo.state.value.config.defaultMethod)
        assertTrue(auth.setTargetProtected(SecurityTarget.MODULE_INSTALL, true))
        assertEquals(AuthMethod.BIOMETRIC, repo.state.value.config.methodFor(SecurityTarget.MODULE_INSTALL))
        assertTrue(auth.setTargetProtected(SecurityTarget.MODULE_INSTALL, false))
        assertEquals(AuthMethod.NONE, repo.state.value.config.methodFor(SecurityTarget.MODULE_INSTALL))
        assertTrue(auth.changeDefault(AuthMethod.NONE))
        assertEquals(AuthMethod.NONE, repo.state.value.config.defaultMethod)
        assertEquals(1, calls)
    }

    @Test fun targetSwitchNeedsAGlobalMethodAndACredential() = runBlocking {
        val none = repository(SecurityConfig.create())
        val auth = AppAuthenticatorImpl(none, { 0 }, { true }) { _, _ -> true }
        assertFalse(auth.setTargetProtected(SecurityTarget.MODULE_INSTALL, true))
        assertTrue(auth.setTargetProtected(SecurityTarget.MODULE_INSTALL, false))
        val noPin = repository(SecurityConfig.create(AuthMethod.PIN), CredentialStatus.ABSENT)
        val missing = AppAuthenticatorImpl(noPin, { 0 }, { true }) { _, _ -> true }
        assertFalse(missing.setTargetProtected(SecurityTarget.MODULE_INSTALL, true))
    }

    @Test fun targetSwitchInvalidatesCachedAuthorizationAndFollowsTheDefault() = runBlocking {
        val repo = repository()
        var calls = 0
        val auth = AppAuthenticatorImpl(repo, { 0 }, { true }) { _, _ -> calls++; true }
        assertTrue(auth.require(SecurityTarget.MODULE_INSTALL))
        assertTrue(auth.setTargetProtected(SecurityTarget.MODULE_INSTALL, false))
        assertTrue(auth.require(SecurityTarget.MODULE_INSTALL))
        assertEquals(1, calls)
        assertTrue(auth.setTargetProtected(SecurityTarget.MODULE_INSTALL, true))
        assertTrue(auth.require(SecurityTarget.MODULE_INSTALL))
        assertEquals(2, calls)
        assertTrue(auth.changeDefault(AuthMethod.BIOMETRIC))
        assertEquals(AuthMethod.BIOMETRIC, repo.state.value.config.methodFor(SecurityTarget.MODULE_INSTALL))
        assertFalse(repo.state.value.config.targetOverrides.containsKey(SecurityTarget.MODULE_INSTALL))
    }

    @Test fun unavailableSessionRejectsPolicyChanges() = runBlocking {
        val repo = repository()
        var foreground = false
        val auth = AppAuthenticatorImpl(repo, { 0 }, { foreground }) { _, _ -> error("Unexpected prompt") }
        assertFalse(auth.changeDefault(AuthMethod.NONE))
        assertFalse(auth.setTargetProtected(SecurityTarget.APP_START, false))
        foreground = true
        repo.state.value = repo.state.value.copy(storageError = true)
        assertFalse(auth.changeDefault(AuthMethod.NONE))
        assertFalse(auth.setTargetProtected(SecurityTarget.APP_START, false))
        assertEquals(AuthMethod.PIN, repo.state.value.config.defaultMethod)
    }

    @Test fun missingOrUnprovedCredentialsPreserveThePolicy() = runBlocking {
        val repo = repository(SecurityConfig.create())
        val auth = AppAuthenticatorImpl(repo, { 0 }, { true }, { false }) { _, _ -> false }
        assertFalse(auth.changeDefault(AuthMethod.BIOMETRIC))
        assertEquals(AuthMethod.NONE, repo.state.value.config.defaultMethod)
        val noPin = repository(SecurityConfig.create(), CredentialStatus.ABSENT)
        val missing = AppAuthenticatorImpl(noPin, { 0 }, { true }) { _, _ -> true }
        assertFalse(missing.changeDefault(AuthMethod.PIN))
        assertEquals(AuthMethod.NONE, noPin.state.value.config.defaultMethod)
    }

    @Test fun concurrentRequestsAreSerializedAndCancelledPromptIsNotCached() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val firstAnswer = CompletableDeferred<Boolean>()
        var calls = 0
        val auth = AppAuthenticatorImpl(repository(), { 0 }, { true }) { _, _ ->
            calls++
            if (calls == 1) {
                firstStarted.complete(Unit)
                firstAnswer.await()
            } else true
        }
        val first = async { auth.require(SecurityTarget.APP_START) }
        firstStarted.await()
        val second = async { auth.require(SecurityTarget.MODULE_INSTALL) }
        first.cancel()
        first.join()
        assertTrue(second.await())
        assertEquals(2, calls)
        assertTrue(auth.require(SecurityTarget.APP_START))
        assertEquals(3, calls)
    }

    @Test fun startupFreshRequestNeverReusesAnEarlierGrant() = runBlocking {
        var calls = 0
        var accepted = true
        val auth = AppAuthenticatorImpl(repository(), { 0 }, { true }) { _, _ -> calls++; accepted }
        assertTrue(auth.require(SecurityTarget.APP_START, fresh = true))
        accepted = false
        assertFalse(auth.require(SecurityTarget.APP_START, fresh = true))
        assertEquals(2, calls)
        assertFalse(auth.require(SecurityTarget.APP_START))
        assertEquals(3, calls)
    }

    @Test fun startupWithNoneStillFailsClosedWhenUnavailable() = runBlocking {
        val repo = repository(SecurityConfig.create())
        var foreground = true
        val auth = AppAuthenticatorImpl(repo, { 0 }, { foreground }) { _, _ -> error("NONE must not prompt") }
        assertTrue(auth.require(SecurityTarget.APP_START, fresh = true))
        foreground = false
        assertFalse(auth.require(SecurityTarget.APP_START, fresh = true))
        foreground = true
        repo.state.value = repo.state.value.copy(storageError = true)
        assertFalse(auth.require(SecurityTarget.APP_START, fresh = true))
    }
}
