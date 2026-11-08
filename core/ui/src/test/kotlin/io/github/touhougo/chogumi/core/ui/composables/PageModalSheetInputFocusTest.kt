package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 打开就要输入的面板：滑上来的途中输入框不拿焦点（拿了焦点键盘就弹出来、盖住正在滑的面板），停稳后才拿。
 * 键盘是系统的窗口，各家弹出的快慢不同，只有「面板先停稳」这一条能保证面板的动画不藏在键盘后面。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PageModalSheetInputFocusTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 面板停稳后输入框才拿到焦点() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    val focus = remember { FocusRequester() }
                    PageModalSheet(onDismiss = {}, fullHeight = false, growsWithContent = true, inputFocus = focus) {
                        BasicTextField(rememberTextFieldState(), Modifier.focusRequester(focus).testTag(FIELD))
                    }
                }
            }
        }
        // 首开动画进行中：几帧之后面板还在往上滑
        repeat(OPENING_FRAMES) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.onNodeWithTag(FIELD).assertIsNotFocused()

        composeRule.mainClock.advanceTimeBy(SETTLED_MILLIS)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(FIELD).assertIsFocused()
    }

    private companion object {
        const val FIELD = "field"
        const val OPENING_FRAMES = 6
        const val SETTLED_MILLIS = 3_000L
    }
}
