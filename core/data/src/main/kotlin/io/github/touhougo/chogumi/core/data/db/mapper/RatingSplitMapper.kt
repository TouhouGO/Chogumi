package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.AirPhase
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.REGDATE_GROUPS
import io.github.touhougo.chogumi.core.model.SubjectRatingSplit
import io.github.touhougo.chogumi.core.model.TOTAL_COLLECT_GROUPS
import io.github.touhougo.chogumi.core.network.model.web.StatsChartSet

/** 透视页按打分人收藏状态拆的那张图；组编号就是收藏状态的接口值（1 想看 … 5 抛弃）。 */
private const val BY_COLLECTION_CHART = "interest_type"

/** 透视页按打分时放送阶段拆的那张图；组编号 1、2、3 依次是放送前、放送中、放送结束后。 */
private const val BY_AIR_PHASE_CHART = "airdate"

/**
 * 透视页的图 → 评分拆分。缺了哪张图那一维就是空的；不认识的组编号丢掉。
 * 「整页认不出」在抓取时已按坏响应抛出，到这里的都是认得出的页面。
 */
fun ratingSplitOf(charts: Map<String, StatsChartSet>): SubjectRatingSplit = SubjectRatingSplit(
    byCollection = charts[BY_COLLECTION_CHART].groupsBy { value ->
        CollectionType.from(value)?.takeIf { it in CollectionType.choices }
    },
    byAirPhase = charts[BY_AIR_PHASE_CHART].groupsBy { AirPhase.entries.getOrNull(it - 1) },
    byTotalCollects = charts["total_collects"].groupsBy { it.takeIf { key -> key in TOTAL_COLLECT_GROUPS } },
    byRegdate = charts["regdate"].groupsBy { it.takeIf { key -> key in REGDATE_GROUPS } },
)

private fun <K> StatsChartSet?.groupsBy(keyOf: (Int) -> K?): Map<K, List<Int>> =
    this?.counts.orEmpty().entries.mapNotNull { (group, counts) ->
        group.toIntOrNull()?.let(keyOf)?.let { it to counts }
    }.toMap()
