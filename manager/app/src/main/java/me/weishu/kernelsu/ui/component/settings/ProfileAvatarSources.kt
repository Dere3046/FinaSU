package me.weishu.kernelsu.ui.component.settings

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.material.ExpressiveDialog
import me.weishu.kernelsu.ui.component.material.FolkTextButton
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedListItem
import me.weishu.kernelsu.ui.component.material.SegmentedTextField
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest

/**
 * Where a custom profile avatar comes from. Mirrors FolkPatch's chooser: the device, a QQ number,
 * or a Gravatar address.
 */
internal enum class AvatarSource(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    Local(R.string.profile_avatar_source_local, Icons.Outlined.Image),
    Qq(R.string.profile_avatar_source_qq, Icons.Outlined.Public),
    Gravatar(R.string.profile_avatar_source_gravatar, Icons.Outlined.AlternateEmail),
}

/**
 * Asks which source the custom avatar should be pulled from. Rendering it as a segmented list keeps
 * it consistent with the editor sheet the user opened it from.
 */
@Composable
internal fun AvatarSourceDialog(
    onDismiss: () -> Unit,
    onSelect: (AvatarSource) -> Unit,
) {
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_avatar_source_title)) },
        text = {
            SegmentedColumn(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AvatarSource.entries.forEach { source ->
                    item(key = source.name) {
                        SegmentedListItem(
                            onClick = { onSelect(source) },
                            headlineContent = { Text(stringResource(source.titleRes)) },
                            leadingContent = {
                                Icon(imageVector = source.icon, contentDescription = null)
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            FolkTextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

/** Collects the QQ number or email that identifies a remote avatar. */
@Composable
internal fun AvatarIdDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            SegmentedColumn {
                item {
                    SegmentedTextField(
                        label = label,
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        placeholder = null,
                    )
                }
            }
        },
        confirmButton = {
            FolkTextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            FolkTextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

/** QQ exposes a head image by number. */
internal fun qqAvatarUrl(qq: String): String =
    "https://q.qlogo.cn/headimg_dl?dst_uin=${Uri.encode(qq)}&spec=640&img_type=jpg"

/**
 * Gravatar keys off the md5 of the lower-cased address. `d=404` makes an unknown address fail
 * instead of returning a placeholder, so the caller can report the failure.
 */
internal fun gravatarUrl(email: String): String {
    val digest = MessageDigest.getInstance("MD5")
        .digest(email.trim().lowercase().toByteArray(Charsets.UTF_8))
    val hash = digest.joinToString("") { "%02x".format(it) }
    return "https://www.gravatar.com/avatar/$hash?s=640&d=404"
}

/**
 * Downloads a remote avatar into a cache file and returns its `file://` URI.
 *
 * The editor keeps avatar choices as a draft, so the download lands in the cache and only becomes
 * the stored avatar when the user saves the sheet; [ProfileConfig.setAvatar] then copies it into
 * app storage. A failed download returns null so the caller can report it.
 */
internal suspend fun downloadAvatarPreview(context: Context, url: String): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "profile_avatar_download")
            if (!dir.isDirectory && !dir.mkdirs()) return@runCatching null
            val target = File(dir, "avatar_${System.currentTimeMillis()}")
            URL(url).openStream().use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            if (target.length() == 0L) {
                target.delete()
                return@runCatching null
            }
            Uri.fromFile(target).toString()
        }.getOrNull()
    }
