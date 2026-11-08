package io.github.touhougo.chogumi.feature.profile

import io.github.touhougo.chogumi.core.data.repository.AUTO_SUMMARIZE_LIMIT
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.CareerStatistics
import io.github.touhougo.chogumi.core.model.CollectionStatistics
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.HIGH_SCORE_MIN
import io.github.touhougo.chogumi.core.model.RankedStatistic
import io.github.touhougo.chogumi.core.model.RatedWork
import io.github.touhougo.chogumi.core.model.RatingVerdict
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.WatchTime
import io.github.touhougo.chogumi.core.res.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 生涯的纯函数层：界面画什么、写什么，都在这里被钉住。 */
class CareerPresentationTest {

    // ---- 三格大数字 ----

    @Test
    fun `动画：本人的集数是精确值，他人的标近似；看完时间不到一千小时按小时`() {
        val stats = CollectionStatistics(
            episodeProgress = 663,
            watchTime = WatchTime(minutes = 265 * 60, estimated = true),
            statusCounts = mapOf(CollectionType.DONE to 22),
        )
        val mine = careerKpisOf(SubjectType.ANIME, stats, isSelf = true)
        assertEquals(listOf(663, 265, 22), mine.map { it.value })
        assertFalse(mine[0].approximate)
        assertEquals(R.string.career_unit_hour, mine[1].unitRes)
        assertTrue(mine[1].approximate, "时间是估算值，始终标近似")

        assertTrue(careerKpisOf(SubjectType.ANIME, stats, isSelf = false)[0].approximate)
    }

    @Test
    fun `没有集数时不标近似`() {
        val kpis = careerKpisOf(SubjectType.ANIME, CollectionStatistics(), isSelf = false)
        assertFalse(kpis[0].approximate)
    }

    @Test
    fun `每种类型都给三格大数字`() {
        ContentDomains.forEach { type ->
            assertEquals(3, careerKpisOf(type, CollectionStatistics(), isSelf = true).size, "$type")
        }
    }

    @Test
    fun `投入时间超过一千小时改按天写`() {
        val kpi = watchTimeKpiOf(minutes = 2070 * 60, estimated = true)
        assertEquals(86, kpi.value)
        assertEquals(R.string.career_unit_day, kpi.unitRes)
        assertEquals(R.string.career_unit_hour, watchTimeKpiOf(minutes = 999 * 60, estimated = true).unitRes)
    }

    @Test
    fun `书籍给卷数话数读完，游戏给玩过在玩平台，音乐给听过在听想听`() {
        val book = CollectionStatistics(
            volumeProgress = 15, chapterProgress = 312,
            statusCounts = mapOf(CollectionType.DONE to 3),
        )
        assertEquals(listOf(15, 312, 3), careerKpisOf(SubjectType.BOOK, book, isSelf = true).map { it.value })

        val game = CollectionStatistics(
            statusCounts = mapOf(CollectionType.DONE to 2, CollectionType.DOING to 1),
            formCounts = listOf(RankedStatistic("PC", 1), RankedStatistic("Switch", 1), RankedStatistic("PS5", 1)),
        )
        assertEquals(listOf(2, 1, 3), careerKpisOf(SubjectType.GAME, game, isSelf = true).map { it.value })

        val music = CollectionStatistics(
            statusCounts = mapOf(CollectionType.DONE to 4, CollectionType.DOING to 1, CollectionType.WISH to 2),
        )
        assertEquals(listOf(4, 1, 2), careerKpisOf(SubjectType.MUSIC, music, isSelf = true).map { it.value })
    }

    // ---- 小函数 ----

    @Test
    fun `峰值并列时取先出现的，空表为空`() {
        assertEquals("a", listOf("a" to 3, "b" to 3, "c" to 1).peakBy { it.second }?.first)
        assertNull(emptyList<Int>().peakBy { it })
    }

    @Test
    fun `打分口味三分类`() {
        assertEquals(TasteLean.STRICTER, tasteLeanOf(-0.1))
        assertEquals(TasteLean.SAME, tasteLeanOf(0.0))
        assertEquals(TasteLean.LENIENT, tasteLeanOf(0.1))
    }

    @Test
    fun `阶梯顺序是看过在看想看搁置抛弃`() {
        val ladder = StatusLadder.of(mapOf(CollectionType.DONE to 5, CollectionType.WISH to 4, CollectionType.DROPPED to 1))
        assertEquals(
            listOf(
                CollectionType.DONE to 5, CollectionType.DOING to 0, CollectionType.WISH to 4,
                CollectionType.ON_HOLD to 0, CollectionType.DROPPED to 1,
            ),
            ladder.waffleOrder,
        )
        assertEquals(10, ladder.total)
    }

