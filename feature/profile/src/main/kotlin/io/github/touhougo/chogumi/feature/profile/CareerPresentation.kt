package io.github.touhougo.chogumi.feature.profile

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.data.repository.SyncProgress
import io.github.touhougo.chogumi.core.data.repository.shouldAutoSummarize
import io.github.touhougo.chogumi.core.model.CareerStatistics
import io.github.touhougo.chogumi.core.model.CollectionStatistics
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.HIGH_SCORE_MIN
import io.github.touhougo.chogumi.core.model.MIN_RATED_FOR_VERDICT
import io.github.touhougo.chogumi.core.model.RatedWork
import io.github.touhougo.chogumi.core.model.RatingVerdict
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.labelResFor
import kotlin.math.roundToInt

/**
 * 生涯（生涯卡 + 完整统计）的**纯函数层**：界面画什么只由这里决定，两处界面都只读结果。
 *
 * 口径（数怎么算）在 core:data 的 `calculateCollectionStatistics`；这里只做「拿到的数怎么呈现」，
 * 不算任何统计——百分比取整、峰值、分箱都是对已有数据的读取。
 */

/**
 * 生涯里**只属于生涯的、随条目类型变化的决定**：单位、称号、三格大数字的组成。
 * 收藏状态的说法不在这里，由 [CollectionType.labelResFor] 按规格表的动词选择。
 *
 * 单位出自原型 `STATS[k].unit`；三格大数字出自原型 `STATS[k].kpi`。
 * 三次元原型里是空态示例，没有给 KPI——这里按「看」类条目取「看过的集数 / 看完 / 在看」
 * （我的设计决定，not in M3：三次元也按集推进，与动画同口径）。
 */
internal data class CareerTypeSpec(
    /** 计数单位（部 / 本 / 张）。 */
    val unitRes: Int,
    /** 生涯卡上的称号：带题材的（「科幻迷」「推理书虫」）与没有题材时的（「动画迷」「书虫」）。 */
    val personaRes: Int,
    val personaPlainRes: Int,
    /**
     * 该类型的三格大数字（顺序即界面顺序）。第二个参数是「看的是不是自己」：
     * 本人的集数走逐集表（精确），他人的集数是话数加总（近似，标「≈」）。
     */
    val kpis: (stats: CollectionStatistics, isSelf: Boolean) -> List<CareerKpi>,
)

