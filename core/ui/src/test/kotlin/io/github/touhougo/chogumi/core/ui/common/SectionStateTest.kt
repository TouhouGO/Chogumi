package io.github.touhougo.chogumi.core.ui.common

import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import kotlin.test.Test
import kotlin.test.assertEquals

class SectionStateTest {

    @Test
    fun `加载中且无内容是 LOADING，不隐藏`() {
        assertEquals(SectionState.LOADING, sectionStateOf(isEmpty = true, isLoading = true))
    }

    @Test
    fun `失败且无内容是 ERROR，不隐藏`() {
        assertEquals(SectionState.ERROR, sectionStateOf(isEmpty = true, error = Failure.Offline))
    }

    @Test
    fun `只有加载完成、无错误且确实为空才是 EMPTY`() {
        assertEquals(SectionState.EMPTY, sectionStateOf(isEmpty = true))
    }

    @Test
    fun `有内容时一律 CONTENT，后台刷新或重试失败都保留已有内容`() {
        assertEquals(SectionState.CONTENT, sectionStateOf(isEmpty = false, isLoading = true))
        assertEquals(SectionState.CONTENT, sectionStateOf(isEmpty = false, error = Failure.Offline))
    }

    @Test
    fun `一拍 Load 走同一个判定：等待、取不到、就绪的空与非空`() {
        assertEquals(SectionState.LOADING, sectionStateOf(Load.Pending))
        assertEquals(SectionState.ERROR, sectionStateOf(Load.Failed(Failure.Offline)))
        assertEquals(SectionState.EMPTY, sectionStateOf(Load.Ready(emptyList<Int>())))
        assertEquals(SectionState.CONTENT, sectionStateOf(Load.Ready(listOf(1))))
        assertEquals(SectionState.CONTENT, sectionStateOf(Load.Ready(0)) { it > 0 })
    }

    @Test
    fun `分页列表走同一个判定：首屏加载、首屏失败、确认为空、有内容`() {
        assertEquals(SectionState.LOADING, sectionStateOf(PagedList<Int>(isLoading = true)))
        assertEquals(SectionState.ERROR, sectionStateOf(PagedList<Int>(isLoading = false, error = Failure.Offline)))
        assertEquals(SectionState.EMPTY, sectionStateOf(PagedList<Int>(isLoading = false)))
        assertEquals(SectionState.CONTENT, sectionStateOf(PagedList(items = listOf(1), isLoading = false)))
    }

    @Test
    fun `分页列表一页没有可见条目但还有下一页时不是空态，页脚继续续取`() {
        val filteredOut = PagedList<Int>(items = emptyList(), nextOffset = 20, isLoading = false)
        assertEquals(SectionState.CONTENT, sectionStateOf(filteredOut))
    }
}
