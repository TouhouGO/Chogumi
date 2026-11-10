package io.github.touhougo.chogumi.core.model

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 系统通知的几个决定：社区提醒一轮发什么、撤什么，短信条数，放送提醒今天有哪几部、几点发。 */
class SystemNotificationsTest {

    private fun notice(id: Int) = NoticeBo(
        id = id,
        type = 2,
        sender = NoticeSenderBo(id = 9, username = "fixture_notice_user", nickname = "示例发送者"),
        title = "示例通知",
        mainId = 1,
        relatedId = 2,
        createdAt = 0L,
        unread = true,
    )

    @Test
    fun `没有基线时不发_基线记成现有未读里最大的`() {
        val round = noticeRoundOf(listOf(notice(5), notice(9)), baseline = null, shown = emptySet(), suppress = false)

        assertTrue(round.post.isEmpty())
        assertEquals(9, round.baseline)
    }

    @Test
    fun `比基线新且没挂着的按id升序发出`() {
        val round = noticeRoundOf(listOf(notice(12), notice(8), notice(11), notice(10)), baseline = 9, shown = setOf(11), suppress = false)

        assertEquals(listOf(10, 12), round.post.map { it.id })
        assertEquals(12, round.baseline)
    }

    @Test
    fun `挂着的不在未读里了就撤掉_还未读的留着`() {
        val round = noticeRoundOf(listOf(notice(11)), baseline = 11, shown = setOf(10, 11), suppress = false)

        assertEquals(setOf(10), round.cancel)
        assertTrue(round.post.isEmpty())
    }

    @Test
    fun `前台时只推进基线不发_读过的照样撤`() {
        val round = noticeRoundOf(listOf(notice(12)), baseline = 9, shown = setOf(8), suppress = true)

        assertTrue(round.post.isEmpty())
        assertEquals(12, round.baseline)
        assertEquals(setOf(8), round.cancel)
    }

    @Test
    fun `未读清空时基线不往回走`() {
        val round = noticeRoundOf(emptyList(), baseline = 20, shown = emptySet(), suppress = false)

        assertEquals(20, round.baseline)
        assertTrue(round.post.isEmpty())
    }

    @Test
    fun `短信条数变多才发_没有记录只记_归零撤掉`() {
        assertEquals(MessageRound.Post(3), messageRoundOf(count = 3, notified = 1, suppress = false))
        assertEquals(MessageRound.Keep, messageRoundOf(count = 3, notified = null, suppress = false))
        assertEquals(MessageRound.Keep, messageRoundOf(count = 3, notified = 3, suppress = false))
        assertEquals(MessageRound.Keep, messageRoundOf(count = 2, notified = 3, suppress = false))
        assertEquals(MessageRound.Keep, messageRoundOf(count = 4, notified = 1, suppress = true))
        assertEquals(MessageRound.Cancel, messageRoundOf(count = 0, notified = 2, suppress = false))
    }

    @Test
    fun `每种提醒都归到一个通知类别`() {
        assertEquals(NoticeChannel.REPLY, noticeChannelOf(NoticeKind.GROUP_POST_REPLY))
        assertEquals(NoticeChannel.MENTION, noticeChannelOf(NoticeKind.TIMELINE_MENTION))
        assertEquals(NoticeChannel.FRIEND, noticeChannelOf(NoticeKind.REQUEST_FRIEND))
        assertEquals(NoticeChannel.OTHER, noticeChannelOf(NoticeKind.SUBJECT_PATCH_ACCEPTED))
        assertEquals(NoticeChannel.OTHER, noticeChannelOf(NoticeKind.UNKNOWN))
    }

    private val today: LocalDate = LocalDate.of(2026, 10, 15)

    private fun subject(id: Int) = SubjectBo(id = id, type = SubjectType.ANIME, name = "Subject $id", nameCn = "条目 $id")

    private fun episode(id: Int, subjectId: Int, sort: Double, airdate: String) =
        EpisodeBo(id = id, subjectId = subjectId, type = 0, name = "", nameCn = "", sort = sort, airdate = airdate)

    private fun entry(id: Int, airing: AiringMinute?) = CalendarEntry(CalendarItemBo(subject = subject(id), watchers = 0), airing)

    private fun day(vararg following: CalendarEntry) = CalendarDaySections(following.toList(), emptyList(), emptyList())

    @Test
    fun `今天播的那一集带上集号_播出日不是今天的不带`() {
        val followed = listOf(
            FollowedWork(subject(1), listOf(episode(10, 1, 7.0, "2026-10-08"), episode(11, 1, 8.0, "2026-10-15"))),
            FollowedWork(subject(2), listOf(episode(20, 2, 3.0, "2026-10-22"))),
            FollowedWork(subject(3), null),
        )

        val reminders = airingRemindersOf(day(entry(1, AiringMinute(23 * 60)), entry(2, AiringMinute(22 * 60)), entry(3, null)), followed, today)

        assertEquals(listOf(1, 2, 3), reminders.map { it.subject.id })
        assertEquals(11, reminders[0].episode?.id)
        assertNull(reminders[1].episode)
        assertNull(reminders[2].episode)
    }

