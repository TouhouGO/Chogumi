package io.github.touhougo.chogumi.feature.community.notice

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.touhougo.chogumi.core.data.repository.AuthRepository
import io.github.touhougo.chogumi.core.data.repository.NotificationRepository
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.SystemNoticeLedger
import io.github.touhougo.chogumi.core.data.repository.UnreadSnapshot
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CommunityCheckInterval
import io.github.touhougo.chogumi.core.model.MessageRound
import io.github.touhougo.chogumi.core.model.NoticeBo
import io.github.touhougo.chogumi.core.model.NoticeChannel
import io.github.touhougo.chogumi.core.model.SessionState
import io.github.touhougo.chogumi.core.model.messageRoundOf
import io.github.touhougo.chogumi.core.model.noticeChannelOf
import io.github.touhougo.chogumi.core.model.noticeKindOf
import io.github.touhougo.chogumi.core.model.noticeLinkOf
import io.github.touhougo.chogumi.core.model.noticeRoundOf
import io.github.touhougo.chogumi.core.model.userDisplayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.SystemNotices
import io.github.touhougo.chogumi.core.ui.common.labelRes
import io.github.touhougo.chogumi.core.ui.common.navigation.InboxTab
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 社区提醒发成系统通知（设置 › 通知）。bgm.tv 没有推送，只能本机去问：WorkManager 按用户选的间隔（[CommunityCheckInterval]，
 * 最短 15 分钟，系统省电时往后推；只在有网时）跑 [CommunityNoticeWorker]，每次 [check] 一轮。
 *
 * - 一轮：读服务端此刻的未读提醒与未读短信（[NotificationRepository.unreadSnapshot]），交给 core:model 的 `noticeRoundOf` /
 *   `messageRoundOf` 决定发什么、撤什么；通知栏里还挂着哪些只从系统读。App 在前台时只记基线、不发（角标已经在）。
 * - 跟着谁：[watch] 跟着开关、专注模式与账号。开着、不在专注模式、登录着就排上；否则取消、撤掉已发的社区提醒。
 *   关掉开关、登出、换号时连基线一起清掉：再打开时打开那一刻已有的未读不推。
 * - 一轮与一次撤一个接一个（[lock]）：撤的时候在途的那一轮（正在取头像）先结束，撤完之后它不会把通知与基线写回来。
 */
