package io.github.touhougo.chogumi.feature.home.notice

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.touhougo.chogumi.core.data.repository.AiringTimeRepository
import io.github.touhougo.chogumi.core.data.repository.AuthRepository
import io.github.touhougo.chogumi.core.data.repository.CalendarRepository
import io.github.touhougo.chogumi.core.data.repository.HomeRepository
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.SystemNoticeLedger
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.displayZone
import io.github.touhougo.chogumi.core.model.AIRING_REMINDER_GRACE_MILLIS
import io.github.touhougo.chogumi.core.model.AiringReminder
import io.github.touhougo.chogumi.core.model.AiringReminderMode
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.CalendarDaySections
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.NoticeChannel
import io.github.touhougo.chogumi.core.model.NotificationSettings
import io.github.touhougo.chogumi.core.model.SessionState
import io.github.touhougo.chogumi.core.model.airingDateOf
import io.github.touhougo.chogumi.core.model.airingRemindersOf
import io.github.touhougo.chogumi.core.model.airingScheduleOf
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.model.lastDailyMillisOf
import io.github.touhougo.chogumi.core.model.nextDailyMillisOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.SystemNotices
import io.github.touhougo.chogumi.feature.home.widget.WidgetCalendar
import io.github.touhougo.chogumi.feature.home.widget.WidgetCalendarSource
import io.github.touhougo.chogumi.feature.home.widget.WidgetProgressActions
import io.github.touhougo.chogumi.feature.home.widget.WidgetUpdates
import io.github.touhougo.chogumi.feature.home.widget.WidgetWork
import io.github.touhougo.chogumi.feature.home.widget.refreshCalendarSources
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

/**
 * 放送提醒发成系统通知（设置 › 通知）：在追作品今天要播。数据都在本机——放送表、放送时刻表、在追作品与它们的章节，
 * 与每日放送页、放送小组件同一份（[WidgetCalendarSource]）——所以不用联网，每小时的后台刷新顺带更新它们。
 *
 * - 开播时：每部在开播时刻发一条（没有时刻的按 core:model `UNTIMED_AIRING_REMINDER`），晚得太久的不补发；
 * - 每天定时：每天在用户选的时刻（设备时区）发一条这个放送日的汇总，一部都没有就不发。
 *
 * 只排一个闹钟：下一条该发的那一刻（系统允许推迟，省电时会晚到）。闹钟响、每小时的 [AiringReminderWorker]、设置变了、App 启动都跑一次
 * [run]：到点的发（记进 [SystemNoticeLedger]，一条只发一次）、排下一次。[watch] 跟着开关与账号：关掉、登出时取消、撤掉已发的；换号时撤掉上一个账号的。
 *
 * 一次跑与一次撤一个接一个（[lock]）：几处同时跑会读到同一份「还没发」各发一遍；撤掉之后，在途的那次也不会再把通知发回来。
 */
