package me.weishu.kernelsu.data.security

import org.junit.Assert.*
import org.junit.Test

class SecurityConfigTest {
    @Test fun missingOverrideInheritsButExplicitNoneDoesNot() {
        val config = SecurityConfig.create(AuthMethod.BIOMETRIC, mapOf(SecurityTarget.SETTINGS to AuthMethod.NONE))
        assertEquals(AuthMethod.BIOMETRIC, config.methodFor(SecurityTarget.APP_START))
        assertEquals(AuthMethod.NONE, config.methodFor(SecurityTarget.SETTINGS))
    }

    @Test fun clearingPasswordPreservesBiometricAndExplicitNone() {
        val config = SecurityConfig.create(AuthMethod.PASSWORD, mapOf(
            SecurityTarget.APP_START to AuthMethod.BIOMETRIC,
            SecurityTarget.MODULE_INSTALL to AuthMethod.PASSWORD,
            SecurityTarget.SETTINGS to AuthMethod.NONE,
        ), true).withoutPassword()
        assertEquals(AuthMethod.NONE, config.defaultMethod)
        assertEquals(AuthMethod.BIOMETRIC, config.methodFor(SecurityTarget.APP_START))
        assertEquals(AuthMethod.NONE, config.methodFor(SecurityTarget.MODULE_INSTALL))
        assertFalse(config.needsPassword())
    }

    @Test fun configurationDefensivelyCopiesOverrides() {
        val source = mutableMapOf(SecurityTarget.APP_START to AuthMethod.PASSWORD)
        val config = SecurityConfig.create(targetOverrides = source)
        source.clear()
        assertEquals(AuthMethod.PASSWORD, config.methodFor(SecurityTarget.APP_START))
    }

    @Test fun keysAreStableUniqueAndUnknownKeysAreRejected() {
        assertEquals(SecurityTarget.entries.size, SecurityTarget.entries.map { it.key }.toSet().size)
        assertNull(SecurityTarget.fromKey("unknown"))
        assertNull(AuthMethod.fromKey("unknown"))
        assertEquals(AuthMethod.PASSWORD, AuthMethod.fromKey("password"))
    }
}
