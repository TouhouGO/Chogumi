package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.AppTime
import io.github.touhougo.chogumi.core.foundation.displayZone
import io.github.touhougo.chogumi.core.res.R
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 「现在」（墙钟毫秒，整分钟一跳）：相对时间、动态的日期头按它显示，读 `.value` 的地方每分钟重算一次。
 * 外壳把 [AppTime.now] 提供进来（全 App 唯一提供点）；没有外壳的地方（组件测试、预览）停在第一次读到的时刻。
 *
 * 给的是 [State] 而不是数值：每分钟一跳只让读它的文字重组，不让整棵树跟着重组。
 */
val LocalNow = staticCompositionLocalOf<State<Long>> { mutableLongStateOf(AppClock.SYSTEM.nowMillis()) }

/** 相对时间的一档：[relativeTimeOf] 判定，[relativeTimeText] 按档取文案。 */
internal sealed interface RelativeTime {
    /** 不到一分钟（含服务端时钟略快造成的「未来」时间）。 */
    data object JustNow : RelativeTime

    /** 1–59 分钟。 */
    data class Minutes(val count: Int) : RelativeTime

    /** 1–23 小时（满 24 小时仍在同一个自然日时也在这一档，只有夏令时回拨那天会出现）。 */
    data class Hours(val count: Int) : RelativeTime

    /** 满 24 小时、不到一周，且按自然日算是昨天。 */
    data object Yesterday : RelativeTime

    /** 满 24 小时、不到一周，按自然日算是 [count] 天前（2–7）。 */
    data class Days(val count: Int) : RelativeTime

    /** 满一周：写日子（[formatPastDate]）。 */
    data object Date : RelativeTime
}

/**
 * 相对时间分档（全 App 唯一的判定）：分钟、小时按经过的时长向下取整；满 24 小时后按 [zone] 里的自然日数
 * （所以「昨天」是日历上的昨天，不是 24–48 小时）；满一周写日子。
 */
internal fun relativeTimeOf(epochMillis: Long, nowMillis: Long, zone: ZoneId): RelativeTime {
    val elapsed = nowMillis - epochMillis
    if (elapsed < MINUTE_MILLIS) return RelativeTime.JustNow
    if (elapsed < HOUR_MILLIS) return RelativeTime.Minutes((elapsed / MINUTE_MILLIS).toInt())
    if (elapsed >= WEEK_MILLIS) return RelativeTime.Date
    val days = ChronoUnit.DAYS.between(dayOf(epochMillis, zone), dayOf(nowMillis, zone)).toInt()
    return when {
        elapsed < DAY_MILLIS || days == 0 -> RelativeTime.Hours((elapsed / HOUR_MILLIS).toInt())
        days == 1 -> RelativeTime.Yesterday
        else -> RelativeTime.Days(days)
    }
}

private fun dayOf(epochMillis: Long, zone: ZoneId) = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS
private const val WEEK_MILLIS = 7 * DAY_MILLIS

/**
 * 相对时间（全 App 唯一实现）：毫秒时间戳 → 「刚刚」「5 分钟前」「9 小时前」「昨天」「3 天前」，一周以前写日子
 * （「10 月 2 日」，与 App 其它日期同一口径）；时间戳无效时为空串。分档见 [relativeTimeOf]，自然日按显示口径的时区
 * （[displayZone]，本机）。评论、动态、通知、收藏面板共用，随 [LocalNow] 每分钟重算（停在页面上，「刚刚」会变成
 * 「1 分钟前」）。
 */
@Composable
fun relativeTimeText(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val now = LocalNow.current.value
    return when (val time = relativeTimeOf(epochMillis, now, displayZone())) {
        RelativeTime.JustNow -> stringResource(R.string.relative_time_just_now)
        is RelativeTime.Minutes -> pluralStringResource(R.plurals.relative_time_minutes, time.count, time.count)
        is RelativeTime.Hours -> pluralStringResource(R.plurals.relative_time_hours, time.count, time.count)
        RelativeTime.Yesterday -> stringResource(R.string.day_offset_yesterday)
        is RelativeTime.Days -> pluralStringResource(R.plurals.relative_time_days, time.count, time.count)
        RelativeTime.Date -> {
            val locale = LocalConfiguration.current.locales[0]
            remember(epochMillis, now, locale) { formatPastDate(epochMillis, now, locale) }
        }
    }
}
