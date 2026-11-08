package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.TopicType
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloorGestureAndQuoteBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 楼层内容变了以后点回复带的是新内容() {
        val targets = mutableListOf<ReplyTarget>()
        var content by mutableStateOf("旧内容")
        composeRule.setContent {
            ChogumiTheme {
                val gate = rememberLoginGate(isLoggedIn = true, onLogin = {})
                Box(Modifier.width(360.dp).testTag("floor")) {
                    CommentThreadItem(
                        thread = CommentThreadBo(anchor = item(1, content), replies = emptyList()),
                        floorNumber = "#1",
                        onAvatarClick = {},
                        surface = CommentSurface.Topic(TopicType.GROUP, 7),
                        onReply = gate.guard { target: ReplyTarget -> targets += target },
                    )
                }
            }
        }
        // 第一下点按让手势协程起来；之后楼层内容变了，同一个协程不能还拿着旧楼层。
        composeRule.onNodeWithTag("floor").performTouchInput { click(Offset(4f, 4f)) }
        composeRule.runOnIdle { content = "新内容" }
        composeRule.onNodeWithTag("floor").performTouchInput { click(Offset(4f, 4f)) }
        composeRule.runOnIdle {
            assertEquals(2, targets.size)
            assertTrue("回复带的是旧楼层：${targets.last().preview}", targets.last().preview.toString().contains("新内容"))
        }
    }

    @Test
    fun 跳转闪烁熄着时是同色全透明而不是透明黑() {
        val primary = Color(0xFF6750A4)
        val off = floorFlashColor(primary, flashing = false)
        assertEquals(0f, off.alpha, 0f)
        assertEquals(primary.copy(alpha = 0f).toArgb(), off.toArgb())
        assertTrue(floorFlashColor(primary, flashing = true).alpha > 0f)
    }

    @Test
    fun 正文里的引用块竖线与内容等高() {
        var barColor = Color.Unspecified
        composeRule.setContent {
            ChogumiTheme {
                barColor = MaterialTheme.colorScheme.outlineVariant
                Box(Modifier.width(300.dp).testTag("quote")) {
                    BBCodeText(BBCodeParser.parse("[quote]一\n二\n三\n四[/quote]"))
                }
            }
        }
        val image = composeRule.onNodeWithTag("quote").captureToImage().asAndroidBitmap()
        val density = composeRule.density.density
        val x = (12.dp.value * density + 1.5f * density).toInt()
        val rows = (12 * density).toInt() + 1
        val bottom = image.height - (12 * density).toInt() - 2
        assertEquals("竖线在第一行就应有色", barColor.toArgb(), image.getPixel(x, rows))
        assertEquals("竖线到最后一行内容仍有色", barColor.toArgb(), image.getPixel(x, bottom))
    }

    private fun item(id: Int, content: String) = CommentItemBo(
        id = id,
        mainId = id,
        relatedId = 0,
        creatorId = id,
        creatorNickname = "甲",
        creatorUsername = "u$id",
        creatorAvatar = null,
        content = content,
        createdAt = 0L,
        state = CommentState.NORMAL,
    )
}
