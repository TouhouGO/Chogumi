package io.github.touhougo.chogumi.core.ui.composables

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 确认框：确认先收起，再把框里问的那一个交给确认的动作；「取消」只收起；确认不可点时「取消」照样可点。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ConfirmDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val calls = mutableListOf<String>()

    private fun show(confirmEnabled: Boolean = true) {
        composeRule.setContent {
            // 页面的写法：开着的框就是「要删的那条」这份状态，收起把它清掉
            var deleting by remember { mutableStateOf<Long?>(TARGET) }
            ChogumiTheme {
                deleting?.let { id ->
                    ConfirmDialog(
                        target = id,
                        confirmLabel = CONFIRM,
                        onConfirm = { confirmed -> calls += "confirm $confirmed" },
                        onDismiss = {
                            calls += "dismiss"
                            deleting = null
                        },
                        text = "删除这条？",
                        destructive = true,
                        confirmEnabled = confirmEnabled,
                    )
                }
            }
        }
    }

    @Test
    fun `点确认先收起_再把框里问的那一条交给确认_对话框关掉`() {
        show()
        composeRule.onNodeWithText(CONFIRM).performClick()
        composeRule.runOnIdle { assertEquals(listOf("dismiss", "confirm $TARGET"), calls) }
        composeRule.onNodeWithText(CONFIRM).assertDoesNotExist()
    }

    @Test
    fun `点取消只收起_不执行`() {
        show()
        composeRule.onNodeWithText(app.getString(R.string.cancel)).performClick()
        composeRule.runOnIdle { assertEquals(listOf("dismiss"), calls) }
    }

    @Test
    fun `确认不可点时取消照样可点`() {
        show(confirmEnabled = false)
        composeRule.onNodeWithText(CONFIRM).assertIsNotEnabled()
        composeRule.onNodeWithText(app.getString(R.string.cancel)).assertIsEnabled()
    }

    private companion object {
        const val CONFIRM = "删除"
        const val TARGET = 42L
    }
}
