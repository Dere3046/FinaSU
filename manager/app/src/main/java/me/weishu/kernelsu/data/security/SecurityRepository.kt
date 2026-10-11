package me.weishu.kernelsu.data.security

import kotlinx.coroutines.flow.StateFlow

enum class PasswordStatus { ABSENT, READY, CORRUPT }

data class SecurityState(
    val loaded: Boolean = false,
    val config: SecurityConfig = SecurityConfig.create(),
    val passwordStatus: PasswordStatus = PasswordStatus.ABSENT,
    val storageError: Boolean = false,
    val revision: Long = 0,
)

sealed interface PasswordResult {
    data object Success : PasswordResult
    data object Incorrect : PasswordResult
    data object Unavailable : PasswordResult
    data object InvalidLength : PasswordResult
    data object AlreadySet : PasswordResult
    data object StorageError : PasswordResult
    data class RetryAfter(val millis: Long) : PasswordResult
}

interface SecurityRepository {
    val state: StateFlow<SecurityState>
    suspend fun initialize()
    suspend fun updateConfig(config: SecurityConfig): Boolean
    suspend fun verifyPassword(password: CharArray): PasswordResult
    suspend fun setPassword(password: CharArray): PasswordResult
    suspend fun changePassword(oldPassword: CharArray, newPassword: CharArray): PasswordResult
    suspend fun clearPassword(oldPassword: CharArray): PasswordResult
}
