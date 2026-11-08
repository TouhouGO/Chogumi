package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 顶栏标题当上级的去处时：上级没到画占位（不念名字、不可点），到了才是名字加可点的去处。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageScaffoldTitleLinkTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `上级没到时顶栏画占位 只有返回键可点 名字不出现`() {
        composeRule.setContent {
            ChogumiTheme { PageScaffold(title = TITLE, onBack = {}, titleLoading = true) {} }
        }
        composeRule.onNodeWithText(TITLE).assertDoesNotExist()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun `上级到了顶栏是名字和可点的去处`() {
        var opened = 0
        composeRule.setContent {
            ChogumiTheme {
                PageScaffold(title = TITLE, onBack = {}, titleLink = TitleLink(onClick = { opened++ }) { Box(Modifier.size(TitleLinkImageSize)) }) {}
            }
        }
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(2)
        composeRule.onNodeWithText(TITLE).performClick()
        composeRule.runOnIdle { assertEquals("点标题打开上级", 1, opened) }
    }

    @Test
    fun `没有上级也没在加载时顶栏是页名`() {
        composeRule.setContent { ChogumiTheme { PageScaffold(title = TITLE, onBack = {}) {} } }
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    private companion object {
        const val TITLE = "某个小组"
    }
}
