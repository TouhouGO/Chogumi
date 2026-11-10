package io.github.touhougo.chogumi.core.netroute

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 代理线路的状态：打开开关、「连接」、冷启动、与直连互斥、WebView 跟着换。都不能在没连上时改走系统网络。 */
class ProxyRouteTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = ProxyServer(ProxyType.SOCKS5, "127.0.0.1", 1080)
    private val other = ProxyServer(ProxyType.HTTP, "192.168.1.5", 8080)
    private val webViewProxies: MutableList<ProxyServer?> = Collections.synchronizedList(mutableListOf())
    private val webView = WebViewProxy { webViewProxies += it }

    @After
    fun tearDown() = cancelAndAwait(listOf(scope))

    private fun dataStore(): DataStore<Preferences> {
        val dir = Files.createTempDirectory("netroute").toFile()
        return PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("netroute.preferences_pb") }
    }

    private fun netRoute(store: DataStore<Preferences> = dataStore(), probe: ProxyProbe, core: DirectCore = NoCore) =
        NetRoute(store, scope, BuiltinEdgeAddresses, supported = true, core = core, networkChanges = NoNetworkChanges, log = null, probe = probe, webView = webView)
            .also { route -> scope.launch { route.run() } }

    private fun client(route: NetRoute, end: RecordingEnd) = OkHttpClient.Builder().apply {
        val routes = CoreRoutes { route.route() }
        DirectRouting(routes).customize(this)
        ProxyRouting(routes).customize(this)
        end.customize(this)
    }.build()

    private fun get(client: OkHttpClient, url: String) = client.newCall(Request.Builder().url(url).build()).execute().close()

    private val reaches = ProxyProbe { Outcome.Success(Unit) }

    @Test
    fun `打开代理还没连上时所有请求失败_不走系统网络`() = runBlocking {
        val route = netRoute(probe = NoProbe)
        assertTrue(route.setMode(RouteMode.PROXY) is Outcome.Success)
        val end = RecordingEnd()
        val client = client(route, end)

        assertThrows(IOException::class.java) { get(client, "https://api.bgm.tv/v0/subjects/12") }
        assertThrows("外站图片也不能改走系统网络", IOException::class.java) { get(client, "https://example.com/cover.jpg") }

        assertTrue("请求不该发出去", end.requests.isEmpty())
    }

    @Test
    fun `连接成功才换过去并存下`() = runBlocking {
        val store = dataStore()
        val route = netRoute(store, probe = reaches)
        route.setMode(RouteMode.PROXY)

        assertEquals(ProxyConnect.Connected, route.connectProxy(server))

        assertEquals(CoreRoute.Proxied(server), route.route())
        assertEquals(server, route.proxy.first())
        assertEquals(listOf<ProxyServer?>(server), webViewProxies.toList())
    }

    @Test
    fun `连不上时旧的那一组照用_也不存`() = runBlocking {
        var reachable = true
        val route = netRoute(probe = { if (reachable) Outcome.Success(Unit) else Outcome.Failed(Failure.Offline) })
        route.setMode(RouteMode.PROXY)
        route.connectProxy(server)
        reachable = false

        assertEquals(ProxyConnect.Unreachable(Failure.Offline), route.connectProxy(other))

        assertEquals(CoreRoute.Proxied(server), route.route())
        assertEquals(server, route.proxy.first())
    }

    @Test
    fun `连的期间关掉了代理_结果不用`() = runBlocking {
        val probing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val route = netRoute(probe = {
            probing.complete(Unit)
            release.await()
            Outcome.Success(Unit)
        })
        route.setMode(RouteMode.PROXY)
        val connecting = scope.async { route.connectProxy(server) }
        probing.await()
        route.setMode(RouteMode.SYSTEM)
        release.complete(Unit)

        assertEquals(ProxyConnect.Withdrawn, connecting.await())
        assertEquals(CoreRoute.System, route.route())
        assertEquals(null, route.proxy.first())
    }

    @Test
    fun `冷启动时设置是代理就用存下的那一组`() = runBlocking {
        val store = dataStore()
        store.edit {
            it[stringPreferencesKey("route_mode")] = RouteMode.PROXY.name
            it[stringPreferencesKey("proxy_type")] = ProxyType.HTTP.name
            it[stringPreferencesKey("proxy_host")] = "192.168.1.5"
            it[intPreferencesKey("proxy_port")] = 8080
        }
        val route = netRoute(store, probe = NoProbe)

        awaitUntil("用上存下的代理") { route.route() == CoreRoute.Proxied(other) }
        assertEquals(listOf<ProxyServer?>(other), webViewProxies.toList())
    }

    @Test
    fun `关掉代理回到系统网络_存下的那一组留着_WebView 清掉代理`() = runBlocking {
        val route = netRoute(probe = reaches)
        route.setMode(RouteMode.PROXY)
        route.connectProxy(server)

        assertTrue(route.setMode(RouteMode.SYSTEM) is Outcome.Success)

        assertEquals(CoreRoute.System, route.route())
        assertEquals(server, route.proxy.first())
        assertEquals(listOf(server, null), webViewProxies.toList())
    }

    @Test
    fun `直连与代理互斥_开直连停掉代理_开代理停掉核心`() = runBlocking {
        val core = CountingCore()
        val route = netRoute(probe = reaches, core = core)
        route.setMode(RouteMode.PROXY)
        route.connectProxy(server)

        assertTrue(route.setMode(RouteMode.DIRECT) is Outcome.Success)
        assertTrue(route.route() is CoreRoute.Core)
        assertEquals(RouteMode.DIRECT, route.mode.first())

        assertTrue(route.setMode(RouteMode.PROXY) is Outcome.Success)
        assertEquals(CoreRoute.ProxyUnavailable, route.route())
        assertEquals(1, core.stops)
    }
}
