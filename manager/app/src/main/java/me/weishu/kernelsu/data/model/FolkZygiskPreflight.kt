package me.weishu.kernelsu.data.model

import org.json.JSONObject

data class FolkZygiskPreflight(val builtinEnabled: Boolean, val providerIds: List<String>, val conflict: Boolean) {
    companion object {
        fun fromJson(json: JSONObject): FolkZygiskPreflight {
            check(json.get("schema_version") == 1) { "Unsupported preflight schema" }
            val enabled = json.get("builtin_enabled") as? Boolean ?: error("Invalid builtin_enabled")
            val conflict = json.get("conflict") as? Boolean ?: error("Invalid conflict")
            val array = json.getJSONArray("provider_ids")
            val ids = List(array.length()) { array.get(it) as? String ?: error("Invalid provider ID") }
            check(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { "Invalid provider IDs" }
            check(conflict == (enabled && ids.isNotEmpty())) { "Inconsistent preflight result" }
            return FolkZygiskPreflight(enabled, ids, conflict)
        }
    }
}