    @Test
    fun `深夜番的集写在第二天也算今天这一集_白天的不算`() {
        val followed = listOf(
            FollowedWork(subject(1), listOf(episode(10, 1, 4.0, "2026-10-16"))),
            FollowedWork(subject(2), listOf(episode(20, 2, 4.0, "2026-10-16"))),
        )

        val reminders = airingRemindersOf(day(entry(1, AiringMinute(25 * 60)), entry(2, AiringMinute(22 * 60))), followed, today)

        assertEquals(10, reminders[0].episode?.id)
        assertNull(reminders[1].episode)
    }

    @Test
    fun `开播时刻按北京时间换算_深夜的落在日历第二天_没有时刻的是中午`() {
        val beijing = ZoneOffset.ofHours(8)
        val reminder = AiringReminder(subject(1), null, AiringMinute(25 * 60 + 30))

        assertEquals(
            ZonedDateTime.of(2026, 10, 16, 1, 30, 0, 0, beijing).toInstant().toEpochMilli(),
            airingReminderMillisOf(reminder, today),
        )
        assertEquals(
            ZonedDateTime.of(2026, 10, 15, 12, 0, 0, 0, beijing).toInstant().toEpochMilli(),
            airingReminderMillisOf(reminder.copy(airing = null), today),
        )
    }

    @Test
    fun `每天定时取严格晚于现在的第一次_按设备时区`() {
        val zone = ZoneId.of("Asia/Tokyo")
        val before = ZonedDateTime.of(2026, 10, 15, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val exactly = ZonedDateTime.of(2026, 10, 15, 9, 0, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals(exactly, nextDailyMillisOf(before, 9 * 60, zone))
        assertEquals(
            ZonedDateTime.of(2026, 10, 16, 9, 0, 0, 0, zone).toInstant().toEpochMilli(),
            nextDailyMillisOf(exactly, 9 * 60, zone),
        )
    }

    @Test
    fun `开播时提醒到点的发_发过的与晚太久的不发_下一次取最早没到的`() {
        val beijing = ZoneOffset.ofHours(8)
        val now = ZonedDateTime.of(2026, 10, 15, 22, 30, 0, 0, beijing).toInstant().toEpochMilli()
        val reminders = listOf(
            AiringReminder(subject(1), null, AiringMinute(22 * 60)),
            AiringReminder(subject(2), null, AiringMinute(21 * 60)),
            AiringReminder(subject(3), null, AiringMinute(20 * 60)),
            AiringReminder(subject(4), null, AiringMinute(25 * 60)),
            AiringReminder(subject(5), null, AiringMinute(23 * 60)),
        )

        val schedule = airingScheduleOf(reminders, today, posted = setOf(2), nowMillis = now)

        assertEquals(listOf(1), schedule.due.map { it.subject.id })
        assertEquals(ZonedDateTime.of(2026, 10, 15, 23, 0, 0, 0, beijing).toInstant().toEpochMilli(), schedule.next)
    }

    @Test
    fun `今天都发完了没有下一次`() {
        val now = ZonedDateTime.of(2026, 10, 15, 23, 30, 0, 0, ZoneOffset.ofHours(8)).toInstant().toEpochMilli()

        val schedule = airingScheduleOf(listOf(AiringReminder(subject(1), null, AiringMinute(23 * 60))), today, posted = setOf(1), nowMillis = now)

        assertTrue(schedule.due.isEmpty())
        assertNull(schedule.next)
    }

    @Test
    fun `每天定时的上一次不晚于现在`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val after = ZonedDateTime.of(2026, 10, 15, 9, 20, 0, 0, zone).toInstant().toEpochMilli()
        val before = ZonedDateTime.of(2026, 10, 15, 8, 0, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals(ZonedDateTime.of(2026, 10, 15, 9, 0, 0, 0, zone).toInstant().toEpochMilli(), lastDailyMillisOf(after, 9 * 60, zone))
        assertEquals(ZonedDateTime.of(2026, 10, 14, 9, 0, 0, 0, zone).toInstant().toEpochMilli(), lastDailyMillisOf(before, 9 * 60, zone))
    }

    @Test
    fun `夏令时切换那天每天定时的上一次按日历往回退`() {
        val zone = ZoneId.of("America/New_York")
        fun at(month: Int, day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

        // 3 月 8 日只有 23 小时：9 点整这一刻就是今天的这一次
        assertEquals(at(3, 8, 9), lastDailyMillisOf(at(3, 8, 9), 9 * 60, zone))
        // 11 月 1 日有 25 小时：8 点半时今天的 9 点还没到，上一次是昨天的
        assertEquals(at(10, 31, 9), lastDailyMillisOf(at(11, 1, 8, 30), 9 * 60, zone))
    }
}