    // ---- 口味象限 / 分差点条 ----

    private fun work(id: Int, mine: Int, public: Double) = RatedWork(id, "w$id", mine, public, mine - public)

    @Test
    fun `口味象限以两边均分画十字，落在线上的算高的一侧`() {
        val verdict = RatingVerdict(
            ratedCount = 5, myAverage = 8.0, publicAverage = 7.0, gap = 1.0,
            works = listOf(work(1, 9, 8.0), work(2, 8, 6.0), work(3, 7, 7.0), work(4, 6, 6.5), work(5, 8, 7.0)),
        )
        val quadrants = tasteQuadrantsOf(verdict)
        // 我 ≥ 8 且大众 ≥ 7：1、5（5 两边都正好在线上）；我高大众低：2；我低大众高：3；都低：4
        assertEquals(listOf(1, 5), quadrants.acclaimed.map { it.subjectId })
        assertEquals(listOf(2), quadrants.personal.map { it.subjectId })
        assertEquals(listOf(3), quadrants.letdown.map { it.subjectId })
        assertEquals(listOf(4), quadrants.ordinary.map { it.subjectId })
    }

    @Test
    fun `分差每半分一箱，超出四分的并进两端`() {
        val bins = gapBinsOf(listOf(work(1, 9, 7.8), work(2, 9, 7.6), work(3, 2, 8.0), work(4, 10, 5.0)))
        // 1.2 → 1.0；1.4 → 1.5；-6 → -4；+5 → +4
        assertEquals(listOf(-4.0 to listOf(3), 1.0 to listOf(1), 1.5 to listOf(2), 4.0 to listOf(4)), bins.map { bin -> bin.gap to bin.works.map { it.subjectId } })
    }

    // ---- 公共评分可见性 ----

    @Test
    fun `关掉公共评分时仍有打分口味，但不带大众一侧`() {
        val verdict = RatingVerdict(
            ratedCount = 6, myAverage = 8.1, publicAverage = 7.9, gap = 0.2,
           
        )
        val stats = CollectionStatistics(ratingVerdict = verdict, ratedCount = 6)
        assertEquals(false, scoreViewOf(stats, publicRatings = false)?.showPublic)
        assertEquals(true, scoreViewOf(stats, publicRatings = true)?.showPublic)
        assertNull(scoreViewOf(CollectionStatistics(ratedCount = 2), publicRatings = true), "没过门槛统计层不给结论")
        assertEquals(3, ratingsStillNeeded(CollectionStatistics(ratedCount = 2)))
    }

    @Test
    fun `高分作品总数读直方图里门槛以上的分数`() {
        val stats = CollectionStatistics(
            scoreCounts = mapOf(HIGH_SCORE_MIN - 1 to 4, HIGH_SCORE_MIN to 3, 9 to 2, 10 to 1),
        )
        assertEquals(6, highRatedCountOf(stats), "只数 ≥ 门槛的：3 + 2 + 1")
        assertEquals(0, highRatedCountOf(CollectionStatistics()))
    }

    // ---- 此刻画哪一种 ----

    private val someStats = CollectionStatistics(totalCount = 3, statusCounts = mapOf(CollectionType.DONE to 3))

    @Test
    fun `本人：流未到是加载，到了没有收藏是空，有收藏是内容`() {
        assertIs<CareerContent.Loading>(careerContentOf(SubjectType.ANIME, true, null, Load.Pending, null))
        assertIs<CareerContent.Empty>(
            careerContentOf(SubjectType.BOOK, true, mapOf(SubjectType.ANIME to someStats), Load.Pending, null),
        )
        assertIs<CareerContent.Empty>(
            careerContentOf(SubjectType.ANIME, true, mapOf(SubjectType.ANIME to CollectionStatistics()), Load.Pending, null),
        )
        assertIs<CareerContent.Ready>(
            careerContentOf(SubjectType.ANIME, true, mapOf(SubjectType.ANIME to someStats), Load.Pending, null),
        )
    }

    private fun counts(total: Int) = Load.Ready(mapOf(SubjectType.ANIME to mapOf(CollectionType.DONE to total)))

