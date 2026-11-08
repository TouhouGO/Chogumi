package io.github.touhougo.chogumi.core.model

/** 章节网格单元种类。 */
enum class EpisodeCellKind {

    /** 看过（连段内，不含「看到这里」那一集）。 */
    WATCHED,

    /** 「看到这里」：正片列表里最后一集看过的，连段的末端。 */
    CURRENT,

    /** 下一集（[EpisodeTracking.nextEpisode]）：整板唯一的圆形格。 */
    NEXT,

    /** 已播未看：描边的空位。 */
    AIRED,

    /** 已播未看，网页上标了想看（App 里只显示，不改）。 */
    WISH,

    /** 已播未看，网页上标了抛弃（App 里只显示，不改）。 */
    DROPPED,

    /** 未播出：灰底灰字，「还没有」。 */
    UNAIRED,
}

/** 网格单元：一集 + 它的显示种类，以及格子里附带的信息。 */
data class EpisodeCell(
    val episode: EpisodeBo,
    val kind: EpisodeCellKind,
    /** 播出日；没有或不合法为 null。点未播出的格子时提示它哪天播。 */
    val airdate: java.time.LocalDate?,
    /** 今天播出且还没看：格子右上角一个小点。 */
    val airsToday: Boolean,
    /** 未播出且 [EpisodeTracking.UPCOMING_AIRDATE_DAYS] 天内要播：格子里写的播出日；其余为 null。 */
    val upcomingAirdate: java.time.LocalDate?,
) {
    /** 能不能标：未播出的集不能标（点了只提示哪天播），同 EP 按钮的等待态。 */
    val markable: Boolean get() = kind != EpisodeCellKind.UNAIRED
}

/** 看过的一段：**本行内连续**看过的列区间（`0` 起、闭区间）。段内相邻两格之间的角收小，读起来是一整条。 */
data class EpisodeBand(
    val startColumn: Int,
    val endColumn: Int,
)

/**
 * 分段网格的**全部结果**：要画的行、可选的段与当前段（默认段在有网格之前就要定，见 [EpisodeTracking.defaultRangeIndex]）。
 *
 * 界面只接收它，不自己切段也不自己算锚点（见 [EpisodeTracking.episodeGrid]）。
 */
data class EpisodeGrid(
    /** 当前段要画的行。 */
    val rows: List<EpisodeRow>,
    /** 全部段（每个段是一串正片）；不分段时只有一段。 */
    val ranges: List<List<EpisodeBo>>,
    /** 每段里看过几集（与 [ranges] 一一对应），分段导航条按它画每段填了多少。 */
    val rangeWatched: List<Int>,
    /** 被 [rows] 画出来的那一段的下标（已在范围内）。 */
    val selectedRange: Int,
)

/** 网格一行：[cells] 为本行的正片单元，[bands] 为本行看过的连段（可为空 = 本行无看过集）。 */
data class EpisodeRow(
    val cells: List<EpisodeCell>,
    val bands: List<EpisodeBand>,
)

/**
 * 「看到这里」的显式进度目标。空进度与真实集分开表达，因此合法的 `sort = 0.0` 仍是 [Episode]。
 */
sealed interface EpisodeProgressTarget {
    /** 一集正片都没看。 */
    data object Empty : EpisodeProgressTarget

    /** 看到 [id] 这一集（含它）；[sort] 只用于显示和收藏面板恢复，进度顺序按正片列表判定。 */
    data class Episode(val id: Int, val sort: Double) : EpisodeProgressTarget
}

/** 「看到 [episode] 这一集」；null（一集都没看）是 [EpisodeProgressTarget.Empty]。 */
fun targetOf(episode: EpisodeBo?): EpisodeProgressTarget =
    episode?.let { EpisodeProgressTarget.Episode(it.id, it.sort) } ?: EpisodeProgressTarget.Empty

/**
 * 点一格的意图（[EpisodeTracking.tapOf]）：要「看到」[target]，[forward] 是往前标（否则是往回退）；
 * [changes] 是这一下让哪些正片变化（按集 id，不变的不在里面）。往回退也可能标上：跳着看过时，退到的那一集之前的空洞会补上。
 * [completes]：这一下之后看完了正片（看到的是最后一集正片，[EpisodeTracking.watchedAllMains]），即看完的那一刻。
 * 已经看完时点任何一格都看不到最后一集（点最后一集是退一集），所以「这一下之后看完」就意味着之前还没看完。
 */
data class EpisodeTap(
    val target: EpisodeProgressTarget,
    val forward: Boolean,
    val changes: Map<Int, WatchChange>,
    val completes: Boolean,
) {
    /** 这一下标成看过的集数。 */
    val filled: Int get() = changes.values.count { it == WatchChange.FILL }

    /** 这一下取消看过的集数。 */
    val cleared: Int get() = changes.values.count { it == WatchChange.CLEAR }

    /** 提示文案用的集号；空进度为 null。 */
    val targetSort: Double? get() = (target as? EpisodeProgressTarget.Episode)?.sort

    /** 合计改动超过 [EpisodeTracking.WATCH_JUMP_CONFIRM_OVER] 集：先确认，再点一次同一格才生效。 */
    val needsConfirm: Boolean get() = filled + cleared > EpisodeTracking.WATCH_JUMP_CONFIRM_OVER
}

/** 一集在「看到第几集」之后的变化（[EpisodeTap.changes]）。 */
enum class WatchChange {
    /** 标上看过。 */
    FILL,

    /** 取消看过。 */
    CLEAR,
}

/** 章节 tab 顶上那一行（[EpisodeTracking.episodeSummary]）。 */
data class EpisodeSummary(
    /** 看到这里；一集都没看过为 null。 */
    val current: EpisodeBo?,
    /** 正片集数。 */
    val total: Int,
    val airing: EpisodeAiring,
    /** 正片全部看完。 */
    val allWatched: Boolean,
)

/** 播出到哪：连载中（带已播集数）、已完结、说不准。 */
sealed interface EpisodeAiring {
    data class Ongoing(val aired: Int) : EpisodeAiring
    data object Finished : EpisodeAiring
    data object Unknown : EpisodeAiring
}
