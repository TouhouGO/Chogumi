package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 各组评分分布的几条线交叠着横贯全宽：点线不出某一组的提示，点轴下的组名才出这一组的。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class StatsDistributionsHitTest {

    @get:Rule
    val compose = createComposeRule()

    private val tips = listOf("放送前 12 人", "放送中 340 人", "完结后 90 人")
    private val chart get() = compose.onNodeWithContentDescription(tips.joinToString())

    private fun show() {
        val counts = listOf(
            listOf(0, 0, 1, 1, 2, 3, 3, 1, 1, 0),
            listOf(4, 2, 6, 10, 30, 60, 90, 80, 40, 18),
            listOf(1, 1, 2, 4, 9, 18, 25, 20, 7, 3),
        )
        compose.setContent {
            ChogumiTheme { Box(Modifier.width(300.dp)) { StatsDistributions(counts, listOf("放送前", "放送中", "完结后"), tips) } }
        }
        compose.waitForIdle()
    }

    @Test
    fun `点在线上不出任何一组的提示`() {
        show()

        chart.performTouchInput { click(Offset(width * 0.5f, height * 0.3f)) }

        tips.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun `点轴下的组名出这一组的提示`() {
        show()

        chart.performTouchInput { click(Offset(width * 0.5f / 3, height - 6f)) }
        compose.onNodeWithText(tips[0]).assertExists()

        chart.performTouchInput { click(Offset(width * 2.5f / 3, height - 6f)) }
        compose.onNodeWithText(tips[2]).assertExists()
    }
}
