package io.github.touhougo.chogumi.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.ui.common.navigation.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 设置的一级菜单：登录时最上面是自己的资料一行；每个分类一行，点哪一行就去哪个分类页。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class SettingsMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private object NoActions : SettingsActions {
        override fun clearImageCache() = Unit
        override fun logout() = Unit
    }

    private fun showSections(networkSection: Boolean, onSection: (SettingsSection) -> Unit) {
        compose.setContent {
            MaterialTheme {
                SettingsContent(
                    state = SettingsUiState(),
                    actions = NoActions,
                    failures = FailureNotices(),
                    onBack = {},
                    onProfile = {},
                    onSection = onSection,
                    onAbout = {},
                    isLoggedIn = true,
                    showDebugTools = false,
                    networkSection = networkSection,
                )
            }
        }
    }

    @Test
    fun 每个分类行打开它自己的分类页() {
        val opened = mutableListOf<SettingsSection>()
        showSections(networkSection = true) { opened += it }

        listOf("外观", "首页与进度", "评分与社区", "内容过滤", "更新", "网络").forEach { title ->
            compose.onNode(hasText(title) and hasClickAction()).performClick()
        }

        assertEquals(SettingsSection.entries, opened)
    }

    @Test
    fun 设备不支持直连时没有网络分类() {
        showSections(networkSection = false) {}

        compose.onNodeWithText("更新").assertExists()
        compose.onNodeWithText("网络").assertDoesNotExist()
    }

    @Test
    fun 登录时最上面一行是自己的显示名_点了去个人资料页() {
        var opened = 0
        showMenu(isLoggedIn = true, onProfile = { opened++ })

        compose.onNode(hasText("小咕") and hasClickAction()).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun 游客没有资料那一行() {
        showMenu(isLoggedIn = false, onProfile = {})

        compose.onNodeWithText("小咕").assertDoesNotExist()
        compose.onNode(hasText("外观") and hasClickAction()).assertExists()
    }

    private fun showMenu(isLoggedIn: Boolean, onProfile: () -> Unit) {
        compose.setContent {
            MaterialTheme {
                SettingsContent(
                    state = SettingsUiState(me = Load.Ready(UserBo(id = 1, username = "chogumi", nickname = "小咕"), fromCache = false)),
                    actions = NoActions,
                    failures = FailureNotices(),
                    onBack = {},
                    onProfile = onProfile,
                    onSection = {},
                    onAbout = {},
                    isLoggedIn = isLoggedIn,
                    showDebugTools = false,
                    networkSection = false,
                )
            }
        }
    }
}
