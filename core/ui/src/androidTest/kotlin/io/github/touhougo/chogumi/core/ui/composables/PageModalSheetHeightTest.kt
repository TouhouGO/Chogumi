package io.github.touhougo.chogumi.core.ui.composables

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 满高页面面板的两档：首开半屏；点拖动条展开，展开时顶上留出 M3 的 72dp；再点回到半屏。
 * 调用方要它收起时滑下去，滑完回调关闭一次。
 */
@RunWith(AndroidJUnit4::class)
class PageModalSheetHeightTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var open by mutableStateOf(true)
    private var hide by mutableStateOf(false)
    private var dismissed by mutableIntStateOf(0)
    private val handleLabel by lazy { ApplicationProvider.getApplicationContext<Context>().getString(R.string.sheet_handle) }

    private fun setSheet() {
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    if (open) {
                        PageModalSheet(onDismiss = { dismissed++; open = false }, fullHeight = true, hideRequested = hide) {
                            Text("sheet", Modifier.testTag(ContentTag))
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** 面板顶边：内容的顶边往上一条拖动条（48dp 触控区）。 */
    private fun sheetTop(): Dp = composeRule.onNodeWithTag(ContentTag).getUnclippedBoundsInRoot().top - 48.dp

    private fun rootHeight(): Dp = composeRule.onRoot().getUnclippedBoundsInRoot().bottom

    private fun assertNear(message: String, expected: Dp, actual: Dp) =
        assertTrue("$message：期望 $expected，实际 $actual", abs((expected - actual).value) < 1f)

    @Test
    fun 首开半屏_点拖动条展开到离顶72dp_再点回半屏() {
        setSheet()
        assertNear("首开半屏", rootHeight() / 2, sheetTop())

        composeRule.onNodeWithContentDescription(handleLabel).performClick()
        composeRule.waitForIdle()
        assertNear("展开也不顶到屏幕顶：留出 M3 的顶边距", 72.dp, sheetTop())

        composeRule.onNodeWithContentDescription(handleLabel).performClick()
        composeRule.waitForIdle()
        assertNear("再点回到半屏", rootHeight() / 2, sheetTop())
    }

    @Test
    fun 调用方要它收起_滑下去后回调关闭一次() {
        setSheet()
        composeRule.runOnIdle { hide = true }
        composeRule.waitForIdle()
        assertEquals(1, dismissed)
        composeRule.onNodeWithTag(ContentTag).assertDoesNotExist()
    }

    private companion object {
        const val ContentTag = "sheet-content"
    }
}
