package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 个人资料错误态的浅深色设备捕获；只画真实组件，不连接账号、不调用退出。 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class ProfileLoadErrorCapture {

    @get:Rule
    val composeRule = createComposeRule()

    private var darkTheme by mutableStateOf(false)
    private var captureName by mutableStateOf("")

    @Test
    fun 捕获本人资料错误态() {
        composeRule.setContent {
            ChogumiTheme(darkTheme = darkTheme) {
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
                    modifier = Modifier.fillMaxSize(),
                    onSettingsClick = {},
                    onBack = null,
                    onOpenBlogs = {},
                    onOpenIndexes = {},
                    onOpenCareer = {},
                )
            }
        }

        listOf(false, true).forEach { dark ->
            composeRule.runOnIdle { darkTheme = dark }
            composeRule.waitForIdle()
            val widthDp = composeRule.onRoot().fetchSemanticsNode().size.width / composeRule.density.density
            composeRule.runOnIdle {
                captureName = "width-${widthDp.toInt()}-${if (dark) "dark" else "light"}"
            }
            saveScreenshot(Folder, captureName, composeRule.onRoot().captureToImage().asAndroidBitmap())
        }
    }

    private companion object {
        const val Folder = "profile-load-error"
    }
}
