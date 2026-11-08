package io.github.touhougo.chogumi.core.model

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSectionsTest {

    private fun fixtureItem(id: Int, watchers: Int) = CalendarItemBo(
        subject = SubjectBo(
            id = id,
            type = SubjectType.ANIME,
            name = "Subject $id",
            nameCn = "条目 $id",
        ),
        watchers = watchers,
    )

    /** 2026-10-15 周四 [hour]:[minute] 北京时间的每周首播（放送日仍是周四）。 */
    private fun thursdayAt(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 10, 15, hour, minute, 0, 0, ZoneOffset.ofHours(8)).toInstant().toEpochMilli()

    private val thursday = 4

    @Test
    fun `空集合分区返回空列表`() {
        val result = calendarDaySections(thursday, emptyList(), setOf(1, 2, 3), emptyMap())
        assertTrue(result.following.isEmpty())
        assertTrue(result.isEmpty)
    }

    @Test
    fun `有时刻的按时刻分组升序_同一时刻按在看人数降序_没有时刻的排最后`() {
        val items = listOf(
            fixtureItem(id = 1, watchers = 100),
            fixtureItem(id = 2, watchers = 500),
            fixtureItem(id = 3, watchers = 300),
            fixtureItem(id = 4, watchers = 900),
        )
        val starts = mapOf(1 to thursdayAt(22, 0), 2 to thursdayAt(20, 30), 3 to thursdayAt(22, 0))

        val result = calendarDaySections(thursday, items, emptySet(), starts)

        assertEquals(listOf("20:30", "22:00"), result.slots.map { it.minute.label() })
        assertEquals(listOf(2), result.slots[0].entries.map { it.subject.id })
        assertEquals(listOf(3, 1), result.slots[1].entries.map { it.subject.id })
        assertEquals(listOf(4), result.unknown.map { it.subject.id })
        assertTrue(result.following.isEmpty())
    }

    @Test
    fun `星期对不上的作品算没有时刻`() {
        // 首播在周五 01:00 北京（放送日周四 25:00）给周四没问题；给周三就对不上
        val items = listOf(fixtureItem(id = 1, watchers = 100))
        val starts = mapOf(1 to ZonedDateTime.of(2026, 10, 16, 1, 0, 0, 0, ZoneOffset.ofHours(8)).toInstant().toEpochMilli())

        assertEquals(listOf("25:00"), calendarDaySections(thursday, items, emptySet(), starts).slots.map { it.minute.label() })
        val wednesday = calendarDaySections(3, items, emptySet(), starts)
        assertTrue(wednesday.slots.isEmpty())
        assertEquals(listOf(1), wednesday.unknown.map { it.subject.id })
    }

    @Test
    fun `在追的按时刻升序_没有时刻的排最后_同一时刻按在看人数降序`() {
        val items = listOf(
            fixtureItem(id = 1, watchers = 100),
            fixtureItem(id = 2, watchers = 500),
            fixtureItem(id = 3, watchers = 300),
            fixtureItem(id = 4, watchers = 900),
        )
        val starts = mapOf(1 to thursdayAt(22, 0), 2 to thursdayAt(23, 30), 3 to thursdayAt(22, 0))

        val result = calendarDaySections(thursday, items, followedSubjectIds = setOf(1, 2, 3, 4), starts)

        assertEquals(listOf(3, 1, 2, 4), result.following.map { it.subject.id })
    }

    @Test
    fun `在看人数相同时按条目 id 升序`() {
        val items = listOf(fixtureItem(id = 10, watchers = 300), fixtureItem(id = 5, watchers = 300), fixtureItem(id = 20, watchers = 300))

        val result = calendarDaySections(thursday, items, followedSubjectIds = setOf(10, 5), emptyMap())

        assertEquals(listOf(5, 10, 20), result.unknown.map { it.subject.id })
        assertEquals(listOf(5, 10), result.following.map { it.subject.id })
    }

    @Test
    fun `在追名单里不在放送表的 id 不影响分区`() {
        val items = listOf(fixtureItem(id = 101, watchers = 150), fixtureItem(id = 102, watchers = 250))

        val result = calendarDaySections(thursday, items, followedSubjectIds = setOf(999, 888, 101), emptyMap())

        assertEquals(listOf(101), result.following.map { it.subject.id })
        assertEquals(listOf(102, 101), result.unknown.map { it.subject.id })
    }

    @Test
    fun `现在线画在第一个还没到的时刻之前_都开播了画在最后`() {
        val slots = listOf(AiringMinute(20 * 60), AiringMinute(22 * 60), AiringMinute(24 * 60 + 30)).map { AiringSlot(it, emptyList()) }

        assertEquals(0, slots.nowLineIndex(AiringMinute(14 * 60)))
        // 正好到点的那一组算已开播
        assertEquals(1, slots.nowLineIndex(AiringMinute(20 * 60)))
        assertEquals(2, slots.nowLineIndex(AiringMinute(22 * 60 + 40)))
        assertEquals(3, slots.nowLineIndex(AiringMinute(25 * 60)))
    }

    private fun mainEpisode(id: Int, airdate: String) = EpisodeBo(id = id, subjectId = 1, type = 0, name = "", nameCn = "", sort = id.toDouble(), airdate = airdate)

    /** 放送日的今天：2026-10-15 周四。 */
    private val today = LocalDate.of(2026, 10, 15)

    @Test
    fun `在追作品哪天播_有章节看下一集未播正片_本机没章节才退回放送表的星期`() {
        val episodes = listOf(mainEpisode(1, "2026-10-08"), mainEpisode(2, "2026-10-15"), mainEpisode(3, "2026-10-22"))
        // 今天播的那集算今天，放送表说周一也不听
        assertEquals(today, followedAiringDateOf(episodes, calendarWeekday = 1, today))
        // 正片都播过了：这几天不播，放送表说什么都不算
        assertEquals(null, followedAiringDateOf(episodes.take(1), calendarWeekday = 4, today))
        // 本机没章节：放送表的星期，从今天起第一个
        assertEquals(LocalDate.of(2026, 10, 19), followedAiringDateOf(null, calendarWeekday = 1, today))
        assertEquals(today, followedAiringDateOf(null, calendarWeekday = 4, today))
        assertEquals(null, followedAiringDateOf(null, calendarWeekday = null, today))
    }

    @Test
    fun `这七天里不播的在追作品哪天都不出现_没在追的照放送表`() {
        val calendar = mapOf(4 to listOf(fixtureItem(id = 1, watchers = 10), fixtureItem(id = 2, watchers = 20)))
        // 条目 1 这周停播：下一集在 8 天后
        val followed = listOf(FollowedWork(fixtureItem(1, 0).subject, listOf(mainEpisode(1, "2026-10-23"))))

        val week = calendarWeek(calendar, followed, today, emptyMap())

        assertEquals((1..7).toSet(), week.keys)
        assertTrue(week.values.none { day -> day.following.isNotEmpty() })
        assertEquals(listOf(2), week.getValue(4).unknown.map { it.subject.id })
    }

    @Test
    fun `在追的只算在看_条目先取收藏行带的_没有取放送表的_都没有的不算`() {
        val calendar = mapOf(4 to listOf(fixtureItem(id = 2, watchers = 20)))
        val own = SubjectBo(id = 1, type = SubjectType.ANIME, name = "Own", nameCn = "")
        val collections = listOf(
            UserCollectionBo(subjectId = 1, subjectType = SubjectType.ANIME, type = CollectionType.DOING, subject = own),
            UserCollectionBo(subjectId = 2, subjectType = SubjectType.ANIME, type = CollectionType.DOING),
            UserCollectionBo(subjectId = 3, subjectType = SubjectType.ANIME, type = CollectionType.DOING),
            UserCollectionBo(subjectId = 4, subjectType = SubjectType.ANIME, type = CollectionType.WISH, subject = own.copy(id = 4)),
        )
        val episodes = mapOf(2 to listOf(mainEpisode(1, "2026-10-15")))

        val followed = followedWorksOf(collections, calendar, episodes)

        assertEquals(listOf(1, 2), followed.map { it.subject.id })
        assertEquals("Own", followed[0].subject.name)
        assertEquals(null, followed[0].episodes)
        assertEquals("条目 2", followed[1].subject.nameCn)
        assertEquals(episodes[2], followed[1].episodes)
    }
}
