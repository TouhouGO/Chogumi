package io.github.touhougo.chogumi.core.model.statistics

import io.github.touhougo.chogumi.core.model.COVER_PICKS_LIMIT
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.HIGH_SCORE_MIN
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TIME_MACHINE_YEARS
import io.github.touhougo.chogumi.core.model.TOP_RATED_LIMIT
import io.github.touhougo.chogumi.core.model.TagBo
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 生涯统计纯函数的单测：每一行期望值都手算在断言旁边。 */
class CollectionStatisticsCalculatorTest {

    @Test
    fun `统计五态计数`() {
        val result = stats(
            collection(type = CollectionType.DONE),
            collection(type = CollectionType.DOING),
            collection(type = CollectionType.WISH),
            collection(type = CollectionType.ON_HOLD),
            collection(type = CollectionType.DROPPED),
            collection(type = CollectionType.WISH, subject = subject(type = SubjectType.BOOK)),
        )

        assertEquals(6, result.totalCount)
        assertEquals(2, result.statusCounts[CollectionType.WISH])
        assertEquals(1, result.statusCounts[CollectionType.DOING])
        assertEquals(1, result.statusCounts[CollectionType.DONE])
        assertEquals(1, result.statusCounts[CollectionType.ON_HOLD])
        assertEquals(1, result.statusCounts[CollectionType.DROPPED])
    }

    @Test
    fun `高分作品只收门槛以上，分高的在前，同分最近收藏的在前`() {
        val ten = subject()
        val nineOld = subject()
        val nineNew = subject()
        val eight = subject()
        val seven = subject()
        val result = stats(
            collection(rate = 8, subject = eight, updatedAt = 50),
            collection(rate = 9, subject = nineOld, updatedAt = 10),
            collection(rate = HIGH_SCORE_MIN - 1, subject = seven, updatedAt = 99),
            collection(rate = 10, subject = ten, updatedAt = 1),
            collection(rate = 9, subject = nineNew, updatedAt = 20),
            collection(rate = 0),
        )

        assertEquals(
            listOf(ten to 10, nineNew to 9, nineOld to 9, eight to 8),
            result.topRated.map { it.subject to it.score },
            "10 分 → 9 分（新的在前）→ 8 分；7 分与未打分不入选",
        )
    }

    @Test
    fun `高分作品最多留上限那么多部，缺条目资料的不入选`() {
        val rows = buildList {
            repeat(TOP_RATED_LIMIT + 3) { add(collection(rate = 9, updatedAt = it.toLong())) }
            add(collection(rate = 10, subject = null))
        }
        val result = stats(*rows.toTypedArray())

        assertEquals(TOP_RATED_LIMIT, result.topRated.size)
        assertTrue(result.topRated.all { it.score == 9 }, "没有条目资料就没有封面可画，10 分那条不入选")
    }

    @Test
    fun `封面候选只收有封面的，分高的在前，同分看过与在看在前，再按最近收藏`() {
        val ten = subject(image = "a")
        val nineWish = subject(image = "b")
        val nineDone = subject(image = "c")
        val nineDoingNew = subject(image = "d")
        val unrated = subject(image = "e")
        val noCover = subject()
        val result = stats(
            collection(rate = 9, type = CollectionType.WISH, subject = nineWish, updatedAt = 90),
            collection(rate = 0, type = CollectionType.DONE, subject = unrated, updatedAt = 99),
            collection(rate = 9, type = CollectionType.DONE, subject = nineDone, updatedAt = 10),
            collection(rate = 10, type = CollectionType.DROPPED, subject = ten, updatedAt = 1),
            collection(rate = 9, type = CollectionType.DOING, subject = nineDoingNew, updatedAt = 20),
            collection(rate = 10, type = CollectionType.DONE, subject = noCover, updatedAt = 50),
        )

        assertEquals(listOf(ten, nineDoingNew, nineDone, nineWish, unrated), result.coverPicks)
    }

