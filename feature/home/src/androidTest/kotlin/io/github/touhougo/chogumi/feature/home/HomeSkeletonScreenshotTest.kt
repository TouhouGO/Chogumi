package io.github.touhougo.chogumi.feature.home

import io.github.touhougo.chogumi.core.foundation.Load
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * 首页骨架与真实内容各一张截图（字体比例 1 与 2），叠起来看对不对得上（[saveScreenshot] 的 `home-skeleton/`）。
 * 位置由 [HomeSkeletonTest] 逐边断言。
 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class HomeSkeletonScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private var state by mutableStateOf(HomeUiState(today = HomeSkeletonFixtures.today, progress = Load.Pending))

    private companion object {
        const val FOLDER = "home-skeleton"
    }

    @Test
    fun 首页骨架与内容各存一张截图() = captureSkeletonAndContent(fontScale = 1f, prefix = "home")

    @Test
    fun 字体放大两倍时首页骨架与内容各存一张截图() = captureSkeletonAndContent(fontScale = 2f, prefix = "home-font2x")

    private fun captureSkeletonAndContent(fontScale: Float, prefix: String) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    TodayHomeContent(
                        state = state,
                        onRefresh = {},
                        topPadding = 0.dp,
                        onSubjectClick = {},
                        onEdit = {},
                        onOpenProgress = {},
                        onContinueFilterChange = {},
                        onEpButtonTap = {},
                        onMarkFinished = {},
                        onFeedClick = {},
                    )
                }
            }
        }
        listOf("skeleton" to HomeUiState(today = HomeSkeletonFixtures.today, progress = Load.Pending), "content" to HomeSkeletonFixtures.fullState()).forEach { (name, next) ->
            composeRule.runOnIdle { state = next }
            composeRule.waitForIdle()
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            saveScreenshot(FOLDER, "$prefix-$name", bitmap)
        }
    }
}
