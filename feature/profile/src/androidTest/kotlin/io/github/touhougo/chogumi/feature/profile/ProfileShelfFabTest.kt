package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 收藏分类由右下角的 FAB 菜单切换：画的是页面用的那一个 [ShelfFabMenu]，展开六项、点一项选中并收起。
 * 挂在收藏 tab、对齐与边距由截图对照把关。
 */
@RunWith(AndroidJUnit4::class)
class ProfileShelfFabTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var expanded by mutableStateOf(false)
    private var selected by mutableStateOf(ShelfCategory.entries.first())
    private var selectionEvents = 0

    @Composable
    private fun BoxScope.FabMenu() {
        ShelfFabMenu(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            selected = selected,
            onSelect = {
                selected = it
                selectionEvents++
            },
        )
    }

    private fun setMenu() {
        composeRule.setContent {
            ChogumiTheme {
                Box(Modifier.fillMaxSize()) { FabMenu() }
            }
        }
    }

    @Test
    fun FAB展开后有六项_对应六个分类() {
        setMenu()
        composeRule.onNodeWithTag("SelectionFab").performClick()
        composeRule.waitForIdle()

        ShelfCategory.entries.forEach { category ->
            composeRule.onNodeWithTag(category.fabOptionTestTag).assertIsDisplayed()
        }
        // 第 6 项文案（目录不在收藏 tab，有独立的目录页）。
        composeRule.onNodeWithText("角色与人物").assertIsDisplayed()
    }

    @Test
    fun 点一项后选中并收起菜单() {
        setMenu()
        composeRule.onNodeWithTag("SelectionFab").performClick()
        composeRule.waitForIdle()

        val target = ShelfCategory.SubjectDomain(io.github.touhougo.chogumi.core.model.SubjectType.GAME)
        composeRule.onNodeWithTag(target.fabOptionTestTag).performClick()
        composeRule.waitForIdle()

        assertEquals("选中事件应发生一次", 1, selectionEvents)
        composeRule.onNodeWithTag(target.fabOptionTestTag).assertIsNotDisplayed()
    }
}
