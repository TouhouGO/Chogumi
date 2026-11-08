package io.github.touhougo.chogumi.core.common.paging

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.PagedResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * 取页的每一步都由测试手动放行（[reply]）：Unconfined 下放行时协程就地恢复，状态立刻落定，不用等、不用猜时序。
 */
class PagerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private var state = PagedList<Int>()
    private val requests = mutableListOf<Int>()
    private val pending = mutableListOf<CompletableDeferred<Outcome<PagedResult<Int>>>>()

    private fun pager(present: (List<Int>) -> List<Int> = { it }) =
        Pager(scope, { state }, { f -> state = f(state) }, present) { offset ->
            requests += offset
            CompletableDeferred<Outcome<PagedResult<Int>>>().also { pending += it }.await()
        }

    /** 放行第 [index] 个请求。 */
    private fun reply(index: Int, items: List<Int>, nextOffset: Int?, total: Int = 100) =
        pending[index].complete(Outcome.Success(PagedResult(items, total, nextOffset)))

    private fun fail(index: Int) = pending[index].complete(Outcome.Failed(Failure.Offline))

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `首屏、续取接在后面、到末页`() {
        val pager = pager()
        pager.refresh()
        assertTrue(state.isLoading)
        reply(0, listOf(1, 2), nextOffset = 2, total = 3)
        assertEquals(PagedList(listOf(1, 2), total = 3, nextOffset = 2, isLoading = false), state)

        pager.loadMore()
        assertTrue(state.isLoadingMore)
        reply(1, listOf(3), nextOffset = null)
        assertEquals(listOf(1, 2, 3), state.items)
        assertEquals(3, state.total, "总数取首屏那一页的")
        assertFalse(state.hasMore)
        pager.loadMore()
        assertEquals(listOf(0, 2), requests, "到末页后不再请求")
    }

    @Test
    fun `重新加载作废在路上的旧请求，旧结果不覆盖新结果`() {
        val pager = pager()
        pager.refresh()
        pager.refresh()
        reply(0, listOf(9), nextOffset = null)
        assertTrue(state.isLoading, "作废的请求不落进状态")
        reply(1, listOf(1), nextOffset = null)
        assertEquals(listOf(1), state.items)
    }

    @Test
    fun `换筛选时旧条目留在原地，新首屏失败则撤掉旧条目整块报错，重试从头来`() {
        val pager = pager()
        pager.refresh()
        reply(0, listOf(1, 2), nextOffset = 2)
        pager.refresh(keepItems = true)
        assertEquals(listOf(1, 2), state.items)
        assertFalse(state.isLoading)
        assertNull(state.nextOffset, "旧条件的游标作废")
        fail(1)
        assertEquals(PagedList(isLoading = false, error = Failure.Offline), state)

        pager.refresh()
        assertTrue(state.isLoading)
        assertNull(state.error)
        assertEquals(listOf(0, 0, 0), requests)
    }

    @Test
    fun `续取失败留着已取的条目与游标，再续取从同一页继续`() {
        val pager = pager()
        pager.refresh()
        reply(0, listOf(1), nextOffset = 1)
        pager.loadMore()
        fail(1)
        assertEquals(listOf(1), state.items)
        assertEquals(1, state.nextOffset)
        assertEquals(Failure.Offline, state.loadMoreError)
        assertNull(state.error, "续取失败不是首屏失败")

        pager.loadMore()
        assertNull(state.loadMoreError)
        reply(2, listOf(2), nextOffset = null)
        assertEquals(listOf(1, 2), state.items)
        assertEquals(listOf(0, 1, 1), requests)
    }

    @Test
    fun `取完剩下的页：一页接一页，失败停在那页，再取从那页继续`() {
        val pager = pager()
        pager.refresh()
        pager.loadAll()
        reply(0, listOf(1), nextOffset = 1)
        reply(1, listOf(2), nextOffset = 2)
        fail(2)
        assertEquals(listOf(1, 2), state.items)
        assertEquals(Failure.Offline, state.loadMoreError)
        assertEquals(listOf(0, 1, 2), requests)

        pager.loadAll()
        reply(3, listOf(3), nextOffset = null)
        assertEquals(listOf(1, 2, 3), state.items)
        assertNull(state.loadMoreError)
        assertEquals(listOf(0, 1, 2, 2), requests)
    }

    @Test
    fun `显示条目在跨页累积的原始条目上整体换算`() {
        val pager = pager(present = { it.distinct() })
        pager.refresh()
        reply(0, listOf(1, 2), nextOffset = 2)
        pager.loadMore()
        reply(1, listOf(2, 3), nextOffset = null)
        assertEquals(listOf(1, 2, 3), state.items)
    }

    @Test
    fun `被过滤成空的一页还有下一页时不算空`() {
        val pager = pager()
        pager.refresh()
        reply(0, emptyList(), nextOffset = 30)
        assertFalse(state.isConfirmedEmpty)
        pager.loadMore()
        reply(1, emptyList(), nextOffset = null)
        assertTrue(state.isConfirmedEmpty)
    }

    @Test
    fun `清空作废在路上的请求，列表回到空且不在加载`() {
        val pager = pager()
        pager.refresh()
        pager.clear()
        reply(0, listOf(1), nextOffset = null)
        assertEquals(PagedList(isLoading = false), state)
    }

    @Test
    fun `下拉刷新：旧条目留在原地，成功整体换掉；失败旧条目和游标都留着，原因交给页面`() {
        val pager = pager()
        val failures = mutableListOf<Failure>()
        pager.refresh()
        reply(0, listOf(1, 2), nextOffset = 2)

        pager.reload { failures += it }
        assertTrue(state.isRefreshing)
        assertEquals(listOf(1, 2), state.items)
        fail(1)
        assertEquals(PagedList(listOf(1, 2), total = 100, nextOffset = 2, isLoading = false), state)
        assertEquals(listOf<Failure>(Failure.Offline), failures)

        pager.reload { failures += it }
        reply(2, listOf(5), nextOffset = null)
        assertEquals(PagedList(listOf(5), total = 100, nextOffset = null, isLoading = false), state)
        assertEquals(1, failures.size)
    }

    @Test
    fun `后台重拉不亮下拉指示器，旧条目留着，成功后换掉`() {
        val pager = pager()
        pager.refresh()
        reply(0, listOf(1, 2), nextOffset = 2)

        pager.reload(showIndicator = false) {}
        assertFalse(state.isRefreshing)
        assertTrue(pager.isActive)
        assertEquals(listOf(1, 2), state.items)
        reply(1, listOf(7), nextOffset = null)
        assertEquals(listOf(7), state.items)
        assertFalse(pager.isActive)
    }

    @Test
    fun `静默重拉作废在路上的续取，途中再要的续取不发旧游标，新第一页落定后从新游标接着取`() {
        val pager = pager()
        pager.refresh()
        reply(0, listOf(1, 2), nextOffset = 2)
        pager.loadMore()

        pager.reload(showIndicator = false) {}
        // 页脚从「加载中」回到待续取，进入时要一次续取
        pager.loadMore()
        assertFalse(state.isLoadingMore)
        assertEquals(listOf(0, 2, 0), requests, "重拉在途时不发旧游标的续页")
        reply(1, listOf(3, 4), nextOffset = 4)
        assertEquals(listOf(1, 2), state.items, "作废的续页不落进状态")

        reply(2, listOf(9, 8), nextOffset = 5)
        assertEquals(listOf(0, 2, 0, 5), requests, "新第一页落定后从它的游标接着取")
        assertTrue(state.isLoadingMore)
        reply(3, listOf(7), nextOffset = null)
        assertEquals(PagedList(listOf(9, 8, 7), total = 100, nextOffset = null, isLoading = false), state)
    }

    @Test
    fun `下拉刷新途中要的续取，新第一页落定后从新游标接着取`() {
        val pager = pager()
        pager.refresh()
        reply(0, listOf(1, 2), nextOffset = 2)

        pager.reload {}
        pager.loadMore()
        assertEquals(listOf(0, 0), requests, "第一页要换掉，刷新途中不续取")
        reply(1, listOf(9, 8), nextOffset = 5)
        assertEquals(listOf(0, 0, 5), requests, "新第一页落定后从它的游标接着取")
        reply(2, listOf(7), nextOffset = null)
        assertEquals(PagedList(listOf(9, 8, 7), total = 100, nextOffset = null, isLoading = false), state)
    }

    @Test
    fun `重拉失败时途中要的续取从留着的旧游标接着取`() {
        val pager = pager()
        val failures = mutableListOf<Failure>()
        pager.refresh()
        reply(0, listOf(1, 2), nextOffset = 2)

        pager.reload(showIndicator = false) { failures += it }
        pager.loadMore()
        assertEquals(listOf(0, 0), requests, "重拉在途时不续取")
        fail(1)
        assertEquals(listOf<Failure>(Failure.Offline), failures)
        assertEquals(listOf(0, 0, 2), requests, "重拉失败，旧条目和游标留着，从旧游标接着取")
        reply(2, listOf(3), nextOffset = null)
        assertEquals(PagedList(listOf(1, 2, 3), total = 100, nextOffset = null, isLoading = false), state)
    }

    @Test
    fun `本地改条目：作废在路上的重拉，改在原始条目上，游标不动、续取接着走`() {
        val pager = pager()
        pager.refresh()
        reply(0, listOf(1, 2, 3), nextOffset = 3)

        pager.reload {}
        pager.edit { items -> items.filterNot { it == 2 } }
        assertEquals(PagedList(listOf(1, 3), total = 100, nextOffset = 3, isLoading = false), state)
        reply(1, listOf(1, 2, 3), nextOffset = 3)
        assertEquals(listOf(1, 3), state.items, "改动之前发出的回包不能把删掉的条目带回来")

        pager.loadMore()
        reply(2, listOf(4), nextOffset = null)
        assertEquals(listOf(1, 3, 4), state.items)
        assertEquals(3, requests.last())
    }

    @Test
    fun `首屏还没有条目时本地改什么都不做`() {
        val pager = pager()
        pager.refresh()
        pager.edit { it + 5 }
        assertTrue(state.isLoading)
        assertTrue(pager.isActive)
    }

    @Test
    fun `换回取过的条件，条目、游标与翻过的页原样回来，不重取`() {
        val pager = pager()
        pager.show("排名")
        reply(0, listOf(1, 2), nextOffset = 2)
        pager.loadMore()
        reply(1, listOf(3), nextOffset = 5)
        pager.show("收藏")
        reply(2, listOf(9), nextOffset = null)

        pager.show("排名")
        assertEquals(PagedList(listOf(1, 2, 3), total = 100, nextOffset = 5, isLoading = false), state)
        assertEquals(listOf(0, 2, 0), requests, "换回取过的条件不该再发请求")

        pager.loadMore()
        reply(3, listOf(4), nextOffset = null)
        assertEquals(listOf(1, 2, 3, 4), state.items, "续取接在换回来的条目后面")
        assertEquals(5, requests.last())
    }

    @Test
    fun `再次提交同一组条件照旧重取`() {
        val pager = pager()
        pager.show("排名")
        reply(0, listOf(1), nextOffset = null)
        pager.show("排名")
        assertEquals(listOf(0, 0), requests)
    }

    @Test
    fun `离开时首屏还没落定的条件不记，换回来重取；它迟到的结果不落进别的条件`() {
        val pager = pager()
        pager.show("排名")
        pager.show("收藏")
        reply(0, listOf(9), nextOffset = null)
        assertTrue(state.isLoading, "排名迟到的首屏不该落进收藏")
        reply(1, listOf(1), nextOffset = null)
        assertEquals(listOf(1), state.items)

        pager.show("排名")
        assertEquals(3, requests.size, "排名离开时首屏没落定，换回来要重取")
    }

    @Test
    fun `换到没显示过的条件，第一页走先用存下的那一路；再次提交、重试、续取照旧取`() {
        val firstShown = mutableListOf<Unit>()
        val pager = Pager(scope, { state }, { f -> state = f(state) }, fetchFirstShown = {
            firstShown += Unit
            Outcome.Success(PagedResult(listOf(7, 8), total = 50, nextOffset = 2))
        }) { offset ->
            requests += offset
            CompletableDeferred<Outcome<PagedResult<Int>>>().also { pending += it }.await()
        }

        pager.show("排名")
        assertEquals(PagedList(listOf(7, 8), total = 50, nextOffset = 2, isLoading = false), state)
        assertEquals(1, firstShown.size)
        assertEquals(emptyList(), requests, "存下的那一页够用时不发请求")

        pager.loadMore()
        reply(0, listOf(9), nextOffset = null)
        assertEquals(listOf(7, 8, 9), state.items, "续取接着存下的游标往下取")
        assertEquals(listOf(2), requests)

        pager.show("排名")
        assertEquals(listOf(2, 0), requests, "再次提交同一组条件照旧取")
        pager.refresh()
        assertEquals(listOf(2, 0, 0), requests, "重试照旧取")
        assertEquals(1, firstShown.size)
    }
}
