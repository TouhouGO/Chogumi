package io.github.touhougo.chogumi.shell

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.res.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 手势引导盖着外壳时，读屏读不到下面的底栏与页面；引导收起后外壳照常可读。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class TourOverShellTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val skip = ApplicationProvider.getApplicationContext<Application>().getString(R.string.gesture_tour_skip)

    @Test
    fun `引导盖着时外壳的底栏与页面不在语义树里`() {
        setShell(showTour = true)
        composeRule.onNodeWithText(skip).assertIsDisplayed()
        composeRule.onNodeWithText(BAR).assertDoesNotExist()
        composeRule.onNodeWithText(PAGE).assertDoesNotExist()
    }

    @Test
    fun `没有引导时外壳的底栏与页面照常可读`() {
        setShell(showTour = false)
        composeRule.onNodeWithText(skip).assertDoesNotExist()
        composeRule.onNodeWithText(BAR).assertExists()
        composeRule.onNodeWithText(PAGE).assertExists()
    }

    private fun setShell(showTour: Boolean) {
        // 引导的演示一直在循环播放，时钟自动走的话永远等不到空闲
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            TourOverShell(showTour = showTour, onTourDone = {}) { modifier ->
                ShellLayout(
                    navigation = TopLevelNavigationKind.BOTTOM_BAR,
                    bottomBar = { Text(BAR) },
                    rail = {},
                    modifier = modifier,
                ) { Text(PAGE) }
            }
        }
        composeRule.mainClock.advanceTimeBy(FRAME_MILLIS)
    }

    private companion object {
        const val BAR = "底栏"
        const val PAGE = "页面"
        const val FRAME_MILLIS = 16L
    }
}
