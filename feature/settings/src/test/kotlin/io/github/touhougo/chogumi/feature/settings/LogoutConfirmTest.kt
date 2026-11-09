package io.github.touhougo.chogumi.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 设置页的「退出登录」先弹确认框：点按钮只开框，点框里的「退出」才登出，「取消」什么也不做。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class LogoutConfirmTest {

    @get:Rule
    val compose = createComposeRule()

    /** 只数登出次数，清缓存与本测试无关。 */
    private class CountingActions : SettingsActions {
        var logouts = 0

        override fun clearImageCache() = Unit
        override fun logout() {
            logouts++
        }
    }

    private fun showAndTapLogout(): CountingActions {
        val actions = CountingActions()
        compose.setContent {
            MaterialTheme {
                SettingsContent(
                    state = SettingsUiState(),
                    actions = actions,
                    failures = FailureNotices(),
                    onBack = {},
                    onProfile = {},
                    onSection = {},
                    onAbout = {},
                    isLoggedIn = true,
                    showDebugTools = false,
                    networkSection = false,
                )
            }
        }
        // 退出按钮在列表最底下，惰性列表要先滚到它
        compose.onNodeWithTag(SettingsListTag).performScrollToNode(hasText("退出登录"))
        compose.onNode(hasText("退出登录") and hasClickAction()).performClick()
        return actions
    }

    @Test
    fun 点退出登录只弹确认框_确认后才登出() {
        val actions = showAndTapLogout()

        compose.onNode(isDialog()).assertExists()
        assertEquals("确认之前不应登出", 0, actions.logouts)

        compose.onNode(hasText("退出") and hasClickAction()).performClick()

        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(1, actions.logouts)
    }

    @Test
    fun 确认框点取消不登出() {
        val actions = showAndTapLogout()

        compose.onNode(hasText("取消") and hasClickAction()).performClick()

        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(0, actions.logouts)
    }
}
