package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 装饰大字比 tile 宽、还带偏移，必须裁在 tile 的容器边界里；可读的标签不在裁切层外被吃掉。
 * 截图在 tile 外多取 [OUTER_MARGIN] 一圈：装饰字漏出容器，这一圈就不再全是背景色。
 */
@RunWith(AndroidJUnit4::class)
class ExploreTileCardDecorationClipTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 真实tile装饰只在容器边裁切而标签仍可见() {
        compose.setContent {
            ChogumiTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(BACKGROUND).padding(OUTER_MARGIN * 2), contentAlignment = Alignment.TopStart) {
                    Box(Modifier.testTag(TILE)) {
                        ExploreTileCard(
                            big = BIG,
                            label = LABEL,
                            style = ExploreTileStyle.Dimension,
                            colorIndex = 0,
                            onClick = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText(LABEL).assertIsDisplayed()

        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        val bounds = compose.onNodeWithTag(TILE).getUnclippedBoundsInRoot()
        val (left, top, right, bottom, margin) = with(compose.density) {
            listOf(
                floor(bounds.left.toPx()).toInt(),
                floor(bounds.top.toPx()).toInt(),
                ceil(bounds.right.toPx()).toInt(),
                ceil(bounds.bottom.toPx()).toInt(),
                OUTER_MARGIN.roundToPx(),
            )
        }
        val background = BACKGROUND.toArgb()
        var leaked = 0
        for (y in top - margin until bottom + margin) {
            for (x in left - margin until right + margin) {
                val outside = x < left || x >= right || y < top || y >= bottom
                if (outside && screenshot.getPixel(x, y) != background) leaked++
            }
        }
        assertEquals("tile 容器外的一圈应全是背景色，装饰字漏出的像素数", 0, leaked)
    }

    private companion object {
        const val TILE = "tile"
        const val BIG = "MMMMMMMM"
        const val LABEL = "可见标签"
        val BACKGROUND = Color.White
        val OUTER_MARGIN = 20.dp
    }
}
