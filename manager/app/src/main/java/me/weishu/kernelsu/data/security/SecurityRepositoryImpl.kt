package me.weishu.kernelsu.data.security

import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One application-scoped instance owns the policy and every credential record. */
class SecurityRepositoryImpl(
    private val prefs: SharedPreferences,
    private val vault: CredentialVault = CredentialVault(),
    private val worker: CoroutineDispatcher = Dispatchers.IO,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val biometricSupported: () -> Boolean = { true },
) : SecurityRepository {
    private val mutableState = MutableStateFlow(SecurityState())
    override val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val records = mutableMapOf<CredentialKind, String?>()
    private var failures = 0
    private var retryAt = 0L

    override suspend fun initialize() = withContext(worker) {
        mutex.withLock { loadIfNeeded() }
    }

    override suspend fun updateConfig(config: SecurityConfig): Boolean = withContext(worker) {
        mutex.withLock {
            loadIfNeeded()
            if (state.value.storageError) return@withLock false
            val missing = config.methods().any { method ->
                method.credential?.let { state.value.statusOf(it) != CredentialStatus.READY } == true
            }
            val fallbackMissing = config.allowCredentialFallback && !state.value.hasAnyCredential()
            if (missing || fallbackMissing) return@withLock false
            currentCoroutineContext().ensureActive()
            persist(config, records)
        }
    }

    override suspend fun verify(kind: CredentialKind, secret: CharArray): CredentialResult = withContext(worker) {
        mutex.withLock {
            loadIfNeeded()
            verifyLocked(kind, secret)
        }
    }

    override suspend fun set(kind: CredentialKind, secret: CharArray): CredentialResult = withContext(worker) {
        mutex.withLock {
            loadIfNeeded()
            if (state.value.storageError) return@withLock CredentialResult.StorageError
            if (records[kind] != null) return@withLock CredentialResult.AlreadySet
            if (!Credentials.isValid(kind, secret)) return@withLock CredentialResult.InvalidFormat
            val record = vault.create(secret)
            currentCoroutineContext().ensureActive()
            val updated = records + (kind to record)
            if (persist(state.value.config, updated)) CredentialResult.Success else CredentialResult.StorageError
        }
    }

    override suspend fun change(
        kind: CredentialKind,
        oldSecret: CharArray,
        newSecret: CharArray,
    ): CredentialResult = withContext(worker) {
        mutex.withLock {
            loadIfNeeded()
            val verified = verifyLocked(kind, oldSecret)
            if (verified != CredentialResult.Success) return@withLock verified
            if (!Credentials.isValid(kind, newSecret)) return@withLock CredentialResult.InvalidFormat
            val record = vault.create(newSecret)
            currentCoroutineContext().ensureActive()
            val updated = records + (kind to record)
            if (persist(state.value.config, updated)) CredentialResult.Success else CredentialResult.StorageError
        }
    }

    override suspend fun clear(kind: CredentialKind, secret: CharArray): CredentialResult = withContext(worker) {
        mutex.withLock {
            loadIfNeeded()
            val verified = verifyLocked(kind, secret)
            if (verified != CredentialResult.Success) return@withLock verified
            currentCoroutineContext().ensureActive()
            val other = CredentialKind.entries.first { it != kind }
            val remaining = records[other] != null
            val config = state.value.config.withoutCredential(kind, remaining)
            val updated = records + (kind to null)
            if (persist(config, updated)) CredentialResult.Success else CredentialResult.StorageError
        }
    }

    private fun loadIfNeeded() {
        if (state.value.loaded) return
        try {
            val legacy = decodeConfig(prefs.getString(KEY_CONFIG, null))
            CredentialKind.entries.forEach { kind -> records[kind] = prefs.getString(keyFor(kind), null) }
            val statuses = CredentialKind.entries.associateWith { kind ->
                when {
                    records[kind] == null -> CredentialStatus.ABSENT
                    vault.isValidRecord(records.getValue(kind)!!) -> CredentialStatus.READY
                    else -> CredentialStatus.CORRUPT
                }
            }
            // A device without biometrics must not keep a policy it can never satisfy.
            val degraded = !biometricSupported() && legacy.config.usesBiometric()
            val state = SecurityState(
                loaded = true,
                config = if (degraded) legacy.config.withoutBiometric() else legacy.config,
                pinStatus = statuses.getValue(CredentialKind.PIN),
                patternStatus = statuses.getValue(CredentialKind.PATTERN),
            )
            val corrupt = statuses.values.any { it == CredentialStatus.CORRUPT }
            val orphaned = state.config.methods().any { method ->
                method.credential?.let { state.statusOf(it) != CredentialStatus.READY } == true
            } || (state.config.allowCredentialFallback && !state.hasAnyCredential())
            mutableState.value = state.copy(storageError = corrupt || orphaned)
            // A damaged record or policy must never look like an unprotected fresh installation.
            if (corrupt || orphaned) return
            if (legacy.migrated || degraded || prefs.contains(LEGACY_KEY_PASSWORD)) {
                // The free-text password is retired: rewrite the policy and drop its record.
                val config = if (state.hasAnyCredential()) state.config else state.config.withoutCredentials()
                if (!persist(config, records)) return
                prefs.edit().remove(LEGACY_KEY_PASSWORD).apply()
            }
        } catch (_: Exception) {
            mutableState.value = SecurityState(loaded = true, storageError = true)
        }
    }

    private fun verifyLocked(kind: CredentialKind, secret: CharArray): CredentialResult {
        if (state.value.storageError) return CredentialResult.StorageError
        if (!Credentials.isValid(kind, secret)) return CredentialResult.InvalidFormat
        val record = records[kind] ?: return CredentialResult.Unavailable
        val remaining = retryAt - elapsedRealtime()
        if (remaining > 0) return CredentialResult.RetryAfter(remaining)
        if (vault.verify(secret, record)) {
            failures = 0
            retryAt = 0
            return CredentialResult.Success
        }
        failures = (failures + 1).coerceAtMost(10)
        if (failures >= 5) retryAt = elapsedRealtime() + (1_000L shl (failures - 5))
        return CredentialResult.Incorrect
    }

    private fun persist(config: SecurityConfig, updated: Map<CredentialKind, String?>): Boolean {
        val written = try {
            val editor = prefs.edit().putString(KEY_CONFIG, encodeConfig(config))
            CredentialKind.entries.forEach { kind ->
                val record = updated[kind]
                if (record == null) editor.remove(keyFor(kind)) else editor.putString(keyFor(kind), record)
            }
            editor.commit()
        } catch (_: Exception) {
            false
        }
        if (!written) {
            // commit() can change the in-memory prefs even when the disk write fails.
            mutableState.value = state.value.copy(storageError = true, revision = state.value.revision + 1)
            return false
        }
        CredentialKind.entries.forEach { records[it] = updated[it] }
        mutableState.value = SecurityState(
            loaded = true,
            config = config,
            pinStatus = statusFor(updated[CredentialKind.PIN]),
            patternStatus = statusFor(updated[CredentialKind.PATTERN]),
            revision = state.value.revision + 1,
        )
        return true
    }

    private fun statusFor(record: String?): CredentialStatus =
        if (record == null) CredentialStatus.ABSENT else CredentialStatus.READY

    private fun encodeConfig(config: SecurityConfig): String {
        val overrides = JSONObject()
        config.targetOverrides.forEach { (target, method) -> overrides.put(target.key, method.key) }
        return JSONObject().put("version", CONFIG_VERSION).put("default", config.defaultMethod.key)
            .put("fallback", config.allowCredentialFallback).put("targets", overrides).toString()
    }

    private class Decoded(val config: SecurityConfig, val migrated: Boolean)

    private fun decodeConfig(raw: String?): Decoded {
        if (raw == null) return Decoded(SecurityConfig.create(), false)
        val json = JSONObject(raw)
        val version = requireNotNull(json.get("version") as? Int) { "Invalid authentication policy version" }
        require(version in 1..CONFIG_VERSION) { "Unsupported authentication policy version" }
        val storedDefault = json.getString("default")
        val default = requireNotNull(
            if (version == 1) AuthMethod.fromStoredKey(storedDefault) else AuthMethod.fromKey(storedDefault)
        ) { "Unknown authentication method" }
        val targets = json.getJSONObject("targets")
        val overrides = buildMap {
            targets.keys().forEach { key ->
                val storedMethod = targets.getString(key)
                val method = requireNotNull(
                    if (version == 1) AuthMethod.fromStoredKey(storedMethod) else AuthMethod.fromKey(storedMethod)
                ) { "Unknown authentication override" }
                if (version < CONFIG_VERSION && key == "module_manage") {
                    put(SecurityTarget.MODULE_UNINSTALL, method)
                    put(SecurityTarget.MODULE_DISABLE, method)
                } else if (version < CONFIG_VERSION && key == "settings") {
                    // Settings is no longer a protected trigger.
                } else {
                    val target = requireNotNull(SecurityTarget.fromKey(key)) { "Unknown authentication target" }
                    put(target, method)
                }
            }
        }
        val migrated = version != CONFIG_VERSION
        val fallback = requireNotNull(json.get("fallback") as? Boolean) { "Invalid credential fallback policy" } &&
            version >= 2
        return Decoded(SecurityConfig.create(default, overrides, fallback), migrated)
    }

    private fun keyFor(kind: CredentialKind): String = when (kind) {
        CredentialKind.PIN -> KEY_PIN
        CredentialKind.PATTERN -> KEY_PATTERN
    }

    companion object {
        const val PREFS_NAME = "authentication"
        private const val CONFIG_VERSION = 3
        private const val KEY_CONFIG = "config"
        private const val KEY_PIN = "pin_record"
        private const val KEY_PATTERN = "pattern_record"
        private const val LEGACY_KEY_PASSWORD = "password_record"
    }
}
