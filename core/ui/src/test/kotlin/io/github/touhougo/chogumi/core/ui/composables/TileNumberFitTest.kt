package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 格子里的集号整个显示：最窄的格子（网格的最小格宽，也是收藏面板进度尺的格子）里，小数集号「1202.5」在 1 倍、
 * 1.1 倍（小屏验收字号）与 2 倍字号下都在一行内放下，不在小数点后断成「1202.」，也不裁掉末位。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileNumberFitTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `最窄的格子里小数集号在各字号下都整个显示`() {
        var fontScale by mutableFloatStateOf(1f)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                // 系统字号一变 Activity 就重建，格子从头量；原地只换 LocalDensity 时 autoSize 沿用上次的字号，所以每个字号重新组合。
                key(fontScale) {
                    ChogumiTheme {
                        Box(Modifier.size(GridCell.EpisodeTile.minWidth)) {
                            TileLabel(number = { TileNumber(text = DECIMAL_SORT, emphasized = true, color = Color.Black) })
                        }
                    }
                }
            }
        }
        listOf(1f, 1.1f, 2f).forEach { scale ->
            fontScale = scale
            composeRule.waitForIdle()
            val layout = numberLayout()
            assertEquals("$scale 倍字号：一行写完整个集号", DECIMAL_SORT.length, layout.getLineEnd(0))
            assertFalse("$scale 倍字号：集号不超出格子留给它的宽", layout.hasVisualOverflow)
        }
    }

    private fun numberLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(DECIMAL_SORT).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }

    private companion object {
        /** 柯南特别篇的集号：五位数字加小数点，格子里最长的一种。 */
        const val DECIMAL_SORT = "1202.5"
    }
}
