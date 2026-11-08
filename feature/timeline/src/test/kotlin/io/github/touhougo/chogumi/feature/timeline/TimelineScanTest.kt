package io.github.touhougo.chogumi.feature.timeline

import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TimelineTargetBo
import io.github.touhougo.chogumi.core.reader.TimelineFilter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 按类别往前找：什么时候停下（[scanPaused]），一轮取几页（[scanRound]）。 */
class TimelineScanTest {

    private var nextId = 1000L

    /** 一条动态：默认是单集进度（cat 4），[cat] 6 是日志。 */
    private fun item(cat: Int = 4, type: Int = 2) = TimelineItemBo(
        id = nextId--, uid = 1, user = null, cat = cat, type = type, createdAt = 0, text = "",
        targets = listOf(TimelineTargetBo(title = "目标", url = "")),
    )

    private fun progress(count: Int) = List(count) { item() }

    @Test
    fun `全部从不停下_连续够数的不展示动态也不停`() {
        // 没有目标的进度动态不展示，「全部」也不列：按计数它早该停了，「全部」照样不停
        val hidden = List(20) { item().copy(targets = emptyList()) }
        assertFalse(TimelineFilter.ALL.accepts(hidden.first()), "前提：这种动态「全部」也不列")
        assertFalse(scanPaused(progress(1) + hidden, TimelineFilter.ALL, resumeAfter = null, budget = 10))
    }

    @Test
    fun `最后一条同类之后连续不同类够数就停下，不够不停`() {
        val items = progress(3) + item(cat = 6) + progress(9)
        assertFalse(scanPaused(items, TimelineFilter.BLOG, resumeAfter = null, budget = 10))
        assertTrue(scanPaused(items + item(), TimelineFilter.BLOG, resumeAfter = null, budget = 10))
        // 一条同类都没有：从头数
        assertTrue(scanPaused(progress(10), TimelineFilter.BLOG, resumeAfter = null, budget = 10))
    }

    @Test
    fun `点了继续往前找从那一刻的最后一条重新计数，刷新后找不到那一条就照常数`() {
        val items = progress(10)
        val resumed = items.last().id
        assertFalse(scanPaused(items + progress(9), TimelineFilter.BLOG, resumeAfter = resumed, budget = 10))
        assertTrue(scanPaused(items + progress(10), TimelineFilter.BLOG, resumeAfter = resumed, budget = 10))
        assertTrue(scanPaused(items, TimelineFilter.BLOG, resumeAfter = -1, budget = 10))
    }

    /** 假的仓库：每次续取接上 [pages] 的下一页，取完就到底。 */
    private class FakeFeed(private val pages: List<List<TimelineItemBo>>, private val failAt: Int? = null) {
        var state = PagedList(items = pages.first(), isLoading = false, nextOffset = 1)
        var loads = 0

        fun load() {
            loads++
            if (loads == failAt) {
                state = state.copy(loadMoreError = Failure.Offline)
                return
            }
            val page = pages.getOrNull(loads).orEmpty()
            state = state.copy(items = state.items + page, loadMoreError = null, nextOffset = if (loads >= pages.lastIndex) null else loads + 1)
        }
    }

    private suspend fun round(feed: FakeFeed, filter: TimelineFilter, budget: Int = 100) = scanRound(
        feed = { feed.state },
        accepts = filter::accepts,
        paused = { scanPaused(it, filter, resumeAfter = null, budget = budget) },
        loadPage = feed::load,
    )

    @Test
    fun `一轮连取多页直到多出一条同类`() = runTest {
        val feed = FakeFeed(listOf(progress(20), progress(20), progress(19) + item(cat = 6), progress(20)))
        round(feed, TimelineFilter.BLOG)
        assertEquals(2, feed.loads)
        assertEquals(1, feed.state.items.count(TimelineFilter.BLOG::accepts))
    }

    @Test
    fun `全部一轮只取一页`() = runTest {
        val feed = FakeFeed(listOf(progress(20), progress(20), progress(20)))
        round(feed, TimelineFilter.ALL)
        assertEquals(1, feed.loads)
    }

    @Test
    fun `连续不同类够数就停下，不再往前取`() = runTest {
        val feed = FakeFeed(List(10) { progress(20) })
        round(feed, TimelineFilter.BLOG, budget = 60)
        assertEquals(2, feed.loads)
        assertEquals(60, feed.state.items.size)
    }

    @Test
    fun `到底或续取失败就结束这一轮`() = runTest {
        val ended = FakeFeed(listOf(progress(20), progress(20)))
        round(ended, TimelineFilter.BLOG)
        assertEquals(1, ended.loads)
        assertFalse(ended.state.hasMore)

        val failed = FakeFeed(List(5) { progress(20) }, failAt = 2)
        round(failed, TimelineFilter.BLOG)
        assertEquals(2, failed.loads)
    }

    @Test
    fun `上一轮失败留着页脚错误时，点重试这一轮照样去取`() = runTest {
        val feed = FakeFeed(listOf(progress(20), progress(20), progress(20)))
        feed.state = feed.state.copy(loadMoreError = Failure.Offline)
        round(feed, TimelineFilter.ALL)
        assertEquals(1, feed.loads)
        assertEquals(40, feed.state.items.size)
        assertNull(feed.state.loadMoreError)
    }

    @Test
    fun `续取没带来新动态就结束，不空转`() = runTest {
        val feed = FakeFeed(List(5) { progress(20) })
        scanRound(feed = { feed.state }, accepts = TimelineFilter.BLOG::accepts, paused = { false }, loadPage = { feed.loads++ })
        assertEquals(1, feed.loads)
    }
}
