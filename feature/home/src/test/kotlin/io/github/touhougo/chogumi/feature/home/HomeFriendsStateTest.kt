package io.github.touhougo.chogumi.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 好友区的等待、失败和空结果各自渲染，不把加载失败当成没有好友动态。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class HomeFriendsStateTest {
    @get:Rule
    val compose = createComposeRule()
    private var state by mutableStateOf(HomeUiState(today = LocalDate.of(2026, 10, 24), progress = Load.Ready(Unit), showFriends = true))
    private var retries = 0

    private fun show() {
        compose.setContent {
            ChogumiTheme {
                TodayHomeContent(
                    state, onRefresh = { retries++ }, topPadding = 0.dp, onSubjectClick = {}, onEdit = {}, onOpenProgress = {},
                    onContinueFilterChange = {}, onEpButtonTap = {}, onMarkFinished = {}, onFeedClick = {},
                )
            }
        }
    }

    @Test
    fun 好友首轮等待显示同框架骨架() {
        show()
        compose.onNodeWithTag("home_friends_loading").assertIsDisplayed()
    }

    @Test
    fun 好友加载失败提供实际重试动作() {
        state = state.copy(friends = Load.Failed(Failure.Offline))
        show()
        compose.onNodeWithText("网络连接失败，请检查网络").assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun 好友就绪空结果保持隐藏() {
        state = state.copy(friends = Load.Ready(emptyList()))
        show()
        compose.onNodeWithText("好友在看").assertDoesNotExist()
    }

    @Test
    fun 未启用好友区域时等待和失败都不画() {
        state = state.copy(showFriends = false)
        show()
        compose.onNodeWithTag("home_friends_loading").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(friends = Load.Failed(Failure.Offline)) }
        compose.onNodeWithText("网络连接失败，请检查网络").assertDoesNotExist()
        compose.onNodeWithText("好友在看").assertDoesNotExist()
    }
}
