package me.weishu.kernelsu.data.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FolkZygiskPreflightTest {
    private fun payload() = JSONObject("""{"schema_version":1,"builtin_enabled":true,"provider_ids":["rezygisk"],"conflict":true}""")

    @Test fun parsesConflict() {
        assertEquals(listOf("rezygisk"), FolkZygiskPreflight.fromJson(payload()).providerIds)
    }

    @Test fun rejectsMalformedOrInconsistentResponses() {
        for ((key, value) in listOf("schema_version" to 2, "builtin_enabled" to "true", "conflict" to false)) {
            assertThrows(Exception::class.java) { FolkZygiskPreflight.fromJson(payload().put(key, value)) }
        }
        assertThrows(Exception::class.java) { FolkZygiskPreflight.fromJson(payload().apply { remove("provider_ids") }) }
    }
}
