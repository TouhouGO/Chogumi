package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.AirPhase
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectRatingSplit
import io.github.touhougo.chogumi.core.network.model.web.StatsChartSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

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
    @Test
    fun `收藏数与注册时长保留网页真实编号而不是连续下标`() {
        val collectKeys = listOf("1", "10", "50", "100", "200", "500", "1000", "1001")
        val ageKeys = listOf("10", "30", "180", "365", "730", "1095", "1825")
        val split = ratingSplitOf(mapOf(
            "total_collects" to StatsChartSet(collectKeys.associateWith { done }),
            "regdate" to StatsChartSet(ageKeys.associateWith { after }),
        ))
        val json = Json { encodeDefaults = true }
        val encoded = json.parseToJsonElement(json.encodeToString(SubjectRatingSplit.serializer(), split)).jsonObject
        assertEquals(collectKeys.toSet(), encoded["byTotalCollects"]?.jsonObject?.keys, "收藏数分组不能丢掉")
        assertEquals(ageKeys.toSet(), encoded["byRegdate"]?.jsonObject?.keys, "注册时长分组不能丢掉")
    }

    @Test
    fun `旧评分拆分缓存缺少新两维仍可读取`() {
        val old = """{"byCollection":{"DONE":[0,0,0,0,0,0,0,20,0,0]},"byAirPhase":{}}"""
        val split = Json.decodeFromString(SubjectRatingSplit.serializer(), old)
        assertEquals(listOf(0,0,0,0,0,0,0,20,0,0), split.byCollection[CollectionType.DONE])
        assertEquals(emptyMap(), split.byTotalCollects)
        assertEquals(emptyMap(), split.byRegdate)
    }

    @Test
    fun `新维度未知编号跳过不把相邻组误读`() {
        val split = ratingSplitOf(mapOf("total_collects" to StatsChartSet(mapOf("2" to done, "1001" to after)),
            "regdate" to StatsChartSet(mapOf("1" to done, "1825" to after))))
        assertEquals(mapOf(1001 to after), split.byTotalCollects)
        assertEquals(mapOf(1825 to after), split.byRegdate)
    }

}
