package io.github.touhougo.chogumi.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * 系统通知的两类提醒（设置 › 通知）。bgm.tv 没有推送，两类都在本机算：
 * - 社区提醒：后台按 [interval] 去问一次未读提醒（`/p1/notify`）与未读短信，新来的发成系统通知；
 * - 放送提醒：在追作品今天要播，按 [airingMode] 在开播时刻各发一条，或每天 [dailyMinute] 发一条汇总。数据都在本机。
 * 两个开关默认关；要发通知还得系统给了通知权限。
 */
data class NotificationSettings(
    val community: Boolean = false,
    val interval: CommunityCheckInterval = CommunityCheckInterval.DEFAULT,
    val airing: Boolean = false,
    val airingMode: AiringReminderMode = AiringReminderMode.AT_AIRING,
    /** 每天定时汇总的时刻：设备本地时间从 0 点起的分钟数（0..1439）。 */
    val dailyMinute: Int = DEFAULT_DAILY_MINUTE,
) {
    companion object {
        const val DEFAULT_DAILY_MINUTE = 9 * 60

        /** 一天的分钟数：[dailyMinute] 在 0 到它之前。 */
        const val MINUTES_PER_DAY = 24 * 60

        /** 存下的汇总时刻读成 [dailyMinute]：没有或不在一天之内（存坏了）读成默认。 */
        fun dailyMinuteOf(stored: Int?): Int = stored?.takeIf { it in 0 until MINUTES_PER_DAY } ?: DEFAULT_DAILY_MINUTE
    }
}

/** 社区提醒多久问一次服务端。15 分钟是系统给周期任务的最短间隔；系统省电时还会往后推。 */
enum class CommunityCheckInterval(val minutes: Long) {
    MINUTES_15(15),
    MINUTES_30(30),
    HOUR_1(60),
    HOURS_3(180),
    ;

    companion object {
        val DEFAULT = MINUTES_15
    }
}

/** 放送提醒怎么发：每部在开播时刻一条，或每天在用户选的时刻一条汇总。 */
enum class AiringReminderMode { AT_AIRING, DAILY }

/** 一条系统通知属于哪一类：每类是系统里的一个通知类别（Android 8 起用户可以单独关、换提示音）。 */
enum class NoticeChannel { REPLY, MENTION, FRIEND, MESSAGE, OTHER, AIRING }

/** 一种站内提醒归哪一类通知（全仓唯一的提醒 → 类别判定）。 */
fun noticeChannelOf(kind: NoticeKind): NoticeChannel = when (kind) {
    NoticeKind.GROUP_TOPIC_REPLY, NoticeKind.GROUP_POST_REPLY,
    NoticeKind.SUBJECT_TOPIC_REPLY, NoticeKind.SUBJECT_POST_REPLY,
    NoticeKind.CHARACTER_TOPIC_REPLY, NoticeKind.CHARACTER_POST_REPLY,
    NoticeKind.BLOG_TOPIC_REPLY, NoticeKind.BLOG_POST_REPLY,
    NoticeKind.EPISODE_TOPIC_REPLY, NoticeKind.EPISODE_POST_REPLY,
    NoticeKind.INDEX_COMMENT, NoticeKind.INDEX_REPLY, NoticeKind.PERSON_REPLY,
    NoticeKind.DOUJIN_CLUB_TOPIC_REPLY, NoticeKind.DOUJIN_CLUB_POST_REPLY, NoticeKind.DOUJIN_SUBJECT_REPLY,
    NoticeKind.DOUJIN_EVENT_TOPIC_REPLY, NoticeKind.DOUJIN_EVENT_POST_REPLY,
    NoticeKind.TIMELINE_REPLY,
    -> NoticeChannel.REPLY

    NoticeKind.GROUP_MENTION, NoticeKind.SUBJECT_MENTION, NoticeKind.CHARACTER_MENTION, NoticeKind.PERSON_MENTION,
    NoticeKind.INDEX_MENTION, NoticeKind.TIMELINE_MENTION, NoticeKind.BLOG_MENTION, NoticeKind.EPISODE_MENTION,
    NoticeKind.DOUJIN_CLUB_SHOUTBOX_MENTION, NoticeKind.DOUJIN_CLUB_TOPIC_MENTION, NoticeKind.DOUJIN_SUBJECT_MENTION,
    NoticeKind.DOUJIN_EVENT_TOPIC_MENTION,
    -> NoticeChannel.MENTION

    NoticeKind.REQUEST_FRIEND, NoticeKind.ACCEPT_FRIEND -> NoticeChannel.FRIEND

    NoticeKind.UNKNOWN,
    NoticeKind.SUBJECT_PATCH_ACCEPTED, NoticeKind.EPISODE_PATCH_ACCEPTED,
    NoticeKind.SUBJECT_PATCH_REJECTED, NoticeKind.EPISODE_PATCH_REJECTED,
    NoticeKind.SUBJECT_PATCH_EXPIRED, NoticeKind.EPISODE_PATCH_EXPIRED,
    NoticeKind.CHARACTER_PATCH_ACCEPTED, NoticeKind.PERSON_PATCH_ACCEPTED,
    NoticeKind.CHARACTER_PATCH_REJECTED, NoticeKind.PERSON_PATCH_REJECTED,
    NoticeKind.CHARACTER_PATCH_EXPIRED, NoticeKind.PERSON_PATCH_EXPIRED,
    NoticeKind.SUBJECT_PATCH_REPLY, NoticeKind.EPISODE_PATCH_REPLY,
    NoticeKind.CHARACTER_PATCH_REPLY, NoticeKind.PERSON_PATCH_REPLY,
    -> NoticeChannel.OTHER
}

