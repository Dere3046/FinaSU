package me.weishu.kernelsu.data.model

import org.json.JSONObject

data class FolkZygiskPreflight(
    val builtinEnabled: Boolean, val providerIds: List<String>, val conflict: Boolean,
    val externalProviders: List<String>, val blocked: Boolean, val stateToken: String,
) {
    companion object {
        fun fromJson(json: JSONObject): FolkZygiskPreflight {
            check(json.get("schema_version") == 2) { "Unsupported preflight schema" }
            val enabled = json.get("builtin_enabled") as? Boolean ?: error("Invalid builtin_enabled")
            val conflict = json.get("conflict") as? Boolean ?: error("Invalid conflict")
            val array = json.getJSONArray("provider_ids")
            val ids = List(array.length()) { array.get(it) as? String ?: error("Invalid provider ID") }
            check(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { "Invalid provider IDs" }
            check(conflict == (enabled && ids.isNotEmpty())) { "Inconsistent preflight result" }
            val externalArray = json.getJSONArray("external_providers")
            val external = List(externalArray.length()) { externalArray.get(it) as? String ?: error("Invalid external ID") }
            check(external.all { it.isNotBlank() } && external.distinct().size == external.size)
            val blocked = json.get("blocked") as? Boolean ?: error("Invalid blocked")
            check(blocked || (ids + external).distinct().size <= 1) { "Inconsistent blocked result" }
            val token = json.get("state_token") as? String ?: error("Invalid state token")
            check(token.matches(Regex("[0-9a-f]{64}"))) { "Invalid state token" }
            return FolkZygiskPreflight(enabled, ids, conflict, external, blocked, token)
        }
    }
}
