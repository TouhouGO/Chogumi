package io.github.touhougo.chogumi.core.netroute

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.touhougo.chogumi.core.data.util.ObservedPreferences
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.network.REQUEST_TIMEOUT_MILLIS
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 连 bgm.tv 走哪条线路；同一时刻只有一条生效。 */
enum class RouteMode {
    /** 系统网络（含系统里配的代理、VPN）。 */
    SYSTEM,

    /** 直连：本机的直连核心用 ECH 加密要访问的站点名，经 Cloudflare 直接连到 bgm.tv。实验性。 */
    DIRECT,

    /** 代理：应用的请求都经用户填的那个 HTTP 或 SOCKS5 代理发出（[ProxyServer]）。 */
    PROXY,
}

/** 这个失败像是连不上 bgm.tv（连不上、超时、传输中被断开）：值得提示试试直连。 */
fun looksUnreachable(failure: Failure): Boolean =
    failure is Failure.Offline || failure is Failure.Timeout || failure is Failure.Interrupted

/** 直连核心连哪些 Cloudflare 边缘地址（"ip" 或 "ip:端口"），按顺序轮换。 */
fun interface EdgeAddresses {
    fun addresses(): List<String>
}

/** bgm.tv 在 Cloudflare 上的三个地址（2026-11-09 实测）。 */
object BuiltinEdgeAddresses : EdgeAddresses {
    override fun addresses() = listOf("104.26.8.23", "104.26.9.23", "172.67.73.67")
}

/** 要不要在连不上时提示「试试直连」：设备支持、且现在走的是系统网络。 */
fun interface DirectRouteHint {
    fun available(): Flow<Boolean>
}

/** 本机直连核心的端口与令牌。 */
internal data class CoreEndpoint(val port: Int, val token: String)

/** 一个请求此刻该怎么走。只有 [System] 放请求走系统网络。 */
internal sealed interface CoreRoute {
    /** 还没定下来（应用刚启动、设置还没读到）：请求失败，不先按系统网络发出。 */
    data object Undecided : CoreRoute

    /** 系统网络：请求原样发出。 */
    data object System : CoreRoute

    /** 交给本机核心。 */
    data class Core(val endpoint: CoreEndpoint) : CoreRoute

    /** 设置是直连、核心却没起来：请求失败（普通 SNI 会被拦，还会连累同一地址上之后的直连）。 */
    data object Unavailable : CoreRoute

    /** 经这个代理（所有请求，不只 bgm.tv）。 */
    data class Proxied(val server: ProxyServer) : CoreRoute

    /** 设置是代理、还没有连上的那一组：所有请求失败，不改走系统网络。 */
    data object ProxyUnavailable : CoreRoute
}

/** 「连接」一个代理的结果。 */
sealed interface ProxyConnect {
    /** 经它连上了 bgm.tv，已经换过去并存下。 */
    data object Connected : ProxyConnect

    /** 经它连不上 bgm.tv；线路与存下的那一组都没变。 */
    data class Unreachable(val failure: Failure) : ProxyConnect

    /** 连的期间关掉了代理（或换了线路）：结果不用。 */
    data object Withdrawn : ProxyConnect

    /** 连上了，但存不进设置；线路没变。 */
    data class NotSaved(val failure: Failure) : ProxyConnect
}

/** 经 [server] 试连一次 bgm.tv（生产是 [OkHttpProxyProbe]）：收到任何 HTTP 响应就算连上。 */
internal fun interface ProxyProbe {
    suspend fun reach(server: ProxyServer): Outcome<Unit>
}

/** 应用里的 WebView（回帖的人机验证页）用哪个代理；null 是跟系统（生产是 [AndroidWebViewProxy]）。 */
internal fun interface WebViewProxy {
    fun use(server: ProxyServer?)
}

/** 问一次「这个请求怎么走」。 */
internal fun interface CoreRoutes {
    fun current(): CoreRoute
}

/**
 * 每个边缘地址的拨号加握手预算：客户端一次请求的超时（core:network [REQUEST_TIMEOUT_MILLIS]）分成「地址数 + 1」份。
 * 核心把地址错开依次起拨，间隔按实测握手耗时算、至多半份预算（core/netroute/go/proxy/edges.go），最坏要 [coreWorstCaseMillis]
 * 才知道全都不行，比客户端一次请求的超时短：能用的地址总能在客户端放弃之前轮到。
 */
