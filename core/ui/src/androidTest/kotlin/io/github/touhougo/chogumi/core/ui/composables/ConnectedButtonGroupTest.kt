package io.github.touhougo.chogumi.core.ui.composables

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.down
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 单选连通按钮组（[ConnectedChoiceGroup]，走全仓唯一的 [ConnectedButtonGroup] 骨架）是全站分段控件的唯一实现
 * （社区好友 / 全站、每日放送、生涯类型、设置里的互斥单选都走它）。这里锁三条行为契约：
 *
 * 1. 点非选中项 → 回调该项、选中态切换（语义 `selected`）；
 * 2. 文字放得下就显示文字；整组放不下时**整组**改成图标版（用静止宽度一次判定），每项的 `contentDescription` 仍是完整标签；
 * 3. 按压回弹过程中各项都在容器内（不被裁出），松手后宽度回到静止宽度。
 *
 * 每项按 `contentDescription`（即完整标签）寻址——生产代码不挂 per-item testTag，这是它的唯一稳定标识。
 * 按压用「同一 `performTouchInput` 只按下、不抬起」持住 pointer（输入分发 dispose 只回收未派发事件、不撤手势），
 * 配合 `mainClock.autoAdvance = false` 逐帧推进。
 */
@RunWith(AndroidJUnit4::class)
class ConnectedButtonGroupTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val options = listOf("好友", "全站")
    private var selected by mutableStateOf(options.first())

    /** [width] 是外层容器宽（dp）；[fontScale] 模拟系统字体放大。 */
    private fun setGroup(width: Int = 400, fontScale: Float = 1f, icon: Boolean = true) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.width(width.dp)) {
                        ConnectedChoiceGroup(
                            options = options,
                            selected = selected,
                            label = { it },
                            onSelect = { selected = it },
                            icon = if (icon) ({ _, _ -> R.drawable.person_24 }) else null,
                            modifier = Modifier.testTag(GROUP),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** 按完整标签寻址某一项（生产代码把标签挂在每项的 contentDescription 上）。 */
    private fun item(label: String) = composeRule.onNodeWithContentDescription(label, useUnmergedTree = true)

    private fun visibleText(label: String) = composeRule.onAllNodesWithText(label, useUnmergedTree = true)

    @Test
    fun 点非选中项回调该项并切换选中态() {
        selected = "好友"
        setGroup()

        item("好友").assertIsSelected()
        item("全站").assertIsNotSelected()

        item("全站").performClick()
        composeRule.waitForIdle()

        assertEquals("点非选中项应回调它", "全站", selected)
        item("全站").assertIsSelected()
        item("好友").assertIsNotSelected()
    }

    @Test
    fun 文字放得下时两项都带完整标签且显示文字()  {
        setGroup(width = 400)
        item("好友").assertContentDescriptionEquals("好友")
        item("全站").assertContentDescriptionEquals("全站")
        visibleText("好友").assertCountEquals(1)
        visibleText("全站").assertCountEquals(1)
    }

    @Test
    fun 字体放大两倍放不下时整组改图标版_每项仍带完整标签() {
        setGroup(width = 120, fontScale = 2f)
        // 整组一次判定：两项都只留图标，都仍带完整标签的 contentDescription。
        item("好友").assertContentDescriptionEquals("好友")
        item("全站").assertContentDescriptionEquals("全站")
        visibleText("好友").assertCountEquals(0)
        visibleText("全站").assertCountEquals(0)
    }

    @Test
    fun 按压回弹过程中各项不被裁出容器() {
        setGroup(width = 400)
        val group = composeRule.onNodeWithTag(GROUP, useUnmergedTree = true).getUnclippedBoundsInRoot()

        composeRule.mainClock.autoAdvance = false
        // 只按下不抬起：持住 pointer，逐帧推进弹簧动画。
        item("好友").performTouchInput { down(center) }
        repeat(20) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            listOf("好友", "全站").forEach { label ->
                val b = item(label).getUnclippedBoundsInRoot()
                assertTrue("第 $frame 帧「$label」左缘被裁出容器 $group：$b", b.left.value >= group.left.value - 0.5f)
                assertTrue("第 $frame 帧「$label」右缘被裁出容器 $group：$b", b.right.value <= group.right.value + 0.5f)
            }
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    @Test
    fun 松手后各项宽度回到静止宽度() {
        setGroup(width = 400)
        fun widths() = listOf("好友", "全站").map { item(it).getUnclippedBoundsInRoot().width.value }
        val rest = widths()

        composeRule.mainClock.autoAdvance = false
        item("好友").performTouchInput { down(center) }
        repeat(4) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        val pressed = widths()
        assertTrue("按下后应有某项比静止时更宽（弹簧加宽生效）：rest=$rest pressed=$pressed", pressed.zip(rest).any { (p, r) -> p > r + 1f })

        // 抬起后跑完弹簧，各项宽度回到静止宽度。
        item("好友").performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        rest.forEachIndexed { index, r ->
            assertEquals("第 ${index + 1} 项松手后宽度应回到静止宽度", r, widths()[index], 0.5f)
        }
    }

    /**
     * 选项增减时相邻格的圆角连续过渡：三格「甲 乙 丙」里藏起「丙」，「乙」由中格变尾格，右上角从小圆角变成全圆。
     * 逐帧量「乙」右上角那一块里圆角之外的像素：必须经过中间值，不能一帧跳到终点
     * （官方按钮按形状组对象记动画，组一换就从新形状起步）。
     */
    @Test
    fun 选项增减时相邻格的圆角连续过渡() {
        val options = listOf("甲", "乙", "丙")
        var shown by mutableStateOf(options.toSet())
        var container = Color.Unspecified
        composeRule.setContent {
            ChogumiTheme {
                container = MaterialTheme.colorScheme.surfaceContainerHigh
                Box(Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface)) {
                    ConnectedButtonGroup(
                        options = options,
                        selected = "甲",
                        onSelect = {},
                        visible = { it in shown },
                        itemModifier = { Modifier.testTag("cbg_$it") },
                    ) { option, _ -> Text(option) }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        fun corner() = cornerOutsidePixels(composeRule.onNodeWithTag("cbg_乙", useUnmergedTree = true).captureToImage().asAndroidBitmap(), container)

        val start = corner()
        composeRule.runOnIdle { shown = setOf("甲", "乙") }
        val outside = (0 until 40).map {
            composeRule.mainClock.advanceTimeByFrame()
            corner()
        }
        composeRule.mainClock.autoAdvance = true
        val end = outside.last()
        assertTrue("尾格的右上角应比中格圆：起 $start → 终 $end", end > start + 20)
        val between = outside.count { it in (start + 5) until (end - 5) }
        assertTrue("圆角要经过中间值、不能一帧跳到终点：$outside", between >= 2)
    }

    /** 容器右上角 [CORNER] 见方的一块里，不是容器色的像素有几个（圆角越大越多）。从容器上缘量起：按钮的触控区比容器高。 */
    private fun cornerOutsidePixels(bitmap: Bitmap, container: Color): Int {
        val target = container.toArgb()
        fun isContainer(x: Int, y: Int) = bitmap.getPixel(x, y).let { p -> listOf(16, 8, 0).all { abs((p shr it and 0xFF) - (target shr it and 0xFF)) <= 6 } }
        val top = (0 until bitmap.height).first { y -> (0 until bitmap.width).count { x -> isContainer(x, y) } > bitmap.width / 2 }
        val size = (CORNER.value * composeRule.density.density).toInt()
        return (top until top + size).sumOf { y -> (bitmap.width - size until bitmap.width).count { x -> !isContainer(x, y) } }
    }

    private companion object {
        const val GROUP = "cbg_group"
        val CORNER = 16.dp
    }
}
