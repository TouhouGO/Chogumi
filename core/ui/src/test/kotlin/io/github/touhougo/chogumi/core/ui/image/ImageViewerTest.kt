package io.github.touhougo.chogumi.core.ui.image

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 看图页的手势：上下拖远了关闭、轻拖弹回；长按弹出保存与分享。
 * 图片由假的图片加载器立即给出（Robolectric 没有网络）。
 */
@OptIn(DelicateCoilApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w360dp-h640dp")
class ImageViewerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var dismissed = 0

    @Before
    fun fakeImages() {
        SingletonImageLoader.setUnsafe { context ->
            ImageLoader.Builder(context)
                .components { add(Interceptor { chain -> SuccessResult(Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888).asImage(), chain.request) }) }
                .build()
        }
    }

    @After
    fun resetImageLoader() {
        SingletonImageLoader.reset()
    }

    private fun showViewer() {
        composeRule.setContent {
            ChogumiTheme {
                ImageViewer(listOf("https://example.invalid/a.png", "https://example.invalid/b.png"), startIndex = 0, onDismiss = { dismissed++ })
            }
        }
        composeRule.waitForIdle()
    }

    private fun currentPage() = composeRule.onAllNodesWithTag(ImageViewerPageTag)[0]

    @Test
    fun 往下拖远了松手就关闭() {
        showViewer()
        // 从屏幕中部拖到底部：远超关闭距离
        currentPage().performTouchInput { swipeDown(startY = centerY, endY = bottom, durationMillis = 400) }
        composeRule.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun 往上拖远了松手也关闭() {
        showViewer()
        currentPage().performTouchInput { swipeUp(startY = centerY, endY = top, durationMillis = 400) }
        composeRule.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun 轻轻往下拖慢慢松手不关闭() {
        showViewer()
        // 拖 40px（远小于 120dp），慢到不算甩
        currentPage().performTouchInput { swipeDown(startY = centerY, endY = centerY + 40f, durationMillis = 1500) }
        composeRule.waitForIdle()
        assertEquals(0, dismissed)
    }

    @Test
    fun 长按弹出保存与分享() {
        showViewer()
        currentPage().performTouchInput { longClick(center) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("保存").assertExists()
        composeRule.onNodeWithText("分享").assertExists()
        assertEquals(0, dismissed)
    }

    @Test
    fun 拖远或顺着拖动方向甩得快才关闭() {
        val distance = 300f
        val velocity = 3000f
        assertTrue(shouldDismiss(dragged = 320f, releaseVelocity = 0f, distance = distance, velocity = velocity))
        assertTrue(shouldDismiss(dragged = -320f, releaseVelocity = 0f, distance = distance, velocity = velocity))
        assertTrue(shouldDismiss(dragged = 60f, releaseVelocity = 4000f, distance = distance, velocity = velocity))
        // 往下拖着往上甩：想放回去
        assertFalse(shouldDismiss(dragged = 60f, releaseVelocity = -4000f, distance = distance, velocity = velocity))
        assertFalse(shouldDismiss(dragged = 60f, releaseVelocity = 1000f, distance = distance, velocity = velocity))
    }
}
