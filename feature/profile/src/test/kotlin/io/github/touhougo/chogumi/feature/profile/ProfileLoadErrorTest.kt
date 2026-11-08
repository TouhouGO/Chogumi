package io.github.touhougo.chogumi.feature.profile

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 个人资料首载失败时只保留重试；退出登录仍从顶栏设置进入。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w448dp-h996dp")
class ProfileLoadErrorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `自己的资料加载失败_保留设置与重试`() {
        composeRule.setContent {
            ChogumiTheme {
                val loginGate = rememberLoginGate(isLoggedIn = true, onLogin = {})
                ProfileContent(
                    user = null,
                    isProfileRefreshing = false,
                    error = Failure.Offline,
                    timeline = PagedList<TimelineItemBo>(isLoading = false),
                    onRefreshTimeline = {},
                    onLoadMoreTimeline = {},
                    onOpenTimeline = {},
                    onDeleteTimeline = {},
                    shelf = ShelfState(),
                    social = SocialState(),
                    onOpenShelf = {},
                    onTimelineShown = {},
                    onSelectShelf = {},
                    onRefreshShelf = {},
                    onLoadMoreShelf = {},
                    onOpenSocial = {},
                    onSelectSocial = {},
                    onLoadMoreSocial = {},
                    onRefreshSocial = {},
                    career = CareerState(isSelf = true),
                    onRetryCareer = {},
                    onUserClick = {},
                    onSubjectClick = {},
                    onCharacterClick = {},
                    onPersonClick = {},
                    onRetry = {},
                    onRefreshProfile = {},
                    isSelf = true,
                    isLoggedIn = true,
                    loginGate = loginGate,
                    socialOps = SocialOpsState(),
                    snackbarHostState = remember { SnackbarHostState() },
                    snackbarOnSheet = false,
                    onAddFriend = {},
                    onRemoveFriend = {},
                    onBlock = {},
                    onUnblock = {},
                    onOpenReport = {},
                    onMessage = {},
                    onSettingsClick = {},
                    onBack = null,
                    onOpenBlogs = {},
                    onOpenIndexes = {},
                    onOpenCareer = {},
                )
            }
        }

        composeRule.onNodeWithText("重试").assertExists()
        composeRule.onNodeWithContentDescription("设置").assertExists()
    }
}
