package io.github.touhougo.chogumi.core.reader

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.touhougo.chogumi.core.model.ThemePreferences
import io.github.touhougo.chogumi.core.ui.theme.ThemeColorSource
import io.github.touhougo.chogumi.core.ui.theme.appColorScheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * BBCode 具名色的明暗两档在**实际主题底色**上必须看得清。
 *
 * 底色取自 [appColorScheme]（ChogumiTheme 用的同一个函数，默认种子色，明暗各一套）里的
 * `surface` 与 `surfaceContainerHigh`（正文两种落底），按 WCAG 相对亮度公式实测对比度，
 * 断言全部 ≥ 3:1。
 */
class BBCodeNamedColorContrastTest {

    private val lightScheme = appColorScheme(ThemeColorSource.Seed(Color(ThemePreferences.DEFAULT_SEED)), isDark = false)
    private val darkScheme = appColorScheme(ThemeColorSource.Seed(Color(ThemePreferences.DEFAULT_SEED)), isDark = true)

    private val names = listOf("red", "orange", "yellow", "green", "blue", "purple", "pink", "brown", "black", "gray", "white")

    /** WCAG 2.x 对比度：(L1+0.05)/(L2+0.05)，L 为相对亮度。 */
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun assertReadable(name: String, scheme: ColorScheme, label: String) {
        val fg = namedBBCodeColor(name, darkTheme = scheme === darkScheme)
        val onSurface = contrast(fg, scheme.surface)
        val onContainer = contrast(fg, scheme.surfaceContainerHigh)
        assertTrue(onSurface >= 3.0, "$name $label 档对 surface 对比度 ${"%.2f".format(onSurface)} < 3.0")
        assertTrue(onContainer >= 3.0, "$name $label 档对 surfaceContainerHigh 对比度 ${"%.2f".format(onContainer)} < 3.0")
    }

    @Test
    fun `亮色档全部具名色对亮色底至少3比1`() {
        names.forEach { assertReadable(it, lightScheme, "light") }
    }

    @Test
    fun `暗色档全部具名色对暗色底至少3比1`() {
        names.forEach { assertReadable(it, darkScheme, "dark") }
    }
}