    @Test
    fun `封面候选最多留上限那么多部`() {
        val rows = List(COVER_PICKS_LIMIT + 5) { collection(subject = subject(image = "x")) }
        assertEquals(COVER_PICKS_LIMIT, stats(*rows.toTypedArray()).coverPicks.size)
    }

    @Test
    fun `时光机按放送年代与收藏年份分格，最近收藏的在前`() {
        val ninetiesOld = subject(date = "1998-04-01")
        val ninetiesNew = subject(date = "1995-04-01")
        val recent = subject(date = "2024-01-01")
        val result = stats(
            collection(subject = ninetiesOld, updatedAt = midYear(2025)),
            collection(subject = ninetiesNew, updatedAt = midYear(2025) + 1),
            collection(subject = recent, updatedAt = midYear(2024)),
        )

        val machine = result.timeMachine
        // 收藏年份从最早的 2024 连续到今年 2026；年代只有出现过的 1990、2020
        assertEquals(listOf(2024, 2025, 2026), machine.years)
        assertEquals(listOf(1990, 2020), machine.decades)
        assertEquals(
            listOf(Triple(1990, 2025, listOf(ninetiesNew.id, ninetiesOld.id)), Triple(2020, 2024, listOf(recent.id))),
            machine.cells.map { Triple(it.decade, it.year, it.subjects.map { subject -> subject.id }) },
        )
    }

    @Test
    fun `时光机只看最近几年，更早收进来的不占列`() {
        val result = stats(
            collection(subject = subject(date = "2000-01-01"), updatedAt = midYear(2026 - TIME_MACHINE_YEARS)),
            collection(subject = subject(date = "2010-01-01"), updatedAt = midYear(2026 - TIME_MACHINE_YEARS + 1)),
        )
        assertEquals((2026 - TIME_MACHINE_YEARS + 1..2026).toList(), result.timeMachine.years)
        assertEquals(listOf(2010), result.timeMachine.decades)
    }

    @Test
    fun `平均晚几年只算看过的，收藏早于放送算零年，年份不明的不计`() {
        val result = stats(
            collection(type = CollectionType.DONE, subject = subject(date = "2020-01-01"), updatedAt = midYear(2025)), // 5 年
            collection(type = CollectionType.DONE, subject = subject(date = "2026-01-01"), updatedAt = midYear(2025)), // 早于放送：0 年
            collection(type = CollectionType.DONE, subject = subject(date = "2022-01-01"), updatedAt = midYear(2024)), // 2 年
            collection(type = CollectionType.WISH, subject = subject(date = "1990-01-01"), updatedAt = midYear(2025)), // 想看：不计
            collection(type = CollectionType.DONE, subject = subject(date = null), updatedAt = midYear(2025)),        // 放送年份不明：不计
            collection(type = CollectionType.DONE, subject = subject(date = "2000-01-01")),                           // 收藏时间不明：不计
        )
        // (5 + 0 + 2) / 3 = 2.33 → 2.3
        assertEquals(2.3, result.timeMachine.averageLag)
    }

    @Test
    fun `篇幅四档只统计动画`() {
        val result = stats(
            collection(subject = subject(type = SubjectType.ANIME, eps = 12)),      // ≤13
            collection(subject = subject(type = SubjectType.ANIME, eps = 13)),      // ≤13
            collection(subject = subject(type = SubjectType.ANIME, eps = 26)),      // 14–26
            collection(subject = subject(type = SubjectType.ANIME, eps = 52)),      // 27–52
            collection(subject = subject(type = SubjectType.ANIME, eps = 100)),     // >52
            collection(subject = subject(type = SubjectType.ANIME, eps = 0)),       // 未知话数：不入档
            collection(subject = subject(type = SubjectType.ANIME, eps = null)),    // 未知话数：不入档
            collection(subject = subject(type = SubjectType.BOOK, eps = 200)),      // 书籍不计
        )
        val lengths = requireNotNull(result.lengthDistribution)
        assertEquals(2, lengths.upTo13)
        assertEquals(1, lengths.from14To26)
        assertEquals(1, lengths.from27To52)
        assertEquals(1, lengths.over52)
        assertEquals(5, lengths.total, "未知话数不得进任何一档")
    }

