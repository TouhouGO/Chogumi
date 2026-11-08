package io.github.touhougo.chogumi.core.model

/**
 * 统计中按名称排名的计数项（题材、形式、标签等一律用它）。
 *
 * [count] 的单位由**上下文**决定（部/本/张/格），调用方按所在类型的口径展示；
 * 模型层不携带单位，避免同一条计数在四处各写一遍单位文案。
 */
data class RankedStatistic(
    val label: String,
    val count: Int,
)

/** 某一年属于哪个年代（年代首年，1990 表示 1990–1999）。**年代归属的唯一规则**——统计层分组与界面刻度都用它。 */
fun decadeOf(year: Int): Int = year / 10 * 10

/**
 * 一部「我打过分、也有大众分」的作品：打分口味的样本，散点图的一个点。
 *
 * [publicScore] 取一位小数；[gap] = 我的分 − 大众分（可为负）。
 */
data class RatedWork(
    val subjectId: Int,
    val title: String,
    val myScore: Int,
    val publicScore: Double,
    val gap: Double,
)

/**
 * 打分口味：**同一个地方算，生涯卡、直方图、散点都用它**。
 *
 * 只在打过分的作品数 ≥ [MIN_RATED_FOR_VERDICT] 时产出；不足时统计层给出 null，
 * 界面不得自己再判一次门槛（门槛的唯一真源是 [MIN_RATED_FOR_VERDICT]）。
 *
 * 平均分口径：先各自取一位小数，[gap] 再相减——两个一位小数相减必然还是一位小数，
 * 避免「8.55 − 8.30 = 0.25」这类浮点尾数在界面上显示成 `0.25000000001`。
 */
data class RatingVerdict(
    /** 我打过的作品数。 */
    val ratedCount: Int,
    /** 我的均分，一位小数。 */
    val myAverage: Double,
    /** 同一批作品的大众均分，一位小数。 */
    val publicAverage: Double,
    /** 我的均分 − 大众均分（各取一位小数后相减）。 */
    val gap: Double,
    /** 全部样本（散点图与数据表都读它，界面不另行收集）。 */
    val works: List<RatedWork> = emptyList(),
)

/**
 * 动画篇幅四档分布。
 *
 * 分档边界（设计决定，非 M3）：≤13 / 14–26 / 27–52 / >52 集。
 * 只对**动画**统计（其他类型没有「一季多少集」的概念）。
 */
data class LengthDistribution(
    val upTo13: Int,
    val from14To26: Int,
    val from27To52: Int,
    val over52: Int,
) {
    /** 四档的计数（顺序固定：短→长），供界面按序画分段条。 */
    val counts: List<Int> get() = listOf(upTo13, from14To26, from27To52, over52)

    val total: Int get() = upTo13 + from14To26 + from27To52 + over52
}

/**
 * 投入时间。
 *
 * [minutes] 一律按每集 [WATCH_MINUTES_PER_EPISODE_FALLBACK] 分钟估算：待看/详情链路的
 * `EpisodeBo` 不携带时长，本地没有可用的真实值。[estimated] 因此在有观看量时恒为 true，
 * 界面据此标注「约」，**不假装是精确值**。
 */
data class WatchTime(
    /** 总分钟数（估算）。 */
    val minutes: Int,
    /** 是否含估算部分（有观看量时恒 true，见类注释）。 */
    val estimated: Boolean,
)

/** 时光机的一格：[year] 年收进来（最后一次改动收藏的那年）的、放送于 [decade] 年代的作品，最近收藏的在前。 */
data class TimeMachineCell(
    val decade: Int,
    val year: Int,
    val subjects: List<SubjectBo>,
)

/**
 * 时光机：每部作品放送的年代 × 收进收藏的年份。收藏年份取最近 [TIME_MACHINE_YEARS] 年（到今年为止），
 * 年代取这些格子里出现过的；放送年份或收藏时间不明的作品不计。
 *
 * [averageLag] 是「看过」的作品平均晚几年才收进来（收藏年份 − 放送年份，一位小数，不计负数）；没有这样的作品为 null。
 */
data class TimeMachine(
    val years: List<Int> = emptyList(),
    val decades: List<Int> = emptyList(),
    val cells: List<TimeMachineCell> = emptyList(),
    val averageLag: Double? = null,
)

/** [TimeMachine] 最多看几年（再多一行就画不下）。我的设计决定（not in M3）。 */
const val TIME_MACHINE_YEARS = 8

/** 冷门度里的一部作品：[members] 是收藏人数，[status] 是我的收藏状态（点的颜色）。 */
data class PopularityPoint(
    val subjectId: Int,
    val title: String,
    val members: Int,
    val status: CollectionType,
)

/**
 * 冷门度：收藏的作品各有多少人收藏（[points]，人少的在前）、一半作品不到多少人（[medianMembers]），
 * 最冷门的几部（[niche]，有封面的，最多 [NICHE_PICKS] 部），以及按站内排名分的档（[rankTiers]，顺序同 [RANK_TIER_LIMITS]，
 * 最后一档是没有排名的）。
 */
data class Popularity(
    val points: List<PopularityPoint>,
    val medianMembers: Int,
    val niche: List<SubjectBo>,
    val rankTiers: List<Int>,
)

/** 冷门度列出最冷门的几部。我的设计决定（not in M3）。 */
const val NICHE_PICKS = 3

/** 排名档的上界（含）：前 100、101–500、501–2000、2000 以后；没有排名另成一档。我的设计决定（not in M3）。 */
val RANK_TIER_LIMITS = listOf(100, 500, 2000, Int.MAX_VALUE)

