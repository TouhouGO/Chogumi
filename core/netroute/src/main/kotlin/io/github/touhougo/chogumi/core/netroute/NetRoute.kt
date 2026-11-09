package io.github.touhougo.chogumi.core.netroute

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.touhougo.chogumi.core.data.util.ObservedPreferences
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.ech.echcore.ConfigSink
import io.github.touhougo.chogumi.ech.echcore.Echcore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

/** 现在要不要把请求交给核心：要就给出它的端口与令牌，不要（系统网络）是 null。 */
internal fun interface CoreEndpoints {
    fun current(): CoreEndpoint?
}

/**
 * 当前线路：设置（设备级，不跟账号走，登录之前就要用）与直连核心的起停。默认 [RouteMode.SYSTEM]。
 *
 * 网络层每个请求经 [endpoint] 问一次「现在要不要走核心」：应用启动后第一次读到设置之前，请求在那里等（至多
 * [READY_TIMEOUT_SECONDS] 秒），不先按系统网络发出去。服务端给的 ECH 配置存在同一个 DataStore 里，下次起核心时直接用；
 * 失效时核心照旧被拒、换上新给的那份、再存一次。
 */
class NetRoute internal constructor(
    dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val edges: EdgeAddresses,
    private val supported: Boolean,
) {
    private val prefs = ObservedPreferences(dataStore)
    private val ready = CountDownLatch(1)

    @Volatile
    private var active: CoreEndpoint? = null

    @Volatile
    private var coreStarted = false

    /** 设置里存的线路。 */
    val mode: Flow<RouteMode> = prefs.data.map(::modeOf).distinctUntilChanged()

    /** 设备支持直连、且现在走系统网络。 */
    val directAvailable: Flow<Boolean> = mode.map { supported && it == RouteMode.SYSTEM }.distinctUntilChanged()

    /** 跟着设置起停核心；应用启动时在应用作用域里调一次，一直跑。 */
    suspend fun run() {
        mode.collect { mode ->
            active = if (mode == RouteMode.DIRECT && supported) {
                when (val started = startCore()) {
                    is Outcome.Success -> started.value
                    is Outcome.Failed -> null
                }
            } else {
                stopCore()
                null
            }
            ready.countDown()
        }
    }

    /** 换线路。换到直连时先把核心起起来，起不来就不换、返回失败。 */
    suspend fun setMode(mode: RouteMode): Outcome<Unit> {
        if (mode == RouteMode.DIRECT) {
            val started = startCore()
            if (started is Outcome.Failed) return started
        }
        return outcomeOf { prefs.edit { it[MODE_KEY] = mode.name } }
    }

    internal fun endpoint(): CoreEndpoint? {
        ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return active
    }

    private val configSink = object : ConfigSink {
        override fun save(config: ByteArray) {
            scope.launch { outcomeOf { prefs.edit { it[ECH_CONFIG_KEY] = config } } }
        }
    }

    private suspend fun startCore(): Outcome<CoreEndpoint> = withContext(Dispatchers.IO) {
        outcomeOf {
            val stored = prefs.data.first()[ECH_CONFIG_KEY] ?: ByteArray(0)
            val endpoint = Echcore.start(edges.addresses().joinToString(","), stored, configSink)
            coreStarted = true
            CoreEndpoint(endpoint.port.toInt(), endpoint.token)
        }
    }

    // 没起过就不碰 Echcore：类一加载就要载入核心，不支持的设备上没有它。
    private suspend fun stopCore() {
        if (!coreStarted) return
        withContext(Dispatchers.IO) { outcomeOf { Echcore.stop() } }
        coreStarted = false
    }

    private companion object {
        val MODE_KEY = stringPreferencesKey("route_mode")
        val ECH_CONFIG_KEY = byteArrayPreferencesKey("ech_config")
        const val READY_TIMEOUT_SECONDS = 5L

        fun modeOf(prefs: Preferences): RouteMode =
            RouteMode.entries.firstOrNull { it.name == prefs[MODE_KEY] } ?: RouteMode.SYSTEM
    }
}
