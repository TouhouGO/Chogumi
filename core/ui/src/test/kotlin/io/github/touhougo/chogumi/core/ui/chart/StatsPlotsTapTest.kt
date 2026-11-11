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

/**
 * 统计图点哪里出哪一块的提示，点的是页面画的那张图：密的折线点在一个点上出这个点的；
 * 各组评分分布的几条线交叠着横贯全宽，点线不出某一组的，点轴下的组名才出这一组的。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class StatsPlotsTapTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `折线的点密到互相盖着时_点在一个点上出这个点的提示`() {
        // 值都一样：每个点都在同一高度，横向每 4.5dp 一个，点的方块（10dp）盖着左右邻点
        val points = List(60) { i -> PlotPoint("${i + 1}", 7.0, "第 ${i + 1} 集 7.0 分") }
        compose.setContent { ChogumiTheme { Box(Modifier.width(300.dp)) { StatsLine(points) } } }
        compose.waitForIdle()
        val chart = compose.onNodeWithContentDescription(points.joinToString { it.tip })
        val origin = chart.fetchSemanticsNode().boundsInRoot.topLeft

        listOf(1, 30, 58).forEach { index ->
            val column = compose.onNodeWithContentDescription(points[index].tip).fetchSemanticsNode().boundsInRoot
            // 值都相同时线在画线区域的正中：上留 16dp、下留 24dp，图高 136dp
            chart.performTouchInput { click(Offset(column.center.x - origin.x, 64f)) }
            compose.onNodeWithText(points[index].tip).assertExists()
        }
    }

    private val groupTips = listOf("放送前 12 人", "放送中 340 人", "完结后 90 人")

    private fun showDistributions() {
        val counts = listOf(
            listOf(0, 0, 1, 1, 2, 3, 3, 1, 1, 0),
            listOf(4, 2, 6, 10, 30, 60, 90, 80, 40, 18),
            listOf(1, 1, 2, 4, 9, 18, 25, 20, 7, 3),
        )
        compose.setContent {
            ChogumiTheme { Box(Modifier.width(300.dp)) { StatsDistributions(counts, listOf("放送前", "放送中", "完结后"), groupTips) } }
        }
        compose.waitForIdle()
    }

    @Test
    fun `分布图点在线上不出任何一组的提示`() {
        showDistributions()

        compose.onNodeWithContentDescription(groupTips.joinToString()).performTouchInput { click(Offset(width * 0.5f, height * 0.3f)) }

        groupTips.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun `分布图点轴下的组名出这一组的提示`() {
        showDistributions()
        val chart = compose.onNodeWithContentDescription(groupTips.joinToString())

        chart.performTouchInput { click(Offset(width * 0.5f / 3, height - 6f)) }
        compose.onNodeWithText(groupTips[0]).assertExists()

        chart.performTouchInput { click(Offset(width * 2.5f / 3, height - 6f)) }
        compose.onNodeWithText(groupTips[2]).assertExists()
    }
}
