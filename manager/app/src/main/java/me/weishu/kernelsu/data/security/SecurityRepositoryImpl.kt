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

/** One application-scoped instance owns both policies and credentials in the same prefs file. */
class SecurityRepositoryImpl(
    private val prefs: SharedPreferences,
    private val vault: PasswordVault = PasswordVault(),
    private val worker: CoroutineDispatcher = Dispatchers.IO,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : SecurityRepository {
    private val mutableState = MutableStateFlow(SecurityState())
    override val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var passwordRecord: String? = null
    private var failures = 0
    private var retryAt = 0L

    override suspend fun initialize() = withContext(worker) {
        mutex.withLock { loadIfNeeded() }
    }

    override suspend fun updateConfig(config: SecurityConfig): Boolean = withContext(worker) {
        mutex.withLock {
            loadIfNeeded()
            if (state.value.storageError ||
                (config.needsPassword() && state.value.passwordStatus != PasswordStatus.READY)
            ) return@withLock false
            persist(config, passwordRecord)
        }
    }

    override suspend fun verifyPassword(password: CharArray): PasswordResult = withContext(worker) {
        mutex.withLock { loadIfNeeded(); verifyLocked(password) }
    }

    override suspend fun setPassword(password: CharArray): PasswordResult = withContext(worker) {
        mutex.withLock {
            loadIfNeeded()
            if (state.value.storageError) return@withLock PasswordResult.StorageError
            if (passwordRecord != null) return@withLock PasswordResult.AlreadySet
            if (!validLength(password)) return@withLock PasswordResult.InvalidLength
            val record = vault.create(password)
            currentCoroutineContext().ensureActive()
            if (persist(state.value.config, record)) PasswordResult.Success else PasswordResult.StorageError
        }
    }

    override suspend fun changePassword(oldPassword: CharArray, newPassword: CharArray): PasswordResult =
        withContext(worker) {
            mutex.withLock {
                loadIfNeeded()
                val verified = verifyLocked(oldPassword)
                if (verified != PasswordResult.Success) return@withLock verified
                if (!validLength(newPassword)) return@withLock PasswordResult.InvalidLength
                val record = vault.create(newPassword)
                currentCoroutineContext().ensureActive()
                if (persist(state.value.config, record)) PasswordResult.Success else PasswordResult.StorageError
            }
        }

    override suspend fun clearPassword(oldPassword: CharArray): PasswordResult = withContext(worker) {
        mutex.withLock {
            loadIfNeeded()
            val verified = verifyLocked(oldPassword)
            if (verified != PasswordResult.Success) return@withLock verified
            currentCoroutineContext().ensureActive()
            if (persist(state.value.config.withoutPassword(), null)) PasswordResult.Success
            else PasswordResult.StorageError
        }
    }

    private fun loadIfNeeded() {
        if (state.value.loaded) return
        try {
            val config = decodeConfig(prefs.getString(KEY_CONFIG, null))
            passwordRecord = prefs.getString(KEY_PASSWORD, null)
            val status = when {
                passwordRecord == null -> PasswordStatus.ABSENT
                vault.isValidRecord(passwordRecord!!) -> PasswordStatus.READY
                else -> PasswordStatus.CORRUPT
            }
            mutableState.value = SecurityState(
                loaded = true,
                config = config,
                passwordStatus = status,
                storageError = status == PasswordStatus.CORRUPT ||
                    (config.needsPassword() && status != PasswordStatus.READY),
            )
        } catch (_: Exception) {
            // A damaged policy must never look like an unprotected fresh installation.
            mutableState.value = SecurityState(loaded = true, storageError = true)
        }
    }

    private fun verifyLocked(password: CharArray): PasswordResult {
        if (state.value.storageError) return PasswordResult.StorageError
        val record = passwordRecord ?: return PasswordResult.Unavailable
        val remaining = retryAt - elapsedRealtime()
        if (remaining > 0) return PasswordResult.RetryAfter(remaining)
        if (vault.verify(password, record)) {
            failures = 0
            retryAt = 0
            return PasswordResult.Success
        }
        failures = (failures + 1).coerceAtMost(10)
        if (failures >= 5) retryAt = elapsedRealtime() + (1_000L shl (failures - 5))
        return PasswordResult.Incorrect
    }

    private fun persist(config: SecurityConfig, record: String?): Boolean {
        val written = try {
            val editor = prefs.edit().putString(KEY_CONFIG, encodeConfig(config))
            if (record == null) editor.remove(KEY_PASSWORD) else editor.putString(KEY_PASSWORD, record)
            editor.commit()
        } catch (_: Exception) {
            false
        }
        if (!written) {
            // commit() can change the in-memory prefs even when the disk write fails.
            mutableState.value = state.value.copy(storageError = true, revision = state.value.revision + 1)
            return false
        }
        passwordRecord = record
        mutableState.value = SecurityState(
            loaded = true,
            config = config,
            passwordStatus = if (record == null) PasswordStatus.ABSENT else PasswordStatus.READY,
            revision = state.value.revision + 1,
        )
        return true
    }

    private fun validLength(password: CharArray) =
        password.size in PasswordVault.MIN_PASSWORD_LENGTH..PasswordVault.MAX_PASSWORD_LENGTH

    private fun encodeConfig(config: SecurityConfig): String {
        val overrides = JSONObject()
        config.targetOverrides.forEach { (target, method) -> overrides.put(target.key, method.key) }
        return JSONObject().put("version", 1).put("default", config.defaultMethod.key)
            .put("fallback", config.allowPasswordFallback).put("targets", overrides).toString()
    }

    private fun decodeConfig(raw: String?): SecurityConfig {
        if (raw == null) return SecurityConfig.create()
        val json = JSONObject(raw)
        require(json.getInt("version") == 1)
        val default = requireNotNull(AuthMethod.fromKey(json.getString("default")))
        val targets = json.getJSONObject("targets")
        val overrides = buildMap {
            targets.keys().forEach { key ->
                val target = requireNotNull(SecurityTarget.fromKey(key))
                put(target, requireNotNull(AuthMethod.fromKey(targets.getString(key))))
            }
        }
        return SecurityConfig.create(default, overrides, json.getBoolean("fallback"))
    }

    companion object {
        const val PREFS_NAME = "authentication"
        private const val KEY_CONFIG = "config"
        private const val KEY_PASSWORD = "password_record"
    }
}
