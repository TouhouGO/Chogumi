package io.github.touhougo.chogumi.feature.subjectdetails

import io.github.touhougo.chogumi.core.model.statistics.EpisodeHeatCard
import io.github.touhougo.chogumi.core.model.statistics.FlowCard
import io.github.touhougo.chogumi.core.model.statistics.LifelineCard
import io.github.touhougo.chogumi.core.model.statistics.ScoreCard
import io.github.touhougo.chogumi.core.model.statistics.SubjectStats
import io.github.touhougo.chogumi.core.model.statistics.SubjectStatsInput
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.orEmpty
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectRatingSplit
import io.github.touhougo.chogumi.core.model.SubjectSeries
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf

/**
 * 统计 tab 的一拍：卡片和它们依赖的独立读取仍带着各自的 [Load]。等待时画同尺寸骨架，初始失败时原位重试，
 * 只有确认没有内容才整块不画；评分卡与一生卡的主体不因辅助读取失败而消失。
 */
internal data class SubjectStatsTab(
    val subject: SubjectBo,
    val score: ScoreCard?,
    val ratingSplit: Load<*>,
    val episodeHeat: Load<EpisodeHeatCard?>,
    val series: Load<SubjectSeries>,
    val lifeline: Load<LifelineCard?>,
    val recentCollectTimes: Load<*>,
    val flow: FlowCard?,
    val recs: Load<List<SubjectBo>>,
)

/**
 * 统计 tab 读的那部分页面状态，只由 [statsSourceOf] 从页面状态里摘出：统计按它算，它不变就不重算——
 * 点 tab、开关面板这类与统计无关的状态变化不在主线程把各张卡再算一遍。条目本体还没到时没有。
 */
internal data class StatsSource(
    val subject: SubjectBo,
    val ratingSplit: Load<SubjectRatingSplit>,
    val myRate: Int,
    val episodes: Load<List<EpisodeBo>>,
    val watchStates: Map<Int, EpisodeStatus>,
    val series: Load<SubjectSeries>,
    val recentCollectTimes: Load<List<Long>>,
    val recs: Load<List<SubjectBo>>,
) {
    /** 各张卡（[subjectStatsOf]）的输入。 */
    val input: SubjectStatsInput
        get() = SubjectStatsInput(
            subject = subject,
            split = ratingSplit.valueOrNull(),
            myRate = myRate,
            episodes = episodes.orEmpty(),
            watchStates = watchStates,
            series = series.valueOrNull(),
            recentCollectTimes = recentCollectTimes.orEmpty(),
        )
}

internal fun statsSourceOf(state: SubjectDetailsUiState): StatsSource? = state.subject?.let { subject ->
    StatsSource(
        subject = subject,
        ratingSplit = state.ratingSplit,
        myRate = state.collection.valueOrNull()?.rate ?: 0,
        episodes = state.episodes,
        watchStates = state.episodeWatchStates,
        series = state.series,
        recentCollectTimes = state.recentCollectTimes,
        recs = state.recs,
    )
}

/** 统计读的状态 + 算好的卡 → 统计 tab 的一拍。每张卡等哪几项数据只写在这里。 */
internal fun statsTabOf(source: StatsSource, stats: SubjectStats): SubjectStatsTab =
    SubjectStatsTab(
        subject = source.subject,
        score = stats.score,
        ratingSplit = source.ratingSplit,
        episodeHeat = whenSettled(source.episodes) { stats.episodeHeat },
        series = source.series,
        // 一生的骨架是各集；各段均分（透视页）、收藏速度与续作晚到时先空着，到了补上（没缓存的透视页服务端要算上一分多钟）
        lifeline = whenSettled(source.episodes) { stats.lifeline },
        recentCollectTimes = source.recentCollectTimes,
        flow = stats.flow,
        recs = source.recs,
    )

/** [source] 有了结果（就绪或取不到）才给出 [value]，否则还在等。 */
private fun <T> whenSettled(source: Load<*>, value: () -> T): Load<T> =
    if (source is Load.Pending) Load.Pending else Load.Ready(value())

/**
 * 系列这一块的三态（概览与统计 tab 共用）：系列取不到只因为本页的关联取不到，重试在关联那一块，这里不另画重试，整块不出现。
 */
internal fun seriesSectionState(series: Load<SubjectSeries>): SectionState =
    when (val state = sectionStateOf(series) { it.isEmpty }) {
        SectionState.ERROR -> SectionState.EMPTY
        else -> state
    }