class AiringReminders internal constructor(
    private val context: Context,
    private val preferences: PreferencesRepository,
    private val ledger: SystemNoticeLedger,
    private val calendarSource: WidgetCalendarSource,
    private val authRepository: AuthRepository,
    private val clock: AppClock,
) {
    private val lock = Mutex()

    /** 应用级：一直跟着开关、方式、时刻与账号（App 启动时开始，进程在就在）。 */
    suspend fun watch() {
        authRepository.ensureInitialized()
        var lastAccount: Int? = null
        combine(preferences.notificationSettings, accounts()) { settings, account -> Plan(settings, account) }
            .distinctUntilChanged()
            .catch { e -> if (e !is Exception) throw e }
            .collect { plan ->
                val switched = lastAccount != null && plan.account != lastAccount
                lastAccount = plan.account
                // 排任务、闹钟、撤通知出错（WorkManager 没起来、系统服务报错）只丢这一拍，跟随接着走
                outcomeOf {
                    lock.withLock {
                        // 换了号：上一个账号的提醒不留在通知栏里
                        if (switched) cancelShown()
                        if (plan.settings.airing && plan.account != null) {
                            WorkManager.getInstance(context)
                                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, AiringReminderWorker.request())
                            runLocked()
                        } else {
                            stop()
                        }
                    }
                }
            }
    }

    /** 跑一次：到点的发、排下一次闹钟。读不出本机的数据时什么都不动（已排的闹钟留着），下一次再来。 */
    suspend fun run() = lock.withLock { runLocked() }

    private suspend fun runLocked() {
        val settings = (outcomeOf { preferences.notificationSettings.first() } as? Outcome.Success)?.value ?: return
        if (!settings.airing) return
        val calendar = calendarSource.load() as? WidgetCalendar.Ready ?: return
        val account = calendar.session.userId
        if (!calendar.signedIn || account == null) return
        outcomeOf {
            when (settings.airingMode) {
                AiringReminderMode.AT_AIRING -> runAtAiring(calendar, account)
                AiringReminderMode.DAILY -> runDaily(calendar, account, settings)
            }
        }
    }

    private suspend fun runAtAiring(calendar: WidgetCalendar.Ready, account: Int) {
        val date = calendar.date
        val reminders = airingRemindersOf(calendar.week[calendar.today] ?: EMPTY_DAY, calendar.followed, date)
        val schedule = airingScheduleOf(reminders, date, ledger.airingPosted(account, date), clock.nowMillis())
        if (schedule.due.isNotEmpty()) SystemNotices.ensureChannels(context)
        schedule.due.forEach { reminder ->
            val notification = reminderNotification(reminder, calendar.works)
            authRepository.withSession(calendar.session) {
                if (SystemNotices.post(context, SystemNotices.AIRING_TAG, reminder.subject.id, notification)) {
                    ledger.markAiringPosted(account, date, reminder.subject.id)
                }
            }
        }
        setAlarm(schedule.next)
    }

    private suspend fun runDaily(calendar: WidgetCalendar.Ready, account: Int, settings: NotificationSettings) {
        val now = clock.nowMillis()
        val last = lastDailyMillisOf(now, settings.dailyMinute, displayZone())
        val date = airingDateOf(last)
        // 汇总的是上一次定时那一刻所在的放送日；读到的整周从今天的放送日起，跨过了北京时间 4 点就不补
        if (now - last <= AIRING_REMINDER_GRACE_MILLIS && date == calendar.date && SystemNoticeLedger.DIGEST !in ledger.airingPosted(account, date)) {
            val reminders = airingRemindersOf(calendar.week[calendar.today] ?: EMPTY_DAY, calendar.followed, date)
            if (reminders.isNotEmpty()) {
                SystemNotices.ensureChannels(context)
                authRepository.withSession(calendar.session) {
                    if (SystemNotices.post(context, SystemNotices.DIGEST_TAG, DIGEST_ID, digestNotification(reminders))) {
                        ledger.markAiringPosted(account, date, SystemNoticeLedger.DIGEST)
                    }
                }
            }
        }
        setAlarm(nextDailyMillisOf(now, settings.dailyMinute, displayZone()))
    }

    /**
     * 调试包的 adb 入口（app 的 debug 源码集 `NoticeSampleReceiver`）：把在追作品的放送提醒照真的样子发出来，不看开关、时刻与发过没有；
     * [digest] 为真时发一条汇总，否则每部一条。取今天的，今天没有就取这一周里最近有的那一天。返回提醒了几部。
     */
    suspend fun postSamples(digest: Boolean): Int {
        val calendar = calendarSource.load() as? WidgetCalendar.Ready ?: return 0
        val reminders = (0L until DAYS_PER_WEEK).asSequence()
            .map { offset ->
                val date = calendar.date.plusDays(offset)
                airingRemindersOf(calendar.week[date.dayOfWeek.value] ?: EMPTY_DAY, calendar.followed, date)
            }
            .firstOrNull { it.isNotEmpty() }
            ?: return 0
        SystemNotices.ensureChannels(context)
        if (digest) {
            authRepository.withSession(calendar.session) { SystemNotices.post(context, SystemNotices.DIGEST_TAG, DIGEST_ID, digestNotification(reminders)) }
        } else {
            for (reminder in reminders) {
                val notification = reminderNotification(reminder, calendar.works)
                authRepository.withSession(calendar.session) { SystemNotices.post(context, SystemNotices.AIRING_TAG, reminder.subject.id, notification) }
            }
        }
        return reminders.size
    }

    /**
     * 一部：标题是作品名，正文「第 8 集 23:00 开播」（没有时刻的「今天更新」），右边是封面；点开打开作品页。
     * 这一集正是首页 +1 要标的那一集时带「看过了」。
     */
    private suspend fun reminderNotification(reminder: AiringReminder, works: Map<Int, WidgetWork>): Notification {
        val subjectId = reminder.subject.id
        val episode = reminder.episode
        val sort = episode?.let { formatEpisodeSort(it.sort) }
        val time = reminder.airing?.label()
        val text = when {
            sort != null && time != null -> context.getString(R.string.notify_airing_episode_at, sort, time)
            sort != null -> context.getString(R.string.notify_airing_episode_today, sort)
            time != null -> context.getString(R.string.notify_airing_at, time)
            else -> context.getString(R.string.notify_airing_today)
        }
        val builder = SystemNotices.builder(context, NoticeChannel.AIRING)
            .setContentTitle(displayNameOf(reminder.subject))
            .setContentText(text)
            .setContentIntent(SystemNotices.contentIntent(context, subjectId, SystemNotices.launchIntent(context, BgmWeb.subject(subjectId))))
        val next = (works[subjectId]?.button as? EpButtonState.Next)?.episode
        if (episode != null && next?.id == episode.id) {
            builder.addAction(0, context.getString(R.string.notify_airing_watched), AiringWatchedReceiver.intent(context, subjectId, episode.id))
        }
        withTimeoutOrNull(ICON_TIMEOUT_MILLIS) { SystemNotices.largeIconOf(context, reminder.subject.coverUrl, circle = false) }
            ?.let(builder::setLargeIcon)
        return builder.build()
    }

    /** 汇总：「今天要播 N 部」，展开后一部一行（时刻、作品名、集数，按时刻排）；点开打开每日放送。 */
    private fun digestNotification(reminders: List<AiringReminder>): Notification {
        val lines = reminders.map { reminder ->
            val time = reminder.airing?.label() ?: context.getString(R.string.calendar_unknown_time)
            val title = displayNameOf(reminder.subject)
            reminder.episode?.let { context.getString(R.string.notify_digest_line_episode, time, title, formatEpisodeSort(it.sort)) }
                ?: context.getString(R.string.notify_digest_line, time, title)
        }
        val style = NotificationCompat.InboxStyle()
        lines.forEach(style::addLine)
        return SystemNotices.builder(context, NoticeChannel.AIRING)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notify_digest_title, reminders.size, reminders.size))
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(SystemNotices.contentIntent(context, DIGEST_ID, SystemNotices.launchIntent(context, BgmWeb.calendar())))
            .build()
    }

    /** 排下一次（同一个闹钟换时刻）；[at] 为 null 时取消。省电时可以推迟（不要精确闹钟的权限）。 */
    private fun setAlarm(at: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = alarmIntent()
        if (at == null) alarms.cancel(intent) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }

    private fun stop() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        setAlarm(null)
        cancelShown()
    }

    private fun cancelShown() {
        SystemNotices.cancelAll(context, SystemNotices.AIRING_TAG)
        SystemNotices.cancelAll(context, SystemNotices.DIGEST_TAG)
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AiringAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 当前账号的 id，没登录为 null；会话还没读出来的那一拍不算。 */
    private fun accounts(): Flow<Int?> = authRepository.sessionState
        .filter { it is SessionState.Authenticated || it == SessionState.Unauthenticated }
        .map { (it as? SessionState.Authenticated)?.account?.userId }

    private data class Plan(val settings: NotificationSettings, val account: Int?)

    private companion object {
        const val WORK_NAME = "airing-reminders"

        /** 汇总只有一条：同一个 id 换成新的。 */
        const val DIGEST_ID = 0

        /** 取封面最多等多久：取不到就不放封面，通知照发。 */
        const val ICON_TIMEOUT_MILLIS = 5_000L

        val EMPTY_DAY = CalendarDaySections(emptyList(), emptyList(), emptyList())

        const val DAYS_PER_WEEK = 7L
    }
}

