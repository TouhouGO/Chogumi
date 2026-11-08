package io.github.touhougo.chogumi.core.model

import java.time.LocalDate
import java.time.ZoneId

/** 一集正片「看过」的记录（逐集表）：[markedAt] 是标成看过的时刻（epoch 毫秒），[airdate] 是首播日，不明为 null。 */
data class EpisodeMark(val markedAt: Long, val airdate: LocalDate?)

/**
 * 本人的逐集记录：`subjectId → 这部作品看过的正片`，以及把时刻换成日子和钟点用的时区（设备时区，见 `displayZone()`）。
 * 只有本人有（逐集接口只给自己），他人的统计没有这一份。
 */
data class EpisodeMarks(val bySubject: Map<Int, List<EpisodeMark>>, val zone: ZoneId)

/** 年轮上的一天：看了几集、看的是哪几部（显示名，这天第一次看的在前）。 */
data class WatchDay(val episodes: Int, val titles: List<String>)

/**
 * 一年的年轮：[days] 是这一年看过的那些天；另有这一年看了几集、几天在看、最长连着几天在看、看得最多的那天。
 */
data class YearRing(
    val year: Int,
    val days: Map<LocalDate, WatchDay>,
    val episodes: Int,
    val activeDays: Int,
    val longestStreak: Int,
    val busiestDay: LocalDate,
)

/**
 * 年轮一天的深浅档：没看是 0，1–2 集 1 档，3–4 集 2 档，5–8 集 3 档，更多是最深的 4 档（[RING_LEVELS]）。
 * 生涯页的年轮与桌面上的热力图都按它分档。分档取自原型，是我的设计决定（not in M3）。
 */
fun ringLevelOf(episodes: Int): Int =
    RING_LEVEL_LIMITS.indexOfFirst { episodes <= it }.let { if (it < 0) RING_LEVEL_LIMITS.size else it }

private val RING_LEVEL_LIMITS = listOf(0, 2, 4, 8)

/** 年轮有数的日子分几档（[ringLevelOf] 的最大值）。 */
val RING_LEVELS: Int = RING_LEVEL_LIMITS.size

/** 看完的一部从标第一集到标最后一集：[days] 是标过的那几天和每天几集。 */
data class WatchSpan(
    val subjectId: Int,
    val title: String,
    val start: LocalDate,
    val end: LocalDate,
    val days: Map<LocalDate, Int>,
) {
    /** 跨了几天（首尾都算）。 */
    val length: Int get() = (end.toEpochDay() - start.toEpochDay()).toInt() + 1
}

/**
 * 逐集记录里看出来的节奏：年轮（[rings]，早的年份在前）、几点看（[hours]，0–23 点各几集）、星期几看（[weekdays]，
 * 周一在前）、追得多紧（[lagTiers]，顺序同 [LAG_TIER_LIMITS]）、一部看多久（[spans]，最近看完的在前，最多 [SPAN_LANES] 部，
 * 以及全部看完的作品跨度的中位数 [medianSpanDays]）。
 *
 * 一部作品里同一秒标的集数达到 [BATCH_MARK_MIN] 时算一次补标（「看到这里」一次标了好几集）：
 * 计入年轮当天的集数，不进几点看、星期几和追得多紧；整部都是补标的作品不进一部看多久。
 */
data class WatchRhythm(
    val rings: List<YearRing>,
    val hours: List<Int>,
    val weekdays: List<Int>,
    /** 几点看里落在 [NIGHT_HOURS] 的百分比；没有一集不是补标时为 null。 */
    val nightShare: Int?,
    val lagTiers: List<Int>,
    /** 追得多紧：[lagTiers] 里首播一周内（前 [CHASE_TIERS] 档）的百分比；一集都没有时为 null。 */
    val chaseShare: Int?,
    val spans: List<WatchSpan>,
    val medianSpanDays: Int?,
)

/** 同一秒标了这么多集就算一次补标。我的设计决定（not in M3）。 */
const val BATCH_MARK_MIN = 5

/** 「晚上十点以后」：22 点到次日 3 点前。我的设计决定（not in M3）。 */
val NIGHT_HOURS: Set<Int> = setOf(22, 23, 0, 1, 2)

/**
 * 追得多紧的档：标看过的那天离首播几天（不到 1 天、7 天、30 天、365 天，其余是更久以后）。
 * 只算不止一集的作品（剧场版没有「追」）。我的设计决定（not in M3）。
 */
val LAG_TIER_LIMITS = listOf(1, 7, 30, 365, Int.MAX_VALUE)

/** 「首播一周内」是 [LAG_TIER_LIMITS] 的前几档（不到 1 天、不到 7 天）。 */
const val CHASE_TIERS = 2

/** 一部看多久最多画几部。我的设计决定（not in M3）。 */
const val SPAN_LANES = 12