    @Test
    fun `他人：资料未到是加载，取不到给重试，摘要为零是空`() {
        assertIs<CareerContent.Loading>(careerContentOf(SubjectType.ANIME, false, null, Load.Pending, null))
        assertEquals(
            Failure.Offline,
            assertIs<CareerContent.Unavailable>(careerContentOf(SubjectType.ANIME, false, null, Load.Failed(Failure.Offline), null)).failure,
        )
        assertIs<CareerContent.Empty>(careerContentOf(SubjectType.GAME, false, null, counts(5), null))
    }

    @Test
    fun `他人：自动汇总的上限含一千，一千零一要点按钮`() {
        assertIs<CareerContent.Summarizing>(
            careerContentOf(SubjectType.ANIME, false, null, counts(AUTO_SUMMARIZE_LIMIT), null),
        )
        val waiting = assertIs<CareerContent.AwaitingConfirm>(
            careerContentOf(SubjectType.ANIME, false, null, counts(AUTO_SUMMARIZE_LIMIT + 1), null),
        )
        assertEquals(AUTO_SUMMARIZE_LIMIT + 1, waiting.count)
    }

    @Test
    fun `他人：汇总中阶梯先用服务端摘要，失败保留阶梯，完成换成统计快照`() {
        val running = OtherSummary.Running(io.github.touhougo.chogumi.core.data.repository.SyncProgress(active = true))
        val summarizing = assertIs<CareerContent.Summarizing>(
            careerContentOf(SubjectType.ANIME, false, null, counts(40), running),
        )
        assertEquals(40, summarizing.ladder.done)

        assertEquals(
            Failure.Timeout,
            assertIs<CareerContent.Failed>(
                careerContentOf(SubjectType.ANIME, false, null, counts(40), OtherSummary.Failed(Failure.Timeout)),
            ).failure,
        )

        val ready = assertIs<CareerContent.Ready>(
            careerContentOf(SubjectType.ANIME, false, null, counts(40), OtherSummary.Loaded(someStats)),
        )
        assertEquals(3, ready.ladder.done, "完成后阶梯读统计快照，不读摘要")
    }

    // ---- 生涯卡 ----

    private fun verdict(gap: Double) = RatingVerdict(ratedCount = 12, myAverage = 7.5 + gap, publicAverage = 7.5, gap = gap)

    private fun typeStats(done: Int, wish: Int = 0, genre: String? = null) = CollectionStatistics(
        totalCount = done + wish,
        statusCounts = mapOf(CollectionType.DONE to done, CollectionType.WISH to wish),
        genreCounts = listOfNotNull(genre?.let { RankedStatistic(it, done) }),
    )

    private fun selfCard(
        byType: Map<SubjectType, CollectionStatistics>,
        overall: CollectionStatistics = CollectionStatistics(totalCount = byType.values.sumOf { it.totalCount }),
        publicRatings: Boolean = true,
    ) = careerCardContentOf(true, CareerStatistics(byType, overall), Load.Pending, null, emptyList(), emptyMap(), publicRatings)

    @Test
    fun `生涯卡：本人统计没到是骨架，一部收藏都没有不画卡`() {
        assertIs<CareerCardContent.Loading>(careerCardContentOf(true, null, Load.Pending, null, emptyList(), emptyMap(), publicRatings = true))
        assertIs<CareerCardContent.Empty>(selfCard(ContentDomains.associateWith { CollectionStatistics() }))
    }

    @Test
    fun `生涯卡：各类合起来数，称号的名词与题材取看过最多的那一类`() {
        val card = assertIs<CareerCardContent.Ready>(
            selfCard(
                mapOf(
                    SubjectType.ANIME to typeStats(done = 4, wish = 8, genre = "科幻"),
                    SubjectType.GAME to typeStats(done = 9, genre = "奇幻"),
                    SubjectType.MUSIC to CollectionStatistics(),
                ),
                overall = CollectionStatistics(genreCounts = listOf(RankedStatistic("奇幻", 9), RankedStatistic("科幻", 4))),
            ),
        )
        val portrait = card.portrait
        assertEquals(13, portrait.done)
        assertEquals(listOf(SubjectType.GAME to 9, SubjectType.ANIME to 4), portrait.doneByType, "没有收藏的类型不列，多的在前")
        assertEquals(Persona(SubjectType.GAME, "奇幻", trait = null), portrait.persona)
        assertEquals(listOf("奇幻", "科幻"), portrait.likes)
        assertEquals(SubjectType.GAME, card.openType)
    }

    @Test
    fun `生涯卡：收藏不到十部是刚入场的`() {
        val portrait = assertIs<CareerCardContent.Ready>(selfCard(mapOf(SubjectType.ANIME to typeStats(done = 1)))).portrait
        assertEquals(PersonaTrait.NEWCOMER, portrait.persona.trait)
        assertEquals(1, portrait.doneByType.size, "只有一类：界面不列右边那一栏")
    }

