package io.github.touhougo.chogumi.feature.home.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.data.progress.ProgressSubject
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.model.CalendarItemBo
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.model.WatchDay
import io.github.touhougo.chogumi.core.model.YearRing
import io.github.touhougo.chogumi.core.model.calendarDaySections
import io.github.touhougo.chogumi.feature.home.HomeContinueItem
import io.github.touhougo.chogumi.feature.home.HomeProgressCard
import io.github.touhougo.chogumi.feature.home.HomeSectionCandidates
import io.github.touhougo.chogumi.feature.home.HomeToWatchItem
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetContentTest {
    private val SESSION = AccountSession(1, 7, "fixture_user", true)


    @Test
    fun `在看列表先待看再其余在追的，两段都有的那部只出现一次`() {
        val candidates = HomeSectionCandidates(
            toWatch = listOf(toWatch(2), toWatch(1)),
            scheduleGroups = emptyList(),
            continueItems = listOf(continueItem(3), continueItem(1), continueItem(4)),
            doingItems = emptyList(),
        )

        assertEquals(listOf(2, 1, 3, 4), widgetWorksOf(candidates).map { it.subjectId })
    }

    @Test
    fun `在看列表的标题中文名优先，空白时用原名`() {
        val candidates = HomeSectionCandidates(
            toWatch = listOf(toWatch(1, nameCn = "葬送的芙莉莲"), toWatch(2, nameCn = " ")),
            scheduleGroups = emptyList(),
            continueItems = emptyList(),
            doingItems = emptyList(),
        )

        assertEquals(listOf("葬送的芙莉莲", "Subject 2"), widgetWorksOf(candidates).map { it.title })
    }

    @Test
    fun `单部作品画选的那部，带上它写失败的标记`() {
        val works = WidgetWorks.Ready(listOf(work(1), work(2)), TODAY)

        assertEquals(SubjectContent.Work(work(2), failed = true, cover = null), subjectContentOf(works, 2, failed = setOf(2), cover = null))
        assertEquals(SubjectContent.Work(work(1), failed = false, cover = null), subjectContentOf(works, 1, failed = setOf(2), cover = null))
    }

    @Test
    fun `单部作品没选或选的那部不在在看里时是要重新选`() {
        val works = WidgetWorks.Ready(listOf(work(1)), TODAY)

        assertEquals(SubjectContent.Missing, subjectContentOf(works, null, emptySet(), null))
        assertEquals(SubjectContent.Missing, subjectContentOf(works, 9, emptySet(), null))
    }

    @Test
    fun `单部作品没登录与读不出来各有各的样子`() {
        assertEquals(SubjectContent.SignedOut, subjectContentOf(WidgetWorks.SignedOut, 1, emptySet(), null))
        assertEquals(SubjectContent.LoadFailed, subjectContentOf(WidgetWorks.Failed(Failure.Storage), 1, emptySet(), null))
    }

    @Test
    fun `热力图按年轮分档，跨年的月份从上一年的年轮里取，今天以后的日子还没到`() {
        val rings = listOf(
            ring(2025, LocalDate.of(2025, 12, 31) to 9),
            ring(2026, LocalDate.of(2026, 1, 1) to 1, LocalDate.of(2026, 1, 3) to 4),
        )

        val heat = heatOf(rings, today = LocalDate.of(2026, 1, 3), count = 2)

        assertEquals(listOf(YearMonth.of(2025, 12), YearMonth.of(2026, 1)), heat.months.map { it.month })
        assertEquals(4, heat.months[0].levels[30])
        assertEquals(listOf(1, 0, 2, null), heat.months[1].levels.take(4))
        assertEquals(31, heat.months[1].levels.size)
        assertEquals(5, heat.monthEpisodes)
    }

    @Test
    fun `热力图没看过的日子是零档，这个月一集没看是零集`() {
        val heat = heatOf(emptyList(), today = LocalDate.of(2026, 2, 28), count = 1)

        assertEquals(List(28) { 0 }, heat.months.single().levels)
        assertEquals(0, heat.monthEpisodes)
    }

    @Test
    fun `在看列表只画放得下的整行，4×2 是标题加两行`() {
        val layout = listLayoutOf(166.dp, count = 77)

        assertEquals(true, layout.header)
        assertEquals(2, layout.rows)
        assertTrue(layout.rowHeight * layout.rows <= 166.dp - 24.dp - HeaderHeight)
    }

    @Test
    fun `在看列表拉高多画几行，封面跟着变大`() {
        val short = listLayoutOf(166.dp, count = 77)
        val tall = listLayoutOf(250.dp, count = 77)

        assertEquals(3, tall.rows)
        assertTrue(tall.coverHeight > short.coverHeight)
    }

    @Test
    fun `在看列表矮到放不下标题时只画一行`() {
        val layout = listLayoutOf(57.dp, count = 77)

        assertEquals(false, layout.header)
        assertEquals(1, layout.rows)
        assertTrue(layout.rowHeight >= WorkRowHeight)
        assertTrue(layout.verticalPadding * 2 + layout.rowHeight <= 57.dp)
    }

    @Test
    fun `在看列表画得下标题时框照常留空`() {
        assertEquals(12.dp, listLayoutOf(166.dp, count = 77).verticalPadding)
    }

    @Test
    fun `只画一行的框矮时把上下的空让给这一行，够高时照常留空`() {
        assertEquals(0.5.dp, rowFramePaddingOf(57.dp, 8.dp))
        assertEquals(0.dp, rowFramePaddingOf(40.dp, 8.dp))
        assertEquals(8.dp, rowFramePaddingOf(107.dp, 8.dp))
    }

    @Test
    fun `在看列表作品比放得下的少时只画那几行，行高不变`() {
        assertEquals(listLayoutOf(340.dp, count = 77).rowHeight, listLayoutOf(340.dp, count = 1).rowHeight)
        assertEquals(1, listLayoutOf(340.dp, count = 1).rows)
    }

    @Test
    fun `单部作品矮的是一行，宽的封面在左，2×2 是海报`() {
        assertEquals(SubjectLayout.ROW, subjectLayoutOf(DpSize(300.dp, 75.dp)))
        assertEquals(SubjectLayout.SIDE, subjectLayoutOf(DpSize(300.dp, 166.dp)))
        assertEquals(SubjectLayout.POSTER, subjectLayoutOf(DpSize(144.dp, 167.dp)))
    }

    @Test
    fun `单部作品在一格很高的桌面上 4×1 与 2×1 仍是一行`() {
        // Pixel 桌面实测：4×1 406×107dp，2×1 195×107dp，4×2 406×226dp，2×2 195×226dp
        assertEquals(SubjectLayout.ROW, subjectLayoutOf(DpSize(406.dp, 107.dp)))
        assertEquals(SubjectLayout.ROW, subjectLayoutOf(DpSize(195.dp, 107.dp)))
        assertEquals(SubjectLayout.SIDE, subjectLayoutOf(DpSize(406.dp, 226.dp)))
        assertEquals(SubjectLayout.POSTER, subjectLayoutOf(DpSize(195.dp, 226.dp)))
    }

    @Test
    fun `今日放送在追的排前面带上进度，其余按时刻接在后面，没有时刻的在最后`() {
        val day = calendarDaySections(
            weekday = 3,
            items = listOf(item(1, watchers = 50), item(2, watchers = 10), item(3, watchers = 90), item(4, watchers = 70)),
            followedSubjectIds = setOf(2, 4),
            broadcastStarts = mapOf(1 to wednesdayAt(22, 0), 2 to wednesdayAt(23, 30), 3 to wednesdayAt(21, 0), 4 to wednesdayAt(25, 0)),
        )

        val rows = todayRowsOf(day, works = mapOf(2 to work(2)))

        assertEquals(listOf(2, 4, 3, 1), rows.map { it.entry.subject.id })
        assertEquals(listOf(2, null, null, null), rows.map { it.work?.subjectId })
    }

    @Test
    fun `今日放送没有在追的就是当天全部，没时刻的排在有时刻的后面`() {
        val day = calendarDaySections(3, listOf(item(1, 5), item(2, 9), item(3, 1)), emptySet(), mapOf(3 to wednesdayAt(20, 0)))

        assertEquals(listOf(3, 2, 1), todayRowsOf(day, emptyMap()).map { it.entry.subject.id })
    }

    @Test
    fun `本周追番周一起七列，只放在追的，部数不重复数`() {
        val wednesday = calendarDaySections(3, listOf(item(1, 5), item(2, 9)), setOf(1), emptyMap())
        val friday = calendarDaySections(5, listOf(item(1, 5)), setOf(1), emptyMap())
        val calendar = WidgetCalendar.Ready(mapOf(3 to wednesday, 5 to friday), date = LocalDate.of(2026, 11, 11), session = SESSION, works = emptyMap(), followed = emptyList())

        val content = weekContentOf(calendar) as WeekContent.Ready

        assertEquals((1..7).toList(), content.days.map { it.weekday })
        assertEquals(listOf(3), content.days.filter { it.today }.map { it.weekday })
        assertEquals(listOf(1), content.days[2].entries.map { it.subject.id })
        assertEquals(1, content.count)
    }

    @Test
    fun `本周追番没登录是登录按钮，读不出来另有样子`() {
        assertEquals(WeekContent.SignedOut, weekContentOf(WidgetCalendar.Ready(emptyMap(), LocalDate.of(2026, 11, 9), session = AccountSession(0, null, null, false), works = emptyMap(), followed = emptyList())))
        assertEquals(WeekContent.LoadFailed, weekContentOf(WidgetCalendar.Failed))
    }

    @Test
    fun `本周追番 4×2 每列两格，拉高或换到一格更高的桌面多放几格`() {
        assertEquals(2, weekLayoutOf(DpSize(300.dp, 166.dp)).slots)
        assertEquals(4, weekLayoutOf(DpSize(300.dp, 250.dp)).slots)
        // Pixel 桌面实测 4×2：406×226dp
        assertEquals(3, weekLayoutOf(DpSize(406.dp, 226.dp)).slots)
        assertTrue(weekLayoutOf(DpSize(406.dp, 226.dp)).coverHeight <= 40.dp)
    }

    @Test
    fun `本周追番一列放得下就全画，放不下时最后一格写剩下几部`() {
        assertEquals(2 to 0, weekColumnOf(count = 2, slots = 2))
        assertEquals(1 to 2, weekColumnOf(count = 3, slots = 2))
        assertEquals(0 to 0, weekColumnOf(count = 0, slots = 2))
        assertEquals(0 to 3, weekColumnOf(count = 3, slots = 0))
    }

    private fun item(id: Int, watchers: Int) = CalendarItemBo(subject(id), watchers)

    /** 2026-11-04 周三北京时间 [hour]:[minute] 的每周首播（[hour] 写到 27，即次日凌晨仍算周三）。 */
    private fun wednesdayAt(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 11, 4, 0, 0, 0, 0, ZoneOffset.ofHours(8)).plusHours(hour.toLong()).plusMinutes(minute.toLong())
            .toInstant().toEpochMilli()

    private fun subject(id: Int, nameCn: String = "") =
        SubjectBo(id = id, type = SubjectType.ANIME, name = "Subject $id", nameCn = nameCn)

    private fun card(id: Int, nameCn: String = "") = HomeProgressCard(
        subject = subject(id, nameCn),
        epButtonState = EpButtonState.Empty,
        progressSubject = ProgressSubject(subjectId = id, subjectType = SubjectType.ANIME, episodes = emptyList()),
        progressCount = ProgressCount.Loading,
    )

    private fun toWatch(id: Int, nameCn: String = "") = HomeToWatchItem(card(id, nameCn), airedUnwatchedCount = 1)

    private fun continueItem(id: Int) =
        HomeContinueItem(card(id), UserCollectionBo(subjectId = id, subjectType = SubjectType.ANIME, type = CollectionType.DOING))

    private fun work(id: Int) = widgetWorksOf(
        HomeSectionCandidates(listOf(toWatch(id)), emptyList(), emptyList(), emptyList()),
    ).single()

    private fun ring(year: Int, vararg days: Pair<LocalDate, Int>) = YearRing(
        year = year,
        days = days.associate { (date, episodes) -> date to WatchDay(episodes, emptyList()) },
        episodes = days.sumOf { it.second },
        activeDays = days.size,
        longestStreak = 1,
        busiestDay = days.maxBy { it.second }.first,
    )

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 11, 4)
    }
}
