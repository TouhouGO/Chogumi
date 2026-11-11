package io.github.touhougo.chogumi.core.ui.common

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.touhougo.chogumi.core.model.CoverQuality
import io.github.touhougo.chogumi.core.model.NoticeChannel
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.image.coverBitmapOf

/**
 * 系统通知与系统设置的唯一入口（社区提醒、放送提醒、设置 › 通知都经这里）：通知类别、发与撤、点开时打开 App 的意图、
 * 系统的通知开关与电池优化。
 *
 * 一条通知由「标签 + id」认：社区提醒是 [NOTICE_TAG] + 提醒 id，短信是 [MESSAGE_TAG]，放送是 [AIRING_TAG] + 作品 id，
 * 汇总是 [DIGEST_TAG]。「通知栏里还挂着哪些」只从系统读（[shownIds]），不另记一份。
 */
object SystemNotices {
    const val NOTICE_TAG = "notice"
    const val MESSAGE_TAG = "message"
    const val AIRING_TAG = "airing"
    const val DIGEST_TAG = "airing_digest"

    /** 点开一条社区提醒时带给 App 的提醒 id：App 打开那一楼的同时把它标为已读。 */
    const val EXTRA_NOTICE_ID = "io.github.touhougo.chogumi.extra.NOTICE_ID"

    /** 本机签发、绑定会话与提醒 id 的动作凭据；缺少或不匹配时只导航。 */
    const val EXTRA_NOTICE_PROOF = "io.github.touhougo.chogumi.extra.NOTICE_PROOF"

    /** 点开时要打开铃铛面板的哪一页（[io.github.touhougo.chogumi.core.ui.common.navigation.InboxTab] 的名字）。 */
    const val EXTRA_INBOX = "io.github.touhougo.chogumi.extra.INBOX"

    /** 系统有没有通知类别（Android 8 起）：没有时设置里不放「通知类别」一行。 */
    val channelsSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    fun channelId(channel: NoticeChannel): String = channel.name.lowercase()

    /** 建好六个通知类别（已有的只更新名字，用户改过的提示音与开关不动）。发通知之前调用。 */
    fun ensureChannels(context: Context) {
        if (!channelsSupported) return
        val channels = NoticeChannel.entries.map {
            NotificationChannel(channelId(it), context.getString(it.label), NotificationManager.IMPORTANCE_DEFAULT)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(channels)
    }

    /** App 现在能不能发通知：系统里的总开关（Android 13 起没给通知权限时它也是关的）。 */
    fun allowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * 还得先问通知权限（[PERMISSION]）：Android 13 起、还没给。给过了而通知仍是关的（[allowed] 为 false），是用户在系统设置里关的，
     * 只能去系统设置页打开。
     */
    fun permissionNeeded(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED

    const val PERMISSION = Manifest.permission.POST_NOTIFICATIONS

    /** 系统的电池优化放过了这个 App 没有：没放过时后台检查与闹钟在省电时会被推迟。 */
    fun backgroundExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** 一条通知的骨架：类别、App 的小图标、点开后自己消失。 */
    fun builder(context: Context, channel: NoticeChannel): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId(channel))
            .setSmallIcon(R.drawable.tv_24)
            .setAutoCancel(true)

    /** 发一条：已交给系统为 true；权限或类别关闭为 false，调用者不能把它记成已发。 */
    fun post(context: Context, tag: String, id: Int, notification: Notification): Boolean {
        if (!allowed(context)) return false
        // 与 permissionNeeded 同一个判定，写在发的这一处：Android lint 只认同一个函数里的权限检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (channelsSupported && context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(notification.channelId)?.importance == NotificationManager.IMPORTANCE_NONE
        ) return false
        NotificationManagerCompat.from(context).notify(tag, id, notification)
        return true
    }

    fun cancel(context: Context, tag: String, id: Int) {
        NotificationManagerCompat.from(context).cancel(tag, id)
    }

