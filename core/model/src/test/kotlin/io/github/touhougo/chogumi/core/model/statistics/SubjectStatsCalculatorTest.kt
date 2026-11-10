package io.github.touhougo.chogumi.core.model.statistics

import io.github.touhougo.chogumi.core.model.AirPhase
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeType
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectRatingSplit
import io.github.touhougo.chogumi.core.model.SubjectRelationBo
import io.github.touhougo.chogumi.core.model.SubjectSeries
import io.github.touhougo.chogumi.core.model.SubjectType
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubjectStatsCalculatorTest {

    private val today = LocalDate.of(2026, 10, 13)
    private val now = 1_000L * DAY

    private fun subject(
        id: Int = 1,
        counts: List<Int> = emptyList(),
        wish: Int = 0, done: Int = 0, doing: Int = 0, onHold: Int = 0, dropped: Int = 0,
    ) = SubjectBo(
        id = id, type = SubjectType.ANIME, name = "n", nameCn = "", ratingCounts = counts,
        collectionWish = wish, collectionCollect = done, collectionDoing = doing,
        collectionOnHold = onHold, collectionDropped = dropped,
    )

    private fun relation(id: Int) =
        SubjectRelationBo(id = id, type = SubjectType.ANIME, name = "s$id", nameCn = "", relationId = 3, score = 8.0)

    private fun episode(id: Int, sort: Int, airdate: String, comments: Int = 0, type: Int = EpisodeType.NORMAL) =
        EpisodeBo(id = id, subjectId = 1, type = type, name = "", nameCn = "", sort = sort.toDouble(), airdate = airdate, comment = comments)

    private fun stats(input: SubjectStatsInput) = subjectStatsOf(input, today, now)

    // 317613 的真实数字（2026-10-13 抓取，下标 0 是 1 分）
    private val counts317613 = listOf(56, 20, 14, 34, 167, 695, 3187, 9229, 7333, 3127)
    private val split317613 = SubjectRatingSplit(
        byCollection = mapOf(
            CollectionType.WISH to listOf(1, 1, 2, 0, 3, 6, 10, 36, 15, 47),
            CollectionType.DONE to listOf(35, 16, 13, 30, 137, 640, 3077, 8995, 7203, 3024),
            CollectionType.DOING to listOf(5, 1, 0, 0, 0, 17, 62, 169, 99, 99),
            CollectionType.ON_HOLD to listOf(1, 0, 0, 0, 7, 20, 30, 55, 20, 1),
            CollectionType.DROPPED to listOf(15, 3, 1, 4, 23, 18, 18, 10, 11, 3),
        ),
        byAirPhase = mapOf(
            AirPhase.BEFORE to listOf(1, 0, 0, 0, 3, 4, 6, 14, 9, 13),
            AirPhase.AIRING to listOf(15, 3, 0, 0, 6, 16, 66, 169, 122, 103),
            AirPhase.AFTER to listOf(40, 17, 14, 34, 158, 675, 3115, 9046, 7202, 3011),
        ),
    )

    @Test
    fun `统计 tab：公共评分关掉或评分不足 10 人不出现，条目未到时按出现`() {
        val on = AppFeatures(community = true, publicRatings = true, coverScores = true, friendMarks = true)
        val off = AppFeatures(community = true, publicRatings = false, coverScores = false, friendMarks = true)
        assertTrue(subjectStatsVisible(10, on))
        assertFalse(subjectStatsVisible(9, on))
        assertFalse(subjectStatsVisible(5000, off))
        assertTrue(subjectStatsVisible(null, on))
        assertFalse(subjectStatsVisible(null, off))
    }

    @Test
    fun `评分卡：各收藏状态按看过、在看、搁置、抛弃排出均分，想看不列`() {
        val card = stats(SubjectStatsInput(subject(counts = counts317613), split = split317613, myRate = 9)).score!!

        assertEquals(counts317613, card.counts)
        assertEquals(
            listOf(CollectionType.DONE, CollectionType.DOING, CollectionType.ON_HOLD, CollectionType.DROPPED),
            card.byCollection.map { it.group },
        )
        assertMeans(listOf(8.343, 8.354, 7.433, 5.642), card.byCollection.map { it.mean })
        assertEquals(9, card.mine)
    }

    @Test
    fun `评分卡：人数不到 10 的组不出现，透视页取不到时没有分组，没打分没有我的分`() {
        val thin = split317613.copy(byCollection = split317613.byCollection + (CollectionType.DOING to listOf(0, 0, 0, 0, 0, 0, 0, 9, 0, 0)))
        val card = stats(SubjectStatsInput(subject(counts = counts317613), split = thin)).score!!
        assertEquals(listOf(CollectionType.DONE, CollectionType.ON_HOLD, CollectionType.DROPPED), card.byCollection.map { it.group })
        assertNull(card.mine)

        assertEquals(emptyList(), stats(SubjectStatsInput(subject(counts = counts317613))).score!!.byCollection)
        assertNull(stats(SubjectStatsInput(subject(counts = List(10) { 0 }))).score)
        assertNull(stats(SubjectStatsInput(subject(counts = emptyList()))).score)
    }

    @Test
    fun `平均分：1 到 10 分按人数加权，没有人打分为空`() {
        assertEquals(8.1, meanScoreOf(listOf(1, 0, 0, 0, 3, 4, 6, 14, 9, 13))!!, 1e-9)
        assertNull(meanScoreOf(List(10) { 0 }))
        assertNull(meanScoreOf(emptyList()))
    }

    @Test
    fun `每集热度：只有已播出的正片，按集数排，标出我看过的集`() {
        val episodes = listOf(
            episode(id = 12, sort = 2, airdate = "2026-10-08", comments = 80),
            episode(id = 11, sort = 1, airdate = "2026-10-01", comments = 300),
            episode(id = 13, sort = 3, airdate = "2026-10-15", comments = 0),
            episode(id = 90, sort = 1, airdate = "2026-10-02", comments = 50, type = EpisodeType.SPECIAL),
        )

        val card = stats(SubjectStatsInput(subject(), episodes = episodes, watchStates = mapOf(11 to EpisodeStatus.DONE, 12 to EpisodeStatus.WISH))).episodeHeat!!

        assertEquals(
            listOf(EpisodeHeatCell(11, 1.0, 300, watched = true), EpisodeHeatCell(12, 2.0, 80, watched = false)),
            card.cells,
        )
    }

    @Test
    fun `每集热度：最多最近 100 集，一集讨论都没有时不出现`() {
        val long = (1..130).map { episode(id = it, sort = it, airdate = "2020-01-01", comments = it) }
        val cells = stats(SubjectStatsInput(subject(), episodes = long)).episodeHeat!!.cells
        assertEquals(100, cells.size)
        assertEquals(31, cells.first().episodeId)

        val silent = (1..3).map { episode(id = it, sort = it, airdate = "2020-01-01") }
        assertNull(stats(SubjectStatsInput(subject(), episodes = silent)).episodeHeat)
    }

    @Test
    fun `一生：三段均分、按播出日排的各集、之后的续作与收藏速度`() {
        val sequel = relation(id = 2)
        val episodes = listOf(
            episode(id = 2, sort = 2, airdate = "2026-10-08", comments = 80),
            episode(id = 1, sort = 1, airdate = "2026-10-01", comments = 300),
            episode(id = 3, sort = 3, airdate = "", comments = 10),
        )
        val card = stats(
            SubjectStatsInput(
                subject(), split = split317613, episodes = episodes,
                series = SubjectSeries(before = listOf(relation(id = 3)), after = listOf(sequel)),
                recentCollectTimes = List(14) { now - it * DAY / 2 },
            ),
        ).lifeline!!

        assertEquals(listOf(AirPhase.BEFORE, AirPhase.AIRING, AirPhase.AFTER), card.phases.map { it.group })
        assertMeans(listOf(8.1, 8.178, 8.330), card.phases.map { it.mean })
        assertEquals(
            listOf(LifelineEpisode(1, 1.0, LocalDate.of(2026, 10, 1), 300), LifelineEpisode(2, 2.0, LocalDate.of(2026, 10, 8), 80)),
            card.episodes,
            "没有播出日的集画不上时间线",
        )
        assertTrue(card.ended, "没有播出日的那集按已播出算，正片全部播完")
        assertEquals(listOf(sequel), card.sequels)
        assertEquals(CollectPace(2, PaceUnit.DAY), card.pace)
    }

    @Test
    fun `一生：还有正片没播出时没有完结后那一段`() {
        val airing = listOf(
            episode(id = 1, sort = 1, airdate = "2026-10-01", comments = 30),
            episode(id = 2, sort = 2, airdate = "2026-10-08", comments = 20),
            episode(id = 3, sort = 3, airdate = "2026-10-15"),
        )
        assertFalse(stats(SubjectStatsInput(subject(), episodes = airing)).lifeline!!.ended)
    }

    @Test
    fun `一生：播出日不到两集的作品不出现`() {
        val movie = listOf(episode(id = 1, sort = 1, airdate = "2020-01-01", comments = 900))
        assertNull(stats(SubjectStatsInput(subject(), split = split317613, episodes = movie)).lifeline)
    }

    @Test
    fun `收藏速度：每天不到 1 人改说每周，每周不到改说每月，每月也不到为空`() {
        assertEquals(CollectPace(3, PaceUnit.DAY), collectPaceOf(List(30) { now - it * DAY / 3 }, now))
        assertEquals(CollectPace(2, PaceUnit.WEEK), collectPaceOf(listOf(now - 10 * DAY, now - 2 * DAY, now - DAY), now))
        assertEquals(CollectPace(2, PaceUnit.MONTH), collectPaceOf(listOf(now - 30 * DAY, now - 5 * DAY), now))
        assertNull(collectPaceOf(listOf(now - 200 * DAY, now - 90 * DAY), now))
        assertNull(collectPaceOf(listOf(now - DAY), now), "一个人说不出速度")
    }

    @Test
    fun `收藏速度量到现在：一天里收藏的 10 人已过去两个月，说成每周 1 人`() {
        val burst = List(10) { now - 60 * DAY - it * DAY / 10 }
        assertEquals(CollectPace(1, PaceUnit.WEEK), collectPaceOf(burst, now))
    }

    @Test
    fun `去向：开始看的人分到看过、在看、搁置、抛弃，想看单独给`() {
        val card = stats(SubjectStatsInput(subject(wish = 3475, done = 40864, doing = 2043, onHold = 769, dropped = 336))).flow!!

        assertEquals(44012, card.started)
        assertEquals(
            listOf(CollectionType.DONE, CollectionType.DOING, CollectionType.ON_HOLD, CollectionType.DROPPED),
            card.branches.map { it.type },
        )
        assertEquals(listOf(40864, 2043, 769, 336), card.branches.map { it.count })
        assertEquals(1.0, card.branches.sumOf { it.share }, 1e-9)
        assertEquals(40864.0 / 44012, card.branches.first().share, 1e-9)
        assertEquals(3475, card.wish)
    }

    @Test
    fun `去向：只有想看的人时没有分母，不出现`() {
        assertNull(stats(SubjectStatsInput(subject(wish = 10))).flow)
    }

    @Test
    fun `人群少于20人不画且20人开始保留`() {
        fun votes(n: Int) = List(10) { if (it == 7) n else 0 }
        val split = SubjectRatingSplit(
            byCollection = mapOf(CollectionType.DONE to votes(19), CollectionType.DOING to votes(20)),
            byAirPhase = emptyMap(),
        )
        val card = stats(SubjectStatsInput(subject(counts = votes(100)), split = split)).score!!
        assertEquals(listOf(CollectionType.DOING), card.byCollection.map { it.group })
    }

    @Test
    fun `争议度用总体标准差且99票隐藏100票出现`() {
        fun distribution(n: Int) = List(10) { if (it == 0) n / 2 else if (it == 9) n - n / 2 else 0 }
        assertNull(ratingControversyOf(distribution(99)))
        val value = ratingControversyOf(distribution(100))!!
        assertEquals(100L, value.count)
        assertEquals(5.5, value.mean)
        assertEquals(4.5, value.sigma)
        assertEquals(ControversyTier.POLARIZED, value.tier)
        assertEquals(listOf(50L, 0L, 50L), value.camps)
        assertEquals(1.0, value.gaugePosition)
    }

    @Test
    fun `争议度分档左闭右开1点2属于略有分歧`() {
        fun centered(outer: Int, total: Int): List<Int> = List(10) {
            when (it + 1) { 3, 7 -> outer; 5 -> total - outer * 2; else -> 0 }
        }
        val cases = listOf(
            Triple(centered(0, 100), 0.0, ControversyTier.UNANIMOUS),
            Triple(centered(24, 200), kotlin.math.sqrt(0.96), ControversyTier.UNANIMOUS),
            Triple(centered(25, 200), 1.0, ControversyTier.CONCENTRATED),
            Triple(centered(17, 100), kotlin.math.sqrt(1.36), ControversyTier.CONCENTRATED),
            Triple(centered(18, 100), 1.2, ControversyTier.SOME_DISAGREEMENT),
            Triple(centered(49, 200), 1.4, ControversyTier.MIXED),
            Triple(centered(32, 100), 1.6, ControversyTier.POLARIZED),
        )
        cases.forEach { (counts, sigma, tier) ->
            val value = ratingControversyOf(counts)!!
            assertEquals(sigma, value.sigma, 1e-12)
            assertEquals(tier, value.tier)
        }
        assertEquals(0.4, ratingControversyOf(centered(18, 100))!!.gaugePosition, 1e-12)
    }

    @Test
    fun `可用目录按实际图与人数过滤缺图无章节不列`() {
        fun votes(n: Int) = List(10) { if (it == 7) n else 0 }
        val subject = subject(counts = votes(100), done = 100)
        val empty = stats(SubjectStatsInput(subject))
        assertEquals(setOf(io.github.touhougo.chogumi.core.model.StatsData.RATING,
            io.github.touhougo.chogumi.core.model.StatsData.COLLECTION,
            io.github.touhougo.chogumi.core.model.StatsData.CONTROVERSY), availableStatsData(empty, false, false))
        val split = SubjectRatingSplit(emptyMap(), mapOf(AirPhase.AIRING to votes(19)),
            byTotalCollects = mapOf(1 to votes(19), 1001 to votes(20)), byRegdate = mapOf(1825 to votes(20)))
        val result = stats(SubjectStatsInput(subject, split))
        val available = availableStatsData(result, false, false)
        assertTrue(io.github.touhougo.chogumi.core.model.StatsData.TOTAL_COLLECTS in available)
        assertTrue(io.github.touhougo.chogumi.core.model.StatsData.REGDATE in available)
        assertFalse(io.github.touhougo.chogumi.core.model.StatsData.AIR_PHASE in available)
        assertEquals(listOf(1001), result.totalCollects.map { it.group })
        assertEquals(20, result.totalCollects.single().count)
    }

    private fun assertMeans(expected: List<Double>, actual: List<Double>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 0.001) }
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
