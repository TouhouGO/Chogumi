package io.github.touhougo.chogumi.core.reader

import android.annotation.SuppressLint
import android.graphics.Color
import android.content.Context
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.isDarkThemeActive

/**
 * 发帖、回复前的人机验证（Cloudflare Turnstile）：对话框里一个 WebView，直接挂 Bangumi 的 Turnstile 组件，
 * 拿到令牌交给 [onVerified]（只交一次），随后由调用方连同正文一起提交。
 *
 * 组件页以 `next.bgm.tv` 为源加载（`loadDataWithBaseURL`），站点密钥与网页回复框是同一个，服务端校验令牌时认的就是这个源；
 * 不借道网页的跳转回调，不需要 Bangumi 为 App 另开白名单。组件加载失败（断网、脚本被拦）或验证出错时换成一行说明和「重试」。
 * 页面里不许跳转：组件内的链接点了也不离开这一页。
 *
 * 页面把结果发回 App 只走 `WebViewCompat.addWebMessageListener`：桥只注入 `next.bgm.tv` 源的 frame，且只认主 frame 的消息，
 * Cloudflare 的第三方 iframe 拿不到它（`addJavascriptInterface` 对页面里所有 frame 都可见，不能按源限制）。
 * 系统 WebView 太旧、不支持这个接口时，组件按加载失败处理。
 */
@Composable
fun TurnstileDialog(onDismiss: () -> Unit, onVerified: (String) -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val dark = isDarkThemeActive()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.verification)) },
        text = {
            if (failed) {
                Text(stringResource(R.string.turnstile_unreachable))
            } else {
                key(attempt) { TurnstileWidget(dark = dark, onVerified = onVerified, onFailed = { failed = true }) }
            }
        },
        confirmButton = {
            if (failed) {
                TextButton(onClick = {
                    failed = false
                    attempt++
                }) { Text(stringResource(R.string.retry)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 组件本身：宽度随对话框，按 Turnstile 标准尺寸（300 × 65）的比例定高，页面 viewport 定为 300 宽，窄屏整体缩放。 */
@Composable
private fun TurnstileWidget(dark: Boolean, onVerified: (String) -> Unit, onFailed: () -> Unit) {
    val latestVerified by rememberUpdatedState(onVerified)
    val latestFailed by rememberUpdatedState(onFailed)
    AndroidView(
        modifier = Modifier.fillMaxWidth().aspectRatio(WIDGET_WIDTH.toFloat() / WIDGET_HEIGHT),
        factory = { context ->
            // 系统 WebView 不可用（被停用、正在更新）时创建会抛：走失败分支，界面显示「重试」
            runCatching { turnstileWebView(context, dark, { latestVerified(it) }, { latestFailed() }) }
                .getOrElse { View(context).also { placeholder -> placeholder.post { latestFailed() } } }
        },
        onRelease = { (it as? WebView)?.destroy() },
    )
}

/** 建好并开始加载验证页的 WebView；系统 WebView 不可用时创建或取 CookieManager 会抛，由调用方接住。 */
@SuppressLint("SetJavaScriptEnabled")
private fun turnstileWebView(context: Context, dark: Boolean, onVerified: (String) -> Unit, onFailed: () -> Unit): WebView =
    WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.isForMainFrame
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                this,
                BRIDGE_NAME,
                setOf(TURNSTILE_ORIGIN),
                TurnstileListener(onVerified = onVerified, onFailed = onFailed),
            )
            loadDataWithBaseURL(TURNSTILE_PAGE, turnstilePage(dark), "text/html", "utf-8", null)
        } else {
            post { onFailed() }
        }
    }

/** 页面发回的一条结果。 */
internal sealed interface TurnstileMessage {
    data class Verified(val token: String) : TurnstileMessage
    data object Failed : TurnstileMessage
}

/**
 * 页面消息 → 结果：只认主 frame（组件页本身）发的，iframe 里的脚本即使拿到桥，发来的也一律不认；
 * 认不出的消息、空令牌都不算。
 */
internal fun turnstileMessageOf(data: String?, isMainFrame: Boolean): TurnstileMessage? = when {
    !isMainFrame || data == null -> null
    data == FAILED_MESSAGE -> TurnstileMessage.Failed
    data.startsWith(VERIFIED_PREFIX) -> data.removePrefix(VERIFIED_PREFIX).takeIf { it.isNotBlank() }?.let(TurnstileMessage::Verified)
    else -> null
}

/** 页面消息的接收方：回调在主线程上；令牌只交一次，交过之后的出错不再报。 */
private class TurnstileListener(private val onVerified: (String) -> Unit, private val onFailed: () -> Unit) : WebViewCompat.WebMessageListener {
    private var done = false

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        if (done) return
        when (val result = turnstileMessageOf(message.data, isMainFrame)) {
            is TurnstileMessage.Verified -> {
                done = true
                onVerified(result.token)
            }
            TurnstileMessage.Failed -> onFailed()
            null -> Unit
        }
    }
}

/**
 * 挂组件的页面：显式渲染（脚本加载完回调 `onTurnstileLoad`），不自动重试（出错交给对话框的「重试」），
 * 令牌过期就地重置让用户再点一次。主题跟着 App 的明暗走。
 */
private fun turnstilePage(dark: Boolean): String = """
<!doctype html>
<html>
<head>
<meta name="viewport" content="width=$WIDGET_WIDTH, user-scalable=no">
<style>html, body { margin: 0; background: transparent; }</style>
<script>
function onTurnstileLoad() {
  turnstile.render('#widget', {
    sitekey: '$TURNSTILE_SITE_KEY',
    theme: '${if (dark) "dark" else "light"}',
    retry: 'never',
    callback: function (token) { $BRIDGE_NAME.postMessage('$VERIFIED_PREFIX' + token); },
    'error-callback': function () { $BRIDGE_NAME.postMessage('$FAILED_MESSAGE'); return true; },
    'expired-callback': function () { turnstile.reset('#widget'); }
  });
}
</script>
<script src="$TURNSTILE_SCRIPT" async defer onerror="$BRIDGE_NAME.postMessage('$FAILED_MESSAGE')"></script>
</head>
<body><div id="widget"></div></body>
</html>
""".trimIndent()

/** Bangumi 回复框用的 Turnstile 站点密钥（网页与官方客户端同一个）。 */
private const val TURNSTILE_SITE_KEY = "0x4AAAAAAABkMYinukE8nzYS"

/** 组件页的源：站点密钥允许的域名之一，服务端也只认这里签出的令牌；页面的桥也只注入这个源。 */
private const val TURNSTILE_ORIGIN = "https://next.bgm.tv"
private const val TURNSTILE_PAGE = "$TURNSTILE_ORIGIN/turnstile"

private const val TURNSTILE_SCRIPT = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=onTurnstileLoad"
private const val BRIDGE_NAME = "ChogumiTurnstile"
private const val VERIFIED_PREFIX = "verified:"
private const val FAILED_MESSAGE = "failed"

/** Turnstile 标准尺寸（CSS 像素）。 */
private const val WIDGET_WIDTH = 300
private const val WIDGET_HEIGHT = 65
