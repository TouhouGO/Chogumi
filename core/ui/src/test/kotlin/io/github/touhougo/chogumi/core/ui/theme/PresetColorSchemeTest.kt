package io.github.touhougo.chogumi.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import io.github.touhougo.chogumi.core.model.ThemePreset
import kotlin.test.Test
import org.junit.Assert.assertTrue

/** 手工定的配色方案：每对前景与底色在浅色、深色下都够对比度，明暗判定（[isDark]）与所在的档一致。 */
class PresetColorSchemeTest {

    @Test
    fun `每套方案的前景与底色都够对比度`() {
        val failures = mutableListOf<String>()
        for (preset in ThemePreset.entries) for (dark in listOf(false, true)) {
            val scheme = presetColorScheme(preset, dark)
            for ((name, pair) in pairsOf(scheme)) {
                val (fg, bg, min) = pair
                val ratio = contrastOf(fg, bg)
                if (ratio < min) failures += "$preset ${if (dark) "深" else "浅"} $name：%.2f，应至少 %.1f".format(ratio, min)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `浅色方案判为浅色_深色方案判为深色`() {
        val failures = ThemePreset.entries.flatMap { preset ->
            listOf(false, true).filter { dark -> presetColorScheme(preset, dark).isDark() != dark }.map { "$preset ${if (it) "深" else "浅"}" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun pairsOf(s: ColorScheme): List<Pair<String, Triple<Color, Color, Double>>> = listOf(
        "正文" to Triple(s.onSurface, s.surface, BODY_CONTRAST),
        "次要文字" to Triple(s.onSurfaceVariant, s.surfaceContainerHighest, MIN_TEXT_CONTRAST),
        "主色上的字" to Triple(s.onPrimary, s.primary, MIN_TEXT_CONTRAST),
        "主色容器上的字" to Triple(s.onPrimaryContainer, s.primaryContainer, MIN_TEXT_CONTRAST),
        "次色上的字" to Triple(s.onSecondary, s.secondary, MIN_TEXT_CONTRAST),
        "次色容器上的字" to Triple(s.onSecondaryContainer, s.secondaryContainer, MIN_TEXT_CONTRAST),
        "点缀色上的字" to Triple(s.onTertiary, s.tertiary, MIN_TEXT_CONTRAST),
        "点缀色容器上的字" to Triple(s.onTertiaryContainer, s.tertiaryContainer, MIN_TEXT_CONTRAST),
        "错误色上的字" to Triple(s.onError, s.error, MIN_TEXT_CONTRAST),
        "错误容器上的字" to Triple(s.onErrorContainer, s.errorContainer, MIN_TEXT_CONTRAST),
        "反色底上的字" to Triple(s.inverseOnSurface, s.inverseSurface, MIN_TEXT_CONTRAST),
        "反色底上的主色" to Triple(s.inversePrimary, s.inverseSurface, MIN_GRAPHIC_CONTRAST),
        "背景上的主色" to Triple(s.primary, s.surface, MIN_TEXT_CONTRAST),
        "背景上的点缀色" to Triple(s.tertiary, s.surface, MIN_TEXT_CONTRAST),
        "背景上的错误色" to Triple(s.error, s.surface, MIN_GRAPHIC_CONTRAST),
        "背景上的描边" to Triple(s.outline, s.surface, MIN_GRAPHIC_CONTRAST),
    )

    private companion object {
        const val BODY_CONTRAST = 7.0
        const val MIN_GRAPHIC_CONTRAST = 3.0
    }
}
