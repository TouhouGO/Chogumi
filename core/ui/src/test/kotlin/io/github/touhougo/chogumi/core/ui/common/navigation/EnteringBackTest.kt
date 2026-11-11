package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation3.runtime.NavEntry
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 真实的页面区接法下，取消过的预测性返回不影响下一次：每一次边缘返回手势，最上面那页都跟着手指走（预览），
 * 不会变成不带预览的普通返回。进场途中的普通返回见 [PageTransitionFramesTest]。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EnteringBackTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val gesture = DirectNavigationEventInput()
    private lateinit var navigator: AppNavigator
    private val details = Route.SubjectDetails(1)
    private val topics = Route.SubjectTopics(1)

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
                AppNavDisplay(navigator, { key -> NavEntry(key) { Box(Modifier.fillMaxSize().testTag(key.toString())) } }, sheets, simpleBack = false)
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            navigator.navigate(details)
            navigator.navigate(topics)
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }

    private fun event(progress: Float) = NavigationEvent(touchX = 400f * progress, touchY = 300f, progress = progress, swipeEdge = NavigationEvent.EDGE_LEFT)

    /** 一帧一帧推，每帧读一次最上面那页：语义树只在被读的时候跟上画面。 */
    private fun frames(count: Int) = repeat(count) {
        composeRule.mainClock.advanceTimeByFrame()
        topLeft()
    }

    private fun topLeft(): Float = composeRule.onNodeWithTag(topics.toString()).fetchSemanticsNode().boundsInRoot.left

    /** 起一次边缘返回手势并拖到 20%、再拖到 90%，返回最上面那页两次的左边缘：预览跟着手指，越拖越靠右。手势留在进行中。 */
    private fun dragAndReadPreview(): Pair<Float, Float> {
        composeRule.runOnIdle { gesture.backStarted(event(0f)) }
        composeRule.runOnIdle { gesture.backProgressed(event(0.2f)) }
        frames(12)
        val early = topLeft()
        composeRule.runOnIdle { gesture.backProgressed(event(0.9f)) }
        frames(12)
        return early to topLeft()
    }

    private fun assertPreviewFollowsFinger(label: String) {
        val (early, late) = dragAndReadPreview()
        assertTrue("$label：预览没有跟着手指走（20% 处 $early，90% 处 $late）", late > early + 1f)
    }

    @Test
    fun 取消过一次预测性返回_再拖仍是预览() {
        setContent()
        assertPreviewFollowsFinger("第一次")
        composeRule.runOnIdle { gesture.backCancelled() }
        frames(120)
        assertPreviewFollowsFinger("取消并停稳后的第二次")
        composeRule.runOnIdle { gesture.backCancelled() }
        frames(120)
        assertPreviewFollowsFinger("再取消并停稳后的第三次")
    }

    @Test
    fun 取消的回弹还没走完就再拖_仍是预览() {
        setContent()
        assertPreviewFollowsFinger("第一次")
        composeRule.runOnIdle { gesture.backCancelled() }
        frames(1)
        assertPreviewFollowsFinger("回弹途中的第二次")
    }

    @Test
    fun 取消过一次后松手_预览着退回上一页_只退一页() {
        setContent()
        assertPreviewFollowsFinger("第一次")
        composeRule.runOnIdle { gesture.backCancelled() }
        frames(120)
        assertPreviewFollowsFinger("第二次")
        composeRule.runOnIdle { gesture.backCompleted() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(topics.toString()).assertDoesNotExist()
        composeRule.onNodeWithTag(details.toString()).assertExists()
    }
}
