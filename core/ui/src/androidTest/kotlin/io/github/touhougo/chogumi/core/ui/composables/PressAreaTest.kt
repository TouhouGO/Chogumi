package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** PressArea 的真实指针状态：按下、没有点击、按住时水波纹铺满整块。 */
@RunWith(AndroidJUnit4::class)
class PressAreaTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 彩色容器不裁掉圆角外内容() {
        val marker = Color.Red
        composeRule.setContent {
            ChogumiTheme {
                PressArea(
                    onClick = {},
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.size(64.dp).testTag(NODE),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Box(Modifier.size(16.dp).background(marker))
                }
            }
        }

        val corner = composeRule.onNodeWithTag(NODE).captureToImage().asAndroidBitmap().getPixel(2, 2)
        assertEquals("圆角外的正文必须保持可见", marker.toArgb(), corner)
    }

    @Test
    fun 彩色点击路径至少48dp而透明默认路径不被放大() {
        composeRule.setContent {
            ChogumiTheme {
                Column {
                    PressArea(
                        onClick = {},
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.testTag(ENABLED),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) { Box(Modifier.size(40.dp)) }
                    PressArea(
                        onClick = {},
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.testTag(TRANSPARENT),
                    ) { Box(Modifier.size(40.dp)) }
                }
            }
        }

        val minTouchPx = with(composeRule.density) { 48.dp.toPx() }
        assertEquals(minTouchPx, composeRule.onNodeWithTag(ENABLED).fetchSemanticsNode().touchBoundsInRoot.height, 0.5f)
        assertEquals(40.dp.value, composeRule.onNodeWithTag(TRANSPARENT).getUnclippedBoundsInRoot().height.value, 0.5f)
    }

    @Test
    fun 按住时水波纹铺满整块而不只盖住内容() {
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                Column(Modifier.background(Color.White).padding(8.dp)) {
                    RippleBlock(color = MaterialTheme.colorScheme.primaryContainer, tag = ENABLED)
                    RippleBlock(color = Color.Unspecified, tag = TRANSPARENT)
                }
            }
        }

        listOf(ENABLED, TRANSPARENT).forEach { tag ->
            val node = composeRule.onNodeWithTag(tag)
            // 右下角往里 16dp：在圆角里面，离左上那一小块内容很远
            fun farCorner(): Color {
                val pixels = node.captureToImage().toPixelMap()
                val inset = with(composeRule.density) { 16.dp.roundToPx() }
                return pixels[pixels.width - inset, pixels.height - inset]
            }
            val before = farCorner()
            node.performTouchInput { down(Offset(width - 16.dp.toPx(), height - 16.dp.toPx())) }
            // 平台水波纹按真实时间展开；按住不放时它一直留着，等到它出现是单调的
            composeRule.waitUntil("$tag 右下角出现水波纹", RIPPLE_BUDGET_MS) { farCorner() != before }
            assertNotEquals("$tag：按住时块右下角（内容外）有水波纹", before, farCorner())
            node.performTouchInput { cancel() }
            composeRule.waitForIdle()
        }
    }

    @Test
    fun 不传容器参数时是透明的可点块() {
        var clicks = 0
        composeRule.setContent {
            ChogumiTheme {
                PressArea(
                    onClick = { clicks++ },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(120.dp).testTag(NODE),
                ) { Text("透明块") }
            }
        }

        composeRule.onNodeWithTag(NODE).assertHasClickAction().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun 按下产生按压态并在抬手后执行一次点击() {
        val interactions = mutableListOf<Interaction>()
        var clicks = 0
        composeRule.setContent {
            val source = remember { MutableInteractionSource() }
            LaunchedEffect(source) { source.interactions.collect(interactions::add) }
            ChogumiTheme {
                PressArea(
                    onClick = { clicks++ },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(120.dp).testTag(NODE),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    interactionSource = source,
                ) { Text("入口") }
            }
        }

        composeRule.onNodeWithTag(NODE).performTouchInput { down(center) }
        composeRule.waitForIdle()
        assertTrue("按下应发出 PressInteraction.Press", interactions.any { it is PressInteraction.Press })
        assertEquals("抬手前不能执行点击", 0, clicks)

        composeRule.onNodeWithTag(NODE).performTouchInput { up() }
        composeRule.waitForIdle()
        assertTrue("抬手应发出 PressInteraction.Release", interactions.any { it is PressInteraction.Release })
        assertEquals("一次按压只执行一次点击", 1, clicks)
    }

    @Test
    fun 没有点击时不带点击语义也不产生按压态() {
        val interactions = mutableListOf<Interaction>()
        composeRule.setContent {
            val source = remember { MutableInteractionSource() }
            LaunchedEffect(source) { source.interactions.collect(interactions::add) }
            ChogumiTheme {
                PressArea(
                    onClick = null,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(120.dp).testTag(NODE),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    interactionSource = source,
                ) { Text("无点击") }
            }
        }

        composeRule.onNodeWithTag(NODE).assertHasNoClickAction().performTouchInput { down(center) }
        composeRule.onNodeWithTag(NODE).performTouchInput { up() }
        composeRule.waitForIdle()
        assertTrue("没有点击时不能产生按压态", interactions.none { it is PressInteraction.Press })
    }

    private companion object {
        const val NODE = "press_area"
        const val ENABLED = "enabled_area"
        const val TRANSPARENT = "transparent_area"

        /** 等水波纹出现的上限：平台水波纹展开约 225ms，留足余量。 */
        const val RIPPLE_BUDGET_MS = 3_000L
    }
}

/** 内容只占左上一小块（40dp）的可点块，宽高同合集方块；[color] 为 [Color.Unspecified] 时是透明块。 */
@Composable
private fun RippleBlock(color: Color, tag: String) {
    PressArea(
        onClick = {},
        shape = RoundedCornerShape(12.dp),
        color = color,
        contentColor = Color.Black,
        modifier = Modifier.size(156.dp, 118.dp).testTag(tag),
    ) {
        Box(Modifier.size(40.dp))
    }
}
