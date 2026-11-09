package io.github.touhougo.chogumi.core.netroute

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 线路出故障时的去向：设置读不出、核心起不来、核心连上游失败。都不能让应用崩，也都不能悄悄退回普通连接。 */
class RouteFailureTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun client(routes: CoreRoutes, end: RecordingEnd) = OkHttpClient.Builder().apply {
        DirectRouting(routes).customize(this)
        end.customize(this)
    }.build()

    private fun get(client: OkHttpClient) =
        client.newCall(Request.Builder().url("https://api.bgm.tv/v0/subjects/12").build()).execute()

    private fun dataStore(): DataStore<Preferences> {
        val dir = Files.createTempDirectory("netroute").toFile()
        return PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("netroute.preferences_pb") }
    }

    @Test
    fun `设置读不出来时按系统网络算_不让应用崩`() = runBlocking {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("disk") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk")
        }
        val route = NetRoute(broken, scope, BuiltinEdgeAddresses, supported = true, core = NoCore)

        withTimeout(5_000) { route.run() }

        assertEquals(CoreRoute.System, route.route())
    }

    @Test
    fun `选了直连而核心起不来时请求失败_不走系统网络`() = runBlocking {
        val store = dataStore()
        store.edit { it[stringPreferencesKey("route_mode")] = RouteMode.DIRECT.name }
        val route = NetRoute(store, scope, BuiltinEdgeAddresses, supported = true, core = BrokenCore)
        scope.launch { route.run() }
        val end = RecordingEnd()

        assertThrows(IOException::class.java) { get(client({ route.route() }, end)) }

        assertTrue("请求不该发出去", end.requests.isEmpty())
        assertEquals(true, route.directUnavailable.first())
    }

    @Test
    fun `核心生成的 502 换回网络失败_上游真的 502 原样交出`() {
        val core = CoreRoute.Core(CoreEndpoint(4242, "secret"))
        val failing = RecordingEnd {
            Response.Builder().code(502).message("Bad Gateway").header("X-Chogumi-Proxy-Error", "connection reset by peer")
        }
        assertThrows(IOException::class.java) { get(client({ core }, failing)) }

        val upstream = RecordingEnd { Response.Builder().code(502).message("Bad Gateway") }
        get(client({ core }, upstream)).use { assertEquals(502, it.code) }
    }
}
