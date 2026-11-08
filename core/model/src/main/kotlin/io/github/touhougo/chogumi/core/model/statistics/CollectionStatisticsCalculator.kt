package io.github.touhougo.chogumi.core.model.statistics

import io.github.touhougo.chogumi.core.foundation.businessDateOf
import io.github.touhougo.chogumi.core.model.COVER_PICKS_LIMIT
import io.github.touhougo.chogumi.core.model.CollectionStatistics
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpisodeMarks
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.HIGH_SCORE_MIN
import io.github.touhougo.chogumi.core.model.LengthDistribution
import io.github.touhougo.chogumi.core.model.MIN_RATED_FOR_VERDICT
import io.github.touhougo.chogumi.core.model.NICHE_PICKS
import io.github.touhougo.chogumi.core.model.Popularity
import io.github.touhougo.chogumi.core.model.PopularityPoint
import io.github.touhougo.chogumi.core.model.RANK_TIER_LIMITS
import io.github.touhougo.chogumi.core.model.RankedStatistic
import io.github.touhougo.chogumi.core.model.RatedSubject
import io.github.touhougo.chogumi.core.model.RatedWork
import io.github.touhougo.chogumi.core.model.RatingVerdict
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectLineup
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TIME_MACHINE_YEARS
import io.github.touhougo.chogumi.core.model.TOP_RATED_LIMIT
import io.github.touhougo.chogumi.core.model.TimeMachine
import io.github.touhougo.chogumi.core.model.TimeMachineCell
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.model.WATCH_MINUTES_PER_EPISODE_FALLBACK
import io.github.touhougo.chogumi.core.model.WatchTime
import io.github.touhougo.chogumi.core.model.decadeOf
import io.github.touhougo.chogumi.core.model.displayNameOf

/**
 * 生涯统计的输入——**与数据来源无关**。
 *
 * 本地自己的收藏、他人公开的收藏，都装成同一个 [StatisticsInput] 喂给
 * [StatisticsInput.calculateCollectionStatistics]，不存在「本人算法」和「他人算法」两份。
 */
data class StatisticsInput(
    /** 一组收藏（每条带其对应条目，缺失条目资料时为 null）。 */
    val collections: List<UserCollectionBo>,
    /**
     * 「逐集表口径」的**看过正片数**：`subjectId → 集数`。
     *
     * 本人数据由数据层调用 `EpisodeTracking.progressCount`（全 App 进度计数唯一判定点）
     * 填充；他人数据取不到逐集表，由调用方用可得的近似值填充。**统计函数不自己再算一遍。**
     */
    val watchedMainEpisodes: Map<Int, Int> = emptyMap(),
    /**
     * [watchedMainEpisodes] 是否为近似值（他人数据用话数加总而来，本人数据非近似）。
     * 为真时 [CollectionStatistics.watchTime] 标 estimated。
     */
    val episodesApproximate: Boolean = false,
    /** 当前年份——时光机看到这一年为止（函数内**不读系统时钟**，由调用方给定）。 */
    val currentYear: Int,
    /** 本人的逐集记录（节奏类的图读它）；他人没有，为 null。 */
    val episodeMarks: EpisodeMarks? = null,
    /** 已拉到的各部阵容（`subjectId → 阵容`，见 [subjectLineupOf]）；没拉的作品不在里面，为 null 时不算阵容。 */
    val lineups: Map<Int, SubjectLineup>? = null,
)

/**
 * 收藏生涯统计的**唯一计算入口**（纯函数，不读写任何外部状态）。
 *
 * 五种类型都能算；放送年份取自条目放送日期（[SubjectBo.date]），收藏年份取自收藏最后一次改动的时间（业务日期）。
 * 调用方（本人 / 他人）只提供数据，不做任何统计口径的判断。
 */
