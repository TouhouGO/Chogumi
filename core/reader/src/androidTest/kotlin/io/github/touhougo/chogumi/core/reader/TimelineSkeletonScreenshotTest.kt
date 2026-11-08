package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 动态页一组（作者头 + 两行）的骨架与真实内容各一张截图（[saveScreenshot] 的 `timeline-skeleton/`），两态内容见
 * [TimelineSkeletonPair]。固定容器是截图的窗口选择（非 M3 规范）。
 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class TimelineSkeletonScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private var showReal by mutableStateOf(false)

    @Test
    fun 时间线骨架与内容各存一张截图() = capturePair(fontScale = 1f, prefix = "timeline")

    @Test
    fun 字体放大两倍时时间线骨架与内容各存一张截图() = capturePair(fontScale = 2f, prefix = "timeline-font2x")

    @Test
    fun 好友标识关闭时时间线骨架与内容各存一张截图() =
        capturePair(fontScale = 2f, prefix = "timeline-font2x-friend-marks-off", friendMarks = false)

    private fun capturePair(fontScale: Float, prefix: String, friendMarks: Boolean = true) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme(darkTheme = false) {
                    Surface {
                        Box(Modifier.size(width = 400.dp, height = 800.dp)) {
                            TimelineSkeletonPair(showReal, friendMarks)
                        }
                    }
                }
            }
        }
        listOf("skeleton" to false, "content" to true).forEach { (name, real) ->
            composeRule.runOnIdle { showReal = real }
            composeRule.waitForIdle()
            // 捕获真正的主题与页面根节点，避免仅保存空容器。
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            saveScreenshot("timeline-skeleton", "$prefix-$name", bitmap)
        }
    }
}
