package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TimelineTargetBo
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 卡片动作行后面的回复与贴贴永远是一行：宽度不够时从后往前少画几种贴贴，回复数完整、不被挤窄也不出卡片。
 *
 * 同一条动态（评分 + 三种贴贴 + 回复）先放在够宽的卡片里，再收窄到放不下全部贴贴，比较两次的「回复 N」与「贴贴 N」。
 */
@RunWith(AndroidJUnit4::class)
class TimelineSignalsLineTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var cardWidth by mutableStateOf(WideCard)

    private val resources get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private val item = TimelineItemBo(
        id = 1,
        uid = 1,
        user = UserBo(id = 1, username = "frieren", nickname = "芙莉莲"),
        cat = 3,
        type = 6,
        createdAt = System.currentTimeMillis(),
        text = "",
        targets = listOf(
            TimelineTargetBo(
                title = "芙莉莲",
                url = "https://bgm.tv/subject/1",
                subject = SubjectBo(id = 1, type = SubjectType.ANIME, name = "芙莉莲", nameCn = "芙莉莲", eps = 28),
                rating = 7.0,
            ),
        ),
        reactions = listOf(CommentReactionBo(0, 17), CommentReactionBo(79, 5), CommentReactionBo(54, 1)),
        replies = 2,
    )

    @Test
    fun 宽度不够时少画几种贴贴_回复数保持完整() {
        composeRule.setContent {
            ChogumiTheme {
                Box(Modifier.width(cardWidth)) {
                    TimelineRow(
                        group = TimelineDisplayGroup(anchor = item, items = listOf(item)),
                        index = 0,
                        count = 1,
                        onOpen = {},
                        onDelete = null,
                        onSubject = {},
                    )
                }
            }
        }
        val replies = resources.getString(R.string.replies_count, 2)
        val reactions = resources.getString(R.string.timeline_reaction_like_count, 23)
        fun bounds(description: String) =
            composeRule.onNodeWithContentDescription(description, useUnmergedTree = true).getUnclippedBoundsInRoot()
        fun widthOf(description: String) = bounds(description).let { it.right - it.left }

        composeRule.waitForIdle()
        val wideReplies = widthOf(replies)
        val wideReactions = widthOf(reactions)

        cardWidth = NarrowCard
        composeRule.waitForIdle()
        assertEquals("回复数不被挤窄", wideReplies.value, widthOf(replies).value, 0.5f)
        assertTrue("回复数在卡片以内", bounds(replies).right <= NarrowCard)
        // 一种也放不下时贴贴整段不排进来；排进来的话一定比宽卡片里少
        val narrowReactions = composeRule.onAllNodesWithContentDescription(reactions, useUnmergedTree = true).fetchSemanticsNodes()
        if (narrowReactions.isNotEmpty()) {
            assertTrue("窄卡片里少画几种贴贴", widthOf(reactions) < wideReactions)
        }
    }

    private companion object {
        val WideCard = 400.dp
        val NarrowCard = 300.dp
    }
}
