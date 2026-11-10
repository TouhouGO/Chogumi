package io.github.touhougo.chogumi.core.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 详情页标题与原名的长按复制：长按放进完整文字（截成一行的也整段），点按什么也不做。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CopyOnLongPressTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)

    private fun show() {
        clipboard.setPrimaryClip(ClipData.newPlainText(null, BEFORE))
        composeRule.setContent {
            ChogumiTheme {
                Text(
                    text = TITLE,
                    modifier = Modifier.testTag(TAG).width(80.dp).copyOnLongPress(TITLE),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    private fun clip(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun 长按截成一行的标题_复制整段() {
        show()
        composeRule.onNodeWithTag(TAG).performTouchInput { longClick(center) }
        composeRule.runOnIdle { assertEquals(TITLE, clip()) }
    }

    @Test
    fun 点按标题_剪贴板不变() {
        show()
        composeRule.onNodeWithTag(TAG).performClick()
        composeRule.runOnIdle { assertEquals(BEFORE, clip()) }
    }

    private companion object {
        const val TAG = "title"
        const val TITLE = "这是一个很长很长、一行放不下的作品标题"
        const val BEFORE = "原来的剪贴板"
    }
}