internal fun careerSpecOf(type: SubjectType): CareerTypeSpec = when (type) {
    SubjectType.BOOK -> CareerTypeSpec(
        unitRes = R.string.career_unit_book,
        personaRes = R.string.career_persona_book,
        personaPlainRes = R.string.career_persona_book_plain,
    ) { s, _ ->
        listOf(
            CareerKpi(s.volumeProgress, R.string.career_unit_volume, R.string.career_kpi_volumes),
            CareerKpi(s.chapterProgress, R.string.career_unit_chapter, R.string.career_kpi_chapters),
            CareerKpi(s.countOf(CollectionType.DONE), R.string.career_unit_book, R.string.career_kpi_finished_book),
        )
    }
    SubjectType.GAME -> CareerTypeSpec(
        unitRes = R.string.career_unit_title,
        personaRes = R.string.career_persona_game,
        personaPlainRes = R.string.career_persona_game_plain,
    ) { s, _ ->
        listOf(
            CareerKpi(s.countOf(CollectionType.DONE), R.string.career_unit_title, CollectionType.DONE.labelResFor(type)),
            CareerKpi(s.countOf(CollectionType.DOING), R.string.career_unit_title, CollectionType.DOING.labelResFor(type)),
            CareerKpi(s.formCounts.size, R.string.career_unit_platform, R.string.career_kpi_platforms),
        )
    }
    SubjectType.MUSIC -> CareerTypeSpec(
        unitRes = R.string.career_unit_album,
        personaRes = R.string.career_persona_music,
        personaPlainRes = R.string.career_persona_music_plain,
    ) { s, _ ->
        listOf(
            CareerKpi(s.countOf(CollectionType.DONE), R.string.career_unit_album, CollectionType.DONE.labelResFor(type)),
            CareerKpi(s.countOf(CollectionType.DOING), R.string.career_unit_album, CollectionType.DOING.labelResFor(type)),
            CareerKpi(s.countOf(CollectionType.WISH), R.string.career_unit_album, CollectionType.WISH.labelResFor(type)),
        )
    }
    // 动画 / 三次元 / 未知：看类。投入时间只给动画（三次元没有可用的每集时长估算口径）。
    SubjectType.ANIME, SubjectType.REAL, SubjectType.UNKNOWN -> CareerTypeSpec(
        unitRes = R.string.career_unit_title,
        personaRes = if (type == SubjectType.REAL) R.string.career_persona_real else R.string.career_persona_anime,
        personaPlainRes = if (type == SubjectType.REAL) R.string.career_persona_real_plain else R.string.career_persona_anime_plain,
    ) { s, isSelf ->
        val episodes = CareerKpi(
            s.episodeProgress, R.string.career_unit_episode, R.string.career_kpi_episodes,
            approximate = !isSelf && s.episodeProgress > 0,
        )
        val finished = CareerKpi(s.countOf(CollectionType.DONE), R.string.career_unit_title, R.string.career_kpi_finished)
        if (type == SubjectType.ANIME) {
            listOf(episodes, watchTimeKpiOf(s.watchTime.minutes, s.watchTime.estimated), finished)
        } else {
            listOf(episodes, finished, CareerKpi(s.countOf(CollectionType.DOING), R.string.career_unit_title, CollectionType.DOING.labelResFor(type)))
        }
    }
}

/** 一格大数字：数、单位、名称；[approximate] 时数字前加「≈」。 */
internal data class CareerKpi(
    val value: Int,
    val unitRes: Int,
    val labelRes: Int,
    val approximate: Boolean = false,
)

/** 某一状态的条数（统计层没有给出的状态即 0）。 */
internal fun CollectionStatistics.countOf(type: CollectionType): Int = statusCounts[type] ?: 0

/** 三格大数字的唯一入口：类型决定选哪三格，[isSelf] 决定集数是否标「≈」。 */
internal fun careerKpisOf(type: SubjectType, stats: CollectionStatistics, isSelf: Boolean): List<CareerKpi> =
    careerSpecOf(type).kpis(stats, isSelf)

/**
 * 投入时间：不到 1000 小时写「小时」，超过换成「天」（原型左栏：2070 小时 ≈ 86 天，
 * 大数字格放得下，也更好体会——原型的设计决定，not in M3）。
 */
internal fun watchTimeKpiOf(minutes: Int, estimated: Boolean): CareerKpi {
    val hours = (minutes / 60.0).roundToInt()
    return if (hours < HOURS_BEFORE_DAYS) {
        CareerKpi(hours, R.string.career_unit_hour, R.string.career_kpi_watch_time, approximate = estimated)
    } else {
        CareerKpi((minutes / 60.0 / 24).roundToInt(), R.string.career_unit_day, R.string.career_kpi_watch_time, approximate = estimated)
    }
}

private const val HOURS_BEFORE_DAYS = 1000

/** 计数最多的一项；并列取先出现的（原型 `peakOf`：严格大于才替换）。空表为 null。 */
internal fun <T> List<T>.peakBy(count: (T) -> Int): T? =
    fold(null as T?) { best, item -> if (best == null || count(item) > count(best)) item else best }

// ---- 状态阶梯 ----

/**
 * 进度阶梯（生涯卡）与华夫格（统计页）读的五态计数。**唯一来源选择**：
 * 有统计快照就用快照的 [CollectionStatistics.statusCounts]；他人还没汇总完时用服务端摘要
 * （`/p1/users/{u}` 的 stats，一次请求、立即可用——原型「进度条立即画」）。
 */
