package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 面板里的列表快速往回甩、滚到顶后剩下的速度不带动面板：面板留着，不被甩关。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PageModalSheetFlingTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 列表往回甩到顶不会把面板甩关() {
        var dismissed = false
        lateinit var list: LazyListState
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    PageModalSheet(onDismiss = { dismissed = true }, fullHeight = false, growsWithContent = true) {
                        // 从靠下的位置开始：甩动先滚列表，滚到顶时还剩很大的速度
                        list = rememberLazyListState(ROWS / 4)
                        LazyColumn(Modifier.fillMaxWidth().height(LIST_HEIGHT).testTag(LIST), list) {
                            items(ROWS) { Text("row $it", Modifier.height(ROW_HEIGHT)) }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertTrue("甩动从列表中途开始", list.firstVisibleItemIndex > 0) }
        composeRule.onNodeWithTag(LIST).performTouchInput { swipeDown(startY = top + 8f, endY = bottom - 8f, durationMillis = SWIPE_MILLIS) }
        composeRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals("这次甩动确实抵达首行", 0, list.firstVisibleItemIndex)
            assertEquals("列表已经完整回到顶部", 0, list.firstVisibleItemScrollOffset)
        }
        assertFalse("列表甩到顶剩下的速度不应把面板甩关", dismissed)
        composeRule.onNodeWithTag(LIST).assertExists()
    }

    private companion object {
        const val LIST = "list"
        const val ROWS = 40
        const val SWIPE_MILLIS = 60L
        const val SETTLE_MILLIS = 3_000L
        val LIST_HEIGHT = 300.dp
        val ROW_HEIGHT = 48.dp
    }
}
