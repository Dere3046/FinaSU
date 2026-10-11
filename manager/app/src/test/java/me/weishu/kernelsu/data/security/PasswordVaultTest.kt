package me.weishu.kernelsu.data.security

import org.junit.Assert.*
import org.junit.Test

class PasswordVaultTest {
    private val vault = PasswordVault()

    @Test fun correctPasswordVerifiesAndRandomSaltChangesRecord() {
        val password = " exact password ".toCharArray()
        val first = vault.create(password)
        val second = vault.create(password)
        assertNotEquals(first, second)
        assertTrue(vault.verify(password, first))
        assertFalse(vault.verify("exact password".toCharArray(), first))
        assertFalse(vault.verify("wrong password".toCharArray(), first))
        assertArrayEquals(" exact password ".toCharArray(), password)
    }

    @Test fun damagedRecordsAndUnboundedIterationsAreRejected() {
        val record = vault.create("password".toCharArray())
        listOf("", "invalid", record.replaceFirst("1:", "2:"),
            record.replace(":600000:", ":2147483647:"),
            record.replace(":600000:", ":1:"), record.substringBeforeLast(':') + ":AA==",
        ).forEach {
            assertFalse(vault.isValidRecord(it))
            assertFalse(vault.verify("password".toCharArray(), it))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortNewPasswordIsRejected() {
        vault.create("short".toCharArray())
    }
}
