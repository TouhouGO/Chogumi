package io.github.touhougo.chogumi.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import io.github.touhougo.chogumi.core.model.ThemePreferences
import io.github.touhougo.chogumi.core.model.ThemePreset
import kotlin.math.max
import kotlin.math.min

/**
 * 对比度测试换着用的配色：默认粉色种子与几种差得很远的种子，加上每一套配色方案——用户换主题色或配色方案后也要成立。
 */
internal val CONTRAST_TEST_SOURCES: List<ThemeColorSource> = listOf(
    ThemePreferences.DEFAULT_SEED.toInt(),
    0xFF3F51B5.toInt(),
    0xFF2E7D32.toInt(),
    0xFFF9A825.toInt(),
    0xFF6A1B9A.toInt(),
    0xFF607D8B.toInt(),
).map { ThemeColorSource.Seed(Color(it)) } + ThemePreset.entries.map { ThemeColorSource.Preset(it) }

/** 失败信息里的配色名：种子的十六进制，或方案名。 */
internal fun ThemeColorSource.label(): String = when (this) {
    is ThemeColorSource.Seed -> "种子 %06X".format(color.toArgb() and 0xFFFFFF)
    is ThemeColorSource.Preset -> "方案 $preset"
    is ThemeColorSource.SystemPrimary -> "系统 %06X".format(color.toArgb() and 0xFFFFFF)
}

/** M3 小字的最低对比度。 */
internal const val MIN_TEXT_CONTRAST = 4.5

/** WCAG 对比度。 */
internal fun contrastOf(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}
