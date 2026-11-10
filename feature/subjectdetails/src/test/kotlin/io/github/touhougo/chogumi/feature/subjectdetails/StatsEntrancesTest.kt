package io.github.touhougo.chogumi.feature.subjectdetails

import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.StatsData
import io.github.touhougo.chogumi.core.model.StatsDrawing
import io.github.touhougo.chogumi.core.model.StatsModule
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectSeries
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.statistics.EpisodeHeatCard
import io.github.touhougo.chogumi.core.model.statistics.EpisodeHeatCell
import io.github.touhougo.chogumi.core.model.statistics.SubjectStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** 进场进度在数据到了之后才建，元素数按那时的数据定；建了之后同一模块一直用这一份（不重播）。 */
class StatsEntrancesTest {

    private val module = StatsModule("module:1", StatsData.EPISODE_DISCUSSION, StatsDrawing.EPISODE_GRID)
    private val subject = SubjectBo(id = 8, type = SubjectType.ANIME, name = "n", nameCn = "")

    private fun tab(heat: EpisodeHeatCard?) = SubjectStatsTab(
        subject = subject,
        calculated = SubjectStats(
            score = null, episodeHeat = heat, lifeline = null, flow = null, controversy = null,
            airPhases = emptyList(), totalCollects = emptyList(), regdate = emptyList(), tags = emptyList(),
        ),
        ratingSplit = Load.Pending,
        episodeHeat = if (heat == null) Load.Pending else Load.Ready(heat),
        series = Load.Ready(SubjectSeries(emptyList(), emptyList())),
        lifeline = Load.Pending,
        recentCollectTimes = Load.Pending,
        recs = Load.Pending,
    )

    @Test
    fun `章节晚到时每集讨论的进场按到达时的格数建`() {
        val entrances = StatsEntrances(CoroutineScope(Dispatchers.Unconfined))

        entrances.of(module, tab(heat = null), ready = false, finished = false)
        val heat = EpisodeHeatCard(List(12) { EpisodeHeatCell(it + 1, it + 1.0, it, watched = false) })
        val arrived = entrances.of(module, tab(heat), ready = true, finished = false)

        assertEquals(12, arrived?.spatial?.size, "每一格各有一段进场")
        assertSame(arrived, entrances.of(module, tab(heat), ready = true, finished = false), "建过就一直是这一份")
    }
}
