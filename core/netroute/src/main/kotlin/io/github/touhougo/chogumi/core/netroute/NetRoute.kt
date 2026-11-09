package io.github.touhougo.chogumi.core.netroute

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.touhougo.chogumi.core.data.util.ObservedPreferences
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
}

/** 问一次「这个请求怎么走」。 */
internal fun interface CoreRoutes {
    fun current(): CoreRoute
}

/** 直连核心的起停（生产是 Go 核心 [GoDirectCore]）。[start] 起不来就抛异常。 */
internal interface DirectCore {
    fun start(addresses: List<String>, config: ByteArray, onConfig: (ByteArray) -> Unit): CoreEndpoint

    fun stop()
}

/**
 * 连 bgm.tv 的线路：设置（设备级，不跟账号走，登录之前就要用；默认 [RouteMode.SYSTEM]）与直连核心。
 *
 * 模型（代码只照这几条做）：
 * - **线路状态只有一个来源** [state]，网络层每个请求经 [route] 读它；只有 [CoreRoute.System] 放请求走系统网络。还没定下来
 *   （[CoreRoute.Undecided]：启动后至多等 [READY_TIMEOUT_SECONDS] 秒，超时也算）与设置是直连而核心没起来（[CoreRoute.Unavailable]）
 *   一样，请求失败——直连失败绝不退回普通连接。
 * - **状态只由 [transition] 改**：开直连、关直连、应用存着的设置，都经它，在同一把锁里起核心、存设置、停旧核心，全部成了才换状态。
 *   用户动手（[setMode]）失败时留在原状态、返回失败；应用存着的设置（[run]）时核心起不来就是 [CoreRoute.Unavailable]。
 * - **观察设置的循环不结束**（[run]）：读失败按系统网络应用一次，隔一会儿重新订阅；每次应用都放行等着的请求。
 *
 * 服务端给的 ECH 配置存在同一个 DataStore 里，下次起核心时直接用；失效时核心照旧被拒、换上新给的那份、再存一次。
 */
class NetRoute internal constructor(
    dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val edges: EdgeAddresses,
    private val supported: Boolean,
    private val core: DirectCore,
) {
    private val prefs = ObservedPreferences(dataStore)
    private val decided = CountDownLatch(1)
    private val transitions = Mutex()
    private val state = MutableStateFlow<CoreRoute>(CoreRoute.Undecided)
    private var running: CoreEndpoint? = null

    /** 设置里存的线路（给界面）；读不出来时是系统网络。 */
    val mode: Flow<RouteMode> = prefs.data
        .map(::modeOf)
        .catch { e -> if (e is IOException) emit(RouteMode.SYSTEM) else throw e }
        .distinctUntilChanged()

    /** 设备支持直连、且现在走系统网络。 */
    val directAvailable: Flow<Boolean> = mode.map { supported && it == RouteMode.SYSTEM }.distinctUntilChanged()

    /** 设置是直连、核心却没起来。 */
    val directUnavailable: Flow<Boolean> = state.map { it == CoreRoute.Unavailable }.distinctUntilChanged()

    /** 跟着设置起停核心；应用启动时在应用作用域里调一次，一直跑（读失败之后重新订阅）。 */
    suspend fun run() {
        while (true) {
            mode.collect { transition(it, persist = false) }
            delay(RESUBSCRIBE_DELAY_MILLIS)
        }
    }

    /** 用户换线路：成了才换（失败时留在原线路）。设置是直连而核心没起来时，再开一次就是「重试」。 */
    suspend fun setMode(mode: RouteMode): Outcome<Unit> = transition(mode, persist = true)

    internal fun route(): CoreRoute {
        decided.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return state.value
    }

    /** 唯一改 [state] 的地方。[persist] 为 true 是用户动手（要存设置、失败留原状），false 是应用存着的设置。 */
    private suspend fun transition(mode: RouteMode, persist: Boolean): Outcome<Unit> = transitions.withLock {
        val wasRunning = running != null
        val next: CoreRoute = if (mode == RouteMode.DIRECT && supported) {
            when (val started = startCore()) {
                is Outcome.Success -> CoreRoute.Core(started.value)
                is Outcome.Failed -> {
                    if (!persist) state.value = CoreRoute.Unavailable
                    return@withLock started
                }
            }
        } else {
            CoreRoute.System
        }
        if (persist) {
            val saved: Outcome<Unit> = outcomeOf { prefs.edit { it[MODE_KEY] = mode.name } }
            if (saved is Outcome.Failed) {
                if (!wasRunning && next is CoreRoute.Core) stopCore()
                return@withLock saved
            }
        }
        if (next == CoreRoute.System) stopCore()
        state.value = next
        Outcome.Success(Unit)
    }.also { decided.countDown() }

    private fun saveConfig(config: ByteArray) {
        scope.launch { outcomeOf { prefs.edit { it[ECH_CONFIG_KEY] = config } } }
    }

    private suspend fun startCore(): Outcome<CoreEndpoint> = running?.let { Outcome.Success(it) } ?: withContext(Dispatchers.IO) {
        val stored = when (val read = outcomeOf { prefs.data.first()[ECH_CONFIG_KEY] }) {
            is Outcome.Success -> read.value
            is Outcome.Failed -> null
        }
        outcomeOf { core.start(edges.addresses(), stored ?: ByteArray(0), ::saveConfig) }
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
        const val READY_TIMEOUT_SECONDS = 5L
        const val RESUBSCRIBE_DELAY_MILLIS = 1_000L

        fun modeOf(prefs: Preferences): RouteMode =
            RouteMode.entries.firstOrNull { it.name == prefs[MODE_KEY] } ?: RouteMode.SYSTEM
    }
}
