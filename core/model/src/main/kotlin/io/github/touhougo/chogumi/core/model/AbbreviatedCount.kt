package io.github.touhougo.chogumi.core.model

import java.util.Locale

/**
 * 数量缩写纯函数（全 App 唯一数字缩写真源）：不到 1000 原样显示；往上一律保留一位小数 + 单位（产品决定）。
 * - 中文：1000 起「K」（1.2K），一万起「W」（= 万，2.1W）；
 * - 其他语言：1000 起「K」，一百万起「M」。
 *
 * 小数位**截断**不四舍五入：9999 是「9.9K」而不是「10.0K」，单位边界上不会出现比下一档还大的读数。
 *
 * @param count 待格式化的整数值
 * @param locale 界面所处区域设置（由调用点从 LocalConfiguration.current.locales[0] 传入，禁止用 Locale.getDefault()）
 */
fun formatAbbreviatedCount(
    count: Int,
    locale: Locale,
): String {
    val units = if (locale.language == Locale.CHINESE.language) ZhUnits else OtherUnits
    val unit = units.lastOrNull { count >= it.first } ?: return count.toString()
    val tenths = count.toLong() * 10 / unit.first
    return "${tenths / 10}.${tenths % 10}${unit.second}"
}

/** 单位档：（起点，后缀），从小到大。 */
private val ZhUnits = listOf(1_000 to "K", 10_000 to "W")
private val OtherUnits = listOf(1_000 to "K", 1_000_000 to "M")