    @Test
    fun `没有动画收藏时篇幅分布为空`() {
        val result = stats(collection(subject = subject(type = SubjectType.BOOK)))
        assertNull(result.lengthDistribution)
    }

    @Test
    fun `形式分布按标签命中且一部只归一档`() {
        val result = stats(
            // 同一部同时带 TV 与 剧场版（标签顺序与候选顺序相反）：只计候选列表里靠前的 TV 一档。
            collection(subject = subject(tags = listOf("剧场版", "TV"))),
            collection(subject = subject(tags = listOf("剧场版"))),
            collection(subject = subject(tags = listOf("WEB"))),
        )
        assertEquals(
            listOf("TV" to 1, "剧场版" to 1, "WEB" to 1).sortedBy { it.first },
            result.formCounts.map { it.label to it.count }.sortedBy { it.first },
        )
    }

    @Test
    fun `迷你专辑单列一档而不并进专辑`() {
        val result = stats(
            collection(subject = subject(type = SubjectType.MUSIC, tags = listOf("迷你专辑"))),
            collection(subject = subject(type = SubjectType.MUSIC, tags = listOf("专辑"))),
        )
        assertEquals(
            listOf("专辑" to 1, "迷你专辑" to 1),
            result.formCounts.map { it.label to it.count }.sortedBy { it.first },
        )
    }

    @Test
    fun `题材均分只算打过分的，一部都没打分的题材不在表里`() {
        val result = stats(
            collection(rate = 9, subject = subject(tags = listOf("搞笑"))),
            collection(rate = 6, subject = subject(tags = listOf("搞笑", "日常"))),
            collection(rate = 0, subject = subject(tags = listOf("搞笑"))),
            collection(rate = 0, subject = subject(tags = listOf("恋爱"))),
        )
        // 搞笑：(9 + 6) / 2 = 7.5；日常：6；恋爱一部都没打分
        assertEquals(mapOf("搞笑" to 7.5, "日常" to 6.0), result.genreRatings)
    }

    @Test
    fun `题材分布按AnimeTaxonomy的题材词表归类`() {
        val result = stats(
            collection(subject = subject(tags = listOf("搞笑", "喜剧", "TV", "漫改"))),
            collection(subject = subject(tags = listOf("搞笑", "日常"))),
        )
        assertEquals(listOf("搞笑" to 2, "日常" to 1), result.genreCounts.map { it.label to it.count })
    }

    @Test
    fun `打分均值与大众均值各取一位小数后相减`() {
        // 我：49 / 6 取一位为 8.2；大众：7.32 取一位为 7.3，再相减为 0.9。
        // 先相减再取一位会得到 0.8，不能代表两项显示均分的差。
        val result = stats(
            collection(rate = 9, subject = subject(id = 1, rating = 7.32)),
            collection(rate = 9, subject = subject(id = 2, rating = 7.32)),
            collection(rate = 8, subject = subject(id = 3, rating = 7.32)),
            collection(rate = 8, subject = subject(id = 4, rating = 7.32)),
            collection(rate = 8, subject = subject(id = 5, rating = 7.32)),
            collection(rate = 7, subject = subject(id = 6, rating = 7.32)),
        )
        val verdict = requireNotNull(result.ratingVerdict)
        assertEquals(6, verdict.ratedCount)
        assertEquals(8.2, verdict.myAverage)
        assertEquals(7.3, verdict.publicAverage)
        assertEquals(0.9, verdict.gap)
        // 散点的点 = 全部样本，按输入顺序，大众分取一位小数
        assertEquals((1..6).toList(), verdict.works.map { it.subjectId })
        assertEquals(List(6) { 7.3 }, verdict.works.map { it.publicScore })
        assertEquals(listOf(1.7, 1.7, 0.7, 0.7, 0.7, -0.3), verdict.works.map { it.gap })
    }