/**
 * 一部「打了高分」的作品：条目资料（要画封面、点进详情）加我给的分。
 */
data class RatedSubject(
    val subject: SubjectBo,
    val score: Int,
)

/**
 * 「高分」的门槛：我打的分 ≥ 它才算高分，**唯一真源**（统计层据此挑出 [CollectionStatistics.topRated]，
 * 界面的文案与计数也读它）。我的设计决定（not in M3）：10 分制里 8 分起算「喜欢」。
 */
const val HIGH_SCORE_MIN = 8

/** [CollectionStatistics.topRated] 最多留几部（界面是一条横滑封面，太长就失去「一眼」）。我的设计决定（not in M3）。 */
const val TOP_RATED_LIMIT = 10

/**
 * [CollectionStatistics.coverPicks] 最多留几部：生涯卡的封面拼图在最宽的卡上也铺得满（我的设计决定，not in M3）。
 */
const val COVER_PICKS_LIMIT = 64

/**
 * 打分门槛：少于这个数字不给结论。
 *
 * **门槛的唯一真源**——统计层据此产出 [RatingVerdict] 或 null，
 * 界面、图表一律不得再写一遍数字。与统计原型的注释同值。
 */
const val MIN_RATED_FOR_VERDICT = 5

/** 没有每集时长时的估算值（分钟）。设计决定，非 M3。 */
const val WATCH_MINUTES_PER_EPISODE_FALLBACK = 24

/** 收藏集合的统计快照（口径见各字段 KDoc，来源无关：本地与他人都走同一套）。 */
data class CollectionStatistics(
    val totalCount: Int = 0,
    /** 打过分的作品数（1–10 分）。 */
    val ratedCount: Int = 0,
    /** 我的均分（未过门槛也照常给出原始均值，供概览展示）。 */
    val averageRating: Double? = null,
    /** 五态计数：想 / 在 / 过 / 搁置 / 抛弃。 */
    val statusCounts: Map<CollectionType, Int> = emptyMap(),
    /** 1–10 分直方图（分数 → 部数）。 */
    val scoreCounts: Map<Int, Int> = emptyMap(),
    /**
     * 打了高分（≥ [HIGH_SCORE_MIN]）且有条目资料的作品，最多 [TOP_RATED_LIMIT] 部：
     * 分高的在前，同分最近收藏的在前。没有高分作品时为空。
     */
    val topRated: List<RatedSubject> = emptyList(),
    /**
     * 最能代表这个人的作品（有封面的才算），最多 [COVER_PICKS_LIMIT] 部：我打的分高的在前，
     * 同分时看过与在看的在前，再按最近收藏排。生涯卡的封面拼图读它。
     */
    val coverPicks: List<SubjectBo> = emptyList(),
    /** 题材流派分布（按筛选词表归类，五种类型通用）。 */
    val genreCounts: List<RankedStatistic> = emptyList(),
    /** 形式 / 平台分布（游戏的大数字「几个平台」读它）。 */
    val formCounts: List<RankedStatistic> = emptyList(),
    /** 各题材里我打过分的作品的均分（一位小数），键是 [genreCounts] 的题材名；那个题材一部都没打分就不在表里。 */
    val genreRatings: Map<String, Double> = emptyMap(),
    /** 放送年代 × 收藏年份（见 [TimeMachine]）。 */
    val timeMachine: TimeMachine = TimeMachine(),
    /** 收藏的作品有多少人收藏、排第几（见 [Popularity]）；没有一部带收藏人数时为 null。 */
    val popularity: Popularity? = null,
    /** 逐集记录看出来的节奏（见 [WatchRhythm]）；他人、或本人一集记录都没有时为 null。 */
    val rhythm: WatchRhythm? = null,
    /** 看过和在看的作品里出现最多的声优与制作公司（见 [Lineup]）；这个类型不算阵容、或一部的阵容都还没拉到时为 null。 */
    val lineup: Lineup? = null,
    /** 篇幅四档分布；非动画类型为 null。 */
    val lengthDistribution: LengthDistribution? = null,
    /** 打分口味结论；打过分的少于 [MIN_RATED_FOR_VERDICT] 部时为 null。 */
    val ratingVerdict: RatingVerdict? = null,
    /** 填坑率百分比（看过 ÷（看过 + 抛弃））；没有分母（都为 0）时为 null。 */
    val finishRate: Int? = null,
    /**
     * 动画**看过的集数**——逐集表口径（与进度页一致），不是 epStatus 加总。
     * 只数状态为「看过」的**正片**，特典不计入。
     */
    val episodeProgress: Int = 0,
    /** 书籍读过的卷数（volStatus 加总；书籍没有逐集表）。 */
    val volumeProgress: Int = 0,
    /** 书籍读过的话数（书籍收藏的 epStatus 加总；书籍的「话」不走逐集表）。 */
    val chapterProgress: Int = 0,
    /** 投入时间（动画逐集口径）。 */
    val watchTime: WatchTime = WatchTime(minutes = 0, estimated = false),
)

/**
 * 一个人的收藏统计：每类一份（[byType]，完整统计按类型切换），加上全部类型合起来算的一份（[overall]，生涯卡读它）。
 * 两份出自同一个计算函数，只是喂进去的收藏不同。
 */
data class CareerStatistics(
    val byType: Map<SubjectType, CollectionStatistics>,
    val overall: CollectionStatistics,
)
