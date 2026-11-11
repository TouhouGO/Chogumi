package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 其他 tab 根部的返回是切回首页 tab：拖动时没有翻页预览（首页不在下面露出来），松手才切。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabRootBackTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val gesture = DirectNavigationEventInput()
    private lateinit var navigator: AppNavigator

    private fun setContent() {
        composeRule.setContent {
            val dispatcher = LocalNavigationEventDispatcherOwner.current!!.navigationEventDispatcher
            DisposableEffect(dispatcher) {
                dispatcher.addInput(gesture)
                onDispose { dispatcher.removeInput(gesture) }
            }
            ChogumiTheme {
                navigator = rememberAppNavigator()
                val sheets = remember { PageSheetRegistry() }
                val page = LocalAppMotion.current.page
                val shiftPx = with(LocalDensity.current) { page.shift.roundToPx() }
                NavDisplay(
                    entries = navigator.entries({ key -> NavEntry(key) { Box(Modifier.fillMaxSize().background(if (key == Route.Home) Color.Red else Color.Blue)) } }, sheets),
                    transitionSpec = { chogumiTransition(PageTransitionKind.TopLevel, page, 1) },
                    popTransitionSpec = { chogumiTransition(PageTransitionKind.TopLevel, page, 1) },
                    predictivePopTransitionSpec = { _ -> chogumiPredictivePopTransition(PageTransitionKind.Back, page, 1, shiftPx, simpleBack = false) },
                    onBack = navigator::goBack,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun pixel(x: Int): Color {
        val image = composeRule.onRoot().captureToImage().toPixelMap()
        return image[x, image.height / 2]
    }

    @Test
    fun 其他tab根部拖动返回_不露出首页_松手切回首页() {
        setContent()
        composeRule.runOnIdle { navigator.navigate(Route.Explore) }
        composeRule.waitForIdle()
        val width = composeRule.onRoot().fetchSemanticsNode().size.width

        composeRule.runOnIdle { gesture.backStarted(NavigationEvent(touchX = 0f, touchY = 300f, progress = 0f, swipeEdge = NavigationEvent.EDGE_LEFT)) }
        composeRule.runOnIdle { gesture.backProgressed(NavigationEvent(touchX = 300f, touchY = 300f, progress = 0.5f, swipeEdge = NavigationEvent.EDGE_LEFT)) }
        composeRule.waitForIdle()
        assertEquals("拖动时这一页原样铺满，左边", Color.Blue, pixel(1))
        assertEquals("拖动时这一页原样铺满，中间", Color.Blue, pixel(width / 2))
        assertEquals(Route.Explore, navigator.currentTab)

        composeRule.runOnIdle { gesture.backCompleted() }
        composeRule.waitForIdle()
        assertEquals("松手切回首页 tab", Route.Home, navigator.currentTab)
        assertEquals(Color.Red, pixel(width / 2))
    }
}