/**
 * 社区提醒的一轮检查得出的事：[post] 是要新发的提醒，[cancel] 是已发、现在不再未读（在 App 或网页上读过）要撤掉的，
 * [baseline] 是下一轮的基线（发过与跳过的最大提醒 id）。
 */
data class NoticeRound(val post: List<NoticeBo>, val cancel: Set<Int>, val baseline: Int)

/**
 * 社区提醒一轮的决定（单一真源纯函数）：[unread] 是服务端此刻的未读提醒，[baseline] 是上一轮记下的基线（这个账号还没有基线时为 null），
 * [shown] 是通知栏里还挂着的提醒 id。
 * - 没有基线（刚打开开关、换了号）：什么都不发，基线记成现有未读里最大的 id：打开那一刻已有的未读不推，免得一下弹一串；
 * - 有基线：id 比基线大、还没挂着的发出去；提醒 id 由服务端递增分配，比基线小的都处理过了；
 * - 挂着的、已经不在未读里的撤掉。
 * [suppress] 为 true（App 在前台，角标已经在）时只推进基线、不发。
 */
fun noticeRoundOf(unread: List<NoticeBo>, baseline: Int?, shown: Set<Int>, suppress: Boolean): NoticeRound {
    val newest = maxOf(baseline ?: 0, unread.maxOfOrNull { it.id } ?: 0)
    val unreadIds = unread.mapTo(HashSet()) { it.id }
    val cancel = shown - unreadIds
    if (baseline == null || suppress) return NoticeRound(post = emptyList(), cancel = cancel, baseline = newest)
    val post = unread.filter { it.id > baseline && it.id !in shown }.sortedBy { it.id }
    return NoticeRound(post = post, cancel = cancel, baseline = newest)
}

/** 未读短信这一轮怎么处理：发（或改成新的条数）、撤掉、不动。 */
sealed interface MessageRound {
    data class Post(val count: Int) : MessageRound

    data object Cancel : MessageRound

    data object Keep : MessageRound
}

/**
 * 未读短信一轮的决定：服务端只给未读条数 [count]，[notified] 是上一轮记下的条数（这个账号还没有记录时为 null）。
 * 没有记录时只记下、不发；条数比记下的多才发（改成新的条数）；归零撤掉；其余不动。
 * 记下的条数总是更新成 [count]（[suppress] 为 true 时 App 在前台，同样只记不发）。
 */
fun messageRoundOf(count: Int, notified: Int?, suppress: Boolean): MessageRound = when {
    count == 0 -> MessageRound.Cancel
    notified == null || suppress -> MessageRound.Keep
    count > notified -> MessageRound.Post(count)
    else -> MessageRound.Keep
}

/**
 * 放送提醒的一条：今天播的一部在追作品。[episode] 是今天播的那一集的集号（本机还没有它的章节时为 null），
 * [airing] 是开播时刻（时刻表没有这部时为 null，白天发一条「今天更新」）。
 */
data class AiringReminder(
    val subject: SubjectBo,
    val episode: EpisodeBo?,
    val airing: AiringMinute?,
)

/**
 * 今天（放送日 [today]）的放送提醒：[day] 是每日放送那一天的分组（[calendarWeek] 的今天），只取其中我在追的（[CalendarDaySections.following]，
 * 已按时刻排好）；集号从 [followed] 里这部作品今天播的那一集取（[EpisodeTracking.nextUpcomingEpisode]，播出日对得上才算）。
 * 深夜番（24 点以后）在 bgm.tv 上的播出日有的写放送日、有的写日历上的第二天，两天都算。
 */
