package io.github.touhougo.chogumi.core.netroute

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import java.nio.file.Files
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.network.REQUEST_TIMEOUT_MILLIS
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    fun tearDown() = cancelAndAwait(listOf(scope))

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

        scope.launch { route.run() }

        assertEquals(CoreRoute.System, route.route())
    }

    @Test
    fun `读失败之后接着观察设置_恢复后照设置走`() = runBlocking {
        val store = dataStore()
        store.edit { it[stringPreferencesKey("route_mode")] = RouteMode.DIRECT.name }
        val reads = AtomicInteger()
        val flaky = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                if (reads.incrementAndGet() == 1) throw IOException("disk")
                emitAll(store.data)
            }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = store.updateData(transform)
        }
        val core = CountingCore()
        val route = NetRoute(flaky, scope, BuiltinEdgeAddresses, supported = true, core = core)
        scope.launch { route.run() }

        awaitUntil("读恢复后起了核心") { route.route() is CoreRoute.Core }
        assertTrue(route.setMode(RouteMode.SYSTEM) is Outcome.Success)
        assertEquals(CoreRoute.System, route.route())
        assertEquals(1, core.stops)
    }

    @Test
    fun `线路一直没定下来时请求失败_不走系统网络`() {
        val stuck = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { awaitCancellation() }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = awaitCancellation()
        }
        val route = NetRoute(stuck, scope, BuiltinEdgeAddresses, supported = true, core = NoCore)
        scope.launch { route.run() }
        val end = RecordingEnd()

        assertThrows(IOException::class.java) { get(client({ route.route() }, end)) }

        assertTrue("请求不该发出去", end.requests.isEmpty())
    }

    @Test
    fun `初始化等满预算后后续请求立即失败_设置到达后仍恢复`() = runBlocking {
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = dataStore()
        val delayed = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                ready.await()
                emitAll(store.data)
            }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = store.updateData(transform)
        }
        val route = NetRoute(delayed, scope, BuiltinEdgeAddresses, supported = true, core = NoCore)
        scope.launch { route.run() }
        assertEquals(CoreRoute.Undecided, route.route())

        val started = System.nanoTime()
        assertEquals(CoreRoute.Undecided, route.route())
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("后续请求又等了初始化预算：${elapsedMillis}ms", elapsedMillis < 1_000)

        ready.complete(Unit)
        awaitUntil("设置到达后恢复系统线路") { route.route() == CoreRoute.System }
    }

    @Test
    fun `冷启动时设置是直连就走核心_关掉后停核心走系统网络`() = runBlocking {
        val store = dataStore()
        store.edit { it[stringPreferencesKey("route_mode")] = RouteMode.DIRECT.name }
        val core = CountingCore()
        val route = NetRoute(store, scope, BuiltinEdgeAddresses, supported = true, core = core)
        scope.launch { route.run() }

        assertEquals(CoreRoute.Core(CoreEndpoint(4242, "secret")), route.route())
        assertTrue(route.setMode(RouteMode.SYSTEM) is Outcome.Success)
        assertEquals(CoreRoute.System, route.route())
        assertEquals(RouteMode.SYSTEM, route.mode.first())
        assertEquals(1, core.starts)
        assertEquals(1, core.stops)
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

    @Test
    fun `从系统网络开直连而核心起不来时留在系统网络`() = runBlocking {
        val route = NetRoute(dataStore(), scope, BuiltinEdgeAddresses, supported = true, core = BrokenCore)
        scope.launch { route.run() }
        route.route()

        val result = route.setMode(RouteMode.DIRECT)

        assertTrue("开不了要返回失败", result is Outcome.Failed)
        assertEquals(CoreRoute.System, route.route())
        assertEquals(RouteMode.SYSTEM, route.mode.first())
        val end = RecordingEnd()
        get(client({ route.route() }, end)).close()
        assertEquals("https://api.bgm.tv/v0/subjects/12", end.requests.single().url.toString())
    }

    @Test
    fun `开直连时设置存不进就停掉新起的核心_留在系统网络`() = runBlocking {
        val store = dataStore()
        val unwritable = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = store.data
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk full")
        }
        val core = CountingCore()
        val route = NetRoute(unwritable, scope, BuiltinEdgeAddresses, supported = true, core = core)
        scope.launch { route.run() }
        route.route()

        val result = route.setMode(RouteMode.DIRECT)

        assertTrue("存不进要返回失败", result is Outcome.Failed)
        assertEquals(CoreRoute.System, route.route())
        assertEquals(1, core.starts)
        assertEquals(1, core.stops)
    }

    @Test
    fun `开直连成功后请求走核心`() = runBlocking {
        val route = NetRoute(dataStore(), scope, BuiltinEdgeAddresses, supported = true, core = CountingCore())
        scope.launch { route.run() }
        route.route()

        assertTrue(route.setMode(RouteMode.DIRECT) is Outcome.Success)

        assertEquals(CoreRoute.Core(CoreEndpoint(4242, "secret")), route.route())
    }

    @Test
    fun `启动的旧系统快照迟到不能覆盖已经保存的直连`() = runBlocking {
        val store = dataStore()
        val old = store.data.first()
        val readStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseOld = kotlinx.coroutines.CompletableDeferred<Unit>()
        val oldProcessed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val firstRead = java.util.concurrent.atomic.AtomicBoolean(true)
        val delayed = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                if (firstRead.compareAndSet(true, false)) {
                    readStarted.complete(Unit)
                    releaseOld.await()
                    emit(old)
                    oldProcessed.complete(Unit)
                }
                emitAll(store.data)
            }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = store.updateData(transform)
        }
        val core = CountingCore()
        val route = NetRoute(delayed, scope, BuiltinEdgeAddresses, supported = true, core = core)
        scope.launch { route.run() }
        readStarted.await()
        assertTrue(route.setMode(RouteMode.DIRECT) is Outcome.Success)
        assertEquals(CoreRoute.Core(CoreEndpoint(4242, "secret")), route.route())

        releaseOld.complete(Unit)
        oldProcessed.await()

        assertEquals("旧系统快照不应停掉新起的核心", 0, core.stops)
    }

    @Test
    fun `界面那样的订阅者在读失败_存储恢复后也跟上设置`() = runBlocking {
        val store = dataStore()
        store.edit { it[stringPreferencesKey("route_mode")] = RouteMode.DIRECT.name }
        val reads = AtomicInteger()
        val flaky = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                if (reads.incrementAndGet() == 1) throw IOException("disk")
                emitAll(store.data)
            }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = store.updateData(transform)
        }
        val route = NetRoute(flaky, scope, BuiltinEdgeAddresses, supported = true, core = CountingCore())
        val seenMode = mutableListOf<RouteMode>()
        val seenHint = mutableListOf<Boolean>()
        // 设置页跟 mode，登录页跟 directAvailable：都不经 run()
        scope.launch { route.mode.collect { synchronized(seenMode) { seenMode += it } } }
        scope.launch { route.directAvailable.collect { synchronized(seenHint) { seenHint += it } } }

        awaitUntil("设置页看到存储恢复后的直连") { synchronized(seenMode) { RouteMode.DIRECT in seenMode } }
        awaitUntil("登录页的提示条件跟着变") { synchronized(seenHint) { false in seenHint } }
    }

    @Test
    fun `界面那样的订阅者跟着开关走`() = runBlocking {
        val route = NetRoute(dataStore(), scope, BuiltinEdgeAddresses, supported = true, core = CountingCore())
        scope.launch { route.run() }
        val seen = mutableListOf<RouteMode>()
        scope.launch { route.mode.collect { synchronized(seen) { seen += it } } }
        awaitUntil("先看到系统网络") { synchronized(seen) { RouteMode.SYSTEM in seen } }

        assertTrue(route.setMode(RouteMode.DIRECT) is Outcome.Success)

        awaitUntil("开了之后看到直连") { synchronized(seen) { RouteMode.DIRECT in seen } }
    }

    @Test
    fun `每个边缘地址的预算加起来小于客户端一次请求的超时`() = runBlocking {
        val core = CountingCore()
        val route = NetRoute(dataStore(), scope, BuiltinEdgeAddresses, supported = true, core = core)
        scope.launch { route.run() }
        route.route()

        assertTrue(route.setMode(RouteMode.DIRECT) is Outcome.Success)

        val edges = BuiltinEdgeAddresses.addresses().size
        assertTrue("预算 ${core.budget} ms × $edges 个地址要小于 $REQUEST_TIMEOUT_MILLIS ms", core.budget * edges < REQUEST_TIMEOUT_MILLIS)
    }
}
