package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 从右到左的布局里，第 1 格与倒计时条的起点都在右边。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RtlDirectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 评分格在从右到左里按镜像取分() {
        assertEquals(1, ratingTileAt(x = 5f, width = 100, rtl = false, fallback = 0))
        assertEquals(10, ratingTileAt(x = 95f, width = 100, rtl = false, fallback = 0))
        assertEquals(10, ratingTileAt(x = 5f, width = 100, rtl = true, fallback = 0))
        assertEquals(1, ratingTileAt(x = 95f, width = 100, rtl = true, fallback = 0))
        assertEquals(7, ratingTileAt(x = 5f, width = 0, rtl = true, fallback = 7))
    }

    @Test
    fun 倒计时条在从右到左里从右边起() {
        val remaining = mutableFloatStateOf(0.5f)
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.width(100.dp).height(20.dp).testTag("bar").commitCountdownBar(remaining, Color.Red))
            }
        }
        val image = composeRule.onNodeWithTag("bar").captureToImage().asAndroidBitmap()
        val bottom = image.height - 1
        assertEquals(Color.Red.toArgb(), image.getPixel(image.width - 2, bottom))
        assertNotEquals(Color.Red.toArgb(), image.getPixel(1, bottom))
    }
}
