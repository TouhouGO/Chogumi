package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.ui.composables.ContentGrid
import io.github.touhougo.chogumi.core.ui.composables.GridCell
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 「动态」为空时的一行字：本人「还没有动态」，他人带主人的昵称，不说「你的」。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class ProfileTimelineEmptyTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showEmptyTimeline(isSelf: Boolean) {
        val timeline = PagedList<TimelineItemBo>(isLoading = false)
        composeRule.setContent {
            ChogumiTheme {
                ContentGrid(cell = GridCell.Poster, modifier = Modifier.size(width = 400.dp, height = 800.dp)) {
                    timelineItems(
                        user = UserBo(id = 9, username = "fixture_user", nickname = "Fixture User"),
                        isSelf = isSelf,
                        state = timeline,
                        days = timelineDaysOf(timeline.items),
                        endText = "",
                        onLoadMore = {},
                        onRetry = {},
                        onOpen = {},
                        onDelete = {},
                        onSubject = {},
                    )
                }
            }
        }
    }

    @Test
    fun `他人的动态为空_写主人的昵称_不说你的`() {
        showEmptyTimeline(isSelf = false)
        composeRule.onNodeWithText("Fixture User 还没有动态").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("你的", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `本人的动态为空_一行还没有动态`() {
        showEmptyTimeline(isSelf = true)
        composeRule.onNodeWithText("还没有动态").assertExists()
    }
}
