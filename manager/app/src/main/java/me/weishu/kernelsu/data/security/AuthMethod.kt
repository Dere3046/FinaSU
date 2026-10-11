package me.weishu.kernelsu.data.security

enum class AuthMethod(val key: String) {
    NONE("none"),
    BIOMETRIC("biometric"),
    PASSWORD("password");

    companion object {
        fun fromKey(key: String): AuthMethod? = entries.firstOrNull { it.key == key }
    }
}
