package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 骨架的示意图画法：[SkeletonSketch] 里的色块取 `primary`、字条取 `outline`（样式缩略图靠它看得清版式），
 * 外面的骨架照旧是安静的 `surfaceContainerHighest`。取样点在色块正中、字条第一行正中。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkeletonSketchTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(): ColorScheme {
        lateinit var scheme: ColorScheme
        compose.setContent {
            ChogumiTheme {
                scheme = MaterialTheme.colorScheme
                Column {
                    SkeletonBox(Modifier.size(40.dp).testTag("plain_block"))
                    SkeletonText(MaterialTheme.typography.titleLarge, Modifier.width(80.dp).testTag("plain_bar"))
                    SkeletonSketch {
                        SkeletonBox(Modifier.size(40.dp).testTag("sketch_block"))
                        SkeletonText(MaterialTheme.typography.titleLarge, Modifier.width(80.dp).testTag("sketch_bar"))
                    }
                }
            }
        }
        compose.waitForIdle()
        return scheme
    }

    private fun centerOf(tag: String): Color {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    private fun assertColor(what: String, expected: Color, actual: Color) {
        val close = abs(expected.red - actual.red) < 0.02f && abs(expected.green - actual.green) < 0.02f && abs(expected.blue - actual.blue) < 0.02f
        assertTrue("$what：期望 $expected，实际 $actual", close)
    }

    @Test
    fun 示意图里色块取主色_字条取轮廓色() {
        val scheme = render()
        assertColor("示意图的色块", scheme.primary, centerOf("sketch_block"))
        assertColor("示意图的字条", scheme.outline, centerOf("sketch_bar"))
    }

    @Test
    fun 示意图外的骨架照旧取最高一层容器色() {
        val scheme = render()
        assertColor("平时的色块", scheme.surfaceContainerHighest, centerOf("plain_block"))
        assertColor("平时的字条", scheme.surfaceContainerHighest, centerOf("plain_bar"))
    }
}