fun airingRemindersOf(day: CalendarDaySections, followed: List<FollowedWork>, today: LocalDate): List<AiringReminder> {
    val episodes = followed.associate { it.subject.id to it.episodes }
    return day.following.map { entry ->
        val lateNight = entry.airing != null && entry.airing.minutes >= NotificationSettings.MINUTES_PER_DAY
        val episode = episodes[entry.subject.id]
            ?.let { EpisodeTracking.nextUpcomingEpisode(it, today) }
            ?.takeIf {
                val airdate = EpisodeTracking.airdateOf(it)
                airdate == today || lateNight && airdate == today.plusDays(1)
            }
        AiringReminder(entry.subject, episode, entry.airing)
    }
}

/** 没有时刻的放送提醒在放送日里几点发：北京时间 12 点。设计决策（not in M3）：白天、午饭时。 */
val UNTIMED_AIRING_REMINDER = AiringMinute(12 * 60)

/** 北京时间：[AiringMinute] 按它写。 */
private val BEIJING: ZoneOffset = ZoneOffset.ofHours(8)

/** 放送日 [date] 里的时刻 [minute] 是哪一刻（epoch 毫秒）：[AiringMinute] 从放送日那天的北京时间 0 点起算，深夜的写成 24 点以后。 */
fun airingMillisOf(date: LocalDate, minute: AiringMinute): Long =
    date.atStartOfDay().atOffset(BEIJING).plusMinutes(minute.minutes.toLong()).toInstant().toEpochMilli()

/** 一条放送提醒在放送日 [date] 里的发出时刻：开播时刻，没有时刻的按 [UNTIMED_AIRING_REMINDER]。 */
fun airingReminderMillisOf(reminder: AiringReminder, date: LocalDate): Long =
    airingMillisOf(date, reminder.airing ?: UNTIMED_AIRING_REMINDER)

/** 放送提醒最多晚多久还补发：过了发出时刻超过它（关着机、刚打开开关）就不发了。设计决策（not in M3）。 */
const val AIRING_REMINDER_GRACE_MILLIS = 60 * 60 * 1000L

/** 开播时提醒的这一刻：[due] 现在该发的（按发出时刻排），[next] 下一条的发出时刻（今天没有了为 null）。 */
data class AiringSchedule(val due: List<AiringReminder>, val next: Long?)

/**
 * 开播时提醒在 [nowMillis] 这一刻怎么做（放送日 [date] 的 [reminders]，[posted] 是今天已经发过的作品 id）：
 * 发出时刻（[airingReminderMillisOf]）到了、晚得不超过 [AIRING_REMINDER_GRACE_MILLIS] 的现在发；还没到的里面最早的那一刻是下一次。
 */
fun airingScheduleOf(reminders: List<AiringReminder>, date: LocalDate, posted: Set<Int>, nowMillis: Long): AiringSchedule {
    val pending = reminders.filter { it.subject.id !in posted }
        .map { it to airingReminderMillisOf(it, date) }
        .filter { (_, at) -> at >= nowMillis - AIRING_REMINDER_GRACE_MILLIS }
        .sortedBy { (_, at) -> at }
    return AiringSchedule(
        due = pending.filter { (_, at) -> at <= nowMillis }.map { (reminder, _) -> reminder },
        next = pending.firstOrNull { (_, at) -> at > nowMillis }?.second,
    )
}

/**
 * 每天定时汇总的上一次时刻：不晚于 [nowMillis] 的最后一次（[nextDailyMillisOf] 的反方向）。按日历的日子往回退，不减 24 小时：
 * 夏令时切换的那天不是 24 小时。
 */
fun lastDailyMillisOf(nowMillis: Long, minuteOfDay: Int, zone: ZoneId): Long {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val today = dailyAt(now.toLocalDate(), minuteOfDay, zone)
    return (if (today.isAfter(now)) dailyAt(now.toLocalDate().minusDays(1), minuteOfDay, zone) else today).toInstant().toEpochMilli()
}

/**
 * 每天定时汇总的下一次时刻（epoch 毫秒）：[minuteOfDay] 是设备时区 [zone] 里从 0 点起的分钟数；严格晚于 [nowMillis] 的第一次。
 * 时区按设备的：用户选的是自己钟上的几点。
 */
fun nextDailyMillisOf(nowMillis: Long, minuteOfDay: Int, zone: ZoneId): Long {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val today = dailyAt(now.toLocalDate(), minuteOfDay, zone)
    return (if (today.isAfter(now)) today else dailyAt(now.toLocalDate().plusDays(1), minuteOfDay, zone)).toInstant().toEpochMilli()
}

/** 设备时区 [zone] 里 [date] 那天的 [minuteOfDay]（夏令时跳过的那段按系统的规则往后挪）。 */
private fun dailyAt(date: LocalDate, minuteOfDay: Int, zone: ZoneId): ZonedDateTime =
    date.atTime(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)).atZone(zone)