class CommunityNotices(
    private val context: Context,
    private val notificationRepository: NotificationRepository,
    private val preferences: PreferencesRepository,
    private val ledger: SystemNoticeLedger,
    private val authRepository: AuthRepository,
    private val foreground: Flow<Boolean>,
) {
    private val lock = Mutex()

    /** 应用级：一直跟着开关、专注模式与账号（App 启动时开始，进程在就在）。 */
    suspend fun watch() {
        authRepository.ensureInitialized()
        var lastAccount: Int? = null
        combine(
            preferences.notificationSettings,
            preferences.appFeatures.map { it.community },
            accounts(),
        ) { settings, community, account -> Plan(settings.community && community && account != null, settings.interval, account) }
            .distinctUntilChanged()
            // 读不出本机的设置：跟随到此为止，进程不崩；已排上的检查照常跑，每一轮自己再读一次设置
            .catch { e -> if (e !is Exception) throw e }
            .collect { plan ->
                val switched = lastAccount != null && plan.account != lastAccount
                lastAccount = plan.account
                // 排任务、撤通知出错（WorkManager 没起来、系统服务报错）只丢这一拍，跟随接着走
                outcomeOf {
                    if (plan.enabled && !switched) {
                        schedule(plan.interval)
                    } else {
                        stop()
                        // 换了号还开着：清掉上一个账号的之后，按新账号重新排
                        if (plan.enabled) schedule(plan.interval)
                    }
                }
            }
    }

    /**
     * 一轮检查（后台，App 可能不在）。读不出来（断网、服务端出错、本机存储）就这一轮什么都不动，下一轮再来；
     * 访问关着（专注模式、没登录、读的期间换了号）撤掉社区提醒。
     */
    suspend fun check(): Unit = lock.withLock {
        val settings = outcomeOf { preferences.notificationSettings.first() }
        if (settings !is Outcome.Success || !settings.value.community) return@withLock
        val snapshot = when (val read = notificationRepository.unreadSnapshot()) {
            is Outcome.Success -> read.value
            is Outcome.Failed -> return@withLock
        }
        if (snapshot == null) {
            cancelShown()
            return@withLock
        }
        outcomeOf { post(snapshot) }
    }

    private suspend fun post(snapshot: UnreadSnapshot) {
        val suppress = foreground.first()
        val round = noticeRoundOf(
            unread = snapshot.notices,
            baseline = ledger.noticeBaseline(snapshot.accountId),
            shown = SystemNotices.shownIds(context, SystemNotices.NOTICE_TAG),
            suppress = suppress,
        )
        round.cancel.forEach { SystemNotices.cancel(context, SystemNotices.NOTICE_TAG, it) }
        if (round.post.isNotEmpty()) SystemNotices.ensureChannels(context)
        round.post.forEach { SystemNotices.post(context, SystemNotices.NOTICE_TAG, it.id, noticeNotification(it)) }
        snapshot.messages?.let { count ->
            when (val messages = messageRoundOf(count, ledger.notifiedMessages(snapshot.accountId), suppress)) {
                is MessageRound.Post -> {
                    SystemNotices.ensureChannels(context)
                    SystemNotices.post(context, SystemNotices.MESSAGE_TAG, MESSAGE_ID, messageNotification(messages.count))
                }
                MessageRound.Cancel -> SystemNotices.cancel(context, SystemNotices.MESSAGE_TAG, MESSAGE_ID)
                MessageRound.Keep -> Unit
            }
        }
        ledger.recordRound(snapshot.accountId, round.baseline, snapshot.messages)
    }

    /**
     * 一条提醒：标题是发送者，正文是提醒的说法（「在小组话题中回复了你」），展开后多一行话题标题；右边是发送者的头像。
     * 点开打开那一楼（好友请求打开对方主页）并标为已读；App 里没有对应一页的（动态、同人）打开铃铛面板。按钮「标为已读」。
     */
    private suspend fun noticeNotification(notice: NoticeBo): Notification {
        val kind = noticeKindOf(notice.type)
        val sender = userDisplayNameOf(notice.sender.username, notice.sender.nickname, notice.sender.id)
        val label = context.getString(kind.labelRes())
        val link = noticeLinkOf(notice)
        val open = SystemNotices.launchIntent(context, link).putExtra(SystemNotices.EXTRA_NOTICE_ID, notice.id)
        if (link == null) open.putExtra(SystemNotices.EXTRA_INBOX, InboxTab.NOTICES.name)
        val builder = SystemNotices.builder(context, noticeChannelOf(kind))
            .setContentTitle(sender)
            .setContentText(label)
            .setWhen(notice.createdAt)
            .setShowWhen(true)
            .setContentIntent(SystemNotices.contentIntent(context, notice.id, open))
            .addAction(0, context.getString(R.string.notify_mark_read), MarkNoticeReadReceiver.intent(context, notice.id))
        if (notice.title.isNotBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notify_notice_detail, label, notice.title)))
        }
        withTimeoutOrNull(ICON_TIMEOUT_MILLIS) { SystemNotices.largeIconOf(context, notice.sender.avatar, circle = true) }
            ?.let(builder::setLargeIcon)
        return builder.build()
    }

    /** 未读短信：只有条数（服务端只给条数），点开打开铃铛面板的短信页。 */
    private fun messageNotification(count: Int): Notification {
        val open = SystemNotices.launchIntent(context, null).putExtra(SystemNotices.EXTRA_INBOX, InboxTab.MESSAGES.name)
        return SystemNotices.builder(context, NoticeChannel.MESSAGE)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notify_unread_messages, count, count))
            .setContentIntent(SystemNotices.contentIntent(context, MESSAGE_ID, open))
            .build()
    }

    private fun schedule(interval: CommunityCheckInterval) {
        val request = PeriodicWorkRequestBuilder<CommunityNoticeWorker>(interval.minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // 已排上的换成新的间隔，沿用原来的起点（UPDATE）：拨一下间隔不会立刻多查一次
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** 先取消排着的检查（在跑的那一轮随之取消），等它结束再撤通知、清基线。 */
    private suspend fun stop() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        lock.withLock {
            cancelShown()
            outcomeOf { ledger.forgetCommunity() }
        }
    }

    private fun cancelShown() {
        SystemNotices.cancelAll(context, SystemNotices.NOTICE_TAG)
        SystemNotices.cancelAll(context, SystemNotices.MESSAGE_TAG)
    }

    /** 当前账号的 id，没登录为 null；会话还没读出来的那一拍不算（不当成登出）。 */
    private fun accounts(): Flow<Int?> = authRepository.sessionState
        .filter { it is SessionState.Authenticated || it == SessionState.Unauthenticated }
        .map { (it as? SessionState.Authenticated)?.account?.userId }

    private data class Plan(val enabled: Boolean, val interval: CommunityCheckInterval, val account: Int?)

    private companion object {
        const val WORK_NAME = "community-notices"

        /** 短信通知只有一条：同一个 id 换成新的条数。 */
        const val MESSAGE_ID = 0

        /** 取头像最多等多久：取不到就不放头像，通知照发。 */
        const val ICON_TIMEOUT_MILLIS = 5_000L
    }
}

/** 社区提醒的一轮检查（[CommunityNotices.check]）；失败也算这一轮完了，下一轮按间隔再来。 */
class CommunityNoticeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val notices: CommunityNotices by inject()

    override suspend fun doWork(): Result {
        notices.check()
        return Result.success()
    }
}
