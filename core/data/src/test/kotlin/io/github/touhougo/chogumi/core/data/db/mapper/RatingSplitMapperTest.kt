package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.AirPhase
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.network.model.web.StatsChartSet
import kotlin.test.Test
import kotlin.test.assertEquals

class RatingSplitMapperTest {

    private val done = listOf(35, 16, 13, 30, 137, 640, 3077, 8995, 7203, 3024)
    private val dropped = listOf(15, 3, 1, 4, 23, 18, 18, 10, 11, 3)
    private val airing = listOf(15, 3, 0, 0, 6, 16, 66, 169, 122, 103)
    private val after = listOf(40, 17, 14, 34, 158, 675, 3115, 9046, 7202, 3011)

    @Test
    fun `收藏状态按接口值认组（0 不是状态，丢掉），放送阶段 1 到 3 依次是前、中、后`() {
        val split = ratingSplitOf(
            mapOf(
                "interest_type" to StatsChartSet(mapOf("0" to after, "2" to done, "5" to dropped)),
                "airdate" to StatsChartSet(mapOf("2" to airing, "3" to after)),
                "other_chart" to StatsChartSet(mapOf("1" to done)),
            ),
        )

        assertEquals(mapOf(CollectionType.DONE to done, CollectionType.DROPPED to dropped), split.byCollection)
        assertEquals(mapOf(AirPhase.AIRING to airing, AirPhase.AFTER to after), split.byAirPhase)
    }

    @Test
    fun `缺图的那一维为空，不认识的组编号丢掉`() {
        val split = ratingSplitOf(mapOf("airdate" to StatsChartSet(mapOf("4" to after, "x" to after, "1" to airing))))

        assertEquals(emptyMap(), split.byCollection)
        assertEquals(mapOf(AirPhase.BEFORE to airing), split.byAirPhase)
    }
}