internal fun edgeBudgetMillis(edges: Int): Long = REQUEST_TIMEOUT_MILLIS / (edges + 1)

/** 核心最坏要多久才知道 [edges] 个地址全都连不上：最后一个地址在 (地址数-1) 个最长的错开（半份预算）之后起拨，再用满一份预算。 */
internal fun coreWorstCaseMillis(edges: Int): Long = edgeBudgetMillis(edges).let { budget -> (edges - 1) * (budget / 2) + budget }

/** 直连核心的起停（生产是 Go 核心 [GoDirectCore]）。[start] 起不来就抛异常。[log] 收核心的运行记录（每次握手一行），可以没有。 */
internal interface DirectCore {
    fun start(
        addresses: List<String>,
        config: ByteArray,
        edgeBudgetMillis: Long,
        onConfig: (ByteArray) -> Unit,
        log: ((String) -> Unit)?,
    ): CoreEndpoint

    /** 设备换了网络：核心忘掉各地址的好坏、关掉旧网络上的空闲连接。 */
    fun resetNetwork()

    fun stop()
}

/** 设备默认网络的变化（换 Wi-Fi、换移动网络）；[observe] 注册之后每次变化调一次 onChange。 */
fun interface NetworkChanges {
    fun observe(onChange: () -> Unit)
}

/**
 * 连 bgm.tv 的线路：设置（设备级，不跟账号走，登录之前就要用；默认 [RouteMode.SYSTEM]）、直连核心与代理。
 *
 * 模型（代码只照这几条做）：
 * - **线路状态只有一个来源** [state]，网络层每个请求经 [route] 读它；只有 [CoreRoute.System] 放请求走系统网络。还没定下来
 *   （[CoreRoute.Undecided]：启动后至多等 [READY_TIMEOUT_SECONDS] 秒，超时也算）、设置是直连而核心没起来（[CoreRoute.Unavailable]）、
 *   设置是代理而还没连上（[CoreRoute.ProxyUnavailable]）一样，请求失败——直连与代理失败都绝不退回系统网络。
 * - **线路只有一条**：[RouteMode] 一个值，直连与代理互斥；两个都关就是系统网络。
 * - **状态只由 [transition] 改**：开关直连与代理、「连接」一个代理、应用存着的设置，都经它，在同一把锁里起核心、存设置、停旧核心，
 *   全部成了才换状态。用户动手（[setMode]、[connectProxy]）失败时留在原状态、返回失败；应用存着的设置（[run]）时核心起不来就是
 *   [CoreRoute.Unavailable]。WebView 的代理跟着状态一起换（[WebViewProxy]）。
 * - **代理先连上才用**：打开代理开关只是选了代理（[CoreRoute.ProxyUnavailable]）；[connectProxy] 先经那一组试连 bgm.tv（[ProxyProbe]），
 *   连上了才换过去并存下，连不上时旧的那一组照用。冷启动时设置是代理，直接用存下的那一组（它连上过）；之后设置里的代理那一拍
 *   （用户打开开关写下的）不再换成存下的那一组，等试连成功。
 * - **设置只有一条流** [mode]，线路与界面都订它：读失败时发一拍系统网络，退避一会儿重新订阅，永不结束——存储恢复后所有订阅者
 *   一起跟上，界面显示的与实际线路始终是同一个值。[run] 照这条流应用，每次应用都放行等着的请求。
 *
 * 服务端给的 ECH 配置存在同一个 DataStore 里，下次起核心时直接用；失效时核心照旧被拒、换上新给的那份、再存一次。
 */
