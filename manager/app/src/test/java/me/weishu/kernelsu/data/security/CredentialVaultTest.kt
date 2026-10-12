package me.weishu.kernelsu.data.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialVaultTest {
    private val vault = CredentialVault()

    @Test
    fun recordsAreSaltedAndVerifyOnlyTheCorrectSecret() {
        val secret = "1234".toCharArray()
        val first = vault.create(secret)
        val second = vault.create(secret)
        assertNotEquals(first, second)
        assertTrue(vault.isValidRecord(first))
        assertTrue(vault.verify(secret, first))
        assertFalse(vault.verify("4321".toCharArray(), first))
        assertFalse(vault.verify(charArrayOf(), first))
    }

    @Test
    fun malformedAndExcessiveWorkFactorRecordsAreRejected() {
        val valid = vault.create("1234".toCharArray())
        listOf(
            "", "invalid", "x".repeat(257),
            valid.replaceFirst("1:", "2:"),
            valid.replaceFirst(":600000:", ":1:"),
            valid.replaceFirst(":600000:", ":1000001:"),
            "1:600000:invalid:invalid",
        ).forEach { record ->
            assertFalse(vault.isValidRecord(record))
            assertFalse(vault.verify("1234".toCharArray(), record))
        }
    }
}
