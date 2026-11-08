package io.github.touhougo.chogumi.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.data.repository.NsfwPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设置「内容过滤」页**失败提示可见性**仪器测试。
 *
 * 为什么必须跑在设备上：JVM 单测能断言 ViewModel 发出了提示，但断不了「用户看得见」——
 * 本缺陷的现场正是提示发出了却无人显示。这里渲染**真实**的 [ContentSettingsContent]，
 * 往它的 [FailureNotices] 发一条提示，断言提示条文案上屏、显示完被确认。
 * 接线一旦被删（比如移除 `snackbarHost` 或 `FailureNoticeEffect`），本测试立刻失败。
 */
@RunWith(AndroidJUnit4::class)
class SettingsErrorVisibilityTest {

    @get:Rule
    val compose = createComposeRule()

    /** 仪器测试用事件：只记录调用（真实拨动语义由 JVM 的 SettingsViewModelNsfwTest 覆盖）。 */
    private class FakeContentSettingsActions : ContentSettingsActions {
        val calls = mutableListOf<String>()

        override fun onNsfwChange(value: Boolean) { calls += "onNsfwChange" }
        override fun retryNsfw() { calls += "retryNsfw" }
    }

    private fun editable(checked: Boolean) = NsfwSwitchState.Editable(
        NsfwPreference(allowNsfw = checked, showNsfwSubject = checked, canSetNsfwSubject = true),
    )

    private val failures = FailureNotices()
    private var errorColor = Color.Unspecified

    private fun show(state: ContentSettingsUiState): FakeContentSettingsActions {
        val actions = FakeContentSettingsActions()
        compose.setContent {
            MaterialTheme {
                errorColor = MaterialTheme.colorScheme.error
                ContentSettingsContent(
                    state = state,
                    actions = actions,
                    failures = failures,
                    isLoggedIn = true,
                    onBack = {},
                    onMutedGroups = {},
                    onBlockedUsers = {},
                )
            }
        }
        return actions
    }

    @Test
    fun `PATCH失败的提示会上屏_显示完被确认`() {
        // 复现本缺陷的现场：ViewModel 发出了提示，界面必须弹出提示条。
        val message = "bgm.tv 暂时无法在 App 里修改这一项，可在网页端的个人设置里修改"
        show(ContentSettingsUiState(nsfw = editable(checked = true)))
        failures.post(Failure.Rejected(RejectReason.PRIVACY_SETTING_UNAVAILABLE))

        compose.onNodeWithText(message).assertIsDisplayed()

        // 显示完必须确认，否则同一提示不会再弹。提示条显示 4 秒（SnackbarDuration.Short）后才确认：
        // 直接把测试时钟拨过这段时长再断言，不按真实时间等（慢模拟器上会超时）。
        compose.mainClock.advanceTimeBy(SNACKBAR_SHORT_MILLIS + 1_000)
        compose.waitForIdle()
        assertNull("提示条消失后应确认这条提示", failures.current.value)
    }

    @Test
    fun `没有失败时不显示提示_开关照常渲染`() {
        val actions = show(ContentSettingsUiState(nsfw = editable(checked = true)))

        // 设置页是惰性列表：先滚到 NSFW 行再断言可见
        compose.onNodeWithTag(SettingsListTag).performScrollToNode(hasText("显示 NSFW 内容"))
        compose.onNodeWithText("显示 NSFW 内容").assertIsDisplayed()
        assertTrue("无失败时不应有任何调用，实际：${actions.calls}", actions.calls.isEmpty())
    }

    @Test
    fun `点击开关会把事件转发出去`() {
        // 钉住界面不是死控件：拨动必须触达事件层（真实拨动语义由 JVM 的
        // SettingsViewModelNsfwTest 覆盖，这里只验"线接上了"）。
        val actions = show(ContentSettingsUiState(nsfw = editable(checked = false)))

        compose.onNodeWithTag(SettingsListTag).performScrollToNode(hasText("显示 NSFW 内容"))
        compose.onNodeWithTag(NsfwSwitchTag).performClick()

        assertTrue("点击开关必须转发 onNsfwChange，实际：${actions.calls}", actions.calls.contains("onNsfwChange"))
    }

    @Test
    fun `账号偏好读取失败用错误色并提供独立重试`() {
        val actions = show(ContentSettingsUiState(nsfw = NsfwSwitchState.Unavailable(loading = false, failureReason = Failure.Interrupted)))
        val message = "读取账号设置失败：网络连接中断，请重试"
        compose.onNodeWithTag(SettingsListTag).performScrollToNode(hasText(message))
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(message, useUnmergedTree = true).assertIsDisplayed()
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertEquals("加载失败不能渲染成普通说明色", errorColor, layouts.single().layoutInput.style.color)
        compose.onNodeWithTag(NsfwSwitchTag).assertIsNotEnabled()
        compose.onNodeWithText("重试").performClick()
        assertEquals(listOf("retryNsfw"), actions.calls)
    }

    private companion object {
        /** M3 Compose 的 SnackbarDuration.Short（无操作按钮、无障碍未加长时）。 */
        const val SNACKBAR_SHORT_MILLIS = 4_000L
    }
}
