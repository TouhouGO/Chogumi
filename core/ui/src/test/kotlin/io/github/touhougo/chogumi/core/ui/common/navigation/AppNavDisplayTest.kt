package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation3.runtime.NavEntry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 真实的页面区接法（[AppNavDisplay]）：压进一页时新页从行尾一侧滑进来，排版方向翻转时也翻转。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppNavDisplayTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navigator: AppNavigator

    private fun setContent(direction: LayoutDirection) {
        composeRule.mainClock.autoAdvance = true
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                ChogumiTheme {
                    navigator = rememberAppNavigator()
                    val sheets = remember { PageSheetRegistry() }
                    AppNavDisplay(navigator, { key -> NavEntry(key) { Box(Modifier.fillMaxSize().testTag(key.toString())) } }, sheets, simpleBack = false)
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** 压进一页后，新页出现的那一帧它左边缘的位置。 */
    private fun enteringLeftAfterPush(): Float {
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { navigator.navigate(Route.SubjectDetails(1)) }
        val page = composeRule.onAllNodesWithTag(Route.SubjectDetails(1).toString(), useUnmergedTree = true)
        repeat(10) {
            composeRule.mainClock.advanceTimeByFrame()
            page.fetchSemanticsNodes().firstOrNull()?.let { return it.boundsInRoot.left }
        }
        error("新页没有出现")
    }

    @Test
    fun 从左到右排版_新页从右侧滑进来() {
        setContent(LayoutDirection.Ltr)
        assertTrue(enteringLeftAfterPush() > 0f)
    }

    @Test
    fun 从右到左排版_新页从左侧滑进来() {
        setContent(LayoutDirection.Rtl)
        assertTrue(enteringLeftAfterPush() < 0f)
    }
}
