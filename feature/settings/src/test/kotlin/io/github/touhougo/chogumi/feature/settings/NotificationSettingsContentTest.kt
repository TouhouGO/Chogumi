package io.github.touhougo.chogumi.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.model.AiringReminderMode
import io.github.touhougo.chogumi.core.model.CommunityCheckInterval
import io.github.touhougo.chogumi.core.model.NotificationSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 设置 › 通知：开关亮不亮由设置、系统与专注模式一起定；下面的选项只在开着时出现。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class NotificationSettingsContentTest {

    @get:Rule
    val compose = createComposeRule()

    private object NoActions : NotificationSettingsActions {
        override fun onCommunityChange(value: Boolean) = Unit
        override fun onIntervalChange(value: CommunityCheckInterval) = Unit
        override fun onAiringChange(value: Boolean) = Unit
        override fun onAiringModeChange(value: AiringReminderMode) = Unit
        override fun onDailyMinuteChange(value: Int) = Unit
        override fun onBackgroundPrompted() = Unit
    }

    private fun show(state: NotificationSettingsUiState, system: SystemNoticeState, onSwitch: (NoticeSwitch, Boolean) -> Unit = { _, _ -> }) {
        compose.setContent {
            MaterialTheme {
                NotificationSettingsContent(
                    state = state,
                    system = system,
                    actions = NoActions,
                    failures = FailureNotices(),
                    onSwitch = onSwitch,
                    onCategories = {},
                    onBackground = {},
                    onBack = {},
                )
            }
        }
    }

    /** 设置页是惰性列表：靠下的行要先滚到，才在组合里。 */
    private fun scrollTo(text: String) {
        compose.onNodeWithTag(SettingsListTag).performScrollToNode(hasText(text))
    }

    @Test
    fun 专注模式下社区提醒灰着写原因_放送提醒照样能拨() {
        val switches = mutableListOf<Pair<NoticeSwitch, Boolean>>()
        show(
            NotificationSettingsUiState(settings = NotificationSettings(community = true), communityAvailable = false),
            SystemNoticeState(allowed = true, backgroundExempt = true),
        ) { which, on -> switches += which to on }

        compose.onNodeWithTag(CommunitySwitchTag).assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("专注模式开着").assertExists()
        compose.onNodeWithText("检查间隔").assertDoesNotExist()
        compose.onNodeWithTag(AiringSwitchTag).assertIsEnabled().performClick()

        assertEquals(listOf(NoticeSwitch.AIRING to true), switches)
    }

    @Test
    fun 系统里关了通知时开关显示关_写去打开的原因_没有下面的选项() {
        show(
            NotificationSettingsUiState(settings = NotificationSettings(community = true, airing = true)),
            SystemNoticeState(allowed = false, backgroundExempt = false),
        )

        compose.onNodeWithTag(CommunitySwitchTag).assertIsOff().assertIsEnabled()
        compose.onNodeWithTag(AiringSwitchTag).assertIsOff()
        assertEquals(2, compose.onAllNodesWithText("系统里关掉了通知，点这里去打开").fetchSemanticsNodes().size)
        compose.onNodeWithText("检查间隔").assertDoesNotExist()
        compose.onNodeWithText("后台运行").assertDoesNotExist()
    }

    @Test
    fun 开着时出现各自的选项_每天定时才有提醒时间() {
        show(
            NotificationSettingsUiState(settings = NotificationSettings(community = true, airing = true, airingMode = AiringReminderMode.DAILY)),
            SystemNoticeState(allowed = true, backgroundExempt = false),
        )

        compose.onNodeWithTag(CommunitySwitchTag).assertIsOn()
        compose.onNodeWithTag(AiringSwitchTag).assertIsOn()
        compose.onNodeWithText("检查间隔").assertExists()
        scrollTo("提醒时间")
        scrollTo("通知类别")
        scrollTo("省电时提醒会晚到，点这里放行")
    }

    @Test
    fun 开播时提醒没有提醒时间_放行过的后台运行不写原因() {
        show(
            NotificationSettingsUiState(settings = NotificationSettings(airing = true, airingMode = AiringReminderMode.AT_AIRING)),
            SystemNoticeState(allowed = true, backgroundExempt = true),
        )

        compose.onNodeWithText("提醒方式").assertExists()
        scrollTo("后台运行")
        compose.onNodeWithText("提醒时间").assertDoesNotExist()
        compose.onNodeWithText("省电时提醒会晚到，点这里放行").assertDoesNotExist()
    }
}
