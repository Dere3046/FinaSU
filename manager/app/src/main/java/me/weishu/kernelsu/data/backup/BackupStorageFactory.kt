package me.weishu.kernelsu.data.backup

import me.weishu.kernelsu.data.backup.webdav.OkHttpWebDavTransport
import me.weishu.kernelsu.data.backup.webdav.WebDavBackupStorage
import okhttp3.OkHttpClient

internal fun webDavBackupStorage(
    client: OkHttpClient,
    secrets: SecretStore,
    url: String,
    username: String,
    path: String,
): WebDavBackupStorage? {
    val baseUrl = url.trim().takeIf { it.isNotEmpty() } ?: return null
    val transport = OkHttpWebDavTransport(client, username, secrets.get(WEBDAV_PASSWORD_SECRET_KEY).orEmpty())
    return WebDavBackupStorage(transport, WebDavPaths.joinBase(baseUrl, path))
}
