package io.github.touhougo.chogumi.shell

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 应用内更新的安装包交给系统安装器用的 FileProvider。单独一个子类：清单里同一个类只能登记一次，core:ui 已经用
 * `androidx.core.content.FileProvider` 登记了看图页分享。
 */
class UpdateFileProvider : FileProvider()

/** [UpdateFileProvider] 的 authority，与清单里的 `${applicationId}.updates` 是同一个。 */
private fun updateAuthorityOf(context: Context) = "${context.packageName}.updates"

/**
 * 把下好、校验过的安装包 [apk] 交给系统安装器，安装器弹出来为 true。没允许过 Chogumi「安装未知应用」时，系统先带用户去允许，
 * 回来接着装；这一步由系统做，这里不查权限。
 */
fun Context.installApk(apk: File): Boolean {
    val uri = FileProvider.getUriForFile(this, updateAuthorityOf(this), apk)
    val install = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, APK_MIME_TYPE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        startActivity(install)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
