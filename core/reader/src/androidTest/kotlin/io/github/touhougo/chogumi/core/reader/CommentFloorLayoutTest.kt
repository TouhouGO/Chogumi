package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 楼层的真实渲染排版：
 * - 正文下方没有动作行：回复是点楼层、贴贴是长按，不为它们占一行高度；
 * - 楼中楼的楼层号按主楼派生（`#2-1`、`#2-2`，原型 `.thread`）。
 */
@RunWith(AndroidJUnit4::class)
class CommentFloorLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleShortThread = CommentThreadBo(
        anchor = CommentItemBo(
            id = 1001,
            mainId = 1,
            relatedId = 1,
            creatorId = 1,
            creatorNickname = "测试用户",
            creatorUsername = "test_user",
            creatorAvatar = null,
            content = "这是一条测试短评。",
            createdAt = 1700000000000L,
            state = CommentState.NORMAL,
            reactions = emptyList(),
        ),
        replies = emptyList(),
    )

    private val sampleThreadWithReplies = CommentThreadBo(
        anchor = CommentItemBo(
            id = 1002,
            mainId = 2,
            relatedId = 2,
            creatorId = 1,
            creatorNickname = "主楼楼主",
            creatorUsername = "anchor_user",
            creatorAvatar = null,
            content = "主楼讨论话题正文。",
            createdAt = 1700000000000L,
            state = CommentState.NORMAL,
            reactions = emptyList(),
        ),
        replies = listOf(
            CommentItemBo(
                id = 2001,
                mainId = 2,
                relatedId = 2,
                creatorId = 2,
                creatorNickname = "楼中楼一号",
                creatorUsername = "reply_1",
                creatorAvatar = null,
                content = "第一条嵌套回复。",
                createdAt = 1700000100000L,
                state = CommentState.NORMAL,
                reactions = emptyList(),
            ),
            CommentItemBo(
                id = 2002,
                mainId = 2,
                relatedId = 2,
                creatorId = 3,
                creatorNickname = "楼中楼二号",
                creatorUsername = "reply_2",
                creatorAvatar = null,
                content = "第二条嵌套回复。",
                createdAt = 1700000200000L,
                state = CommentState.NORMAL,
                reactions = emptyList(),
            ),
        ),
    )

    @Test
    fun 正文下方没有动作行() {
        composeRule.setContent {
            ChogumiTheme {
                Box(modifier = Modifier.width(360.dp).testTag("FloorBox")) {
                    CommentThreadItem(
                        thread = sampleShortThread,
                        floorNumber = "#1",
                        onAvatarClick = {},
                        surface = CommentSurface.Mono(MonoType.CHARACTER, 1),
                    )
                }
            }
        }

        val bodyBounds = composeRule.onNodeWithText(sampleShortThread.anchor.content).getUnclippedBoundsInRoot()
        val floorBounds = composeRule.onNodeWithTag("FloorBox").getUnclippedBoundsInRoot()

        // 回复是「点楼层」，没有回复图标；正文下方只剩楼层下内边距（12dp），没有动作行
        composeRule.onAllNodesWithContentDescription("回复").assertCountEquals(0)
        val belowBody = floorBounds.bottom - bodyBounds.bottom
        assertTrue(
            "正文下方只剩楼层内边距，实测 $belowBody（不得有动作行）",
            belowBody <= 13.dp,
        )
    }

    @Test
    fun 楼中楼楼层号按主楼派生() {
        composeRule.setContent {
            ChogumiTheme {
                Box(modifier = Modifier.width(360.dp).testTag("ThreadWithRepliesBox")) {
                    CommentThreadItem(
                        thread = sampleThreadWithReplies,
                        floorNumber = "#2",
                        onAvatarClick = {},
                        surface = CommentSurface.Mono(MonoType.CHARACTER, 1),
                    )
                }
            }
        }

        // 验证两层回复正文均正确渲染
        composeRule.onNodeWithText("主楼讨论话题正文。").assertExists()
        composeRule.onNodeWithText("第一条嵌套回复。").assertExists()
        composeRule.onNodeWithText("第二条嵌套回复。").assertExists()

        // 验证楼层号推导正确
        composeRule.onNodeWithText("#2").assertExists()
        composeRule.onNodeWithText("#2-1").assertExists()
        composeRule.onNodeWithText("#2-2").assertExists()
    }
}
