package me.weishu.kernelsu.data.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FolkZygiskTest {
    private fun payload() = JSONObject("""{
        "schema_version":1,"enabled":false,"engine_available":true,
        "enable_blockers":["license_review_required"],"external_providers":[],
        "injection_state":"unverified","reboot_required":null,
        "startup_failures":0,"startup_blocked":false
    }""")

    @Test fun unknownRebootStateIsNotFalse() {
        val status = FolkZygiskStatus.fromJson(payload())
        assertNull(status.rebootRequired)
        assertEquals(listOf("license_review_required"), status.enableBlockers)
    }

    @Test fun malformedFieldsFailInsteadOfDefaulting() {
        for ((key, value) in listOf(
            "schema_version" to 2, "enabled" to "false", "startup_failures" to -1,
            "injection_state" to "unknown", "reboot_required" to "false",
        )) {
            assertThrows(Exception::class.java) {
                FolkZygiskStatus.fromJson(payload().put(key, value))
            }
        }
        val missing = payload().apply { remove("enable_blockers") }
        assertThrows(Exception::class.java) { FolkZygiskStatus.fromJson(missing) }
    }
}
