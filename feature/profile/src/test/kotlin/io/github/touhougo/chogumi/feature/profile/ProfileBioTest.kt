package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** ⓘ 面板里的简介：全文展开、整块不可点（只有 @ 与链接可点），@ 的点击接到页面。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileBioTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun clickableCount(bbcode: String): Int {
        composeRule.setContent {
            ChogumiTheme {
                Box(Modifier.width(360.dp)) {
                    ProfileBio(ast = BBCodeParser.parse(bbcode), onUserClick = {})
                }
            }
        }
        composeRule.waitForIdle()
        return composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size
    }

    @Test
    fun 长简介整块不可点() {
        val lines = (1..20).map { "第${it}行" }
        assertEquals(0, clickableCount(lines.joinToString("\n")))
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .forEach { node -> node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) }
        assertTrue("长简介应有可核对的文字布局", layouts.isNotEmpty())
        assertEquals("简介每一行都进入真实文字布局", lines, layouts.flatMap { it.layoutInput.text.text.split('\n') }.filter { it.isNotBlank() })
        assertFalse("长简介必须全文展开，不能被单行或行数上限截断", layouts.any { it.multiParagraph.didExceedMaxLines })
    }

    @Test
    fun 资料面板里点简介中的用户_回调收到用户名() {
        var clicked: String? = null
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    ProfileInfoSheet(
                        user = UserBo(id = 1, username = "me", nickname = "我", bio = "[user=alice]爱丽丝[/user]"),
                        onUserClick = { clicked = it },
                        onDismiss = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("爱丽丝", substring = true, useUnmergedTree = true).performClick()
        assertEquals("alice", clicked)
    }
}
