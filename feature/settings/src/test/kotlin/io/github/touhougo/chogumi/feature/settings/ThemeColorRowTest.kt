package io.github.touhougo.chogumi.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.ThemeColorMode
import io.github.touhougo.chogumi.core.model.ThemePreferences
import io.github.touhougo.chogumi.core.model.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 配色一行：点格子只换这个来源里的选择，换来源不丢另外两种的选择。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class ThemeColorRowTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(value: ThemePreferences): MutableList<ThemePreferences> {
        val changes = mutableListOf<ThemePreferences>()
        compose.setContent {
            val rows = themeColorRows(value) { changes += it }
            Column { rows.forEachIndexed { index, row -> row(index, rows.size) } }
        }
        return changes
    }

    @Test
    fun 点一格配色方案只换方案_种子色留着() {
        val start = ThemePreferences(ThemeColorMode.PRESET, seedArgb = BLUE, preset = ThemePreset.INK)
        val changes = show(start)

        compose.onNode(hasText("孔雀") and hasClickAction()).performClick()

        assertEquals(listOf(start.copy(preset = ThemePreset.PEACOCK)), changes)
    }

    @Test
    fun 从主题色换到配色方案_两边的选择都留着() {
        val start = ThemePreferences(ThemeColorMode.SEED, seedArgb = BLUE, preset = ThemePreset.PINE)
        val changes = show(start)

        compose.onNode(hasText("配色方案") and hasClickAction()).performClick()

        assertEquals(listOf(start.copy(mode = ThemeColorMode.PRESET)), changes)
    }

    @Test
    fun 点一个种子色只换种子色() {
        val start = ThemePreferences(ThemeColorMode.SEED, preset = ThemePreset.PINE)
        val changes = show(start)

        compose.onNode(hasText("蓝色") and hasClickAction()).performClick()

        assertEquals(listOf(start.copy(seedArgb = BLUE)), changes)
    }

    private companion object {
        const val BLUE = 0xFF526CFDL
    }
}
