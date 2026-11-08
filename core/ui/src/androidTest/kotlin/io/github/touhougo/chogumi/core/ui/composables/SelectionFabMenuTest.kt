package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.res.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectionFabMenuTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val options = listOf("全部", "动画", "三次元")

    private var expanded by mutableStateOf(false)
    private var selected by mutableStateOf("全部")
    private var underneathClicks by mutableIntStateOf(0)

    private fun setMenu() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("Underneath")
                        .clickable { underneathClicks++ },
                )
                SelectionFabMenu(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    options = options,
                    selected = selected,
                    iconRes = { R.drawable.close_24 },
                    label = { it },
                    onSelect = { selected = it },
                    collapsedDescription = "选项菜单",
                    itemTestTag = { "Item-$it" },
                )
            }
        }
    }

    @Test
    fun `展开后各菜单项等宽_三字选项不比两字选项宽`() {
        setMenu()
        composeRule.onNodeWithTag("SelectionFab").performClick()
        composeRule.waitForIdle()

        val widths = options.map { composeRule.onNodeWithTag("Item-$it").getUnclippedBoundsInRoot().width }
        assertEquals("菜单项宽度应一致：$widths", 1, widths.distinct().size)
    }

    @Test
    fun `点菜单外收起菜单_且这次点击照常传给下层内容`() {
        setMenu()
        composeRule.onNodeWithTag("SelectionFab").performClick()
        composeRule.waitForIdle()

        // 左上角远离右下角菜单
        composeRule.onNodeWithTag("Underneath").performTouchInput { click(Offset(10f, 10f)) }
        composeRule.waitForIdle()

        assertFalse("点菜单外应收起菜单", expanded)
        assertEquals("菜单外的点击不得被吞掉", 1, underneathClicks)
        composeRule.onNodeWithTag("Item-动画").assertIsNotDisplayed()
    }

    @Test
    fun `点菜单项照常选中并收起_不被旁听层抢先`() {
        setMenu()
        composeRule.onNodeWithTag("SelectionFab").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("Item-三次元").performClick()
        composeRule.waitForIdle()

        assertEquals("三次元", selected)
        assertFalse(expanded)
        assertEquals("点菜单项不应穿透到下层", 0, underneathClicks)
    }
}
