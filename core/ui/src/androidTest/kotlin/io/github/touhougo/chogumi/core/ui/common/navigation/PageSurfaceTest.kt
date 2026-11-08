package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 导航项是不透明的：页面只画了一点内容、其余空白时，画在它下面的东西（预测性返回手势里的上一页）透不上来。
 * 走真实的接线 [AppNavigator.entries]，守住「每个导航项都垫了页面底色」。导航项和外壳里一样画在 `NavDisplay` 里：
 * 装饰器读的转场作用域只有 `NavDisplay` 才提供。
 */
@RunWith(AndroidJUnit4::class)
class PageSurfaceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 页面的空白处不透出画在下面的上一页() {
        var background = Color.Unspecified
        composeRule.setContent {
            ChogumiTheme {
                background = MaterialTheme.colorScheme.background
                val navigator = rememberAppNavigator()
                val sheets = remember { PageSheetRegistry() }
                Box(Modifier.fillMaxSize().testTag(Root)) {
                    Box(Modifier.fillMaxSize().background(Underneath))
                    NavDisplay(
                        entries = navigator.entries({ key -> NavEntry(key) { Box(Modifier.size(1.dp)) } }, sheets),
                        modifier = Modifier.fillMaxSize(),
                        onBack = navigator::goBack,
                    )
                }
            }
        }
        val pixels = composeRule.onNodeWithTag(Root).captureToImage().toPixelMap()
        assertEquals(
            "页面空白处应是页面底色，而不是下面那一层",
            background.toArgb(),
            pixels[pixels.width / 2, pixels.height / 2].toArgb(),
        )
    }

    private companion object {
        const val Root = "root"

        /** 下面那一层（上一页）的颜色：与任何主题底色都不同。 */
        val Underneath = Color.Red
    }
}
