package me.weishu.kernelsu.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityConfigTest {
    @Test
    fun missingOverrideInheritsButExplicitNoneDoesNot() {
        val config = SecurityConfig.create(AuthMethod.PIN, mapOf(SecurityTarget.THEME_IMPORT to AuthMethod.NONE))
        assertEquals(AuthMethod.PIN, config.methodFor(SecurityTarget.APP_START))
        assertEquals(AuthMethod.NONE, config.methodFor(SecurityTarget.THEME_IMPORT))
    }

    @Test
    fun clearingCredentialKeepsBiometricAndExplicitNone() {
        val config = SecurityConfig.create(
            AuthMethod.PIN,
            mapOf(
                SecurityTarget.APP_START to AuthMethod.BIOMETRIC,
                SecurityTarget.MODULE_INSTALL to AuthMethod.PATTERN,
                SecurityTarget.THEME_IMPORT to AuthMethod.NONE,
            ),
            allowCredentialFallback = true,
        ).withoutCredentials()
        assertEquals(AuthMethod.NONE, config.defaultMethod)
        assertEquals(AuthMethod.BIOMETRIC, config.methodFor(SecurityTarget.APP_START))
        assertEquals(AuthMethod.NONE, config.methodFor(SecurityTarget.MODULE_INSTALL))
        assertFalse(config.allowCredentialFallback)
    }

    @Test
    fun targetedMethodsAreDistinctFromInheritedOnes() {
        val config = SecurityConfig.create(AuthMethod.PATTERN, mapOf(SecurityTarget.MODULE_INSTALL to AuthMethod.PIN))
        assertEquals(listOf(AuthMethod.PATTERN, AuthMethod.PIN), config.methods().distinct())
    }

    @Test
    fun configurationDefensivelyCopiesOverrides() {
        val source = mutableMapOf(SecurityTarget.APP_START to AuthMethod.PIN)
        val config = SecurityConfig.create(targetOverrides = source)
        source.clear()
        assertEquals(AuthMethod.PIN, config.methodFor(SecurityTarget.APP_START))
    }

    @Test
    fun keysAreStableAndRetiredNamesDegradeToNone() {
        assertEquals(SecurityTarget.entries.size, SecurityTarget.entries.map { it.key }.toSet().size)
        assertNull(SecurityTarget.fromKey("unknown"))
        assertNull(AuthMethod.fromKey("unknown"))
        assertEquals(AuthMethod.PIN, AuthMethod.fromKey("pin"))
        assertEquals(AuthMethod.NONE, AuthMethod.fromStoredKey("password"))
        assertNull(AuthMethod.fromStoredKey("bogus"))
        assertEquals(CredentialKind.PIN, AuthMethod.PIN.credential)
        assertEquals(CredentialKind.PATTERN, AuthMethod.PATTERN.credential)
        assertNull(AuthMethod.BIOMETRIC.credential)
    }

    @Test
    fun missingBiometricDegradesBothDefaultAndOverrides() {
        val config = SecurityConfig.create(
            AuthMethod.BIOMETRIC,
            mapOf(
                SecurityTarget.MODULE_INSTALL to AuthMethod.BIOMETRIC,
                SecurityTarget.THEME_IMPORT to AuthMethod.PIN,
            ),
            allowCredentialFallback = true,
        )
        assertTrue(config.usesBiometric())
        val degraded = config.withoutBiometric()
        assertEquals(AuthMethod.NONE, degraded.defaultMethod)
        assertEquals(AuthMethod.NONE, degraded.methodFor(SecurityTarget.MODULE_INSTALL))
        assertEquals(AuthMethod.PIN, degraded.methodFor(SecurityTarget.THEME_IMPORT))
        assertTrue(degraded.allowCredentialFallback)
        assertFalse(degraded.usesBiometric())
    }
}
