package io.github.touhougo.chogumi.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * 放送日：每日放送按它分天、标「今天」、画「现在」线。
 *
 * 一天从北京时间 4 点（日本时间 5 点）开始，深夜番归前一天。bgm.tv 放送表的星期就是这么归的（拿 bangumi-data 的首播时刻对照，
 * 一季 73 部里 71 部吻合），所以放送日就是 UTC+4 的日期：北京时间减 4 小时与日本时间减 5 小时是同一刻。时刻按北京时间写，
 * 0 点到 4 点写成 24 点到 27 点（[AiringMinute]）。这是放送表自己的「今天」，只用在这一页；别处的业务日仍是
 * [io.github.touhougo.chogumi.core.foundation.businessDateOf]。
 */
val AIRING_DAY_OFFSET: ZoneOffset = ZoneOffset.ofHours(4)

/** 放送日从北京时间几点开始，换成分钟。 */
const val AIRING_DAY_START_MINUTE = 4 * 60

/**
 * 放送日里的一个时刻：从北京时间 0 点起的分钟数，从 [AIRING_DAY_START_MINUTE]（4:00）起，到次日 4 点前；
 * 跨过零点的那一段是 24:00 到 27:59（1440 到 1679）。
 */
@JvmInline
value class AiringMinute(val minutes: Int) : Comparable<AiringMinute> {
    val hour: Int get() = minutes / 60
    val minute: Int get() = minutes % 60

    override fun compareTo(other: AiringMinute): Int = minutes.compareTo(other.minutes)

    /** 「22:30」「24:20」：小时不补零，分钟两位。 */
    fun label(): String = String.format(Locale.ROOT, "%d:%02d", hour, minute)
}

/** 放送日里的「现在」：[weekday] 是放送日的星期（1=周一…7=周日），[minute] 是此刻在放送日里的时刻。 */
data class AiringNow(val weekday: Int, val minute: AiringMinute)

/** [nowMillis] 那一刻在哪个放送日、放送日里的几点（[AIRING_DAY_OFFSET]）。 */
fun airingNowOf(nowMillis: Long): AiringNow {
    val at = Instant.ofEpochMilli(nowMillis).atOffset(AIRING_DAY_OFFSET)
    return AiringNow(at.dayOfWeek.value, AiringMinute(at.hour * 60 + at.minute + AIRING_DAY_START_MINUTE))
}

/** [nowMillis] 那一刻是哪个放送日（[AIRING_DAY_OFFSET] 的日期）：每日放送的七页对应从它起的七天。 */
fun airingDateOf(nowMillis: Long): LocalDate = Instant.ofEpochMilli(nowMillis).atOffset(AIRING_DAY_OFFSET).toLocalDate()

/**
 * 每周首播时刻 [broadcastStartMillis] 在 bgm.tv 给的星期 [bgmWeekday] 里是几点；首播落在别的放送日就是 null：
 * 两边的数据有一边错了（bangumi-data 填错时区、日期差一两天），不显示时刻，不猜。
 */
fun airingMinuteOf(bgmWeekday: Int, broadcastStartMillis: Long): AiringMinute? {
    val at = Instant.ofEpochMilli(broadcastStartMillis).atOffset(AIRING_DAY_OFFSET)
    if (at.dayOfWeek.value != bgmWeekday) return null
    return AiringMinute(at.hour * 60 + at.minute + AIRING_DAY_START_MINUTE)
}

/**
 * bangumi-data 一条 `broadcast`（`R/<首播时刻>/P7D`，首播时刻是带时区的 ISO 8601）里的首播时刻，epoch 毫秒。
 * 只认每周一次（`P7D`）的；缺字段、别的周期、写法不对都是 null（这部没有时刻）。
 */
fun weeklyBroadcastStartOf(broadcast: String?): Long? {
    val parts = broadcast?.split('/') ?: return null
    if (parts.size != 3 || parts[0] != "R" || parts[2] != "P7D") return null
    return try {
        OffsetDateTime.parse(parts[1]).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }
}