data class StatusLadder(
    val wish: Int,
    val doing: Int,
    val done: Int,
    val onHold: Int,
    val dropped: Int,
) {
    val total: Int get() = wish + doing + done + onHold + dropped

    /** 华夫格与数据表的顺序：看过 → 在看 → 想看 → 搁置 → 抛弃（原型 `waffle` 的 order）。 */
    val waffleOrder: List<Pair<CollectionType, Int>>
        get() = listOf(
            CollectionType.DONE to done,
            CollectionType.DOING to doing,
            CollectionType.WISH to wish,
            CollectionType.ON_HOLD to onHold,
            CollectionType.DROPPED to dropped,
        )

    companion object {
        fun of(counts: Map<CollectionType, Int>) = StatusLadder(
            wish = counts[CollectionType.WISH] ?: 0,
            doing = counts[CollectionType.DOING] ?: 0,
            done = counts[CollectionType.DONE] ?: 0,
            onHold = counts[CollectionType.ON_HOLD] ?: 0,
            dropped = counts[CollectionType.DROPPED] ?: 0,
        )
    }
}

// ---- 一类生涯此刻显示什么 ----

/** 他人某一类的汇总进度（会话内，VM 持有）。 */
sealed interface OtherSummary {
    /** 正在现拉；[progress] 拿到总页数前为不定进度。 */
    data class Running(val progress: SyncProgress) : OtherSummary

    data class Loaded(val stats: CollectionStatistics) : OtherSummary

    data class Failed(val failure: Failure) : OtherSummary
}

/**
 * 生涯卡与统计页**此刻画哪一种**——两处界面共用这一个判定，不各写一套 `if`。
 */
sealed interface CareerContent {
    /** 本人：本地统计流还没发第一拍；他人：资料（服务端摘要）还没到。 */
    data object Loading : CareerContent

    /** 他人：资料没取到，没有阶梯可画，给重试（重取资料）。 */
    data class Unavailable(val failure: Failure) : CareerContent

    /** 这一类没有（公开的）收藏。 */
    data object Empty : CareerContent

    /** 他人：正在汇总，阶梯先用服务端摘要画。 */
    data class Summarizing(val ladder: StatusLadder, val progress: SyncProgress?) : CareerContent

    /** 他人：这一类超过自动汇总上限，等用户点「汇总 X 的 N 部」。 */
    data class AwaitingConfirm(val ladder: StatusLadder, val count: Int) : CareerContent

    /** 他人：汇总失败，阶梯照画，给重试。 */
    data class Failed(val ladder: StatusLadder, val failure: Failure) : CareerContent

    data class Ready(val stats: CollectionStatistics, val ladder: StatusLadder) : CareerContent
}

/**
 * 由 VM 的原始状态推出某一类此刻的显示形态。**唯一判定点**（见 [CareerContent]）。
 *
 * - 本人：[ownStats] 为 null 即还没加载；有了就看总数是否为 0。
 * - 他人：[serverCounts] 还在等即资料还没到，取不到即 [CareerContent.Unavailable]；摘要为 0 即没有公开收藏（不拉）；
 *   之后按 [summary] 的进度决定；还没开始汇总时，是否超过自动上限由 [shouldAutoSummarize] 定。
 */
internal fun careerContentOf(
    type: SubjectType,
    isSelf: Boolean,
    ownStats: Map<SubjectType, CollectionStatistics>?,
    serverCounts: Load<Map<SubjectType, Map<CollectionType, Int>>>,
    summary: OtherSummary?,
): CareerContent {
    if (isSelf) {
        val stats = ownStats?.get(type) ?: return if (ownStats == null) CareerContent.Loading else CareerContent.Empty
        if (stats.totalCount == 0) return CareerContent.Empty
        return CareerContent.Ready(stats, StatusLadder.of(stats.statusCounts))
    }
    val server = when (serverCounts) {
        Load.Pending -> return CareerContent.Loading
        is Load.Failed -> return CareerContent.Unavailable(serverCounts.failure)
        is Load.Ready -> serverCounts.value
    }
    val serverLadder = StatusLadder.of(server[type].orEmpty())
    if (serverLadder.total == 0) return CareerContent.Empty
    return when (summary) {
        is OtherSummary.Loaded ->
            if (summary.stats.totalCount == 0) CareerContent.Empty
            else CareerContent.Ready(summary.stats, StatusLadder.of(summary.stats.statusCounts))
        is OtherSummary.Running -> CareerContent.Summarizing(serverLadder, summary.progress)
        is OtherSummary.Failed -> CareerContent.Failed(serverLadder, summary.failure)
        null ->
            if (shouldAutoSummarize(serverLadder.total)) CareerContent.Summarizing(serverLadder, progress = null)
            else CareerContent.AwaitingConfirm(serverLadder, serverLadder.total)
    }
}

