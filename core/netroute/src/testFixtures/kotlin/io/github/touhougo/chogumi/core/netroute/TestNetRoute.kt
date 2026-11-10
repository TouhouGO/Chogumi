package io.github.touhougo.chogumi.core.netroute

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.foundation.Outcome
import kotlinx.coroutines.CoroutineScope

/**
 * feature 模块测试用的 [NetRoute]：设备不支持直连（不起核心），网络不变，WebView 不管；试连按 [reach] 答。存储由测试给，
 * 冷启动的设置提前写进去。[NetRoute.run] 由调用方在 [scope] 里起。
 */
fun testNetRoute(
    store: DataStore<Preferences>,
    scope: CoroutineScope,
    reach: suspend (ProxyServer) -> Outcome<Unit>,
): NetRoute = NetRoute(
    store,
    scope,
    BuiltinEdgeAddresses,
    supported = false,
    core = object : DirectCore {
        override fun start(addresses: List<String>, config: ByteArray, edgeBudgetMillis: Long, onConfig: (ByteArray) -> Unit, log: ((String) -> Unit)?) =
            error("测试里的 NetRoute 不起直连核心")

        override fun resetNetwork() = Unit

        override fun stop() = Unit
    },
    networkChanges = { },
    log = null,
    probe = ProxyProbe(reach),
    webView = { },
)

/** 测试里的「检查连接」：不套线路调整（feature 测试不发它的请求）。 */
fun testRouteCheck(): RouteCheck = RouteCheck(emptyList())
