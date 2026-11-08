package io.github.touhougo.chogumi.core.data.util

import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.settled
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

class CachedNetworkTest {

    data class TestCachedItem(val value: String, val updatedAt: Long)


    @Test
    fun `默认配置下有缓存直出且不发网络请求`() = runTest {
        var networkCalls = 0
        val flow = cachedNetworkCall(
            readCache = { "cache_val" },
            mapCache = { it },
            fetchNetwork = { networkCalls++; "net_val" },
            writeCache = { },
            clock = AppClock.SYSTEM,
        )
        val results = mutableListOf<Load<String>>()
        flow.collect { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(Load.Ready("cache_val", fromCache = true), results[0])
        assertEquals(0, networkCalls)
    }

    @Test
    fun `各步换到别的调度器上跑时 只要一个答案的读取拿到过期缓存就停 不发网络`() = runTest {
        val clock = TestClock(time = 10_000L)
        var networkCalls = 0
        val result = cachedNetworkCall(
            readCache = { TestCachedItem("old_val", updatedAt = 8_000L) },
            mapCache = { it.value },
            fetchNetwork = { networkCalls++; "net_val" },
            writeCache = { },
            ttlMillis = 1000L,
            cachedAt = { it.updatedAt },
            clock = clock,
            // 立即执行的调度器：上游若与下游之间隔着缓冲，交出缓存拍后会当场接着发网络
            workContext = Dispatchers.Unconfined,
        ).settled()
        assertEquals(Outcome.Success("old_val"), result)
        assertEquals(0, networkCalls, "settled() 拿到缓存拍就该停下，不该再发网络请求")
    }

    @Test
    fun `配置TTL时新鲜缓存只发缓存不发网络`() = runTest {
        val clock = TestClock(time = 10_000L)
        var networkCalls = 0
        val cached = TestCachedItem("cache_val", updatedAt = 9_500L) // 500ms 前，TTL 为 1000ms
        val flow = cachedNetworkCall(
            readCache = { cached },
            mapCache = { it.value },
            fetchNetwork = { networkCalls++; "net_val" },
            writeCache = { },
            ttlMillis = 1000L,
            cachedAt = { it.updatedAt },
            clock = clock,
        )
        val results = mutableListOf<Load<String>>()
        flow.collect { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(Load.Ready("cache_val", fromCache = true), results[0])
        assertEquals(0, networkCalls)
    }

    @Test
    fun `过期缓存先发旧值再发刷新新值`() = runTest {
        val clock = TestClock(time = 10_000L)
        var networkCalls = 0
        var cache = TestCachedItem("old_val", updatedAt = 8_000L) // 2000ms 前，TTL 为 1000ms（已过期）
        val flow = cachedNetworkCall(
            readCache = { cache },
            mapCache = { it.value },
            fetchNetwork = {
                networkCalls++
                "new_net_val"
            },
            writeCache = { cache = TestCachedItem(it, updatedAt = clock.nowMillis()) },
            ttlMillis = 1000L,
            cachedAt = { it.updatedAt },
            clock = clock,
        )
        val results = mutableListOf<Load<String>>()
        flow.collect { results.add(it) }
        assertEquals(2, results.size)
        assertEquals(Load.Ready("old_val", fromCache = true), results[0])
        assertEquals(Load.Ready("new_net_val", fromCache = false), results[1])
        assertEquals(1, networkCalls)
        assertEquals(10_000L, cache.updatedAt)
    }

    @Test
    fun `时间戳为 0 的存量行走一次后台刷新后获得真实时间戳`() = runTest {
        val clock = TestClock(time = 10_000L)
        var networkCalls = 0
        // 存量行 updatedAt = 0L，必须被判定为过期，从而触发后台刷新自愈
        var cache = TestCachedItem("legacy_val", updatedAt = 0L)
        val flow = cachedNetworkCall(
            readCache = { cache },
            mapCache = { it.value },
            fetchNetwork = {
                networkCalls++
                "refreshed_val"
            },
            writeCache = { cache = TestCachedItem(it, updatedAt = clock.nowMillis()) },
            ttlMillis = 1000L,
            cachedAt = { it.updatedAt },
            clock = clock,
        )
        val results = mutableListOf<Load<String>>()
        flow.collect { results.add(it) }
        assertEquals(2, results.size)
        assertEquals(Load.Ready("legacy_val", fromCache = true), results[0])
        assertEquals(Load.Ready("refreshed_val", fromCache = false), results[1])
        assertEquals(1, networkCalls)
        assertEquals(10_000L, cache.updatedAt)
    }

    @Test
    fun `过期缓存刷新失败原样交出失败 缓存仍可显示`() = runTest {
        val clock = TestClock(time = 10_000L)
        val cached = TestCachedItem("stale_val", updatedAt = 1_000L)
        val flow = cachedNetworkCall(
            readCache = { cached },
            mapCache = { it.value },
            fetchNetwork = { throw java.io.IOException("network down") },
            writeCache = { },
            ttlMillis = 1000L,
            cachedAt = { it.updatedAt },
            clock = clock,
        )
        val results = mutableListOf<Load<String>>()
        flow.collect { results.add(it) }
        assertEquals(2, results.size)
        assertEquals(Load.Ready("stale_val", fromCache = true), results[0])
        assertEquals(Load.Failed(Failure.Interrupted), results[1])
    }

    @Test
    fun `无缓存时先 Loading 再网络结果`() = runTest {
        val flow = cachedNetworkCall(
            readCache = { null },
            mapCache = { it },
            fetchNetwork = { "net" },
            writeCache = { },
            clock = AppClock.SYSTEM,
        )
        val results = mutableListOf<Load<String>>()
        flow.collect { results.add(it) }
        assertEquals(listOf(Load.Pending, Load.Ready("net")), results)
    }

    @Test
    fun `无缓存网络失败时报 Error`() = runTest {
        val flow = cachedNetworkCall(
            readCache = { null },
            mapCache = { it },
            fetchNetwork = { throw java.net.UnknownHostException("x") },
            writeCache = { },
            clock = AppClock.SYSTEM,
        )
        val results = mutableListOf<Load<String>>()
        flow.collect { results.add(it) }
        assertEquals(2, results.size)
        assertEquals(Load.Pending, results[0])
        assertTrue(results[1] is Load.Failed)
    }

    @Test
    fun `refresh 强刷模式绕过 TTL 失败后旧缓存完好`() = runTest {
        var cache = "cache_value"
        val flow = cachedNetworkCall(
            readCache = { cache },
            mapCache = { it },
            fetchNetwork = { throw RuntimeException("server error") },
            writeCache = { cache = it },
            refresh = true,
            ttlMillis = 1000L,
            cachedAt = { 99999L },
            clock = AppClock.SYSTEM,
        )
        val results = mutableListOf<Load<String>>()
        flow.collect { results.add(it) }
        assertEquals(2, results.size)
        assertEquals(Load.Pending, results[0])
        assertTrue(results[1] is Load.Failed)
        // 缓存完好未被破坏
        assertEquals("cache_value", cache)
    }

    @Test
    fun `CancellationException 透传不吞掉`() = runTest {
        val flow = cachedNetworkCall<String, String>(
            readCache = { null },
            mapCache = { it },
            fetchNetwork = { throw CancellationException("cancelled") },
            writeCache = { },
            clock = AppClock.SYSTEM,
        )
        assertFailsWith<CancellationException> { flow.collect { } }
    }

    @Test
    fun `读缓存时被取消_取消透传_不改走网络`() = runTest {
        var fetched = false
        val flow = cachedNetworkCall<String, String>(
            readCache = { throw CancellationException("cancelled") },
            mapCache = { it },
            fetchNetwork = { fetched = true; "net" },
            writeCache = { },
            clock = AppClock.SYSTEM,
        )
        assertFailsWith<CancellationException> { flow.collect { } }
        assertFalse(fetched, "取消后不该再发请求")
    }
}
