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
                "contains" -> values.containsKey(args!![0])
                "edit" -> editor()
                else -> error("Unexpected prefs call: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, String?>()
            fun apply() {
                changes.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "putString" -> { changes[args!![0] as String] = args[1] as String?; editor }
                    "remove" -> { changes[args!![0] as String] = null; editor }
                    "commit" -> { if (canCommit) apply(); canCommit }
                    "apply" -> { apply(); Unit }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
            return editor
        }
    }

    private fun repository(prefs: Prefs, clock: () -> Long = { 0L }) =
        SecurityRepositoryImpl(prefs.prefs, worker = Dispatchers.Unconfined, elapsedRealtime = clock)

    private val pin = "1234".toCharArray()
    private val pattern = requireNotNull(Credentials.patternSecret(listOf(0, 3, 6, 7)))

    @Test fun storedBiometricPolicyDegradesWhenTheDeviceHasNoFingerprint() = runBlocking {
        val prefs = Prefs()
        prefs.values["config"] = "{\"version\":2,\"default\":\"biometric\",\"fallback\":false,\"targets\":{}}"
        val repo = SecurityRepositoryImpl(
            prefs.prefs,
            worker = Dispatchers.Unconfined,
            elapsedRealtime = { 0L },
            biometricSupported = { false },
        )
        repo.initialize()
        assertEquals(AuthMethod.NONE, repo.state.value.config.defaultMethod)
        assertEquals(AuthMethod.NONE, repo.state.value.config.methodFor(SecurityTarget.APP_START))
        assertFalse(repo.state.value.storageError)
        // The downgrade is persisted, so a later launch never reports biometric again.
        assertTrue(prefs.values.getValue("config").contains("\"default\":\"none\""))
    }

    @Test fun freshInstallationLoadsUnprotectedAndWithoutCredentials() = runBlocking {
        val repo = repository(Prefs())
        assertFalse(repo.state.value.loaded)
        repo.initialize()
        val state = repo.state.value
        assertTrue(state.loaded)
        assertFalse(state.storageError)
        assertFalse(state.hasAnyCredential())
        assertEquals(CredentialStatus.ABSENT, state.pinStatus)
        assertEquals(CredentialStatus.ABSENT, state.patternStatus)
        assertEquals(AuthMethod.NONE, state.config.defaultMethod)
    }

    @Test fun pinRejectsWrongFormatsAndBothCredentialsCanCoexist() = runBlocking {
        val repo = repository(Prefs())
        assertEquals(CredentialResult.InvalidFormat, repo.set(CredentialKind.PIN, "12".toCharArray()))
        assertEquals(CredentialStatus.ABSENT, repo.state.value.pinStatus)
        assertEquals(CredentialResult.Success, repo.set(CredentialKind.PIN, pin))
        assertEquals(CredentialResult.AlreadySet, repo.set(CredentialKind.PIN, pin))
        assertEquals(CredentialResult.InvalidFormat, repo.verify(CredentialKind.PATTERN, pin))
        assertEquals(CredentialResult.Success, repo.set(CredentialKind.PATTERN, pattern))
        assertEquals(CredentialResult.Incorrect, repo.verify(CredentialKind.PIN, "9999".toCharArray()))
        assertEquals(CredentialResult.Success, repo.verify(CredentialKind.PIN, pin))
        assertEquals(CredentialResult.Success, repo.verify(CredentialKind.PATTERN, pattern))
        assertEquals(CredentialStatus.READY, repo.state.value.pinStatus)
        assertEquals(CredentialStatus.READY, repo.state.value.patternStatus)
    }

    @Test fun policyRequiringAMissingCredentialIsRefused() = runBlocking {
        val repo = repository(Prefs())
        assertFalse(repo.updateConfig(SecurityConfig.create(AuthMethod.PIN)))
        assertFalse(repo.updateConfig(SecurityConfig.create(allowCredentialFallback = true)))
        assertEquals(CredentialResult.Success, repo.set(CredentialKind.PIN, pin))
        assertTrue(repo.updateConfig(SecurityConfig.create(AuthMethod.PIN)))
        assertEquals(AuthMethod.PIN, repo.state.value.config.defaultMethod)
    }

    @Test fun repeatedFailuresBackOffAndSuccessResetsTheCounter() = runBlocking {
        var now = 0L
        val repo = repository(Prefs()) { now }
        repo.set(CredentialKind.PIN, pin)
        repeat(5) { assertEquals(CredentialResult.Incorrect, repo.verify(CredentialKind.PIN, "9999".toCharArray())) }
        assertEquals(CredentialResult.RetryAfter(1_000L), repo.verify(CredentialKind.PIN, pin))
        now = 1_000L
        assertEquals(CredentialResult.Success, repo.verify(CredentialKind.PIN, pin))
        assertEquals(CredentialResult.Incorrect, repo.verify(CredentialKind.PIN, "9999".toCharArray()))
        assertEquals(CredentialResult.Success, repo.verify(CredentialKind.PIN, pin))
    }

    @Test fun changingRequiresTheOldSecretAndClearingRestoresThePolicy() = runBlocking {
        val repo = repository(Prefs())
        val replacement = "654321".toCharArray()
        assertEquals(CredentialResult.Success, repo.set(CredentialKind.PIN, pin))
        assertTrue(repo.updateConfig(SecurityConfig.create(AuthMethod.PIN,
            mapOf(SecurityTarget.APP_START to AuthMethod.PIN), true)))
        assertEquals(CredentialResult.Incorrect, repo.change(CredentialKind.PIN, "9999".toCharArray(), replacement))
        assertEquals(CredentialResult.Success, repo.change(CredentialKind.PIN, pin, replacement))
        assertEquals(CredentialResult.Incorrect, repo.clear(CredentialKind.PIN, pin))
        assertEquals(CredentialResult.Success, repo.clear(CredentialKind.PIN, replacement))
        val state = repo.state.value
        assertEquals(CredentialStatus.ABSENT, state.pinStatus)
        assertEquals(AuthMethod.NONE, state.config.defaultMethod)
        assertEquals(AuthMethod.NONE, state.config.methodFor(SecurityTarget.APP_START))
        assertFalse(state.config.allowCredentialFallback)
    }

    @Test fun corruptedRecordAndFailedWritesNeverLookUnprotected() = runBlocking {
        val corrupt = repository(Prefs().also { it.values["pin_record"] = "invalid" })
        corrupt.initialize()
        assertTrue(corrupt.state.value.storageError)
        assertEquals(CredentialStatus.CORRUPT, corrupt.state.value.pinStatus)
        assertEquals(CredentialResult.StorageError, corrupt.verify(CredentialKind.PIN, pin))
        assertFalse(corrupt.updateConfig(SecurityConfig.create()))
        val failed = repository(Prefs().also { it.canCommit = false })
        assertEquals(CredentialResult.StorageError, failed.set(CredentialKind.PIN, pin))
        assertTrue(failed.state.value.storageError)
    }

    @Test fun configurationAndCredentialsSurviveReloading() = runBlocking {
        val prefs = Prefs()
        val repo = repository(prefs)
        assertEquals(CredentialResult.Success, repo.set(CredentialKind.PATTERN, pattern))
        assertTrue(repo.updateConfig(SecurityConfig.create(AuthMethod.PATTERN)))
        val restored = repository(prefs)
        restored.initialize()
        assertFalse(restored.state.value.storageError)
        assertEquals(AuthMethod.PATTERN, restored.state.value.config.defaultMethod)
        assertEquals(CredentialResult.Success, restored.verify(CredentialKind.PATTERN, pattern))
    }

    @Test fun clearingOneCredentialRemovesOnlyItsPoliciesAndSurvivesReloading() = runBlocking {
        CredentialKind.entries.forEach { removed ->
            val prefs = Prefs()
            val repo = repository(prefs)
            repo.set(CredentialKind.PIN, pin)
            repo.set(CredentialKind.PATTERN, pattern)
            val removedMethod = if (removed == CredentialKind.PIN) AuthMethod.PIN else AuthMethod.PATTERN
            val remainingMethod = if (removed == CredentialKind.PIN) AuthMethod.PATTERN else AuthMethod.PIN
            assertTrue(repo.updateConfig(SecurityConfig.create(
                removedMethod,
                mapOf(SecurityTarget.SETTINGS to remainingMethod, SecurityTarget.APP_START to removedMethod),
                allowCredentialFallback = true,
            )))
            val secret = if (removed == CredentialKind.PIN) pin else pattern
            assertEquals(CredentialResult.Success, repo.clear(removed, secret))
            val restored = repository(prefs)
            restored.initialize()
            assertFalse(restored.state.value.storageError)
            assertEquals(AuthMethod.NONE, restored.state.value.config.defaultMethod)
            assertEquals(AuthMethod.NONE, restored.state.value.config.methodFor(SecurityTarget.APP_START))
            assertEquals(remainingMethod, restored.state.value.config.methodFor(SecurityTarget.SETTINGS))
            assertTrue(restored.state.value.config.allowCredentialFallback)
            assertEquals(CredentialStatus.ABSENT, restored.state.value.statusOf(removed))
        }
    }

    @Test fun malformedOrUnsupportedPoliciesFailClosedWithoutBeingOverwritten() = runBlocking {
        listOf(
            "{\"version\":2,\"default\":\"unknown\",\"fallback\":false,\"targets\":{}}",
            "{\"version\":3,\"default\":\"none\",\"fallback\":false,\"targets\":{}}",
            "{\"version\":2.5,\"default\":\"none\",\"fallback\":false,\"targets\":{}}",
            "{\"version\":\"2\",\"default\":\"none\",\"fallback\":false,\"targets\":{}}",
            "{\"version\":2,\"default\":\"password\",\"fallback\":false,\"targets\":{}}",
            "{\"version\":2,\"default\":\"none\",\"fallback\":\"false\",\"targets\":{}}",
            "{\"version\":2,\"fallback\":false,\"targets\":{}}",
            "{\"version\":2,\"default\":\"none\",\"fallback\":false,\"targets\":{\"app_start\":\"unknown\"}}",
            "{\"version\":2,\"default\":\"none\",\"fallback\":false,\"targets\":[]}",
            "{\"version\":2,\"default\":\"none\",\"fallback\":false,\"targets\":{\"unknown\":\"none\"}}",
        ).forEach { raw ->
            val prefs = Prefs().also { it.values["config"] = raw }
            val repo = repository(prefs)
            repo.initialize()
            assertTrue(repo.state.value.storageError)
            assertFalse(repo.updateConfig(SecurityConfig.create()))
            assertEquals(raw, prefs.values["config"])
        }
    }

    @Test fun retiredPasswordPolicyMigratesWithoutDroppingBiometricOverrides() = runBlocking {
        val prefs = Prefs()
        prefs.values["config"] = "{\"version\":1,\"default\":\"password\",\"fallback\":true," +
            "\"targets\":{\"app_start\":\"biometric\",\"settings\":\"password\"}}"
        prefs.values["password_record"] = "retired"
        val repo = repository(prefs)
        repo.initialize()
        assertFalse(repo.state.value.storageError)
        assertEquals(AuthMethod.NONE, repo.state.value.config.defaultMethod)
        assertEquals(AuthMethod.BIOMETRIC, repo.state.value.config.methodFor(SecurityTarget.APP_START))
        assertEquals(AuthMethod.NONE, repo.state.value.config.methodFor(SecurityTarget.SETTINGS))
        assertFalse(repo.state.value.config.allowCredentialFallback)
        assertFalse(prefs.values.containsKey("password_record"))
        assertTrue(prefs.values.getValue("config").contains("\"version\":2"))
    }
}