// ---- 生涯卡：一个人的全部类型 ----

/**
 * 生涯卡（「概览」tab）此刻画哪一种。卡片把全部类型合起来看这个人，不按类型切换：
 * 没有任何（公开的）收藏时不画卡（[Empty]）。
 */
internal sealed interface CareerCardContent {
    /** 本人：本地统计流还没发第一拍；他人：资料还没到，或可自动汇总的类别还没汇总完。 */
    data object Loading : CareerCardContent

    /** 他人：资料没取到，卡片的位置给重试。 */
    data class Unavailable(val failure: Failure) : CareerCardContent

    data object Empty : CareerCardContent

    /** [openType] 是点卡片打开完整统计时先看的那一类：看过最多的那类。 */
    data class Ready(val portrait: CareerPortrait, val openType: SubjectType) : CareerCardContent
}

/**
 * 生涯卡上的这个人：各类看过多少、均分、称号、最爱的题材与封面。全部出自统计层已经算好的值，这里只挑与合计。
 */
internal data class CareerPortrait(
    /** 全部类型看过（读过、玩过……）的部数。 */
    val done: Int,
    /** 全部类型正在进行的部数。 */
    val doing: Int,
    /** 有收藏的各类与它看过的部数，多的在前（同数按 [ContentDomains] 的顺序）。 */
    val doneByType: List<Pair<SubjectType, Int>>,
    /** 全部打过分的作品的均分；一部都没打过分为 null。 */
    val average: Double?,
    /** 我比大众严还是松、差多少；打过分的不够多或关掉了公共评分时为 null。 */
    val publicGap: Double?,
    val persona: Persona,
    /** 全部类型合起来最多的几个题材。 */
    val likes: List<String>,
    /** 封面拼图用的作品，最能代表这个人的在前。 */
    val covers: List<SubjectBo>,
)

/**
 * 称号：[type] 是看得最多的那一类（决定「迷」「书虫」「玩家」……），[genre] 是那一类最多的题材，
 * [trait] 是前面的形容（新来的，或打分比大众严 / 一致 / 松）。
 */
internal data class Persona(val type: SubjectType, val genre: String?, val trait: PersonaTrait?)

internal enum class PersonaTrait(val res: Int) {
    NEWCOMER(R.string.career_trait_newcomer),
    STRICT(R.string.career_trait_strict),
    EVEN(R.string.career_trait_even),
    LENIENT(R.string.career_trait_lenient),
}

/** 收藏少于这么多部时称号是「刚入场的」（我的设计决定，not in M3：十部以内还谈不上口味）。 */
internal const val NEWCOMER_LIMIT = 10

/** 生涯卡上列几个最爱的题材。 */
internal const val CARD_LIKES = 3

/** 有收藏的各类与它的阶梯，看过多的在前（同数按 [ContentDomains] 的顺序）；一类都没有则为空。 */
internal fun rankedLaddersOf(counts: Map<SubjectType, Map<CollectionType, Int>>): List<Pair<SubjectType, StatusLadder>> =
    ContentDomains.map { it to StatusLadder.of(counts[it].orEmpty()) }
        .filter { it.second.total > 0 }
        .sortedByDescending { it.second.done }

