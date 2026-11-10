package io.github.touhougo.chogumi.core.netroute

import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.network.OkHttpCustomizer
import io.github.touhougo.chogumi.core.network.createImageOkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 「检查连接」测的站点：应用实际连的那几个 bgm.tv 主机，各对应一块功能。 */
enum class CheckSite(val host: String) {
    WEB("bgm.tv"),
    API("api.bgm.tv"),
    NEXT("next.bgm.tv"),
    IMAGES("lain.bgm.tv"),
}

/**
 * 「检查连接」：按现在的线路给 [CheckSite] 的一个站点发一次 HTTPS 请求（HEAD），量从发出到收到响应头的毫秒数，含拨号与握手
 * （自己的连接池，每轮开始前清空：测的是一次真正的连接，不是复用应用开着的连接）。走的是应用发请求的那条路：网络层的全部
 * 调整（直连、代理）都套上。只看一眼，不改任何设置。
 */
class RouteCheck internal constructor(customizers: List<OkHttpCustomizer>) {
    private val pool = ConnectionPool()
    private val client: OkHttpClient = createImageOkHttpClient(customizers).newBuilder().connectionPool(pool).build()

    /** 新的一轮开始：清掉上一轮的连接。 */
    fun startRound() = pool.evictAll()

    /** 测一个站点：连上了是毫秒数，连不上是失败。 */
    suspend fun measure(site: CheckSite): Outcome<Long> = outcomeOf {
        val started = System.nanoTime()
        client.newCall(headOf(site.host)).await().close()
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    }
}

/** 经候选的代理试连一次 bgm.tv，不经网络层的线路调整（这一组还没用上）。 */
internal object OkHttpProxyProbe : ProxyProbe {
    private val base by lazy { createImageOkHttpClient(emptyList()) }

    override suspend fun reach(server: ProxyServer): Outcome<Unit> = outcomeOf {
        base.newBuilder().proxy(server.toProxy()).build().newCall(headOf(CheckSite.WEB.host)).await().close()
    }
}

private fun headOf(host: String) = Request.Builder().url("https://$host/").head().build()

/** 发出 [Call]，等到响应头；协程取消时取消这次调用。 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response) { _, value, _ -> value.close() }
        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
    })
}