    @Test
    fun `生涯卡：打分比大众严是挑剔的，松是心软的；关掉公共评分就不比`() {
        val byType = mapOf(SubjectType.ANIME to typeStats(done = 20))
        fun traitOf(gap: Double, publicRatings: Boolean = true) =
            assertIs<CareerCardContent.Ready>(selfCard(byType, CollectionStatistics(averageRating = 7.0, ratingVerdict = verdict(gap)), publicRatings)).portrait

        assertEquals(PersonaTrait.STRICT, traitOf(-0.4).persona.trait)
        assertEquals(PersonaTrait.EVEN, traitOf(0.0).persona.trait)
        assertEquals(PersonaTrait.LENIENT, traitOf(0.3).persona.trait)
        assertEquals(-0.4, traitOf(-0.4).publicGap)
        val hidden = traitOf(-0.4, publicRatings = false)
        assertNull(hidden.persona.trait)
        assertNull(hidden.publicGap)
        assertEquals(7.0, hidden.average, "均分照写，只是不和大众比")
    }

    @Test
    fun `生涯卡：他人计数读服务端摘要，资料没到是骨架，取不到给重试`() {
        val server = Load.Ready(mapOf(SubjectType.BOOK to mapOf(CollectionType.DONE to 30), SubjectType.ANIME to mapOf(CollectionType.DOING to 2)))
        assertIs<CareerCardContent.Loading>(careerCardContentOf(false, null, Load.Pending, null, emptyList(), emptyMap(), publicRatings = true))
        assertIs<CareerCardContent.Unavailable>(careerCardContentOf(false, null, Load.Failed(Failure.Offline), null, emptyList(), emptyMap(), publicRatings = true))
        assertIs<CareerCardContent.Empty>(careerCardContentOf(false, null, Load.Ready(emptyMap()), null, emptyList(), emptyMap(), publicRatings = true))

        val portrait = assertIs<CareerCardContent.Ready>(
            careerCardContentOf(
                false, null, server, CollectionStatistics(), emptyList(), mapOf(SubjectType.BOOK to OtherSummary.Loaded(typeStats(30, genre = "推理"))),
                publicRatings = true,
            ),
        ).portrait
        assertEquals(30, portrait.done)
        assertEquals(2, portrait.doing)
        assertEquals(Persona(SubjectType.BOOK, "推理", trait = null), portrait.persona)
    }

    @Test
    fun `生涯卡：他人资料到了、汇总还没完时先画计数与最近一页的封面，均分与题材空着`() {
        val server = Load.Ready(mapOf(SubjectType.ANIME to mapOf(CollectionType.DONE to 860, CollectionType.DOING to 9)))
        val preview = listOf(SubjectBo(id = 1, type = SubjectType.ANIME, name = "a", nameCn = ""))

        val portrait = assertIs<CareerCardContent.Ready>(
            careerCardContentOf(false, null, server, otherOverall = null, otherCovers = preview, summaries = emptyMap(), publicRatings = true),
        ).portrait
        assertEquals(860, portrait.done)
        assertEquals(9, portrait.doing)
        assertEquals(listOf(SubjectType.ANIME to 860), portrait.doneByType)
        assertEquals(preview, portrait.covers)
        assertNull(portrait.average)
        assertTrue(portrait.likes.isEmpty())
        assertEquals(Persona(SubjectType.ANIME, genre = null, trait = null), portrait.persona)
    }

    @Test
    fun `生涯卡：他人某一类汇总好了、合起来的还没算完时称号的题材也空着，一次补全`() {
        val server = Load.Ready(mapOf(SubjectType.ANIME to mapOf(CollectionType.DONE to 860, CollectionType.DOING to 9)))
        val summaries = mapOf(SubjectType.ANIME to OtherSummary.Loaded(typeStats(860, genre = "战斗")))

        val waiting = assertIs<CareerCardContent.Ready>(
            careerCardContentOf(false, null, server, otherOverall = null, otherCovers = emptyList(), summaries = summaries, publicRatings = true),
        ).portrait
        assertEquals(Persona(SubjectType.ANIME, genre = null, trait = null), waiting.persona)

        val done = assertIs<CareerCardContent.Ready>(
            careerCardContentOf(false, null, server, CollectionStatistics(), otherCovers = emptyList(), summaries = summaries, publicRatings = true),
        ).portrait
        assertEquals("战斗", done.persona.genre)
    }
}
