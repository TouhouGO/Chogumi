package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 满高面板停在半展开时，下半截在屏幕外：列表滚到底，最后一行要落在屏幕里，不能躲在屏幕边缘后面。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PageModalSheetVisibleBottomTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 半展开时滚到底最后一行在屏幕内() {
        val scroll = ScrollState(0)
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    ScrollableModalSheet(onDismiss = {}) {
                        SheetTitleRow("title")
                        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) {
                            repeat(40) { Text("row $it", Modifier.height(48.dp)) }
                            Text("last", Modifier.testTag(LAST).height(48.dp))
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(2_000)
        val screen = composeRule.onRoot().getUnclippedBoundsInRoot()
        val title = composeRule.onNodeWithText("title").getUnclippedBoundsInRoot()
        assertTrue("面板确实停在半展开位置", title.top > (screen.top + screen.bottom) / 2)
        assertTrue("内容确实需要滚动", scroll.maxValue > 0)
        composeRule.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue) } }
        composeRule.waitForIdle()

        val screenBottom = composeRule.onRoot().getUnclippedBoundsInRoot().bottom.value
        val lastBottom = composeRule.onNodeWithTag(LAST).getUnclippedBoundsInRoot().bottom.value
        assertEquals("滚动确实到底", scroll.maxValue, scroll.value)
        assertTrue("最后一行在视口内", lastBottom > title.bottom.value)
        assertTrue("最后一行的下沿 $lastBottom 超出屏幕 $screenBottom", lastBottom <= screenBottom + 0.5f)
    }

    private companion object {
        const val LAST = "last"
    }
}
