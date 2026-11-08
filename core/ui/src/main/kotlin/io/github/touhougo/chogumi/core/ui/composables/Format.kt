package io.github.touhougo.chogumi.core.ui.composables

import io.github.touhougo.chogumi.core.foundation.displayZone
import io.github.touhougo.chogumi.core.model.AppFeatures
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 评分展示格式（bgm 评分习惯用小数点，跨 locale 统一为点号）。
 */
fun formatScore(score: Double): String = formatOneDecimal(score)

/** 一位小数的数字文本，各语言都用点号（「8.0」「2.3」）：分数与其他一位小数的统计值（平均晚几年、小占比）共用。 */
fun formatOneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

/**
 * 公共评分文本的可见性 —— **公共评分展示的唯一判定点**（卡片与列表项共用）。
 *
 * 评分数值属于 [AppFeatures.publicRatings]：为 false（专注模式）时返回 null，
 * 调用方据此整条不渲染（不留占位空行）；未评分（<= 0）同样返回 null。
 * [render] 由调用方给出**该处自己的文案格式**（卡片是「★ 8.2」，列表项是本地化的「8.2 分」），
 * 所以判定与文案分离：判定只此一处，文案各随语境——这正是它做成纯函数的原因，
 * 使"该不该显示评分"能被 JVM 单元测试直接断言（组件本身只在仪器测试里可跑）。
 */
fun subjectRatingText(score: Double, features: AppFeatures, render: (Double) -> String): String? =
    if (features.publicRatings && score > 0) render(score) else null

/**
 * 封面分数角标的文案：`★ 8.2`。判定是 [AppFeatures.coverScores]（公共评分开着、封面评分也开着）且有分。
 */
fun subjectCardRatingSubtitle(score: Double, features: AppFeatures): String? =
    if (features.coverScores) subjectRatingText(score, features, ::starScoreText) else null

/** 星号加分数（「★ 8.2」）：封面角标与目录条目共用，各语言写法相同，所以不进字符串资源。 */
fun starScoreText(score: Double): String = "★ ${formatScore(score)}"

/**
 * 货架里的位次大字（压在排行榜封面上的那个数字）的可见性 —— 同 [subjectRatingText]。
 *
 * 位次是按排名排出来的，属于 [AppFeatures.publicRatings] 说的「他人的排名数字」：
 * 专注模式下副标题藏了评分，封面上的大字也必须一起藏，否则等于从另一个口子漏出去。
 */
fun shelfRankNumeralText(position: Int, features: AppFeatures): String? =
    if (features.publicRatings) position.takeIf { it > 0 }?.toString() else null

/**
 * 他人打的分（吐槽里的星级）的可见性 —— 同 [subjectRatingText]：属于 [AppFeatures.publicRatings]，
 * 关掉或没打分（<= 0）时返回 null，调用方整行不画。
 * 返回值是 5 颗星里实心的颗数：10 分制折半，差 0.25 颗以内算满（原型 `starsRow`，不画半星）。
 */
fun othersRatingStars(rate: Int, features: AppFeatures): Int? =
    if (features.publicRatings && rate > 0) (1..RATING_STAR_COUNT).count { rate / 2.0 >= it - 0.25 } else null

/** 星级的总颗数。 */
const val RATING_STAR_COUNT = 5

private val formatters = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()

/** 列表里每行都会格式化日期：同一 (格式, 语言) 只建一个 formatter。 */
private fun formatterOf(pattern: String, locale: Locale): DateTimeFormatter =
    formatters.getOrPut(pattern to locale) { DateTimeFormatter.ofPattern(pattern, locale) }

/**
 * 日期文案跟着界面文字走：资源只有英文（values-en）与中文（默认的 values，构建再发布一份 values-zh），
 * 其余系统语言界面落回中文，日期也就用中文写法与中文星期，不会同一行中英（或中日）混排。
 */
private fun textLocaleOf(locale: Locale): Locale =
    if (locale.language == Locale.ENGLISH.language) locale else Locale.SIMPLIFIED_CHINESE

/**
 * 「月、日、星期」的日期文案，**全 App 唯一**：首页页头的今天、追更时间表的分组日期都用它。
 *
 * 中文「9 月 30 日 周三」（数字与汉字之间留空格，与字符串资源同一口径），英文「Sep 30, Wed」。
 * [locale] 由调用方从 `LocalConfiguration` 取：切换系统语言时随之重组（`Locale.getDefault()` 不可观察）。
 */
fun formatMonthDay(date: LocalDate, locale: Locale): String {
    val text = textLocaleOf(locale)
    val pattern = if (text.language == Locale.ENGLISH.language) "MMM d, E" else "M 月 d 日 E"
    return date.format(formatterOf(pattern, text))
}

/** 格子里放得下的短日期「10/18」（章节方格里未播出集的播出日），各语言同一写法。 */
fun formatShortDate(date: LocalDate): String = "${date.monthValue}/${date.dayOfMonth}"

/**
 * 已经过去一阵子的日子（评论、动态超过一周时的时间）：今年的写「10 月 2 日」，往年的写「2024 年 3 月 2 日」
 * （英文「Oct 2」「Mar 2, 2024」）。与 [formatMonthDay] 同一口径，数字与汉字之间留空格。
 */
fun formatPastDate(date: LocalDate, today: LocalDate, locale: Locale): String {
    val text = textLocaleOf(locale)
    val chinese = text.language != Locale.ENGLISH.language
    val pattern = when {
        date.year == today.year -> if (chinese) "M 月 d 日" else "MMM d"
        chinese -> "yyyy 年 M 月 d 日"
        else -> "MMM d, yyyy"
    }
    return date.format(formatterOf(pattern, text))
}

/** 同上，两个毫秒时间戳按显示口径的时区（[displayZone]，本机）换成日子。 */
fun formatPastDate(epochMillis: Long, nowMillis: Long, locale: Locale): String {
    val zone = displayZone()
    return formatPastDate(
        date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate(),
        today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate(),
        locale = locale,
    )
}

/**
 * 毫秒时间戳 → 「2026-09-30」，**全 App 唯一**：加入日期、日志发表日这类只要日子的地方用它。
 * 0 及以下是「不知道」，返回空串；按显示口径的时区（[displayZone]，本机）换算。
 */
fun formatIsoDate(epochMillis: Long): String =
    if (epochMillis <= 0L) "" else Instant.ofEpochMilli(epochMillis).atZone(displayZone()).toLocalDate().toString()
