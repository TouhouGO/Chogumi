package io.github.touhougo.chogumi.core.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 放送表里的一部作品：条目与在看人数（[item]），和它在放送日里的时刻（[airing]；bangumi-data 没有、或星期对不上是 null）。 */
data class CalendarEntry(val item: CalendarItemBo, val airing: AiringMinute?) {
    val subject: SubjectBo get() = item.subject
    val watchers: Int get() = item.watchers
}

/** 同一时刻播出的一组作品，组内按在看人数降序。 */
data class AiringSlot(val minute: AiringMinute, val entries: List<CalendarEntry>)

/**
 * 每日放送一天的内容：
 * - [following]：当前用户在追的，按时刻升序，没有时刻的排最后，同一时刻按在看人数降序；
 * - [slots]：有时刻的全部作品按时刻分组、升序；
 * - [unknown]：没有时刻的全部作品，按在看人数降序。
 * [slots] 与 [unknown] 合起来是这一天的全量（在追的也在里面，包括 bgm.tv 放送表里没有的，见 [calendarWeek]）；
 * 在追与否不另作标记，封面右上角统一显示「我」的收藏状态。
 */
data class CalendarDaySections(
    val following: List<CalendarEntry>,
    val slots: List<AiringSlot>,
    val unknown: List<CalendarEntry>,
) {
    val isEmpty: Boolean get() = slots.isEmpty() && unknown.isEmpty()

    companion object {
        val EMPTY = CalendarDaySections(following = emptyList(), slots = emptyList(), unknown = emptyList())
    }
}

/**
 * 每日放送一天的分组与排序（单一真源纯函数）：[weekday] 是这一天在 bgm.tv 放送表里的星期，[broadcastStarts] 是
 * bangumi-data 给的每周首播时刻（条目 id → epoch 毫秒），时刻由 [airingMinuteOf] 判，星期对不上就当没有。
 * 决胜：在看人数相同按条目 id 升序，列表确定稳定。
 */
fun calendarDaySections(
    weekday: Int,
    items: List<CalendarItemBo>,
    followedSubjectIds: Set<Int>,
    broadcastStarts: Map<Int, Long>,
): CalendarDaySections {
    if (items.isEmpty()) return CalendarDaySections.EMPTY
    val byWatchers = compareByDescending<CalendarEntry> { it.watchers }.thenBy { it.subject.id }
    val entries = items.map { item ->
        CalendarEntry(item, broadcastStarts[item.subject.id]?.let { start -> airingMinuteOf(weekday, start) })
    }.sortedWith(byWatchers)
    val (timed, unknown) = entries.partition { it.airing != null }
    val slots = timed.groupBy { it.airing!! }.entries
        .sortedBy { it.key }
        .map { (minute, group) -> AiringSlot(minute, group) }
    val following = entries.filter { it.subject.id in followedSubjectIds }
        .sortedWith(compareBy<CalendarEntry, AiringMinute?>(nullsLast()) { it.airing }.then(byWatchers))
    return CalendarDaySections(following = following, slots = slots, unknown = unknown)
}

/** 一部在追作品：条目，和本机已有的章节（null = 本机还没有它的章节）。 */
data class FollowedWork(val subject: SubjectBo, val episodes: List<EpisodeBo>?)

/**
 * 当前用户在追的作品（收藏里「在看」的那些）：条目优先取收藏行带的，没有时取放送表 [calendar] 里的同一部，两边都没有的不算
 * （还不知道它是什么）；章节取本机已有的 [airingEpisodes]（与首页同一份）。每日放送页与放送小组件都从这里拼「我在追」。
 */
fun followedWorksOf(
    collections: List<UserCollectionBo>,
    calendar: Map<Int, List<CalendarItemBo>>,
    airingEpisodes: Map<Int, List<EpisodeBo>>,
): List<FollowedWork> {
    val calendarSubjects = calendar.values.asSequence().flatten().map { it.subject }.associateBy { it.id }
    return collections.filter { it.type == CollectionType.DOING }.mapNotNull { collection ->
        val subject = collection.subject ?: calendarSubjects[collection.subjectId] ?: return@mapNotNull null
        FollowedWork(subject, airingEpisodes[collection.subjectId])
    }
}

/**
 * 一部在追作品下一次在哪天播：「哪天播」的单一真源，首页追更时间表与每日放送的「我在追」都按它。
 * - 有章节（[episodes] 不为 null）：下一集未播正片的播出日（[EpisodeTracking.nextUpcomingAirdate]，播出日 ≥ [today]）；
 *   正片都播过了、或章节里没有播出日，就是 null（这几天不播）。首页的作品都带齐了章节，只走这一条。
 * - 本机还没有章节：bgm.tv 放送表给的星期 [calendarWeekday]，从 [today] 起第一个这一天；放送表里也没有是 null。
 * 章节的播出日与放送表的星期按同一套放送日归（docs/dev/guide.md §1），两条可以直接换用。
 */
fun followedAiringDateOf(episodes: List<EpisodeBo>?, calendarWeekday: Int?, today: LocalDate): LocalDate? =
    if (episodes != null) {
        EpisodeTracking.nextUpcomingAirdate(episodes, today)
    } else {
        calendarWeekday?.let { today.plusDays(((it - today.dayOfWeek.value + 7) % 7).toLong()) }
    }

/**
 * 每日放送一整周（单一真源纯函数）：[calendar] 是 bgm.tv 放送表（星期 → 作品），[followed] 是当前用户在追的作品，
 * [today] 是放送日的今天（[airingDateOf]）。七个星期各是从今天起七天里的那一天。
 *
 * 在追的作品放在 [followedAiringDateOf] 判出的那一天：不管放送表把它放在哪天、有没有收它（国产动画、部分新番不在表里），
 * 这七天里不播的哪天都不出现；它在放送表里时沿用表里的条目与在看人数，不在时在看人数记 0。其余作品照放送表。
 * 每天再经 [calendarDaySections] 分组排序，七天都有一份（没有作品的是 [CalendarDaySections.EMPTY]）。
 */
fun calendarWeek(
    calendar: Map<Int, List<CalendarItemBo>>,
    followed: List<FollowedWork>,
    today: LocalDate,
    broadcastStarts: Map<Int, Long>,
): Map<Int, CalendarDaySections> {
    val listed = HashMap<Int, Pair<Int, CalendarItemBo>>()
    calendar.entries.sortedBy { it.key }.forEach { (weekday, items) ->
        items.forEach { item -> listed.putIfAbsent(item.subject.id, weekday to item) }
    }
    val followedIds = followed.mapTo(HashSet()) { it.subject.id }
    val placed = followed.mapNotNull { work ->
        val inCalendar = listed[work.subject.id]
        val date = followedAiringDateOf(work.episodes, inCalendar?.first, today) ?: return@mapNotNull null
        if (ChronoUnit.DAYS.between(today, date) >= 7) return@mapNotNull null
        date.dayOfWeek.value to (inCalendar?.second ?: CalendarItemBo(work.subject, watchers = 0))
    }.groupBy({ it.first }, { it.second })
    return (1..7).associateWith { weekday ->
        val items = calendar[weekday].orEmpty().filter { it.subject.id !in followedIds } + placed[weekday].orEmpty()
        calendarDaySections(weekday, items, followedIds, broadcastStarts)
    }
}

/** 「现在」线画在哪一组之前：第一个还没到的时刻的下标；都已开播就是 [List.size]（画在最后一组之后）。 */
fun List<AiringSlot>.nowLineIndex(now: AiringMinute): Int =
    indexOfFirst { it.minute > now }.let { if (it < 0) size else it }
