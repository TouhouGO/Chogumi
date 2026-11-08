package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
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

/** 动态 tab 的长按删除只看取数时标好的 [TimelineItemBo.isOwn]：别人的动态长按不发起删除。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class ProfileTimelineDeleteTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `只有自己的动态长按发起删除`() {
        val deleted = mutableListOf<Long>()
        val timeline = PagedList(items = listOf(status(1, isOwn = true), status(2, isOwn = false)), isLoading = false)
        composeRule.setContent {
            ChogumiTheme {
                ContentGrid(cell = GridCell.Poster, modifier = Modifier.size(width = 400.dp, height = 800.dp)) {
                    timelineItems(
                        user = UserBo(id = 9, username = "fixture_user", nickname = "Fixture User"),
                        isSelf = false,
                        state = timeline,
                        days = timelineDaysOf(timeline.items),
                        endText = "",
                        onLoadMore = {},
                        onRetry = {},
                        onOpen = {},
                        onDelete = { deleted += it },
                        onSubject = {},
                    )
                }
            }
        }
        val rows = composeRule.onAllNodesWithTag(ProfileTimelineRowTag)
        rows[1].performTouchInput { longClick() }
        rows[0].performTouchInput { longClick() }
        composeRule.runOnIdle { assertEquals(listOf(1L), deleted) }
    }

    private fun status(id: Long, isOwn: Boolean) = TimelineItemBo(
        id = id,
        uid = 9,
        user = null,
        cat = 5,
        type = 1,
        createdAt = 1_700_000_000L - id,
        text = "吐槽 $id",
        isOwn = isOwn,
    )
}
