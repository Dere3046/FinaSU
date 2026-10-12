package me.weishu.kernelsu.data.security

import kotlinx.coroutines.flow.StateFlow

enum class CredentialStatus { ABSENT, READY, CORRUPT }

data class SecurityState(
    val loaded: Boolean = false,
    val config: SecurityConfig = SecurityConfig.create(),
    val pinStatus: CredentialStatus = CredentialStatus.ABSENT,
    val patternStatus: CredentialStatus = CredentialStatus.ABSENT,
    val storageError: Boolean = false,
    val revision: Long = 0,
) {
    fun statusOf(kind: CredentialKind): CredentialStatus = when (kind) {
        CredentialKind.PIN -> pinStatus
        CredentialKind.PATTERN -> patternStatus
    }

    fun hasAnyCredential(): Boolean = CredentialKind.entries.any { statusOf(it) == CredentialStatus.READY }
}

sealed interface CredentialResult {
    data object Success : CredentialResult
    data object Incorrect : CredentialResult
    data object Unavailable : CredentialResult
    data object InvalidFormat : CredentialResult
    data object AlreadySet : CredentialResult
    data object StorageError : CredentialResult
    data class RetryAfter(val millis: Long) : CredentialResult
}

interface SecurityRepository {
    val state: StateFlow<SecurityState>
    suspend fun initialize()
    suspend fun updateConfig(config: SecurityConfig): Boolean
    suspend fun verify(kind: CredentialKind, secret: CharArray): CredentialResult
    suspend fun set(kind: CredentialKind, secret: CharArray): CredentialResult
    suspend fun change(kind: CredentialKind, oldSecret: CharArray, newSecret: CharArray): CredentialResult
    suspend fun clear(kind: CredentialKind, secret: CharArray): CredentialResult
}
