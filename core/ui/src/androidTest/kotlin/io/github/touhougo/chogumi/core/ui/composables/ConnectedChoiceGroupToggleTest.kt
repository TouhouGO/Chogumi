package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [ConnectedChoiceGroup] 的「可以一个都不选」（给了 onDeselect：再点已选中的格收回选择）与每格的数字徽标
 * （进度页筛选面板的分类维度：点一下展开、再点收起，有已选取值的带数字）。
 */
class ConnectedChoiceGroupToggleTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = listOf("形式", "来源", "题材", "地区", "受众")
    private var selected by mutableStateOf<String?>(null)

    private fun setGroup(width: Int = 380, fontScale: Float = 1f, badge: (String) -> Int = { 0 }) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.width(width.dp)) {
                        ConnectedChoiceGroup(
                            options = options,
                            selected = selected,
                            label = { it },
                            onSelect = { selected = it },
                            onDeselect = { selected = null },
                            badge = badge,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun 一开始一个都不选_点一下选上_再点收回() {
        setGroup()
        options.forEach { compose.onNodeWithText(it).assertIsNotSelected() }

        compose.onNodeWithText("题材").performClick()
        assertEquals("题材", selected)
        compose.onNodeWithText("题材").assertIsSelected()

        compose.onNodeWithText("地区").performClick()
        assertEquals("同一时间只选一个", "地区", selected)

        compose.onNodeWithText("地区").performClick()
        assertNull("再点已选中的格收回选择", selected)
        compose.onNodeWithText("地区").assertIsNotSelected()
    }

    @Test
    fun 有数字的格画出徽标() {
        setGroup(badge = { if (it == "题材") 2 else 0 })
        compose.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun 小屏放大字号时带徽标的文字与徽标都在按钮里() {
        // 380dp 屏去掉两侧 16dp、字号 1.1：五格都带徽标时排法必须把徽标宽度算进去，放不下的收进组尾「更多」；
        // 只按文字排的话按钮里留给徽标的地方不够，徽标被挤扁、被按钮裁掉
        setGroup(width = 348, fontScale = 1.1f, badge = { 12 })
        val shown = options.filter { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("至少前几格排在组里", shown.size >= 3)
        val badgeNodes = compose.onAllNodesWithText("12", useUnmergedTree = true).fetchSemanticsNodes()
        badgeNodes.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            assertTrue(
                "徽标数字被挤窄（${layout.size.width} / ${layout.multiParagraph.maxIntrinsicWidth} px）",
                layout.size.width >= layout.multiParagraph.maxIntrinsicWidth - 0.5f,
            )
        }
        val badges = badgeNodes.map { it.boundsInRoot }
        shown.forEach { label ->
            val button = compose.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode().boundsInRoot
            val text = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val badge = badges.single { it.center.x in button.left..button.right }
            assertTrue("「$label」的文字超出按钮（$text / $button）", text.left >= button.left && text.right <= button.right)
            assertTrue("「$label」的徽标超出按钮（$badge / $button）", badge.right <= button.right + 0.5f)
        }
    }
}
