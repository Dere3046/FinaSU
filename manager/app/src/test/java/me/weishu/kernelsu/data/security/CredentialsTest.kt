package me.weishu.kernelsu.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialsTest {
    @Test
    fun pinAcceptsOnlyFourOrSixDigits() {
        assertTrue(Credentials.isValidPin("1234".toCharArray()))
        assertTrue(Credentials.isValidPin("123456".toCharArray()))
        assertFalse(Credentials.isValidPin("123".toCharArray()))
        assertFalse(Credentials.isValidPin("12345".toCharArray()))
        assertFalse(Credentials.isValidPin("1234567".toCharArray()))
        assertFalse(Credentials.isValidPin("12a4".toCharArray()))
    }

    @Test
    fun patternRequiresFourUniqueInRangeNodes() {
        assertTrue(Credentials.isValidPattern(listOf(0, 1, 2, 5)))
        assertTrue(Credentials.isValidPattern(listOf(0, 1, 2, 5, 8, 7, 6, 3, 4)))
        assertFalse(Credentials.isValidPattern(listOf(0, 1, 2)))
        assertFalse(Credentials.isValidPattern(listOf(0, 1, 2, 1)))
        assertFalse(Credentials.isValidPattern(listOf(0, 1, 2, 9)))
    }

    @Test
    fun patternSecretIsCanonicalAndValidatesAsItsOwnKind() {
        val secret = Credentials.patternSecret(listOf(0, 3, 6, 7))
        assertNotNull(secret)
        assertEquals("0-3-6-7", secret!!.concatToString())
        assertTrue(Credentials.isValid(CredentialKind.PATTERN, secret))
        assertNull(Credentials.patternSecret(listOf(0, 1, 2)))
    }

    @Test
    fun kindsDoNotAcceptEachOthersSecrets() {
        assertTrue(Credentials.isValid(CredentialKind.PIN, "1234".toCharArray()))
        assertFalse(Credentials.isValid(CredentialKind.PIN, "0-3-6-7".toCharArray()))
        assertFalse(Credentials.isValid(CredentialKind.PATTERN, "1234".toCharArray()))
    }

    @Test
    fun patternRejectsNonCanonicalAndMalformedText() {
        listOf("00-3-6-7", "0-03-6-7", "0--3-6-7", "-0-3-6-7", "0-3-6-7-", "0-3-6-9")
            .forEach { text ->
                assertFalse(text, Credentials.isValid(CredentialKind.PATTERN, text.toCharArray()))
            }
    }
}