    /** 撤掉一个标签下的全部（关掉开关、登出、换号）。 */
    fun cancelAll(context: Context, tag: String) {
        shownIds(context, tag).forEach { cancel(context, tag, it) }
    }

    /** 通知栏里这个标签下还挂着的通知 id（用户划掉的不在里面）。 */
    fun shownIds(context: Context, tag: String): Set<Int> =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.tag == tag }
            .mapTo(HashSet()) { it.id }

    /**
     * 打开 App 的意图：[url] 是 bgm.tv 网页地址（`BgmWeb` 拼的）时 App 打开它对应的那一页（`inAppRoute` 认），null 时只打开 App。
     * 用桌面图标同一个入口（用户在设置里换过图标也一样），App 已经开着就回到它、不另开一个。小组件与通知都从这里开 App。
     */
    fun launchIntent(context: Context, url: String?): Intent {
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)) { "App 没有桌面入口" }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (url != null) intent.data = Uri.parse(url)
        return intent
    }

    /** 点开通知时直接打开 App（Android 12 起不许经广播转一手）。[requestCode] 区分同时挂着的几条。 */
    fun contentIntent(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * 通知右边的大图标：[url] 那张图裁成方形（[circle] 时裁成圆，头像用），按系统给大图标的 64dp 取；取不到是 null（不放大图标）。
     * 取图走应用唯一的 Coil 加载器（磁盘缓存里多半已经有）。
     */
    suspend fun largeIconOf(context: Context, url: String?, circle: Boolean): Bitmap? {
        if (url.isNullOrBlank()) return null
        val size = (LARGE_ICON_DP * context.resources.displayMetrics.density).toInt()
        val corner = if (circle) size / 2f else LARGE_ICON_CORNER_DP * context.resources.displayMetrics.density
        return coverBitmapOf(context, url, size, size, corner, CoverQuality.AUTO)
    }

    /** 系统里这个 App 的通知设置页：总开关与各个类别都在那里（Android 8 之前是应用信息页）。 */
    fun openNotificationSettings(context: Context) {
        val intent = if (channelsSupported) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            appDetails(context)
        }
        start(context, intent) || start(context, appDetails(context))
    }

    /** 系统的「允许在后台运行」对话框（电池优化放过这个 App）。 */
    fun requestBackgroundExemption(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        start(context, intent) || start(context, appDetails(context))
    }

    /**
     * 系统的限制放过之后，再去厂商自己的「自启动 / 后台」页：认得出的几家按顺序试，都打不开就去应用信息页。
     * 厂商页面没有公开接口，系统升级后可能失效，所以只是尽力而为。
     */
    fun openBackgroundSettings(context: Context) {
        VENDOR_BACKGROUND_PAGES.any { (pkg, activity) -> start(context, Intent().setComponent(ComponentName(pkg, activity))) } ||
            start(context, appDetails(context))
    }

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** 打开一个系统或厂商的页面；没有这个页面、或厂商不让外面打开时回 false。 */
    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private const val LARGE_ICON_DP = 64

    /** 封面大图标的圆角：M3 圆角阶梯的 small 档（设计决策，not in M3：系统自己画大图标时不加圆角）。 */
    private const val LARGE_ICON_CORNER_DP = 8

    /** 厂商的自启动、后台耗电管理页（包名 → Activity），按市场份额排。 */
    private val VENDOR_BACKGROUND_PAGES = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
    )
}

/** 一个通知类别在系统设置里的名字。 */
@get:StringRes
val NoticeChannel.label: Int
    get() = when (this) {
        NoticeChannel.REPLY -> R.string.notify_channel_reply
        NoticeChannel.MENTION -> R.string.notify_channel_mention
        NoticeChannel.FRIEND -> R.string.notify_channel_friend
        NoticeChannel.MESSAGE -> R.string.notify_channel_message
        NoticeChannel.OTHER -> R.string.notify_channel_other
        NoticeChannel.AIRING -> R.string.notify_channel_airing
    }
