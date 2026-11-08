package io.github.touhougo.chogumi.core.model.statistics

import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpisodeMark
import io.github.touhougo.chogumi.core.model.EpisodeMarks
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.model.WatchDay
import io.github.touhougo.chogumi.core.model.ringLevelOf
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 逐集记录看出来的节奏：年轮、几点看、追得多紧、一部看多久，以及补标怎么算。 */
class WatchRhythmCalculatorTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `年轮一天按集数分五档`() {
        assertEquals(listOf(0, 1, 1, 2, 2, 3, 3, 4), listOf(0, 1, 2, 3, 4, 5, 8, 9).map(::ringLevelOf))
    }

    // 一部连着三天看完的 12 集（首播都是 3 月 1 日）
    private val series = collection(1, eps = 12)
    private val seriesMarks = listOf(
        mark(LocalDateTime.of(2026, 3, 1, 21, 0), air = LocalDate.of(2026, 3, 1)),
        mark(LocalDateTime.of(2026, 3, 2, 23, 30), air = LocalDate.of(2026, 3, 1)),
        mark(LocalDateTime.of(2026, 3, 3, 1, 0), air = LocalDate.of(2026, 3, 1)),
    )

    // 一部老番「看到这里」一次补标了 5 集：同一秒里（毫秒不同）
    private val backfilled = collection(2, eps = 12)
    private val backfilledMarks = (0 until 5).map { i ->
        EpisodeMark(millisOf(LocalDateTime.of(2026, 3, 10, 12, 0)) + i * 100L, LocalDate.of(2020, 1, 1))
    }

    // 一部剧场版
    private val movie = collection(3, eps = 1)
    private val movieMarks = listOf(mark(LocalDateTime.of(2025, 12, 31, 20, 0), air = LocalDate.of(2025, 12, 1)))

    private val rhythm = watchRhythmOf(
        listOf(series, backfilled, movie),
        EpisodeMarks(mapOf(1 to seriesMarks, 2 to backfilledMarks, 3 to movieMarks), zone),
    )!!

    @Test
    fun `年轮按年分，每天记集数和作品，另有看番天数、最长连看、最多的一天`() {
        assertEquals(listOf(2025, 2026), rhythm.rings.map { it.year })
        val ring = rhythm.rings.last()
        assertEquals(
            mapOf(
                LocalDate.of(2026, 3, 1) to WatchDay(1, listOf("条目1")),
                LocalDate.of(2026, 3, 2) to WatchDay(1, listOf("条目1")),
                LocalDate.of(2026, 3, 3) to WatchDay(1, listOf("条目1")),
                LocalDate.of(2026, 3, 10) to WatchDay(5, listOf("条目2")),
            ),
            ring.days,
        )
        assertEquals(8, ring.episodes, "补标的 5 集也算进当天")
        assertEquals(4, ring.activeDays)
        assertEquals(3, ring.longestStreak)
        assertEquals(LocalDate.of(2026, 3, 10), ring.busiestDay)
    }

    @Test
    fun `早一年的年轮只有那一年的日子，跨年连看在新年那天断开`() {
        val marks = listOf(
            mark(LocalDateTime.of(2025, 12, 30, 21, 0), air = null),
            mark(LocalDateTime.of(2025, 12, 31, 21, 0), air = null),
            mark(LocalDateTime.of(2025, 12, 31, 22, 0), air = null),
            mark(LocalDateTime.of(2026, 1, 1, 21, 0), air = null),
        )
        val rings = watchRhythmOf(listOf(series), EpisodeMarks(mapOf(1 to marks), zone))!!.rings
        assertEquals(listOf(2025, 2026), rings.map { it.year })
        val earlier = rings.first()
        assertEquals(
            mapOf(
                LocalDate.of(2025, 12, 30) to WatchDay(1, listOf("条目1")),
                LocalDate.of(2025, 12, 31) to WatchDay(2, listOf("条目1")),
            ),
            earlier.days,
        )
        assertEquals(3, earlier.episodes)
        assertEquals(2, earlier.activeDays)
        assertEquals(2, earlier.longestStreak)
        assertEquals(LocalDate.of(2025, 12, 31), earlier.busiestDay)
        assertEquals(setOf(LocalDate.of(2026, 1, 1)), rings.last().days.keys)
        assertEquals(1, rings.last().longestStreak)
    }

    @Test
    fun `几点看和星期几不算补标，晚上十点以后占一半`() {
        val hours = MutableList(24) { 0 }.apply { this[21] = 1; this[23] = 1; this[1] = 1; this[20] = 1 }
        assertEquals(hours, rhythm.hours)
        // 3 月 1 日周日、2 日周一、3 日周二，12 月 31 日周三
        assertEquals(listOf(1, 1, 1, 0, 0, 0, 1), rhythm.weekdays)
        assertEquals(50, rhythm.nightShare)
    }

    @Test
    fun `追得多紧只算不止一集的作品且不算补标`() {
        // 首播当天 21 点看的不到 1 天；第二天深夜和第三天凌晨的都在一周内
        assertEquals(listOf(1, 2, 0, 0, 0), rhythm.lagTiers)
        assertEquals(100, rhythm.chaseShare)
    }

    @Test
    fun `只有补标时追得多紧没有答案`() {
        val onlyBackfilled = watchRhythmOf(listOf(backfilled), EpisodeMarks(mapOf(2 to backfilledMarks), zone))!!
        assertNull(onlyBackfilled.chaseShare)
    }

    @Test
    fun `一部看多久只算看完的连续剧，整部都是补标的不算`() {
        val span = rhythm.spans.single()
        assertEquals(1, span.subjectId)
        assertEquals(LocalDate.of(2026, 3, 1), span.start)
        assertEquals(LocalDate.of(2026, 3, 3), span.end)
        assertEquals(3, span.length)
        assertEquals(3, rhythm.medianSpanDays)
    }

    @Test
    fun `日子按给定的时区算`() {
        // 北京时间 3 月 3 日 1 点在 UTC 还是 3 月 2 日 17 点
        val utc = watchRhythmOf(listOf(series), EpisodeMarks(mapOf(1 to seriesMarks), ZoneId.of("UTC")))!!
        assertEquals(setOf(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2)), utc.rings.single().days.keys)
        assertEquals(listOf(13, 15, 17), utc.hours.withIndex().filter { it.value > 0 }.map { it.index })
    }

    @Test
    fun `一集记录都没有时没有节奏`() {
        assertNull(watchRhythmOf(listOf(series), EpisodeMarks(emptyMap(), zone)))
        assertNull(watchRhythmOf(listOf(series), EpisodeMarks(mapOf(1 to listOf(EpisodeMark(0L, null))), zone)))
    }

    private fun millisOf(time: LocalDateTime): Long = time.atZone(zone).toInstant().toEpochMilli()

    private fun mark(time: LocalDateTime, air: LocalDate?) = EpisodeMark(millisOf(time), air)

    private fun collection(id: Int, eps: Int) = UserCollectionBo(
        subjectId = id,
        subjectType = SubjectType.ANIME,
        type = CollectionType.DONE,
        subject = SubjectBo(id = id, type = SubjectType.ANIME, name = "条目$id", nameCn = "条目$id", eps = eps),
        updatedAt = 1L,
    )
}
