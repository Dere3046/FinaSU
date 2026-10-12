package me.weishu.kernelsu.data.repository

import me.weishu.kernelsu.data.model.Plugin

interface PluginRepository {
    suspend fun getPlugins(): Result<List<Plugin>>

    suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit>

    suspend fun install(path: String): Result<Unit>

    suspend fun uninstall(id: String): Result<Unit>

    suspend fun getLog(id: String): Result<String>

    suspend fun getConfig(id: String): Result<Map<String, String>>

    suspend fun setConfig(id: String, key: String, value: String): Result<Unit>

    suspend fun run(id: String, callback: String): Result<Unit>
}
