package io.github.touhougo.chogumi.core.model.statistics

import io.github.touhougo.chogumi.core.model.AirPhase
import io.github.touhougo.chogumi.core.model.AirState
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectRatingSplit
import io.github.touhougo.chogumi.core.model.SubjectRelationBo
import io.github.touhougo.chogumi.core.model.SubjectSeries
import io.github.touhougo.chogumi.core.model.collectionCountOf
import java.time.LocalDate

// 作品详情「统计」tab 的各张卡（唯一计算处）：界面只画这里的结果，一张卡没有数据就是 null，整张不画。

/** 统计 tab 出现所需的最少评分人数；按人群拆开的一组也要有这么多人才算出均分。 */
const val STATS_MIN_RATINGS = 10

/**
 * 统计 tab 是否出现（唯一判定）：公共评分关掉、或评分人数不到 [STATS_MIN_RATINGS] 时不出现。
 * [ratingTotal] 为 null 表示条目还没到（骨架阶段），按出现处理，与「类型未知按含章节」同口径。
 */
fun subjectStatsVisible(ratingTotal: Int?, features: AppFeatures): Boolean =
    features.publicRatings && (ratingTotal == null || ratingTotal >= STATS_MIN_RATINGS)

/** 一组人的平均分。 */
data class GroupMean<K>(val group: K, val mean: Double)

/**
 * 评分卡：1–10 分各多少人、各收藏状态的人平均打几分、我打的分。
 * @property byCollection 看过、在看、搁置、抛弃的顺序，人数不到 [STATS_MIN_RATINGS] 的组不出现；网页透视页取不到时为空。
 * @property mine 我打的分；没登录或没打分为 null。
 */
data class ScoreCard(
    val counts: List<Int>,
    val byCollection: List<GroupMean<CollectionType>>,
    val mine: Int?,
)

/** 每集讨论热度的一格：[watched] 是我标了看过这一集。 */
data class EpisodeHeatCell(val episodeId: Int, val sort: Double, val comments: Int, val watched: Boolean)

/** 每集讨论热度：已播出的正片按集数排，最多最近 [EpisodeTracking.EPISODE_RANGE_SIZE] 集。 */
data class EpisodeHeatCard(val cells: List<EpisodeHeatCell>)

/** 开始看的人的一条去向：[share] 是占开始看的人的比例（0..1）。 */
data class FlowBranch(val type: CollectionType, val count: Int, val share: Double)

/**
 * 去向卡：开始看的人（看过 + 在看 + 搁置 + 抛弃）各去了哪，顺序固定为看过、在看、搁置、抛弃；
 * 想看的人还没开始，单独给 [wish]。
 */
data class FlowCard(val started: Int, val branches: List<FlowBranch>, val wish: Int)

/** 时间线上的一集：集数、播出日与讨论数。 */
data class LifelineEpisode(val episodeId: Int, val sort: Double, val airdate: LocalDate, val comments: Int)

/** 收藏的速度按哪个时间单位说。 */
enum class PaceUnit(val days: Int) { DAY(1), WEEK(7), MONTH(30) }

/** 最近每 [unit] 有 [count] 人收藏。 */
data class CollectPace(val count: Int, val unit: PaceUnit)

/**
 * 一生时间线：放送前、放送中、放送结束后三段各自的平均分（人数够的段才有），已播出正片的播出日与讨论数，
 * 之后的续作，以及最近收藏的速度。[ended] 是正片已全部播出：时间线才有「完结后」那一段，否则放送中一直画到今天。
 */
data class LifelineCard(
    val phases: List<GroupMean<AirPhase>>,
    val episodes: List<LifelineEpisode>,
    val ended: Boolean,
    val sequels: List<SubjectRelationBo>,
    val pace: CollectPace?,
)

/** 统计 tab 的全部卡；系列与推荐是作品列表，直接画，不经这里。 */
data class SubjectStats(
    val score: ScoreCard?,
    val episodeHeat: EpisodeHeatCard?,
    val lifeline: LifelineCard?,
    val flow: FlowCard?,
)

/**
 * 统计 tab 的输入：条目本体之外，各项都可能还没到或取不到，取不到的那张卡就不出现。
 * @property myRate 我打的分，0 是没打分。
 * @property watchStates 我各集的观看状态（章节 id → 状态）。
 * @property recentCollectTimes 最近一批收藏者的收藏时间（毫秒），用来算收藏速度。
 */
data class SubjectStatsInput(
    val subject: SubjectBo,
    val split: SubjectRatingSplit? = null,
    val myRate: Int = 0,
    val episodes: List<EpisodeBo> = emptyList(),
    val watchStates: Map<Int, EpisodeStatus> = emptyMap(),
    val series: SubjectSeries? = null,
    val recentCollectTimes: List<Long> = emptyList(),
)

private const val RATING_LEVELS = 10