fun StatisticsInput.calculateCollectionStatistics(): CollectionStatistics {
    val statusCounts = mutableMapOf<CollectionType, Int>()
    val scoreCounts = mutableMapOf<Int, Int>()
    val genreCounts = mutableMapOf<String, Int>()
    val formCounts = mutableMapOf<String, Int>()
    // 题材 → 我打过的分：题材均分在循环外一次算。
    val genreScores = mutableMapOf<String, MutableList<Int>>()
    // 时光机与冷门度的样本：有条目资料的收藏，在循环外一次分组。
    val withSubject = mutableListOf<Pair<UserCollectionBo, SubjectBo>>()

    var ratedCount = 0
    var ratingTotal = 0
    var volumeProgress = 0
    var chapterProgress = 0
    var lengthUpTo13 = 0
    var length14To26 = 0
    var length27To52 = 0
    var lengthOver52 = 0

    // 打分口味用的样本（我的分、大众分、标题），只在打过分且有大众分时收集。
    val ratedPairs = mutableListOf<RatedPair>()
    // 高分作品候选：(收藏时间, 作品)；排序与截断在循环外一次做。
    val highRated = mutableListOf<Pair<Long, RatedSubject>>()
    // 封面拼图候选：有封面的收藏；排序与截断在循环外一次做。
    val withCover = mutableListOf<UserCollectionBo>()

    for (collection in collections) {
        statusCounts.increment(collection.type)
        volumeProgress += collection.volStatus
        if (EpisodeTracking.tracksCounts(collection.subjectType)) chapterProgress += collection.epStatus

        if (collection.rate in 1..10) {
            ratedCount++
            ratingTotal += collection.rate
            scoreCounts.increment(collection.rate)
        }

        val subject = collection.subject ?: continue

        if (collection.rate in HIGH_SCORE_MIN..10) highRated += collection.updatedAt to RatedSubject(subject, collection.rate)
        if (subject.coverUrl != null) withCover += collection

        for (genre in AnimeTaxonomy.extractGenres(subject.tags.map { it.name }.distinct())) {
            genreCounts.increment(genre)
            if (collection.rate in 1..10) genreScores.getOrPut(genre) { mutableListOf() } += collection.rate
        }

        formOf(subject)?.let { formCounts.increment(it) }

        withSubject += collection to subject

        // 篇幅四档只对动画统计；集数取条目话数（逐集表口径在 KPI 里，不在分布里）。
        // **未知话数（0 或缺省）不入任何档**——把未知当成「≤13 集」会凭空造出短番。
        if (subject.type == SubjectType.ANIME) {
            when (val eps = subject.eps ?: 0) {
                in 1..13 -> lengthUpTo13++
                in 14..26 -> length14To26++
                in 27..52 -> length27To52++
                in 53..Int.MAX_VALUE -> lengthOver52++
                else -> Unit
            }
        }

        if (collection.rate in 1..10 && subject.ratingScore > 0) {
            ratedPairs += RatedPair(
                subjectId = subject.id,
                title = displayNameOf(subject),
                myScore = collection.rate,
                publicScore = subject.ratingScore,
            )
        }
    }

    val avg = ratingTotal.takeIf { ratedCount > 0 }?.let { it.toDouble() / ratedCount }

    val done = statusCounts[CollectionType.DONE] ?: 0
    val dropped = statusCounts[CollectionType.DROPPED] ?: 0
    val finishRate = (done + dropped).takeIf { it > 0 }?.let { percentOf(done, it) }

    // 投入时间：动画逐集口径；没有每集时长按 24 分钟估。
    val watchedEpisodes = watchedMainEpisodes.values.sumOf { if (it > 0) it else 0 }
    val estimated = episodesApproximate || watchedEpisodes > 0
    val watchMinutes = watchedEpisodes.toLong() * WATCH_MINUTES_PER_EPISODE_FALLBACK

    return CollectionStatistics(
        totalCount = collections.size,
        ratedCount = ratedCount,
        averageRating = avg,
        statusCounts = statusCounts.toMap(),
        scoreCounts = scoreCounts.toMap(),
        topRated = highRated
            .sortedWith(compareByDescending<Pair<Long, RatedSubject>> { it.second.score }.thenByDescending { it.first })
            .take(TOP_RATED_LIMIT)
            .map { it.second },
        coverPicks = withCover
            .sortedWith(
                compareByDescending<UserCollectionBo> { it.rate.coerceIn(0, 10) }
                    .thenBy { it.type !in SETTLED_OR_ONGOING }
                    .thenByDescending { it.updatedAt },
            )
            .take(COVER_PICKS_LIMIT)
            .mapNotNull { it.subject },
        genreCounts = genreCounts.toRankedStatistics(),
        genreRatings = genreScores.mapValues { (_, scores) -> scores.average().round1() },
        formCounts = formCounts.toRankedStatistics(),
        timeMachine = timeMachineOf(withSubject, currentYear),
        popularity = popularityOf(withSubject),
        rhythm = episodeMarks?.let { watchRhythmOf(collections, it) },
        lineup = lineups?.let { lineupOf(collections, it) },
        lengthDistribution = lengthDistributionOf(lengthUpTo13, length14To26, length27To52, lengthOver52),
        ratingVerdict = ratingVerdictOf(ratedPairs),
        finishRate = finishRate,
        episodeProgress = watchedEpisodes,
        volumeProgress = volumeProgress,
        chapterProgress = chapterProgress,
        watchTime = WatchTime(minutes = watchMinutes.toInt(), estimated = estimated),
    )
}

