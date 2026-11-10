package io.github.touhougo.chogumi.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubjectStatsLayoutTest {
    @Test
    fun `重复数据可以改名换画法增删排序且新身份从当前布局分配`() {
        val first = defaultStatsLayout()
        val draft = first.newStatsModule(StatsData.RATING).copy(name = "我的评分图", drawing = StatsDrawing.RATING_DOTS)
        val once = first.withStatsModule(draft, true)
        val twice = once.withStatsModule(draft, true)
        assertEquals(listOf("module:1", "module:2"), twice.takeLast(2).map { it.id })
        assertEquals(listOf(StatsDrawing.RATING_DOTS, StatsDrawing.RATING_DOTS), twice.takeLast(2).map { it.drawing })
        assertEquals("我的评分图", twice.last().name)
        val hidden = twice.withoutStatsModule("module:1")
        assertTrue(hidden.first { it.id == "module:1" }.hidden)
        assertFalse(hidden.last().hidden)
        val reordered = hidden.reorderedStats(listOf("module:2", first.first().id))
        assertEquals(listOf("module:2", first.first().id), reordered.take(2).map { it.id })
        assertEquals(first.drop(1).map { it.id } + "module:1", reordered.drop(2).map { it.id })
    }
}
