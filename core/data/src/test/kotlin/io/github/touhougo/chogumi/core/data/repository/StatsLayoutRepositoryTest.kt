package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.stats.StatsModuleEntity
import io.github.touhougo.chogumi.core.data.db.stats.toStatsRows
import io.github.touhougo.chogumi.core.model.StatsData
import io.github.touhougo.chogumi.core.model.StatsDrawing
import io.github.touhougo.chogumi.core.model.defaultStatsLayout
import io.github.touhougo.chogumi.core.model.newStatsModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class StatsLayoutRepositoryTest {
    private val dao = FakeStatsModuleDao()
    private val repository = StatsLayoutRepository(dao)

    @Test
    fun `默认七模块保留内容顺序且争议度不在页面`() = runTest {
        assertEquals(listOf(StatsData.RATING, StatsData.STATUS_RATING, StatsData.COLLECTION, StatsData.LIFELINE,
            StatsData.EPISODE_DISCUSSION, StatsData.SERIES, StatsData.RECOMMENDATIONS), repository.observe().first().map { it.data })
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `重复数据改名换画法排序隐藏存取往返`() = runTest {
        val defaults = defaultStatsLayout()
        val added = defaults.newStatsModule(StatsData.RATING).copy(name = "另一种评分", drawing = StatsDrawing.RATING_DOTS)
        val expected = listOf(added) + defaults.map { it.copy(hidden = true) }
        repository.edit { expected }
        assertEquals(expected, repository.observe().first())
        assertEquals(expected.indices.toList(), dao.rows.value.map { it.position })
    }

    @Test
    fun `未知类型与画法行跳过不拖垮后续行`() = runTest {
        val known = defaultStatsLayout().take(2)
        dao.rows.value = known.toStatsRows().take(1) + listOf(
            StatsModuleEntity(1, """{"type":"future"}"""),
            StatsModuleEntity(2, """{"type":"module","id":"future:1","data":"RATING","drawing":"FUTURE"}"""),
            StatsModuleEntity(3, """{"type":"module","id":"wrong:1","data":"RATING","drawing":"LIFELINE"}"""),
        ) + known.toStatsRows().drop(1).map { it.copy(position = 4) }
        assertEquals(known, repository.observe().first())
    }
}
