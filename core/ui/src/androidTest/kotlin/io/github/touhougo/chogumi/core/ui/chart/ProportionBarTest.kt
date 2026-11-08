package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 比例条的可见段、间隔与点按身份一致，短尾不会被整条外形裁掉。 */
@RunWith(AndroidJUnit4::class)
class ProportionBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 极端权重的五段都能在画布内看到() {
        setContent(300.dp)

        assertColors(listOf(138f, 280f, 286f, 292f, 298f), width = 300f)
        val pixels = chart().captureToImage().toPixelMap()
        assertTrue("段间保留空白而不是连成一块", pixels[276f.scaledX(300f, pixels.width), pixels.height / 2].closeTo(surface))
    }

    @Test
    fun 极端权重的可见尾段点按显示自己的数() {
        setContent(300.dp)

        chart().performTouchInput { click(Offset(width * 298f / 300f, height / 2f)) }
        composeRule.onNodeWithText(Tips.last()).assertIsDisplayed()
    }

    @Test
    fun 最小预算放不下时每段仍画在压缩后的位置() {
        setContent(14.dp)

        assertColors(listOf(1f, 4f, 7f, 10f, 13f), width = 14f)
        chart().performTouchInput { click(Offset(width * 13f / 14f, height / 2f)) }
        composeRule.onNodeWithText(Tips.last()).assertIsDisplayed()
    }

    @Test
    fun 全零既不画段也不给空段读屏节点() {
        setContent(300.dp, weights = List(5) { 0 })

        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).assertCountEquals(0)
        val pixels = chart().captureToImage().toPixelMap()
        assertTrue("全零保留页面底色", pixels[pixels.width / 2, pixels.height / 2].closeTo(surface))
        chart().performTouchInput { click(center) }
        Tips.forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
    }

    private var surface = Color.Unspecified

    private fun setContent(width: Dp, weights: List<Int> = listOf(9_999, 1, 1, 1, 1)) {
        composeRule.setContent {
            ChogumiTheme {
                surface = MaterialTheme.colorScheme.surface
                Box(Modifier.fillMaxSize().background(surface), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(width)) {
                        ProportionBar(
                            segments = weights.mapIndexed { index, weight -> ProportionSegment(weight, Colors[index], Tips[index]) },
                            description = Description,
                            height = 44.dp,
                        )
                    }
                }
            }
        }
    }

    private fun chart() = composeRule.onNodeWithContentDescription(Description)

    private fun assertColors(centers: List<Float>, width: Float) {
        val pixels = chart().captureToImage().toPixelMap()
        centers.forEachIndexed { index, x ->
            val actual = pixels[x.scaledX(width, pixels.width), pixels.height / 2]
            assertTrue("第 $index 段在 $x 应为 ${Colors[index]}，实际 $actual", actual.closeTo(Colors[index]))
        }
    }

    private fun Float.scaledX(width: Float, pixelWidth: Int) = (this / width * pixelWidth).toInt().coerceIn(0, pixelWidth - 1)

    private fun Color.closeTo(other: Color) =
        abs(red - other.red) < 0.01f && abs(green - other.green) < 0.01f && abs(blue - other.blue) < 0.01f

    private companion object {
        const val Description = "比例条绘制与点按"
        val Tips = listOf("首段 9999", "第二段 1", "第三段 1", "第四段 1", "尾段 1")
        val Colors = listOf(Color.Red, Color.Green, Color.Blue, Color.Cyan, Color.Magenta)
    }
}
