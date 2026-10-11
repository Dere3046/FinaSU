package me.weishu.kernelsu.data.security

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class SecurityRepositoryTest {
    private class Prefs {
        val values = mutableMapOf<String, String>()
        var canCommit = true
        val prefs: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected prefs call: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, String?>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "putString" -> { changes[args!![0] as String] = args[1] as String?; editor }
                    "remove" -> { changes[args!![0] as String] = null; editor }
                    "commit" -> {
                        changes.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        canCommit
                    }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
            return editor
        }
    }

    private fun repository(prefs: Prefs, clock: () -> Long = { 0L }) =
        SecurityRepositoryImpl(prefs.prefs, worker = Dispatchers.Unconfined, elapsedRealtime = clock)

    @Test fun defaultsLoadAndPasswordRequiredConfigCannotBeEnabledWithoutCredential() = runBlocking {
        val repo = repository(Prefs())
        assertFalse(repo.state.value.loaded)
        repo.initialize()
        assertTrue(repo.state.value.loaded)
        assertFalse(repo.state.value.storageError)
        assertFalse(repo.updateConfig(SecurityConfig.create(AuthMethod.PASSWORD)))
        assertFalse(repo.updateConfig(SecurityConfig.create(allowPasswordFallback = true)))
    }

    @Test fun oldPasswordIsRequiredAndClearingUpdatesPolicyAndCredentialTogether() = runBlocking {
        val prefs = Prefs()
        val repo = repository(prefs)
        val old = "old password".toCharArray()
        val new = "new password".toCharArray()
        assertEquals(PasswordResult.Success, repo.setPassword(old))
        assertTrue(repo.updateConfig(SecurityConfig.create(AuthMethod.PASSWORD,
            mapOf(SecurityTarget.APP_START to AuthMethod.BIOMETRIC), true)))
        assertEquals(PasswordResult.Incorrect, repo.changePassword(new, new))
        assertEquals(PasswordResult.Success, repo.changePassword(old, new))
        assertEquals(PasswordResult.Incorrect, repo.clearPassword(old))
        assertEquals(PasswordResult.Success, repo.clearPassword(new))
        val restored = repository(prefs)
        restored.initialize()
        assertEquals(PasswordStatus.ABSENT, restored.state.value.passwordStatus)
        assertEquals(AuthMethod.NONE, restored.state.value.config.defaultMethod)
        assertEquals(AuthMethod.BIOMETRIC, restored.state.value.config.methodFor(SecurityTarget.APP_START))
        assertFalse(restored.state.value.config.allowPasswordFallback)
    }

    @Test fun failuresAreThrottledAndSuccessResetsCounter() = runBlocking {
        var now = 0L
        val repo = repository(Prefs()) { now }
        val password = "password".toCharArray()
        repo.setPassword(password)
        repeat(5) { assertEquals(PasswordResult.Incorrect, repo.verifyPassword("bad".toCharArray())) }
        assertEquals(PasswordResult.RetryAfter(1000L), repo.verifyPassword(password))
        now = 1000L
        assertEquals(PasswordResult.Success, repo.verifyPassword(password))
        assertEquals(PasswordResult.Incorrect, repo.verifyPassword("bad".toCharArray()))
        assertEquals(PasswordResult.Success, repo.verifyPassword(password))
    }

    @Test fun corruptConfigCredentialAndDiskWriteFailureNeverLookUnprotected() = runBlocking {
        val corrupt = Prefs().also { it.values["config"] = "invalid" }
        val repo = repository(corrupt)
        repo.initialize()
        assertTrue(repo.state.value.storageError)
        assertFalse(repo.updateConfig(SecurityConfig.create()))
        val credential = repository(Prefs().also { it.values["password_record"] = "invalid" })
        credential.initialize()
        assertEquals(PasswordStatus.CORRUPT, credential.state.value.passwordStatus)
        assertEquals(PasswordResult.StorageError, credential.verifyPassword("password".toCharArray()))
        val failed = repository(Prefs().also { it.canCommit = false })
        assertEquals(PasswordResult.StorageError, failed.setPassword("password".toCharArray()))
        assertTrue(failed.state.value.storageError)
    }
}
