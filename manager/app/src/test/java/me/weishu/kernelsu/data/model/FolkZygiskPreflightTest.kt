package me.weishu.kernelsu.data.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FolkZygiskPreflightTest {
    private fun payload() = JSONObject("""{"schema_version":2,"builtin_enabled":true,"provider_ids":["rezygisk"],"conflict":true,"external_providers":[],"blocked":false,"state_token":"${"a".repeat(64)}"}""")

    @Test fun parsesConflict() {
        assertEquals(listOf("rezygisk"), FolkZygiskPreflight.fromJson(payload()).providerIds)
    }

    @Test fun rejectsMalformedOrInconsistentResponses() {
        for ((key, value) in listOf("schema_version" to 1, "builtin_enabled" to "true", "conflict" to false, "state_token" to "bad")) {
            assertThrows(Exception::class.java) { FolkZygiskPreflight.fromJson(payload().put(key, value)) }
        }
        assertThrows(Exception::class.java) { FolkZygiskPreflight.fromJson(payload().apply { remove("provider_ids") }) }
    }

    @Test fun rejectsUnblockedMultipleProviders() {
        val json = payload().put("external_providers", org.json.JSONArray().put("onyxzygisk"))
        assertThrows(Exception::class.java) { FolkZygiskPreflight.fromJson(json) }
        assertEquals(true, FolkZygiskPreflight.fromJson(json.put("blocked", true)).blocked)
        // A duplicate provider archive is blocked by the backend even if IDs deduplicate.
        assertEquals(true, FolkZygiskPreflight.fromJson(payload().put("blocked", true)).blocked)
    }
}
