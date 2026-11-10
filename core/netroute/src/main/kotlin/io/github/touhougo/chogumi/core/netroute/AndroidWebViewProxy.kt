package io.github.touhougo.chogumi.core.netroute

import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

/**
 * 应用里 WebView（回帖的人机验证页）的代理，经 androidx.webkit 的 [ProxyController]：代理线路连上时设上，其余时候清掉（跟系统）。
 * 只管这个进程里的 WebView；浏览器登录与「在浏览器打开」由浏览器自己联网，管不到。WebView 太旧、不支持代理覆盖时什么也不做。
 * 在主线程上依次设，后设的盖住先设的。
 */
internal object AndroidWebViewProxy : WebViewProxy {
    private val main = Handler(Looper.getMainLooper())
    private val direct = Executor { it.run() }

    override fun use(server: ProxyServer?) {
        main.post {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return@post
            val controller = ProxyController.getInstance()
            if (server == null) {
                controller.clearProxyOverride(direct) {}
            } else {
                controller.setProxyOverride(ProxyConfig.Builder().addProxyRule(ruleOf(server)).build(), direct) {}
            }
        }
    }
}

/** WebView 的代理规则：`http://主机:端口` 或 `socks://主机:端口`（WebView 的 socks 是 SOCKS5）；IPv6 加方括号。 */
internal fun ruleOf(server: ProxyServer): String {
    val scheme = if (server.type == ProxyType.HTTP) "http" else "socks"
    val host = if (':' in server.host) "[${server.host}]" else server.host
    return "$scheme://$host:${server.port}"
}
