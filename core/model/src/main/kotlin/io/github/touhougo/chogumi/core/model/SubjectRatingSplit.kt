package io.github.touhougo.chogumi.core.model

import kotlinx.serialization.Serializable

/** 打分时作品所处的阶段（网页「透视」页的放送时间分布）。 */
enum class AirPhase { BEFORE, AIRING, AFTER }

/**
 * 一部作品的评分按人群拆开：每组 1–10 分各多少人（下标 0 是 1 分，恒为 10 项）。只有网页「透视」页给，
 * 接口没有；取不到时整份为 null，依赖它的数值不画，持有这次读取的界面仍按自己的 [io.github.touhougo.chogumi.core.foundation.Load] 显示失败。
 *
 * @property byCollection 按打分人的收藏状态分组。
 * @property byAirPhase 按打分时作品处在放送前、放送中还是放送结束后分组。
 */
@Serializable
data class SubjectRatingSplit(
    val byCollection: Map<CollectionType, List<Int>>,
    val byAirPhase: Map<AirPhase, List<Int>>,
    /** 收藏数量的网页组编号（1、10、50、100、200、500、1000、1001），不是连续下标。 */
    val byTotalCollects: Map<Int, List<Int>> = emptyMap(),
    /** 注册时长的网页组编号（10、30、180、365、730、1095、1825）。 */
    val byRegdate: Map<Int, List<Int>> = emptyMap(),
)