/** 卡片的主类（称号、封面都看它）：有收藏里看过最多的那一类；没有任何收藏为 null。 */
internal fun mainTypeOf(counts: Map<SubjectType, Map<CollectionType, Int>>): SubjectType? = rankedLaddersOf(counts).firstOrNull()?.first

/**
 * 生涯卡的唯一判定：本人读本地的 [CareerStatistics]；他人的各类计数读服务端摘要，资料一到就画；均分、称号的题材与口味、
 * 最爱的题材读已汇总的各类合起来的统计（[otherOverall]，汇总上限以上、没有汇总的类别只出现在计数里），
 * 还没汇总完（null）时一起空着，算完一次补全。他人的封面是 [otherCovers]（先到的那份，见 `CareerState.otherCovers`）。
 */
internal fun careerCardContentOf(
    isSelf: Boolean,
    ownStats: CareerStatistics?,
    serverCounts: Load<Map<SubjectType, Map<CollectionType, Int>>>,
    otherOverall: CollectionStatistics?,
    otherCovers: List<SubjectBo>,
    summaries: Map<SubjectType, OtherSummary>,
    publicRatings: Boolean,
): CareerCardContent {
    val counts: Map<SubjectType, Map<CollectionType, Int>>
    val overall: CollectionStatistics
    val byType: Map<SubjectType, CollectionStatistics>
    val covers: List<SubjectBo>
    if (isSelf) {
        val own = ownStats ?: return CareerCardContent.Loading
        counts = own.byType.mapValues { it.value.statusCounts }
        overall = own.overall
        byType = own.byType
        covers = own.overall.coverPicks
    } else {
        counts = when (serverCounts) {
            Load.Pending -> return CareerCardContent.Loading
            is Load.Failed -> return CareerCardContent.Unavailable(serverCounts.failure)
            is Load.Ready -> serverCounts.value
        }
        if (ContentDomains.none { StatusLadder.of(counts[it].orEmpty()).total > 0 }) return CareerCardContent.Empty
        overall = otherOverall ?: CollectionStatistics()
        byType = if (otherOverall == null) {
            emptyMap()
        } else {
            summaries.mapNotNull { (type, summary) -> (summary as? OtherSummary.Loaded)?.let { type to it.stats } }.toMap()
        }
        covers = otherCovers
    }
    val byDone = rankedLaddersOf(counts)
    if (byDone.isEmpty()) return CareerCardContent.Empty
    val ladders = byDone
    val top = byDone.first().first
    val verdict = scoreViewOf(overall, publicRatings)?.takeIf { it.showPublic }?.verdict
    val trait = when {
        ladders.sumOf { it.second.total } < NEWCOMER_LIMIT -> PersonaTrait.NEWCOMER
        verdict != null -> when (tasteLeanOf(verdict.gap)) {
            TasteLean.STRICTER -> PersonaTrait.STRICT
            TasteLean.SAME -> PersonaTrait.EVEN
            TasteLean.LENIENT -> PersonaTrait.LENIENT
        }
        else -> null
    }
    val portrait = CareerPortrait(
        done = ladders.sumOf { it.second.done },
        doing = ladders.sumOf { it.second.doing },
        doneByType = byDone.map { (type, ladder) -> type to ladder.done },
        average = overall.averageRating,
        publicGap = verdict?.gap,
        persona = Persona(top, byType[top]?.genreCounts?.firstOrNull()?.label, trait),
        likes = overall.genreCounts.take(CARD_LIKES).map { it.label },
        covers = covers,
    )
    return CareerCardContent.Ready(portrait, openType = top)
}

// ---- 打分口味 ----

/**
 * 打分口味在界面上的可见形态。**公共评分的可见性只在这里判一次**：
 * 关掉公共评分（[publicRatings] = false）时不出现「大众」一侧，只留我的均分与直方图。
 *
 * 门槛不在这里判：统计层过了 [MIN_RATED_FOR_VERDICT] 才给 [RatingVerdict]，这里只读它有没有。
 */
