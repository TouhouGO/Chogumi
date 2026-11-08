package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 拥挤时尺下文字可以省略，全部均分仍从同一图表气泡读出。 */
@RunWith(AndroidJUnit4::class)
class ScoreRulerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `长标签在两倍字号窄尺上仍可读屏与点按获取完整文字`() {
        val labels = listOf(
            "Completed collection average 8.50",
            "Watching collection average 8.50",
            "On hold collection average 8.50",
        )
        val allLabels = labels.joinToString("\n")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChogumiTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ScoreRuler(labels.map { ScoreMark(it, 8.5, own = false, color = Color.Red) }, "均分尺", Modifier.width(300.dp))
                    }
                }
            }
        }
        val chart = compose.onNodeWithContentDescription("均分尺")
        compose.onNodeWithContentDescription(allLabels).assertExists()
        compose.mainClock.autoAdvance = false
        chart.performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(400)
        val tip = compose.onNodeWithText(allLabels).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        tip.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse("完整均分文字不能截掉末行", layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) {
            assertFalse("第 ${line + 1} 行不能省略", layout.isLineEllipsized(line))
            assertTrue("第 ${line + 1} 行字形不能出框", layout.getLineLeft(line) >= 0f && layout.getLineRight(line) <= layout.size.width)
        }
    }
}
