package io.github.touhougo.chogumi.feature.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs

/** 四个个人页比例图在真实区块中保留短尾，图例、可见色块与尾段气泡指向同一数据。 */
@RunWith(AndroidJUnit4::class)
class CareerProportionBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var darkTheme by mutableStateOf(false)
    private lateinit var expectations: List<CareerProportionExpectation>

    @Test
    fun 一倍字号下浅深色的四个图都保留可见且可点的短尾() = assertCharts(fontScale = 1f)

    @Test
    fun 一点一倍字号下浅深色的四个图都保留可见且可点的短尾() = assertCharts(fontScale = 1.1f)

    @Test
    fun 两倍字号下浅深色的四个图都保留可见且可点的短尾() = assertCharts(fontScale = 2f)

    private fun assertCharts(fontScale: Float) {
        composeRule.setContent {
            CareerProportionHost(fontScale, darkTheme) {
                expectations = careerProportionExpectations()
                CareerProportionSections()
            }
        }
        listOf(false, true).forEach { dark ->
            composeRule.runOnIdle { darkTheme = dark }
            composeRule.waitForIdle()
            expectations.forEach { expected ->
                // 图本身（标记是盖在图上的可点节点）
                val chart = composeRule.onNode(hasContentDescription(expected.title, substring = true) and !hasClickAction())
                chart.performScrollTo().assertIsDisplayed()
                val bounds = chart.fetchSemanticsNode().boundsInRoot
                val marks = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
                    .filter { bounds.contains(it.boundsInRoot.center) }
                    .sortedBy { it.boundsInRoot.left }
                assertEquals("每个正值档都有读屏节点", expected.colors.size, marks.size)
                assertEquals("末档仍属于原数据", expected.tail, marks.last().config[SemanticsProperties.ContentDescription].single())
                val pixels = chart.captureToImage().toPixelMap()
                val runs = mutableListOf<Color>()
                for (x in 0 until pixels.width) {
                    val actual = pixels[x, pixels.height / 2]
                    val color = expected.colors.firstOrNull { actual.closeTo(it) } ?: continue
                    if (runs.lastOrNull() != color) runs += color
                }
                assertEquals("浅深色下所有档都在画布内出现，顺序不变", expected.colors, runs)
                val tailX = (pixels.width - 1 downTo 0).first { x -> pixels[x, pixels.height / 2].closeTo(expected.colors.last()) }
                chart.performTouchInput { click(Offset(tailX + 0.5f, height / 2f)) }
                composeRule.onNodeWithText(expected.tail).assertIsDisplayed()
            }
        }
    }

    private fun Color.closeTo(other: Color) =
        abs(red - other.red) < 0.01f && abs(green - other.green) < 0.01f && abs(blue - other.blue) < 0.01f
}
