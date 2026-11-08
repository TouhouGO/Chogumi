package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [PressArea] 的排版：画了容器的块与 M3 `Surface` 一样把块的最小尺寸交给内容，透明块不交。
 * 水波纹铺满整块要真机画出来才看得到，在仪器测试 `PressAreaTest` 里。
 *
 * 画容器的块（探索页的合集方块：定宽高 156×118，题材名在左上、三张小封面靠右下）若不把尺寸交给内容，
 * 里面那层 `Box` 缩成封面那么大，封面跟着缩到块的左上角，正好压在题材名上。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PressAreaLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `画了容器的定宽高块 靠右下对齐的内容落在块的右下 可点与不可点都一样`() {
        composeRule.setContent {
            ChogumiTheme {
                Column {
                    StackLikeBlock(onClick = {}, tag = CLICKABLE)
                    StackLikeBlock(onClick = null, tag = STATIC)
                }
            }
        }

        listOf(CLICKABLE, STATIC).forEach { tag ->
            val block = bounds(tag)
            val corner = bounds("$tag-corner")
            assertEquals("$tag：右下的内容离块右边 12dp", (block.right - 12.dp).value, corner.right.value, 0.5f)
            assertEquals("$tag：右下的内容离块底边 12dp", (block.bottom - 12.dp).value, corner.bottom.value, 0.5f)
            assertEquals("$tag：内容那层铺满块宽", block.width.value, bounds("$tag-content").width.value, 0.5f)
            assertEquals("$tag：内容那层铺满块高", block.height.value, bounds("$tag-content").height.value, 0.5f)
        }
    }

    @Test
    fun `透明块不把尺寸交给内容 内容按自己的大小排`() {
        composeRule.setContent {
            ChogumiTheme {
                PressArea(
                    onClick = {},
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(BLOCK_WIDTH, BLOCK_HEIGHT).testTag(TRANSPARENT),
                ) {
                    Box(Modifier.size(CONTENT_SIZE).testTag("$TRANSPARENT-content"))
                }
            }
        }

        val content = bounds("$TRANSPARENT-content")
        assertEquals(CONTENT_SIZE.value, content.width.value, 0.5f)
        assertEquals(CONTENT_SIZE.value, content.height.value, 0.5f)
    }

    private fun bounds(tag: String): DpRect = composeRule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()

    private companion object {
        const val CLICKABLE = "painted_clickable"
        const val STATIC = "painted_static"
        const val TRANSPARENT = "transparent"
        val CONTENT_SIZE = 40.dp
    }
}

/** 合集方块的宽高。 */
private val BLOCK_WIDTH = 156.dp
private val BLOCK_HEIGHT = 118.dp

/** 与合集方块同一种写法：定宽高、画容器，里面一层不铺满的 `Box`，左上一行字、右下一块。 */
@Composable
private fun StackLikeBlock(onClick: (() -> Unit)?, tag: String) {
    PressArea(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(BLOCK_WIDTH, BLOCK_HEIGHT).testTag(tag),
    ) {
        Box(Modifier.testTag("$tag-content")) {
            Text("题材", modifier = Modifier.padding(start = 12.dp, top = 10.dp))
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 12.dp)
                    .size(width = 50.dp, height = 66.dp)
                    .testTag("$tag-corner"),
            )
        }
    }
}
