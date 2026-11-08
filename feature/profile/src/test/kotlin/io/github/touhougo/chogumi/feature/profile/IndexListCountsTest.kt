package io.github.touhougo.chogumi.feature.profile

import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.model.IndexBo
import kotlin.test.Test
import kotlin.test.assertEquals

/** 目录页两页的条数与打开时的页签（[IndexListCounts] 与 [IndexListUiState.countOf]）。 */
class IndexListCountsTest {

    @Test
    fun `创建过就落在创建的`() {
        assertEquals(IndexListPage.CREATED, IndexListCounts(created = 2, collected = 5).initialPage)
    }

    @Test
    fun `没创建过但收藏过_落在收藏的而不是空页`() {
        assertEquals(IndexListPage.COLLECTED, IndexListCounts(created = 0, collected = 2).initialPage)
    }

    @Test
    fun `两页都空_落在创建的`() {
        assertEquals(IndexListPage.CREATED, IndexListCounts(created = 0, collected = 0).initialPage)
    }

    @Test
    fun `页签标签上的数各取各的`() {
        val counts = IndexListCounts(created = 2, collected = 5)
        assertEquals(2, counts.countOf(IndexListPage.CREATED))
        assertEquals(5, counts.countOf(IndexListPage.COLLECTED))
    }

    @Test
    fun `列表第一页到了_页签的数取列表的total_不用资料头的旧数`() {
        // 资料头是建目录之前的缓存：「创建的 0」，列表里却已经有两条
        val state = IndexListUiState(page = IndexListPage.CREATED, headerCounts = IndexListCounts(created = 0, collected = 5))
        assertEquals(0, state.countOf(IndexListPage.CREATED), "第一页到之前先用资料头的数")

        val loaded = state.copy(lists = state.lists + (IndexListPage.CREATED to PagedList(items = listOf(index(1), index(2)), total = 2, isLoading = false)))
        assertEquals(2, loaded.countOf(IndexListPage.CREATED))
        assertEquals(5, loaded.countOf(IndexListPage.COLLECTED), "没打开的那页仍是资料头的数")
    }

    @Test
    fun `服务端没给total_取完了是已取到的条数_首屏失败仍用资料头的数`() {
        val state = IndexListUiState(page = IndexListPage.CREATED, headerCounts = IndexListCounts(created = 4, collected = 0))
        val withoutTotal = state.copy(lists = state.lists + (IndexListPage.CREATED to PagedList(items = listOf(index(1)), isLoading = false)))
        assertEquals(1, withoutTotal.countOf(IndexListPage.CREATED))

        val failed = state.copy(lists = state.lists + (IndexListPage.CREATED to PagedList(isLoading = false, error = Failure.Offline)))
        assertEquals(4, failed.countOf(IndexListPage.CREATED))
    }

    private fun index(id: Int) = IndexBo(id = id, title = "t$id", total = 0)
}