/**
 * 最能代表这个人的状态：看过与在看（想看、搁置、抛弃的不如它们）。封面拼图把它们排在前面，阵容只数它们
 * （[countsForLineup]）。
 */
internal val SETTLED_OR_ONGOING = setOf(CollectionType.DONE, CollectionType.DOING)

/** 一条「我打过分的作品」样本：我的分、大众分、可显示标题。 */
private data class RatedPair(
    val subjectId: Int,
    val title: String,
    val myScore: Int,
    val publicScore: Double,
)

/** 一份一位小数的四舍五入，全统计层唯一一份。 */
private fun Double.round1(): Double = kotlin.math.round(this * 10) / 10

/**
 * 打分口味结论。少于 [MIN_RATED_FOR_VERDICT] 部打过分一律返回 null——
 * 门槛只在 [MIN_RATED_FOR_VERDICT] 定义一处，界面不得再乘一份。
 */
private fun ratingVerdictOf(pairs: List<RatedPair>): RatingVerdict? {
    if (pairs.size < MIN_RATED_FOR_VERDICT) return null
    val myAverage = pairs.map { it.myScore }.average().round1()
    val publicAverage = pairs.map { it.publicScore }.average().round1()
    val works = pairs.map { pair ->
        RatedWork(
            subjectId = pair.subjectId,
            title = pair.title,
            myScore = pair.myScore,
            publicScore = pair.publicScore.round1(),
            gap = (pair.myScore - pair.publicScore).round1(),
        )
    }
    return RatingVerdict(
        ratedCount = pairs.size,
        myAverage = myAverage,
        publicAverage = publicAverage,
        gap = (myAverage - publicAverage).round1(),
        works = works,
    )
}

/** 篇幅四档：全 0 时返回 null（非动画或没有动画收藏）。 */
private fun lengthDistributionOf(upTo13: Int, from14To26: Int, from27To52: Int, over52: Int): LengthDistribution? =
    if (upTo13 + from14To26 + from27To52 + over52 == 0) null
    else LengthDistribution(upTo13, from14To26, from27To52, over52)

/**
 * 时光机：收藏年份取 [currentYear] 往前 [TIME_MACHINE_YEARS] 年里出现过的最早一年到 [currentYear]，年代取这些年里出现过的；
 * 平均晚几年只算「看过」的（收藏比放送还早的算 0 年）。
 */
private fun timeMachineOf(collections: List<Pair<UserCollectionBo, SubjectBo>>, currentYear: Int): TimeMachine {
    val dated = collections.mapNotNull { (collection, subject) ->
        val aired = subject.date.toYearOrNull() ?: return@mapNotNull null
        if (collection.updatedAt <= 0) return@mapNotNull null
        Dated(collection, subject, aired, businessDateOf(collection.updatedAt).year)
    }
    val lags = dated.filter { it.collection.type == CollectionType.DONE }.map { (it.collected - it.aired).coerceAtLeast(0) }
    val recent = dated.filter { it.collected in (currentYear - TIME_MACHINE_YEARS + 1)..currentYear }
    if (recent.isEmpty()) return TimeMachine(averageLag = lags.averageOrNull())
    val cells = recent.groupBy { decadeOf(it.aired) to it.collected }.map { (key, rows) ->
        TimeMachineCell(
            decade = key.first,
            year = key.second,
            subjects = rows.sortedByDescending { it.collection.updatedAt }.map { it.subject },
        )
    }
    return TimeMachine(
        years = (recent.minOf { it.collected }..currentYear).toList(),
        decades = cells.map { it.decade }.distinct().sorted(),
        cells = cells.sortedWith(compareBy<TimeMachineCell> { it.decade }.thenBy { it.year }),
        averageLag = lags.averageOrNull(),
    )
}