    @Test
    fun `打分少于五部不给结论`() {
        val result = stats(
            collection(rate = 9, subject = subject(id = 1, rating = 8.0)),
            collection(rate = 8, subject = subject(id = 2, rating = 7.0)),
            collection(rate = 7, subject = subject(id = 3, rating = 5.0)),
            collection(rate = 6, subject = subject(id = 4, rating = 5.0)),
        )
        assertNull(result.ratingVerdict)
    }

    @Test
    fun `填坑率看过除以看过加抛弃没有分母给null`() {
        val withDrop = stats(
            collection(type = CollectionType.DONE),
            collection(type = CollectionType.DONE),
            collection(type = CollectionType.DONE),
            collection(type = CollectionType.DROPPED),
        )
        // 3 / (3+1) = 75%
        assertEquals(75, withDrop.finishRate)

        val noDenominator = stats(collection(type = CollectionType.WISH))
        assertNull(noDenominator.finishRate)
    }

    @Test
    fun `填坑率与其他占比一样四舍五入`() {
        val twoOfThree = stats(
            collection(type = CollectionType.DONE),
            collection(type = CollectionType.DONE),
            collection(type = CollectionType.DROPPED),
        )
        // 2 / 3 = 66.7%，和生涯页其他占比一样记 67
        assertEquals(67, twoOfThree.finishRate)
    }

    @Test
    fun `看过的集数用逐集口径且投入时间按24分钟估算`() {
        val result = statsWithEpisodes(mapOf(1 to 12, 2 to 24))
        assertEquals(36, result.episodeProgress)
        // 36 集 × 24 分钟 = 864 分钟
        assertEquals(864, result.watchTime.minutes)
        assertTrue(result.watchTime.estimated)
    }

    @Test
    fun `书籍卷数仍按volStatus加总`() {
        val result = stats(
            collection(volStatus = 4, subject = subject(type = SubjectType.BOOK)),
            collection(volStatus = 3, subject = subject(type = SubjectType.BOOK)),
        )
        assertEquals(7, result.volumeProgress)
        assertEquals(0, result.episodeProgress)
    }

    @Test
    fun `书籍话数按书籍收藏的epStatus加总其他类型不计`() {
        val result = stats(
            collection(epStatus = 120, subject = subject(type = SubjectType.BOOK)),
            collection(epStatus = 30, subject = subject(type = SubjectType.BOOK)),
            collection(epStatus = 12, subject = subject(type = SubjectType.ANIME)),
        )
        // 120 + 30 = 150；动画的 12 不是「话」
        assertEquals(150, result.chapterProgress)
    }

    @Test
    fun `空输入等于全零默认快照`() {
        val result = stats()
        assertEquals(io.github.touhougo.chogumi.core.model.CollectionStatistics(), result)
    }

    @Test
    fun `缺失条目资料的收藏计入五态_不进题材与篇幅`() {
        val result = stats(collection(type = CollectionType.DONE, subject = null))
        assertEquals(1, result.statusCounts[CollectionType.DONE])
        assertTrue(result.genreCounts.isEmpty())
        assertNull(result.lengthDistribution)
    }

    @Test
    fun `评分零不参与平均分`() {
        val result = stats(collection(rate = 0), collection(rate = 7), collection(rate = 9))
        assertEquals(2, result.ratedCount)
        assertEquals(8.0, result.averageRating)
    }

    @Test
    fun `没打过分时没有均分`() {
        val result = stats(collection(rate = 0))
        assertEquals(0, result.ratedCount)
        assertNull(result.averageRating)
    }

    // ---- 冷门度 ----

