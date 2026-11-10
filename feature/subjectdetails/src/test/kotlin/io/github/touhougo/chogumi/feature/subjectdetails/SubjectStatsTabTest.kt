package io.github.touhougo.chogumi.feature.subjectdetails

import io.github.touhougo.chogumi.core.model.statistics.SubjectStats
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectSeries
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.common.SectionState
import kotlin.test.Test
import kotlin.test.assertEquals

/** 统计 tab 各块等哪几项数据、去向图的排法与占比的写法。 */
class SubjectStatsTabTest {

    private val subject = SubjectBo(id = 8, type = SubjectType.ANIME, name = "n", nameCn = "")
    private val noCards = SubjectStats(
        score = null, episodeHeat = null, lifeline = null, flow = null,
        controversy = null, airPhases = emptyList(), totalCollects = emptyList(), regdate = emptyList(), tags = emptyList(),
    )

    /** 条目本体已到的页面状态里统计读的那部分。 */
    private fun sourceOf(state: SubjectDetailsUiState) = checkNotNull(statsSourceOf(state.copy(subjectLoad = Load.Ready(subject))))

    @Test
    fun `一生与每集讨论只等章节，透视页、最近收藏与系列随后补上`() {
        val episodesOnly = SubjectDetailsUiState(episodes = Load.Ready(emptyList()))
        assertEquals(Load.Ready(null), statsTabOf(sourceOf(episodesOnly), noCards).lifeline)
        assertEquals(Load.Ready(null), statsTabOf(sourceOf(episodesOnly), noCards).episodeHeat)
        assertEquals(Load.Pending, statsTabOf(sourceOf(SubjectDetailsUiState()), noCards).lifeline)
        assertEquals(Load.Pending, statsTabOf(sourceOf(SubjectDetailsUiState()), noCards).episodeHeat)
    }

    @Test
    fun `分数尺保留透视页的完整Load，失败由评分卡原位画`() {
        assertEquals(Load.Pending, statsTabOf(sourceOf(SubjectDetailsUiState()), noCards).ratingSplit)
        val failed = SubjectDetailsUiState(ratingSplit = Load.Failed(Failure.Unknown))
        assertEquals(Load.Failed(Failure.Unknown), statsTabOf(sourceOf(failed), noCards).ratingSplit)
    }

    @Test
    fun `一生卡保留最近收藏的完整Load，辅助读取失败不抹掉卡主体`() {
        val failed = SubjectDetailsUiState(
            episodes = Load.Ready(emptyList()),
            recentCollectTimes = Load.Failed(Failure.Http(500)),
        )
        val tab = statsTabOf(sourceOf(failed), noCards)
        assertEquals(Load.Ready(null), tab.lifeline)
        assertEquals(Load.Failed(Failure.Http(500)), tab.recentCollectTimes)
    }

    @Test
    fun `系列取不到不画重试（重试在关联那一块），没有系列也不画`() {
        assertEquals(SectionState.EMPTY, seriesSectionState(Load.Failed(Failure.Unknown)))
        assertEquals(SectionState.EMPTY, seriesSectionState(Load.Ready(SubjectSeries(before = emptyList(), after = emptyList()))))
        assertEquals(SectionState.LOADING, seriesSectionState(Load.Pending))
    }

    @Test
    fun `占比：百分之十及以上取整，以下一位小数`() {
        assertEquals(listOf("93", "10", "4.6", "0.8"), listOf(0.9285, 0.1, 0.0464, 0.0076).map(::formatShare))
    }

    @Test
    fun `去向：节点按占比分高、至少最小高，行高至少一行字`() {
        val rows = flowRows(listOf(0.9, 0.05, 0.05), budget = 100f, minNode = 6f, line = 20f, gap = 10f)
        assertEquals(listOf(0f, 100f, 130f), rows.map { it.top })
        assertEquals(listOf(90f, 6f, 6f), rows.map { it.node })
        assertEquals(listOf(90f, 20f, 20f), rows.map { it.height })
    }
}
