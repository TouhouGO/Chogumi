package io.github.touhougo.chogumi.core.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 分享出去的正文：标题一行、链接一行；标题还没到（空白）时只有链接。
 */
fun shareTextOf(title: String, url: String): String =
    if (title.isBlank()) url else "$title\n$url"

/**
 * 把一个网页链接交给系统分享面板（页面 ⋮ 的「分享」）。正文见 [shareTextOf]；标题同时放进 `EXTRA_TITLE`，
 * 系统面板顶上的预览显示它。
 */
fun Context.shareLink(title: String, url: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, shareTextOf(title, url))
        .putExtra(Intent.EXTRA_TITLE, title.ifBlank { url })
    openShareSheet(send)
}

/** 把一个文件（[uri] 由 FileProvider 给出，类型 [mimeType]）交给系统分享面板，面板弹出为 true（图片查看器的「分享」）。 */
fun Context.shareFile(uri: Uri, mimeType: String): Boolean {
    val send = Intent(Intent.ACTION_SEND)
        .setType(mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    return openShareSheet(send)
}

/**
 * 系统分享面板，**全 App 唯一的分享出口**（门禁 check_web_links.py：`ACTION_SEND` 与 `createChooser` 只在本文件）。
 * 各处自己拼分享 Intent，正文格式、文件授权就各写各的。
 */
private fun Context.openShareSheet(send: Intent): Boolean = try {
    startActivity(Intent.createChooser(send, null))
    true
} catch (_: ActivityNotFoundException) {
    false
}
