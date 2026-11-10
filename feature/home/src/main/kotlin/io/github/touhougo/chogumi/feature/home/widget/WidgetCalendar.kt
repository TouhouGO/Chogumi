package io.github.touhougo.chogumi.feature.home.widget

import io.github.touhougo.chogumi.core.data.repository.AiringTimeRepository
import io.github.touhougo.chogumi.core.data.repository.AuthRepository
import io.github.touhougo.chogumi.core.data.repository.CalendarRepository
import io.github.touhougo.chogumi.core.data.repository.CollectionObserver
import io.github.touhougo.chogumi.core.data.repository.CollectionProgressUpdate
import io.github.touhougo.chogumi.core.data.repository.HomeRepository
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.settled
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CalendarDaySections
import io.github.touhougo.chogumi.core.model.CalendarEntry
import io.github.touhougo.chogumi.core.model.CalendarItemBo
import io.github.touhougo.chogumi.core.model.FollowedWork
import io.github.touhougo.chogumi.core.model.airingDateOf
import io.github.touhougo.chogumi.core.model.calendarWeek
import io.github.touhougo.chogumi.core.model.followedWorksOf
import java.time.LocalDate
import kotlinx.coroutines.flow.mapNotNull

/** 放送小组件（今日放送、本周追番）这一次读到的东西。 */
internal sealed interface WidgetCalendar {
    /** 本机会话读不出来，或放送表一份都拿不到（没有缓存、网络也不通）。 */
    data object Failed : WidgetCalendar

    /**
     * [week] 是每日放送页同一份整周（[calendarWeek]：七个星期各是从今天起七天里的那一天，在追的放在它真正播的那天），
     * [date] 是读的那一刻的放送日（北京时间 4 点翻页），[today] 是它的星期；没登录（[signedIn] 为 false）时没有「我在追」。
     * [works] 是在看小组件同一份作品（按 id）：在追的那几行从它取进度与 +1。[followed] 是拼出整周的在追作品与它们本机已有的章节
     * （放送提醒从里面取今天播的那一集）。
     */
    data class Ready(
        val week: Map<Int, CalendarDaySections>,
        val date: LocalDate,
        val session: AccountSession,
        val works: Map<Int, WidgetWork>,
        val followed: List<FollowedWork>,
    ) : WidgetCalendar {
        val today: Int get() = date.dayOfWeek.value
        val signedIn: Boolean get() = session.authenticated
    }
}

/** 今日放送的一行：放送表里的一部（[entry]）；在追、且在在看列表里时带上它的进度与按钮（[work]）。 */
internal data class TodayRow(val entry: CalendarEntry, val work: WidgetWork?)

/**
 * 今日放送的行：今天播的我在追的排前面（[CalendarDaySections.following] 的顺序：按时刻），其余今天播的按时刻接在后面，
 * 没有时刻的在最后（按在看人数）——与每日放送页同一个分法。
 */
internal fun todayRowsOf(day: CalendarDaySections, works: Map<Int, WidgetWork>): List<TodayRow> {
    val following = day.following.mapTo(HashSet()) { it.subject.id }
    val rest = (day.slots.flatMap { it.entries } + day.unknown).filter { it.subject.id !in following }
    return day.following.map { TodayRow(it, works[it.subject.id]) } + rest.map { TodayRow(it, null) }
}

/** 本周追番的一列：星期 [weekday]（1=周一），是不是今天，这天播的我在追的作品（按时刻）。 */
internal data class WeekDay(val weekday: Int, val today: Boolean, val entries: List<CalendarEntry>)

/** 本周追番的七列，周一起（与每日放送页的星期选择器一样）。 */
internal fun weekDaysOf(week: Map<Int, CalendarDaySections>, today: Int): List<WeekDay> =
    (1..7).map { weekday -> WeekDay(weekday, weekday == today, week[weekday]?.following.orEmpty()) }

/**
 * 放送小组件读的数据，与每日放送页同一套：放送表（缓存优先，没有缓存才等网络）、放送时刻表、本机收藏与在看作品已有的章节，
 * 经 [followedWorksOf] 与 [calendarWeek] 拼成整周。时刻表读不出来时作品都没有时刻，与页面一样不说。
 *
 * 「现在」在每次读的时候按 [clock] 取：小组件常在 App 不在前台时刷新，那时 `AppTime` 不走。
 */
internal class WidgetCalendarSource(
    private val calendarRepository: CalendarRepository,
    private val airingTimeRepository: AiringTimeRepository,
    private val collectionObserver: CollectionObserver,
    private val homeRepository: HomeRepository,
    private val authRepository: AuthRepository,
    private val worksSource: WidgetWorksSource,
    private val clock: AppClock,
) {
    suspend fun load(): WidgetCalendar {
        val result = outcomeOf {
            val session = when (val captured = authRepository.currentSession()) {
                is Outcome.Failed -> return@outcomeOf WidgetCalendar.Failed
                is Outcome.Success -> captured.value
            }
            val signedIn = session.authenticated
            val calendar = when (val read = calendarRepository.getCalendar().settled()) {
                is Outcome.Success -> read.value
                is Outcome.Failed -> return@outcomeOf WidgetCalendar.Failed
            }
            val starts = airingTimeRepository.observeBroadcastStarts().settled().valueOrNull().orEmpty()
            val followed = if (signedIn) followedOf(calendar) ?: return@outcomeOf WidgetCalendar.Failed else emptyList()
            val works = if (signedIn) (worksSource.load(session) as? WidgetWorks.Ready)?.works.orEmpty() else emptyList()
            val date = airingDateOf(clock.nowMillis())
            val ready = WidgetCalendar.Ready(
                week = calendarWeek(calendar, followed, date, starts),
                date = date,
                session = session,
                works = works.associateBy { it.subjectId },
                followed = followed,
            )
            authRepository.withSession(session) { ready }
        }
        return (result as? Outcome.Success)?.value ?: WidgetCalendar.Failed
    }

    /** 在追的作品；本机收藏或章节读不出来是 null。 */
    private suspend fun followedOf(calendar: Map<Int, List<CalendarItemBo>>): List<FollowedWork>? {
        val snapshot = collectionObserver.observeProgress().mapNotNull { (it as? CollectionProgressUpdate.Value)?.load }.settled()
            .valueOrNull() ?: return null
        val episodes = homeRepository.observeCachedEpisodes().settled().valueOrNull() ?: return null
        return followedWorksOf(snapshot.collections, calendar, episodes)
    }
}