    @Test
    fun `冷门度按收藏人数从少到多，中位数取人少的一半的上界，最冷门的取有封面的`() {
        val tiny = subject(members = 300)
        val small = subject(members = 900, image = "s")
        val mid = subject(members = 5_000, image = "m")
        val big = subject(members = 40_000, image = "b")
        val huge = subject(members = 80_000, image = "h")
        val result = stats(
            collection(type = CollectionType.DONE, subject = big),
            collection(type = CollectionType.DOING, subject = tiny),
            collection(subject = huge),
            collection(subject = mid),
            collection(subject = small),
            collection(subject = subject(members = 0)), // 没有收藏人数：不画点
        )

        val popularity = requireNotNull(result.popularity)
        assertEquals(listOf(300, 900, 5_000, 40_000, 80_000), popularity.points.map { it.members })
        assertEquals(CollectionType.DOING, popularity.points.first().status)
        assertEquals(5_000, popularity.medianMembers, "五部取第三部")
        assertEquals(listOf(small, mid, big), popularity.niche, "最冷门的那部没有封面，跳过")
    }

    @Test
    fun `排名分档，排名为零算没有排名，没有收藏人数的作品也分档`() {
        val result = stats(
            collection(subject = subject(members = 10, rank = 100)),
            collection(subject = subject(members = 10, rank = 101)),
            collection(subject = subject(members = 10, rank = 2000)),
            collection(subject = subject(members = 10, rank = 2001)),
            collection(subject = subject(members = 0, rank = 0)),
        )
        // 前 100、101–500、501–2000、2000 以后、没有排名
        assertEquals(listOf(1, 1, 1, 1, 1), requireNotNull(result.popularity).rankTiers)
    }

    @Test
    fun `一部都没有收藏人数时没有冷门度`() {
        assertNull(stats(collection(subject = subject(members = 0))).popularity)
    }

    // ---- 辅助 ----

    /** 默认当前年 2026、无逐集数据。 */
    private fun stats(vararg rows: UserCollectionBo) = calculate(rows.toList())

    /** 带「逐集口径看过集数」的入口。 */
    private fun statsWithEpisodes(watchedEpisodes: Map<Int, Int>, vararg rows: UserCollectionBo) =
        calculate(rows.toList(), watchedEpisodes)

    private fun calculate(
        rows: List<UserCollectionBo>,
        watchedEpisodes: Map<Int, Int> = emptyMap(),
    ) = StatisticsInput(
        collections = rows,
        watchedMainEpisodes = watchedEpisodes,
        currentYear = 2026,
    ).calculateCollectionStatistics()

    private var nextId = 1

    /** [year] 年年中（业务日期与 UTC 同一年）。 */
    private fun midYear(year: Int): Long = LocalDate.of(year, 7, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun collection(
        type: CollectionType = CollectionType.WISH,
        rate: Int = 0,
        tags: List<String> = emptyList(),
        volStatus: Int = 0,
        epStatus: Int = 0,
        subject: SubjectBo? = subject(),
        updatedAt: Long = 0L,
    ): UserCollectionBo = UserCollectionBo(
        subjectId = subject?.id ?: 1,
        subjectType = subject?.type ?: SubjectType.ANIME,
        type = type,
        rate = rate,
        tags = tags,
        volStatus = volStatus,
        epStatus = epStatus,
        subject = subject,
        updatedAt = updatedAt,
    )

    private fun subject(
        id: Int = nextId++,
        type: SubjectType = SubjectType.ANIME,
        date: String? = "2024-01-01",
        eps: Int? = null,
        rating: Double = 0.0,
        tags: List<String> = emptyList(),
        image: String? = null,
        members: Int = 0,
        rank: Int = 0,
    ): SubjectBo = SubjectBo(
        id = id,
        type = type,
        name = "条目$id",
        nameCn = "条目$id",
        date = date,
        eps = eps,
        ratingScore = rating,
        imageLarge = image,
        collectionTotal = members,
        ratingRank = rank,
        tags = tags.mapIndexed { index, name -> TagBo(name = name, count = index + 1) },
    )
}
