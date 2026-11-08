package io.github.touhougo.chogumi.core.ui.moderation

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 楼层菜单的绝交确认框：点确认按钮，请求真的发出去（确认框先收起，再绝交它问的那个人）。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class ModerationHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val moderation = FakeModeration()
    private var state by mutableStateOf(ModerationState())
    private val controller = ModerationController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        read = { state },
        write = { change -> state = change(state) },
        failures = FailureNotices(),
        moderation = moderation,
    )

    private fun showBlockConfirm() {
        controller.perform(ModerationAction.Block("eve", "Eve"))
        composeRule.setContent { ChogumiTheme { ModerationHost(state, controller) } }
    }

    @Test
    fun `点确认框的「绝交」发出这个人的绝交请求_确认框关掉`() {
        showBlockConfirm()
        composeRule.onNodeWithText(context.getString(R.string.block_confirm_action)).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("eve"), moderation.blocked)
            assertNull(state.blocking)
        }
        composeRule.onNodeWithText(context.getString(R.string.block_confirm_action)).assertDoesNotExist()
    }

    @Test
    fun `点「取消」不发请求_确认框关掉`() {
        showBlockConfirm()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        composeRule.runOnIdle {
            assertEquals(emptyList<String>(), moderation.blocked)
            assertNull(state.blocking)
        }
    }
}
