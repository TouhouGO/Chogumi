package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/** 分页取全：第一页之后其余各页一起发（可限在途页数、可共用许可），游标按服务端跨度推进，没有 total 时逐页取到空页，翻页不前进按坏响应失败，可只取前若干行，缺一页就整体失败。 */
class FetchAllPagesTest {

    @Test
    fun `其余各页在第一页回来后一起发出，不逐页等待`() = runTest {
        // offset=100 那一页要等到 offset=200 也发出去才回：逐页取的话第二页永远等不到第三页，超时失败。
        val lastPageRequested = CompletableDeferred<Unit>()
        val requested = mutableListOf<Int>()

        val items = fetchAllPages(100) { offset, limit ->
            requested += offset
            when (offset) {
                0 -> PageSlice((1..100).toList(), total = 230)
                100 -> {
                    checkNotNull(withTimeoutOrNull(1_000) { lastPageRequested.await() }) { "offset=200 没有和 offset=100 一起发出" }
                    PageSlice((101..200).toList(), total = 230)
                }
                200 -> {
                    lastPageRequested.complete(Unit)
                    PageSlice((201..230).toList(), total = 230)
                }
                else -> error("不该请求 offset=$offset，limit=$limit")
            }
        }

        assertEquals((1..230).toList(), items, "按 offset 顺序拼接")
        assertEquals(listOf(0, 100, 200), requested)
    }

    @Test
    fun `服务端给的跨度小于请求值时按服务端跨度推进`() = runTest {
        val requested = mutableListOf<Int>()

        val items = fetchAllPages(100) { offset, _ ->
            requested += offset
            PageSlice((offset + 1..minOf(offset + 50, 120)).toList(), total = 120, responseLimit = 50)
        }

        assertEquals(listOf(0, 50, 100), requested)
        assertEquals((1..120).toList(), items)
    }

    @Test
    fun `一页回不满不代表到底，末页只看 total`() = runTest {
        val requested = mutableListOf<Int>()

        val items = fetchAllPages(100) { offset, _ ->
            requested += offset
            when (offset) {
                0 -> PageSlice((1..60).toList(), total = 250)
                100 -> PageSlice((101..200).toList(), total = 250)
                else -> PageSlice((201..250).toList(), total = 250)
            }
        }

        assertEquals(listOf(0, 100, 200), requested)
        assertEquals(210, items.size)
    }

    @Test
    fun `没有 total 时逐页往后取到空页为止`() = runTest {
        val requested = mutableListOf<Int>()

        val items = fetchAllPages(100) { offset, _ ->
            requested += offset
            // 中间一页被服务端过滤得回不满，也不代表到底
            when (offset) {
                0 -> PageSlice((1..100).toList(), total = 0)
                100 -> PageSlice((101..140).toList(), total = 0)
                200 -> PageSlice((201..300).toList(), total = 0)
                else -> PageSlice(emptyList<Int>(), total = 0)
            }
        }

        assertEquals(listOf(0, 100, 200, 300), requested)
        assertEquals((1..140).toList() + (201..300).toList(), items)
    }

    @Test
    fun `没有 total 时翻页不前进_按坏响应失败而不是一直取`() = runTest {
        var calls = 0

        val failure = assertFailsWith<FailureException> {
            fetchAllPages(100) { _, _ ->
                check(++calls < 10) { "一直在取同一页" }
                PageSlice((1..100).toList(), total = 0)
            }
        }

        assertEquals(Failure.BadResponse, failure.failure)
        assertEquals(2, calls)
    }

    @Test
    fun `有 total 时某页与上一页完全相同_按坏响应失败`() = runTest {
        val failure = assertFailsWith<FailureException> {
            fetchAllPages(100) { offset, _ ->
                // 服务端忽略 offset：每页都是第一页
                PageSlice((1..100).toList(), total = 300).also { check(offset <= 200) }
            }
        }

        assertEquals(Failure.BadResponse, failure.failure)
    }

    @Test
    fun `任一页失败整体失败，不拼出缺页的列表`() = runTest {
        assertFailsWith<IllegalStateException> {
            fetchAllPages(100) { offset, _ ->
                check(offset != 100) { "offset=100 失败" }
                PageSlice((offset + 1..minOf(offset + 100, 230)).toList(), total = 230)
            }
        }
    }

    @Test
    fun `只取结果集前 maxRows 行，超出的页不请求`() = runTest {
        val requested = mutableListOf<Int>()

        val items = fetchAllPages(100, maxRows = 250) { offset, _ ->
            requested += offset
            PageSlice((offset + 1..offset + 100).toList(), total = 1000)
        }

        assertEquals(listOf(0, 100, 200), requested)
        assertEquals((1..250).toList(), items)
    }

    @Test
    fun `同时在途的页数不超过许可数`() = runTest {
        var inFlight = 0
        var peak = 0

        val items = fetchAllPages(100, permits = Semaphore(2)) { offset, _ ->
            inFlight++
            peak = maxOf(peak, inFlight)
            delay(10)
            inFlight--
            PageSlice((offset + 1..offset + 100).toList(), total = 600)
        }

        assertEquals(2, peak)
        assertEquals((1..600).toList(), items)
    }

    @Test
    fun `几次取全共用一份许可_第一页也要先拿到许可`() = runTest {
        val permits = Semaphore(1)
        var inFlight = 0
        var peak = 0
        suspend fun fetchOne() = fetchAllPages(100, permits = permits) { offset, _ ->
            inFlight++
            peak = maxOf(peak, inFlight)
            delay(10)
            inFlight--
            PageSlice((offset + 1..offset + 100).toList(), total = 200)
        }

        val results = coroutineScope { listOf(async { fetchOne() }, async { fetchOne() }).awaitAll() }

        assertEquals(1, peak, "两次取全的第一页也不能同时在途")
        assertEquals(listOf((1..200).toList(), (1..200).toList()), results)
    }

    @Test
    fun `页进度_第一页回来后登记总页数_每页报一次_几次取全累加`() = runTest {
        val reports = mutableListOf<Pair<Int, Int>>()
        val counter = PageCounter { completed, planned -> reports += completed to planned }

        fetchAllPages(100, permits = Semaphore(1), progress = counter) { offset, _ ->
            PageSlice((offset + 1..minOf(offset + 100, 250)).toList(), total = 250)
        }
        fetchAllPages(100, permits = Semaphore(1), progress = counter) { offset, _ ->
            PageSlice((offset + 1..offset + 100).toList(), total = 100)
        }

        assertEquals(listOf(1 to 0, 1 to 3, 2 to 3, 3 to 3, 4 to 3, 4 to 4), reports)
    }
}
