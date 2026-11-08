package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.ui.graphics.toArgb
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.ui.theme.CONTRAST_TEST_SOURCES
import io.github.touhougo.chogumi.core.ui.theme.MIN_TEXT_CONTRAST
import io.github.touhougo.chogumi.core.ui.theme.appColorScheme
import io.github.touhougo.chogumi.core.ui.theme.contrastOf
import io.github.touhougo.chogumi.core.ui.theme.label
import com.materialkolor.hct.Hct
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏状态色：每种状态在浅色与深色主题下，字和底的对比度（容器上 / 容器、色上 / 色）都够 4.5（M3 小字最低对比度）；
 * 换几种主题色与每种配色方案都成立；同一主题下五种状态彼此不撞色；五种状态（含抛弃）的四个角色都在同一色调档，浅底一样浅。
 */
class CollectionTypeColorsTest {

    @Test
    fun `每种状态浅色深色都够对比度`() {
        val failures = mutableListOf<String>()
        for (source in CONTRAST_TEST_SOURCES) for (dark in listOf(false, true)) {
            val scheme = appColorScheme(source, dark)
            val all = CollectionType.choices.associateWith { collectionTypeColors(it, scheme) } + (null to neutralMarkColors(scheme))
            for ((type, colors) in all) {
                val onContainer = contrastOf(colors.onContainer, colors.container)
                val onColor = contrastOf(colors.onColor, colors.color)
                if (onContainer < MIN_TEXT_CONTRAST || onColor < MIN_TEXT_CONTRAST) {
                    failures += "${source.label()} ${if (dark) "深" else "浅"} ${type ?: "管理"}：容器 %.2f、实色 %.2f".format(onContainer, onColor)
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `同一主题下五种状态的颜色各不相同`() {
        for (source in CONTRAST_TEST_SOURCES) for (dark in listOf(false, true)) {
            val scheme = appColorScheme(source, dark)
            val containers = CollectionType.choices.map { collectionTypeColors(it, scheme).container }
            val colors = CollectionType.choices.map { collectionTypeColors(it, scheme).color }
            assertEquals(source.label(), CollectionType.choices.size, containers.toSet().size)
            assertEquals(source.label(), CollectionType.choices.size, colors.toSet().size)
        }
    }

    @Test
    fun `五种状态的四个角色在同一色调档_抛弃也不例外`() {
        val failures = mutableListOf<String>()
        for (source in CONTRAST_TEST_SOURCES) for (dark in listOf(false, true)) {
            val scheme = appColorScheme(source, dark)
            val expected = if (dark) listOf(80.0, 20.0, 30.0, 90.0) else listOf(40.0, 100.0, 90.0, 30.0)
            for (type in CollectionType.choices) {
                val c = collectionTypeColors(type, scheme)
                val tones = listOf(c.color, c.onColor, c.container, c.onContainer).map { Hct.fromInt(it.toArgb()).tone }
                tones.zip(expected).forEach { (actual, want) ->
                    if (abs(actual - want) > TONE_TOLERANCE) {
                        failures += "${source.label()} ${if (dark) "深" else "浅"} $type：色调 %.1f，应为 %.0f".format(actual, want)
                    }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private companion object {
        /** 取色调板再转回 RGB 的舍入误差。 */
        const val TONE_TOLERANCE = 1.0
    }
}