internal data class ScoreView(
    val verdict: RatingVerdict,
    /** 关掉公共评分时为 false：不画大众均值线、不画散点、不说「比大众」。 */
    val showPublic: Boolean,
)

internal fun scoreViewOf(stats: CollectionStatistics, publicRatings: Boolean): ScoreView? =
    stats.ratingVerdict?.let { ScoreView(it, showPublic = publicRatings) }

/** 还差几部打分才给结论（原型「再打 N 部分就能看出来」）；至少 1。 */
internal fun ratingsStillNeeded(stats: CollectionStatistics): Int =
    (MIN_RATED_FOR_VERDICT - stats.ratedCount).coerceAtLeast(1)

// ---- 高分作品 ----

/** 打了高分（≥ [HIGH_SCORE_MIN]）的作品总数：读统计层的分数直方图，不另算（展示的 [CollectionStatistics.topRated] 至多 10 部）。 */
internal fun highRatedCountOf(stats: CollectionStatistics): Int =
    stats.scoreCounts.filterKeys { it >= HIGH_SCORE_MIN }.values.sum()

// ---- 篇幅 ----

/** 篇幅四档的文案（顺序固定：短 → 长，与 [io.github.touhougo.chogumi.core.model.LengthDistribution.counts] 对应）。 */
internal val LengthBucketLabels = listOf(
    R.string.career_length_up_to_13,
    R.string.career_length_14_to_26,
    R.string.career_length_27_to_52,
    R.string.career_length_over_52,
)

// ---- 数字格式 ----

/** 大数字的千分位写法（原型 `5,180`）。 */
internal fun formatCount(value: Int): String = java.text.NumberFormat.getIntegerInstance().format(value)

// ---- 打分口味的措辞 ----

/** 我和大众比：偏严 / 一致 / 偏宽。**这个三分类只在这里判**，生涯卡与统计页的文案都读它。 */
internal enum class TasteLean { STRICTER, SAME, LENIENT }

internal fun tasteLeanOf(gap: Double): TasteLean = when {
    gap < 0 -> TasteLean.STRICTER
    gap > 0 -> TasteLean.LENIENT
    else -> TasteLean.SAME
}

// ---- 口味象限 / 分差点条 ----

/**
 * 口味象限的四角：以我的均分（纵）与大众均分（横）画十字，[works] 按落在哪一角分组。
 * 落在线上的算「高」的一侧（与原型同口径）。
 */
internal data class TasteQuadrants(
    /** 公认神作：我高、大众也高。 */
    val acclaimed: List<RatedWork>,
    /** 私心偏爱：我高、大众低。 */
    val personal: List<RatedWork>,
    /** 众望难符：我低、大众高。 */
    val letdown: List<RatedWork>,
    /** 公认一般：都低。 */
    val ordinary: List<RatedWork>,
)

internal fun tasteQuadrantsOf(verdict: RatingVerdict): TasteQuadrants {
    val (mineHigh, mineLow) = verdict.works.partition { it.myScore >= verdict.myAverage }
    return TasteQuadrants(
        acclaimed = mineHigh.filter { it.publicScore >= verdict.publicAverage },
        personal = mineHigh.filter { it.publicScore < verdict.publicAverage },
        letdown = mineLow.filter { it.publicScore >= verdict.publicAverage },
        ordinary = mineLow.filter { it.publicScore < verdict.publicAverage },
    )
}

/** 分差点条的一箱：[gap] 是箱中心（每 0.5 分一箱），点条横轴只画到 ±[GAP_AXIS_LIMIT]，更远的并进两端那箱。 */
internal data class GapBin(val gap: Double, val works: List<RatedWork>)

internal const val GAP_AXIS_LIMIT = 4.0

internal fun gapBinsOf(works: List<RatedWork>): List<GapBin> =
    works.groupBy { (Math.round(it.gap * 2) / 2.0).coerceIn(-GAP_AXIS_LIMIT, GAP_AXIS_LIMIT) }
        .map { (gap, rows) -> GapBin(gap, rows) }
        .sortedBy { it.gap }
