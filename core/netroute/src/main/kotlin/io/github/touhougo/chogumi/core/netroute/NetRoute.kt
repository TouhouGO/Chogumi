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

/** 一个请求此刻该怎么走。 */
internal sealed interface CoreRoute {
    /** 系统网络：请求原样发出。 */
    data object System : CoreRoute

    /** 交给本机核心。 */
    data class Core(val endpoint: CoreEndpoint) : CoreRoute

    /** 选了直连、核心却没起来：请求失败，不退回系统网络（普通 SNI 会被拦，还会连累同一地址上之后的直连）。 */
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
 * 当前线路：设置（设备级，不跟账号走，登录之前就要用）与直连核心的起停。默认 [RouteMode.SYSTEM]。
 *
 * 网络层每个请求经 [route] 问一次怎么走：应用启动后第一次读到设置之前，请求在那里等（至多 [READY_TIMEOUT_SECONDS] 秒）。
 * 选了直连而核心起不来时是 [CoreRoute.Unavailable]：请求失败，直到核心起来（用户在设置里重试）或换回系统网络。
 * 设置读不出来（磁盘出错）按系统网络算。服务端给的 ECH 配置存在同一个 DataStore 里，下次起核心时直接用；失效时核心照旧被拒、
 * 换上新给的那份、再存一次。
 */
class NetRoute internal constructor(
    dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val edges: EdgeAddresses,
    private val supported: Boolean,
    private val core: DirectCore,
) {
    private val prefs = ObservedPreferences(dataStore)
    private val ready = CountDownLatch(1)
    private val transitions = Mutex()
    private val state = MutableStateFlow<CoreRoute>(CoreRoute.System)
    private var coreStarted = false

    /** 设置里存的线路；读不出来时是系统网络。 */
    val mode: Flow<RouteMode> = prefs.data
        .map(::modeOf)
        .catch { e -> if (e is IOException) emit(RouteMode.SYSTEM) else throw e }
        .distinctUntilChanged()

    /** 设备支持直连、且现在走系统网络。 */
    val directAvailable: Flow<Boolean> = mode.map { supported && it == RouteMode.SYSTEM }.distinctUntilChanged()

    /** 选了直连、核心却没起来。 */
    val directUnavailable: Flow<Boolean> = state.map { it == CoreRoute.Unavailable }.distinctUntilChanged()

    /** 跟着设置起停核心；应用启动时在应用作用域里调一次，一直跑。 */
    suspend fun run() {
        mode.collect { mode ->
            transitions.withLock {
                state.value = if (mode == RouteMode.DIRECT && supported) startedRoute() else stoppedRoute()
            }
            ready.countDown()
        }
    }

    /** 换线路。换到直连时先把核心起起来，起不来就不换、返回失败；已经选着直连时这也是「重试」。 */
    suspend fun setMode(mode: RouteMode): Outcome<Unit> {
        if (mode == RouteMode.DIRECT) {
            val route = transitions.withLock { startedRoute().also { state.value = it } }
            if (route !is CoreRoute.Core) return Outcome.Failed(Failure.Unknown)
        }
        return outcomeOf { prefs.edit { it[MODE_KEY] = mode.name } }
    }

    internal fun route(): CoreRoute {
        ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return state.value
    }

    private fun saveConfig(config: ByteArray) {
        scope.launch { outcomeOf { prefs.edit { it[ECH_CONFIG_KEY] = config } } }
    }

    private suspend fun startedRoute(): CoreRoute = withContext(Dispatchers.IO) {
        val stored = when (val read = outcomeOf { prefs.data.first()[ECH_CONFIG_KEY] }) {
            is Outcome.Success -> read.value
            is Outcome.Failed -> null
        }
        when (val started = outcomeOf { core.start(edges.addresses(), stored ?: ByteArray(0), ::saveConfig) }) {
            is Outcome.Success -> {
                coreStarted = true
                CoreRoute.Core(started.value)
            }
            is Outcome.Failed -> CoreRoute.Unavailable
        }
    }

    // 没起过就不碰核心：Go 核心的类一加载就要载入 .so，不支持的设备上没有它。
    private suspend fun stoppedRoute(): CoreRoute {
        if (coreStarted) {
            withContext(Dispatchers.IO) { outcomeOf { core.stop() } }
            coreStarted = false
        }
        return CoreRoute.System
    }

    private companion object {
        val MODE_KEY = stringPreferencesKey("route_mode")
        val ECH_CONFIG_KEY = byteArrayPreferencesKey("ech_config")
        const val READY_TIMEOUT_SECONDS = 5L

        fun modeOf(prefs: Preferences): RouteMode =
            RouteMode.entries.firstOrNull { it.name == prefs[MODE_KEY] } ?: RouteMode.SYSTEM
    }
}
