package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.CollectionStatistics
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.LAG_TIER_LIMITS
import io.github.touhougo.chogumi.core.model.NICHE_PICKS
import io.github.touhougo.chogumi.core.model.Popularity
import io.github.touhougo.chogumi.core.model.PopularityPoint
import io.github.touhougo.chogumi.core.model.RANK_TIER_LIMITS
import io.github.touhougo.chogumi.core.model.RankedStatistic
import io.github.touhougo.chogumi.core.model.RatedSubject
import io.github.touhougo.chogumi.core.model.RatedWork
import io.github.touhougo.chogumi.core.model.RatingVerdict
import io.github.touhougo.chogumi.core.model.SPAN_LANES
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TIME_MACHINE_YEARS
import io.github.touhougo.chogumi.core.model.TOP_RATED_LIMIT
import io.github.touhougo.chogumi.core.model.TimeMachine
import io.github.touhougo.chogumi.core.model.TimeMachineCell
import io.github.touhougo.chogumi.core.model.WatchDay
import io.github.touhougo.chogumi.core.model.WatchRhythm
import io.github.touhougo.chogumi.core.model.WatchSpan
import io.github.touhougo.chogumi.core.model.YearRing
import io.github.touhougo.chogumi.core.ui.composables.StatsCard
import java.time.LocalDate

/**
 * 完整统计页骨架：大数字与真内容同一个外框；下面的区块是 [CareerSections] 用样例数据（[careerSkeletonStats]）在
 * [StatPlaceholders] 里摆出来的——每块按样例把真图画出来、藏起来、盖一层色块，所以骨架的每一块与真块同大、同位置，
 * 放大字号、换屏宽时一起变，数据到了原位换上。他人汇总中（[summarizing] 非空）把进度写在大数字下面。
 */
@Composable
internal fun CareerStatsSkeleton(
    summarizing: CareerContent.Summarizing?,
    type: SubjectType,
    isSelf: Boolean,
    who: String,
    publicRatings: Boolean,
) {
    val sample = remember(type, isSelf) { careerSkeletonStats(type, isSelf) }
    Column(verticalArrangement = Arrangement.spacedBy(CAREER_CARD_SPACING)) {
        StatsCard {
            CareerKpiRowSkeleton(type, isSelf)
            if (summarizing != null) SummarizingText(summarizing, type, who, Modifier.padding(top = 10.dp))
        }
        StatPlaceholders {
            CareerSections(type, who, sample, SkeletonLadder, publicRatings, onSubjectClick = {}, onPersonClick = {})
        }
    }
}

/**
 * 骨架摆的样例：一份常见的满页。每张图取常见的大小——时光机三个年代、分差点条最高一摞四部、一部看多久排满 [SPAN_LANES] 行、
 * 最爱排满 [TOP_RATED_LIMIT] 部、题材十块、冷门作品 [NICHE_PICKS] 部。
 *
 * 逐集节奏只有本人、而且按集推进的类型才有（[EpisodeTracking.tracksEpisodes]，与数据层收集逐集记录同一个判定）。
 * 排在最后的篇幅、收藏走到哪、声优与制作公司不带：有没有取决于类型和数据，又在好几屏以下，数据到时推不动看得见的内容。
 */
internal fun careerSkeletonStats(type: SubjectType, isSelf: Boolean): CollectionStatistics =
    SampleStats.copy(rhythm = SampleRhythm.takeIf { isSelf && EpisodeTracking.tracksEpisodes(type) })

/** 收藏走到哪一块不画：五态都是 0（[StatusSection] 不画空的）。 */
private val SkeletonLadder = StatusLadder(wish = 0, doing = 0, done = 0, onHold = 0, dropped = 0)

private const val SAMPLE_YEAR = 2026

private fun sampleSubject(id: Int) = SubjectBo(id = id, type = SubjectType.ANIME, name = "作品 $id", nameCn = "作品 $id")

private val SampleWorks: List<RatedWork> = (0 until 24).map { i ->
    val mine = 5 + i % 6
    val public = 6.0 + (i * 7 % 30) / 10.0
    RatedWork(subjectId = i, title = "作品 $i", myScore = mine, publicScore = public, gap = mine - public)
}

private val SampleTimeMachine: TimeMachine = run {
    val years = (SAMPLE_YEAR - TIME_MACHINE_YEARS + 1..SAMPLE_YEAR).toList()
    val decades = listOf(2000, 2010, 2020)
    TimeMachine(
        years = years,
        decades = decades,
        cells = years.flatMapIndexed { i, year ->
            decades.filterIndexed { d, _ -> (i + d) % 2 == 0 || d == decades.lastIndex }.map { decade ->
                TimeMachineCell(decade, year, List(1 + (i + decade / 10) % 4) { sampleSubject(it) })
            }
        },
        averageLag = 2.4,
    )
}

private val SampleStats = CollectionStatistics(
    totalCount = 128,
    ratedCount = SampleWorks.size,
    statusCounts = mapOf(CollectionType.DONE to 88, CollectionType.DOING to 12, CollectionType.WISH to 24),
    scoreCounts = SampleWorks.groupingBy { it.myScore }.eachCount(),
    topRated = (1..TOP_RATED_LIMIT).map { RatedSubject(sampleSubject(it), score = 9) },
    genreCounts = (0 until 10).map { RankedStatistic("题材 $it", 30 - it * 3) },
    genreRatings = (0 until 10).associate { "题材 $it" to 7.0 + it % 3 / 2.0 },
    timeMachine = SampleTimeMachine,
    popularity = Popularity(
        points = (0 until 60).map { PopularityPoint(subjectId = it, title = "作品 $it", members = 200 + it * it * 40, status = CollectionType.DONE) },
        medianMembers = 4_800,
        niche = (1..NICHE_PICKS).map(::sampleSubject),
        rankTiers = List(RANK_TIER_LIMITS.size + 1) { 4 + it },
    ),
    ratingVerdict = RatingVerdict(
        ratedCount = SampleWorks.size,
        myAverage = 7.5,
        publicAverage = 7.4,
        gap = 0.1,
        works = SampleWorks,
    ),
)

private val SampleRhythm: WatchRhythm = run {
    val first = LocalDate.of(SAMPLE_YEAR, 1, 1)
    val days = (0 until 365 step 3).associate { first.plusDays(it.toLong()) to WatchDay(episodes = 1 + it % 5, titles = listOf("作品")) }
    val spanStart = LocalDate.of(SAMPLE_YEAR, 3, 1)
    WatchRhythm(
        rings = listOf(
            YearRing(
                year = SAMPLE_YEAR,
                days = days,
                episodes = days.values.sumOf { it.episodes },
                activeDays = days.size,
                longestStreak = 12,
                busiestDay = days.maxBy { it.value.episodes }.key,
            ),
        ),
        hours = List(24) { (it * 5) % 17 },
        weekdays = List(7) { 10 + it * 3 },
        nightShare = 35,
        lagTiers = List(LAG_TIER_LIMITS.size) { 30 - it * 5 },
        chaseShare = 50,
        spans = (0 until SPAN_LANES).map { i ->
            val start = spanStart.plusDays(i * 14L)
            val end = start.plusDays(10L + i % 4 * 9)
            WatchSpan(subjectId = i, title = "作品 $i", start = start, end = end, days = mapOf(start to 3, end to 2))
        },
        medianSpanDays = 21,
    )
}