/** 一部放送年份与收藏年份都已知的作品。 */
private data class Dated(val collection: UserCollectionBo, val subject: SubjectBo, val aired: Int, val collected: Int)

private fun List<Int>.averageOrNull(): Double? = if (isEmpty()) null else average().round1()

/**
 * 冷门度：只看带收藏人数的作品；中位数取人少的一半的上界（偶数部时取中间两部里人多的那部）。
 * 排名档按 [RANK_TIER_LIMITS] 分，排名 0 是没有排名。
 */
private fun popularityOf(collections: List<Pair<UserCollectionBo, SubjectBo>>): Popularity? {
    val known = collections.filter { (_, subject) -> subject.collectionTotal > 0 }.sortedBy { (_, subject) -> subject.collectionTotal }
    if (known.isEmpty()) return null
    val tiers = MutableList(RANK_TIER_LIMITS.size + 1) { 0 }
    collections.forEach { (_, subject) ->
        val rank = subject.ratingRank
        tiers[if (rank <= 0) RANK_TIER_LIMITS.size else RANK_TIER_LIMITS.indexOfFirst { rank <= it }]++
    }
    return Popularity(
        points = known.map { (collection, subject) ->
            PopularityPoint(subject.id, displayNameOf(subject), subject.collectionTotal, collection.type)
        },
        medianMembers = known[known.size / 2].second.collectionTotal,
        niche = known.map { it.second }.filter { it.coverUrl != null }.take(NICHE_PICKS),
        rankTiers = tiers,
    )
}

private fun <K> MutableMap<K, Int>.increment(key: K) {
    this[key] = (this[key] ?: 0) + 1
}

private fun Map<String, Int>.toRankedStatistics(): List<RankedStatistic> =
    entries
        .map { (label, count) -> RankedStatistic(label = label, count = count) }
        .sortedWith(compareByDescending<RankedStatistic> { it.count }.thenBy { it.label })

private fun String?.toYearOrNull(): Int? =
    this?.substringBefore('-')?.toIntOrNull()?.takeIf { it in 1..9999 }

/**
 * 他人数据的「看过正片数」近似：逐集表取不到，只能用收藏自带的话数进度（`epStatus`）加总。
 *
 * **只有逐集推进的类型**（动画/三次元，[EpisodeTracking.tracksEpisodes]）参与——书籍按卷、
 * 音乐无进度，都不算「看过的集数」。返回的映射喂给 [StatisticsInput.watchedMainEpisodes] 时
 * 调用方必须把 [StatisticsInput.episodesApproximate] 置真，界面据此标「约」。
 */
fun otherWatchedEpisodes(collections: List<UserCollectionBo>): Map<Int, Int> =
    collections.mapNotNull { collection ->
        if (!EpisodeTracking.tracksEpisodes(collection.subjectType)) return@mapNotNull null
        collection.epStatus.takeIf { it > 0 }?.let { collection.subjectId to it }
    }.toMap()

/**
 * 条目形式 / 平台——**唯一判定点**，五种类型各一套候选词（设计决定，非 M3）。
 *
 * 每部作品最多归到**一个**形式：候选列表的顺序就是优先级，按列表顺序取第一个在 `metaTags`、`tags`、
 * `platform` 任一处出现（子串、不分大小写）的候选词；命中多个也只计列表里靠前的那个，避免同一部作品在多个形式里重复计数。
 */
private val FORM_TAGS: Map<SubjectType, List<String>> = mapOf(
    SubjectType.ANIME to listOf("TV", "剧场版", "OVA", "OAD", "WEB", "动态漫", "泡面番", "特摄", "短片"),
    SubjectType.BOOK to listOf("漫画", "轻小说", "小说", "画集", "绘本", "写真"),
    SubjectType.MUSIC to listOf("迷你专辑", "专辑", "单曲", "原声", "角色歌", "同人音乐"),
    SubjectType.GAME to listOf("PC", "Switch", "PS5", "PS4", "Xbox", "手游", "街机", "掌机"),
    SubjectType.REAL to listOf("电视剧", "电影", "综艺", "纪录片", "舞台剧"),
)

private fun formOf(subject: SubjectBo): String? {
    val candidates = FORM_TAGS[subject.type] ?: return null
    val haystack = subject.metaTags + subject.tags.map { it.name } + subject.platform
    return candidates.firstOrNull { candidate -> haystack.any { it.contains(candidate, ignoreCase = true) } }
}