/** 放送提醒的闹钟响了：跑一次 [AiringReminders.run]（发到点的、排下一次）。 */
class AiringAlarmReceiver : BroadcastReceiver(), KoinComponent {
    private val reminders: AiringReminders by inject()
    private val scope: CoroutineScope by inject(named("appScope"))

    override fun onReceive(context: Context, intent: Intent) {
        // 广播结束之前进程不会被回收：读本机数据、发完再结束（后台广播最长约一分钟，留出余量）
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(RUN_TIMEOUT_MILLIS) { reminders.run() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val RUN_TIMEOUT_MILLIS = 30_000L
    }
}

/** 放送提醒上的「看过了」：撤掉这条，把那一集标成看过（与首页 +1 同一条路，[WidgetProgressActions.advanceEpisode]）。 */
class AiringWatchedReceiver : BroadcastReceiver(), KoinComponent {
    private val actions: WidgetProgressActions by inject()
    private val updates: WidgetUpdates by inject()
    private val scope: CoroutineScope by inject(named("appScope"))

    override fun onReceive(context: Context, intent: Intent) {
        val subjectId = intent.getIntExtra(EXTRA_SUBJECT_ID, 0)
        val episodeId = intent.getIntExtra(EXTRA_EPISODE_ID, 0)
        if (subjectId <= 0 || episodeId <= 0) return
        SystemNotices.cancel(context, SystemNotices.AIRING_TAG, subjectId)
        // 协调器停手 2 秒才提交：广播一直挂到这一轮写完（含失败回滚），提交不会因进程被回收而丢
        val pending = goAsync()
        scope.launch {
            try {
                actions.advanceEpisode(subjectId, episodeId)
                updates.updateNow()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_SUBJECT_ID = "io.github.touhougo.chogumi.extra.SUBJECT_ID"
        private const val EXTRA_EPISODE_ID = "io.github.touhougo.chogumi.extra.EPISODE_ID"

        internal fun intent(context: Context, subjectId: Int, episodeId: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            subjectId,
            Intent(context, AiringWatchedReceiver::class.java)
                .putExtra(EXTRA_SUBJECT_ID, subjectId)
                .putExtra(EXTRA_EPISODE_ID, episodeId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** 放送提醒每小时一次：先按到期刷新放送表、时刻表与首页进度（[refreshCalendarSources]），再 [AiringReminders.run]。 */
class AiringReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val reminders: AiringReminders by inject()
    private val homeRepository: HomeRepository by inject()
    private val calendarRepository: CalendarRepository by inject()
    private val airingTimeRepository: AiringTimeRepository by inject()

    override suspend fun doWork(): Result {
        refreshCalendarSources(homeRepository, calendarRepository, airingTimeRepository)
        reminders.run()
        return Result.success()
    }

    internal companion object {
        /** 不要求联网：断网时取数各自失败，照样按本机的数据提醒。 */
        fun request() = PeriodicWorkRequestBuilder<AiringReminderWorker>(1, TimeUnit.HOURS).build()
    }
}