/** 开始看之后的四种去向及其顺序：评分卡按它列各组均分，去向图按它分支。 */
private val STARTED_STATES = listOf(CollectionType.DONE, CollectionType.DOING, CollectionType.ON_HOLD, CollectionType.DROPPED)

/** 条目 → 统计 tab 各张卡（唯一计算处）。[today] 判哪些集已播出，[nowMillis] 算收藏速度。 */
fun subjectStatsOf(input: SubjectStatsInput, today: LocalDate, nowMillis: Long): SubjectStats {
    val mains = EpisodeTracking.mainEpisodes(input.episodes)
    val airedMains = mains.filter { EpisodeTracking.airState(it, today) != AirState.UNAIRED }
    return SubjectStats(
        score = scoreOf(input),
        episodeHeat = episodeHeatOf(airedMains, input.watchStates),
        lifeline = lifelineOf(input, airedMains, ended = airedMains.size == mains.size, nowMillis),
        flow = flowOf(input.subject),
    )
}

/** 1–10 分人数的平均分；没有人打分为 null。 */
fun meanScoreOf(counts: List<Int>): Double? {
    val total = counts.sumOf { it.toLong() }
    if (total <= 0) return null
    return counts.withIndex().sumOf { (index, count) -> (index + 1) * count.toDouble() } / total
}

/** 人数够 [STATS_MIN_RATINGS] 的组才算均分，按 [order] 排。 */
private fun <K> groupMeansOf(groups: Map<K, List<Int>>, order: List<K>): List<GroupMean<K>> =
    order.mapNotNull { key ->
        groups[key]?.takeIf { it.size == RATING_LEVELS && it.sum() >= STATS_MIN_RATINGS }
            ?.let(::meanScoreOf)
            ?.let { GroupMean(key, it) }
    }

private fun scoreOf(input: SubjectStatsInput): ScoreCard? {
    val counts = input.subject.ratingCounts.takeIf { it.size == RATING_LEVELS && it.sum() > 0 } ?: return null
    return ScoreCard(
        counts = counts,
        byCollection = input.split?.let { groupMeansOf(it.byCollection, STARTED_STATES) }.orEmpty(),
        mine = input.myRate.takeIf { it in 1..RATING_LEVELS },
    )
}

private fun episodeHeatOf(airedMains: List<EpisodeBo>, watchStates: Map<Int, EpisodeStatus>): EpisodeHeatCard? {
    val recent = airedMains.takeLast(EpisodeTracking.EPISODE_RANGE_SIZE)
    if (recent.none { it.comment > 0 }) return null
    return EpisodeHeatCard(recent.map { EpisodeHeatCell(it.id, it.sort, it.comment, watchStates[it.id] == EpisodeStatus.DONE) })
}

private fun lifelineOf(input: SubjectStatsInput, airedMains: List<EpisodeBo>, ended: Boolean, nowMillis: Long): LifelineCard? {
    val episodes = airedMains.mapNotNull { episode ->
        EpisodeTracking.airdateOf(episode)?.let { LifelineEpisode(episode.id, episode.sort, it, episode.comment) }
    }.sortedBy { it.airdate }
    // 只播一集（剧场版、单集 OVA）或没有播出日的作品没有「一生」可画
    if (episodes.size < 2) return null
    return LifelineCard(
        phases = input.split?.let { groupMeansOf(it.byAirPhase, AirPhase.entries) }.orEmpty(),
        episodes = episodes,
        ended = ended,
        sequels = input.series?.after.orEmpty(),
        pace = collectPaceOf(input.recentCollectTimes, nowMillis),
    )
}

/**
 * 最近收藏的速度：这批收藏者从最早一位到现在平均每天几人（四舍五入），得 0 改按每周、再得 0 按每月说，每月也得 0 为 null。
 * 量到「现在」而不是最晚一位：久没人收藏的作品速度随之变慢。少于 2 人说不出速度，为 null。
 */
fun collectPaceOf(times: List<Long>, nowMillis: Long): CollectPace? {
    if (times.size < 2) return null
    val spanMillis = nowMillis - times.min()
    if (spanMillis <= 0) return null
    val perDay = times.size.toDouble() * DAY_MILLIS / spanMillis
    return PaceUnit.entries.firstNotNullOfOrNull { unit ->
        Math.round(perDay * unit.days).toInt().takeIf { it >= 1 }?.let { CollectPace(it, unit) }
    }
}

private fun flowOf(subject: SubjectBo): FlowCard? {
    val counts = STARTED_STATES.map { subject.collectionCountOf(it) }
    val started = counts.sum()
    if (started <= 0) return null
    return FlowCard(
        started = started,
        branches = STARTED_STATES.zip(counts) { type, count -> FlowBranch(type, count, count.toDouble() / started) },
        wish = subject.collectionWish,
    )
}