class NetRoute internal constructor(
    dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val edges: EdgeAddresses,
    private val supported: Boolean,
    private val core: DirectCore,
    private val networkChanges: NetworkChanges,
    private val log: ((String) -> Unit)?,
    private val probe: ProxyProbe,
    private val webView: WebViewProxy,
) {
    private val prefs = ObservedPreferences(dataStore)
    private val decided = CountDownLatch(1)
    private val transitions = Mutex()
    private val state = MutableStateFlow<CoreRoute>(CoreRoute.Undecided)
    private var running: CoreEndpoint? = null

    /** 设置里存的线路，线路与界面共用；读不出来时是系统网络，之后退避重新订阅（见类注释）。 */
    val mode: Flow<RouteMode> = prefs.data
        .map(::modeOf)
        .retryWhen { cause, attempt ->
            if (cause !is IOException) return@retryWhen false
            emit(RouteMode.SYSTEM)
            delay(resubscribeDelayMillis(attempt))
            true
        }
        .distinctUntilChanged()

    /** 存下的代理：连上过的那一组（关掉开关也留着，再打开时带出来）。读不出来时是 null。 */
    val proxy: Flow<ProxyServer?> = prefs.data
        .map(::proxyOf)
        .retryWhen { cause, attempt ->
            if (cause !is IOException) return@retryWhen false
            emit(null)
            delay(resubscribeDelayMillis(attempt))
            true
        }
        .distinctUntilChanged()

    /** 正在用的代理（已连上）；不是代理线路或还没连上时为 null。 */
    val activeProxy: Flow<ProxyServer?> = state.map { (it as? CoreRoute.Proxied)?.server }.distinctUntilChanged()

    /** 设备能不能用直连（核心只有 arm64 一份）；不能时「网络」页没有直连那一块，代理照常。 */
    val directSupported: Boolean get() = supported

    /** 设备支持直连、且现在走系统网络。 */
    val directAvailable: Flow<Boolean> = mode.map { supported && it == RouteMode.SYSTEM }.distinctUntilChanged()

    /** 设置是直连、核心却没起来。 */
    val directUnavailable: Flow<Boolean> = state.map { it == CoreRoute.Unavailable }.distinctUntilChanged()

    /** 跟着设置起停核心、设备换网络时让核心重新看地址好坏；应用启动时在应用作用域里调一次，一直跑。 */
    suspend fun run() {
        networkChanges.observe { scope.launch { transitions.withLock { if (running != null) core.resetNetwork() } } }
        mode.collect { transition(it, persist = false) }
    }

    /**
     * 用户换线路：成了才换（失败时留在原线路）。设置是直连而核心没起来时，再开一次就是「重试」。选代理只是打开开关：
     * 还没连上（[CoreRoute.ProxyUnavailable]），连哪一组经 [connectProxy]。
     */
    suspend fun setMode(mode: RouteMode): Outcome<Unit> = transition(mode, persist = true)

    /** 「连接」：先经 [server] 试连 bgm.tv，连上了才换过去并存下；期间关掉了代理则不用这个结果。 */
    suspend fun connectProxy(server: ProxyServer): ProxyConnect {
        when (val reached = probe.reach(server)) {
            is Outcome.Failed -> return ProxyConnect.Unreachable(reached.failure)
            is Outcome.Success -> Unit
        }
        return transitions.withLock {
            if (mode.first() != RouteMode.PROXY) return@withLock ProxyConnect.Withdrawn
            when (val saved = outcomeOf { prefs.edit { writeProxy(it, server) } }) {
                is Outcome.Failed -> ProxyConnect.NotSaved(saved.failure)
                is Outcome.Success -> {
                    publish(CoreRoute.Proxied(server))
                    ProxyConnect.Connected
                }
            }
        }
    }

    internal fun route(): CoreRoute {
        if (!decided.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) decided.countDown()
        return state.value
    }

    /** 唯一改 [state] 的地方。[persist] 为 true 是用户动手（要存设置、失败留原状），false 是应用存着的设置。 */
    private suspend fun transition(requestedMode: RouteMode, persist: Boolean): Outcome<Unit> = transitions.withLock {
        // 观察者收到的拍可能早于等待锁期间用户存下的线路：锁内重读当前快照，不用迟到的拍停掉新核心。
        val mode = if (persist) requestedMode else this.mode.first()
        val wasRunning = running != null
        val next: CoreRoute = when {
            mode == RouteMode.DIRECT && supported -> when (val started = startCore()) {
                is Outcome.Success -> CoreRoute.Core(started.value)
                is Outcome.Failed -> {
                    if (!persist) publish(CoreRoute.Unavailable)
                    return@withLock started
                }
            }
            // 用户打开开关时还没连上。应用存着的设置：已经在代理线路上（刚打开开关、已连上）就不动，不然那一拍会在试连之前
            // 用上存下的那一组；线路还不是代理时（冷启动、存储读不出先按系统网络之后又恢复）用存下的那一组（它连上过）
            mode == RouteMode.PROXY -> if (persist) {
                CoreRoute.ProxyUnavailable
            } else if (state.value is CoreRoute.Proxied || state.value == CoreRoute.ProxyUnavailable) {
                state.value
            } else {
                when (val stored = outcomeOf { proxyOf(prefs.data.first()) }) {
                    is Outcome.Success -> stored.value?.let { CoreRoute.Proxied(it) } ?: CoreRoute.ProxyUnavailable
                    is Outcome.Failed -> CoreRoute.ProxyUnavailable
                }
            }
            else -> CoreRoute.System
        }
        if (persist) {
            val saved: Outcome<Unit> = outcomeOf { prefs.edit { it[MODE_KEY] = mode.name } }
            if (saved is Outcome.Failed) {
                if (!wasRunning && next is CoreRoute.Core) stopCore()
                return@withLock saved
            }
        }
        if (next !is CoreRoute.Core) stopCore()
        publish(next)
        Outcome.Success(Unit)
    }.also { decided.countDown() }

    /** 换上 [next]；WebView 的代理跟着换。只在 [transitions] 锁里调。 */
    private fun publish(next: CoreRoute) {
        val before = (state.value as? CoreRoute.Proxied)?.server
        state.value = next
        val after = (next as? CoreRoute.Proxied)?.server
        if (after != before) webView.use(after)
    }

    private fun saveConfig(config: ByteArray) {
        scope.launch { outcomeOf { prefs.edit { it[ECH_CONFIG_KEY] = config } } }
    }

    private suspend fun startCore(): Outcome<CoreEndpoint> = running?.let { Outcome.Success(it) } ?: withContext(Dispatchers.IO) {
        val stored = when (val read = outcomeOf { prefs.data.first()[ECH_CONFIG_KEY] }) {
            is Outcome.Success -> read.value
            is Outcome.Failed -> null
        }
        val addresses = edges.addresses()
        outcomeOf { core.start(addresses, stored ?: ByteArray(0), edgeBudgetMillis(addresses.size), ::saveConfig, log) }
            .also { if (it is Outcome.Success) running = it.value }
    }

    // 没起过就不碰核心：Go 核心的类一加载就要载入 .so，不支持的设备上没有它。
    private suspend fun stopCore() {
        if (running == null) return
        withContext(Dispatchers.IO) { outcomeOf { core.stop() } }
        running = null
    }

    private companion object {
        val MODE_KEY = stringPreferencesKey("route_mode")
        val ECH_CONFIG_KEY = byteArrayPreferencesKey("ech_config")
        val PROXY_TYPE_KEY = stringPreferencesKey("proxy_type")
        val PROXY_HOST_KEY = stringPreferencesKey("proxy_host")
        val PROXY_PORT_KEY = intPreferencesKey("proxy_port")
        const val READY_TIMEOUT_SECONDS = 5L
        const val RESUBSCRIBE_FIRST_DELAY_MILLIS = 1_000L
        const val RESUBSCRIBE_MAX_DELAY_MILLIS = 60_000L

        /** 第 [attempt] 次重新订阅前等多久：1 秒起翻倍，至多 1 分钟（存储一直坏着时不忙等）。 */
        fun resubscribeDelayMillis(attempt: Long): Long =
            (RESUBSCRIBE_FIRST_DELAY_MILLIS shl attempt.coerceAtMost(6).toInt()).coerceAtMost(RESUBSCRIBE_MAX_DELAY_MILLIS)

        fun modeOf(prefs: Preferences): RouteMode =
            RouteMode.entries.firstOrNull { it.name == prefs[MODE_KEY] } ?: RouteMode.SYSTEM

        /** 存下的代理；缺一项或不合规（外部输入：旧版本或手改的文件）时没有。 */
        fun proxyOf(prefs: Preferences): ProxyServer? {
            val type = ProxyType.entries.firstOrNull { it.name == prefs[PROXY_TYPE_KEY] } ?: return null
            return proxyServerOf(type, prefs[PROXY_HOST_KEY] ?: return null, prefs[PROXY_PORT_KEY]?.toString() ?: return null)
        }

        fun writeProxy(prefs: MutablePreferences, server: ProxyServer) {
            prefs[PROXY_TYPE_KEY] = server.type.name
            prefs[PROXY_HOST_KEY] = server.host
            prefs[PROXY_PORT_KEY] = server.port
        }
    }
}
