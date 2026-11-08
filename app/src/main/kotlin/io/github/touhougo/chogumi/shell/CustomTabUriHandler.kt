package io.github.touhougo.chogumi.shell

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.platform.UriHandler
import androidx.core.net.toUri

/**
 * 全 App 打开网页的唯一方式：[AppShell] 把它放进 `LocalUriHandler`，页面只调 `LocalUriHandler.current.openUri`。
 *
 * 用 Custom Tab 打开（停在 App 里，返回即回到原页；登录授权也走这里）。不绑会话的 Custom Tab 就是一个带附加参数的
 * `ACTION_VIEW`：默认浏览器不支持 Custom Tab 时它自己按普通网页打开，所以不需要第二条回退；
 * 只有设备上一个能打开网址的应用都没有时才失败，这时什么也不做。
 *
 * 地址在这里统一校验（[webUrlOf]）：没写协议的补 `https://`，不是 http(s) 的不打开，调用方不各自判断。
 *
 * 清单里登记的 Bangumi 链接 Chogumi 自己也接（[LinkActivity]）：要在浏览器里看的这类地址（应用里没有的页）不指定应用就会被
 * 系统送回来、再交给浏览器，绕成圈。所以这个地址 Chogumi 自己也接时，指名交给浏览器（[browserPackageOf]）；别的地址照旧由系统
 * 选（视频链接仍去视频应用）。
 */
class CustomTabUriHandler(private val context: Context) : UriHandler {
    override fun openUri(uri: String) {
        val url = webUrlOf(uri)?.toUri() ?: return
        val tab = CustomTabsIntent.Builder().build()
        if (handledBySelf(url)) tab.intent.setPackage(browserPackageOf(context) ?: return)
        try {
            tab.launchUrl(context, url)
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun handledBySelf(url: Uri): Boolean =
        context.packageManager.queryIntentActivities(webIntent(url), PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.packageName == context.packageName }
}

/** 打开网页的浏览器：系统默认的那个；没设默认（会弹选择器）或默认就是 Chogumi 时，取第一个能开网页的别的应用。 */
private fun browserPackageOf(context: Context): String? {
    val pm = context.packageManager
    val probe = webIntent(BROWSER_PROBE.toUri())
    val default = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    if (default != null && default != RESOLVER_PACKAGE && default != context.packageName) return default
    return pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        .map { it.activityInfo.packageName }
        .firstOrNull { it != context.packageName }
}

private fun webIntent(url: Uri) = Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE)

/** 没有域名的网址只有浏览器接（应用登记的链接都带域名）：拿它问「谁是浏览器」。 */
private const val BROWSER_PROBE = "https://"

/** 没设默认、有几个应用可选时，[PackageManager.resolveActivity] 答的是系统的选择器。 */
private const val RESOLVER_PACKAGE = "android"

private val SCHEME_WITH_SLASHES = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

/** 带协议但不是 `//` 形式（`mailto:`、`javascript:`），冒号后是端口号的 `example.com:8080` 除外。 */
private val OPAQUE_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:(?!\\d+(?:/|$))")

/**
 * 一个要在浏览器里打开的地址：http(s) 原样，`//host/…` 与没写协议的 `host/…` 补 `https://`，其余协议（`file:`、`intent:`、
 * `javascript:`、`mailto:`）为 null。
 */
internal fun webUrlOf(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    SCHEME_WITH_SLASHES.find(text)?.let { match ->
        return if (match.groupValues[1].lowercase() in WEB_SCHEMES) text else null
    }
    return when {
        text.startsWith("//") -> "https:$text"
        OPAQUE_SCHEME.containsMatchIn(text) -> null
        else -> "https://$text"
    }
}

private val WEB_SCHEMES = setOf("http", "https")
