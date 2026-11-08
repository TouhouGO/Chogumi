package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 帖子里连续的几张图按各自的原比例排：不裁成方格。尺寸由 `[img=W,H]` 写明（Robolectric 没有网络，图总是加载失败，
 * 一组里失败的格子保持原来的大小）。栏宽 300dp，密度 1（1dp = 1px）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w360dp-h640dp-mdpi")
class BBCodeImageRowLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val opensImage = SemanticsMatcher("点击标签是「查看图片」") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "查看图片"
    }

    private fun imageBounds(raw: String): List<Rect> {
        composeRule.setContent {
            ChogumiTheme { Box(Modifier.width(300.dp)) { BBCodeText(BBCodeParser.parse(raw)) } }
        }
        return composeRule.onAllNodes(opensImage).fetchSemanticsNodes().map { it.boundsInRoot }
    }

    /** 格宽向下取整到整像素：与期望差不到 1px。 */
    private fun assertPx(expected: Float, actual: Float, message: String) =
        assertTrue(abs(expected - actual) < 1f, "$message：期望 $expected，实际 $actual")

    @Test
    fun 隔着空行的两张横向宽图各占一行按原比例画不裁成方格() {
        val (first, second) = imageBounds(
            "[img=1707,600]https://example.invalid/wide-1.png[/img]\n\n[img=1512,801]https://example.invalid/wide-2.png[/img]",
        )

        assertPx(300f, first.width, "第一张铺满栏宽")
        assertPx(300f * 600 / 1707, first.height, "第一张按原比例的高")
        assertPx(300f, second.width, "第二张铺满栏宽")
        assertPx(300f * 801 / 1512, second.height, "第二张按原比例的高")
        assertTrue(second.top >= first.bottom, "第二张在第一张下面一行，而不是并排")
    }

    @Test
    fun 同一行的三张竖图并成一行等高铺满栏宽() {
        val bounds = imageBounds("[img=1080,2340]https://example.invalid/1.png[/img]".repeat(3))

        assertEquals(1, bounds.map { it.top }.distinct().size, "三张在同一行")
        val height = (300f - 4f * 2) / 3 * 2340 / 1080
        bounds.forEachIndexed { i, rect ->
            assertPx(height, rect.height, "第 ${i + 1} 张与同行等高")
            assertPx(height * 1080 / 2340, rect.width, "第 ${i + 1} 张按原比例的宽")
        }
        assertTrue(300f - bounds.last().right < 3f, "这一行铺满栏宽（每格向下取整，至多差几个像素）：右边到 ${bounds.last().right}")
    }
}
