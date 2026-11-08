package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 外层 `[size]` 里的表情：占位（em 按所在位置字号换算）与表情图（按正文字号画）必须同按正文字号取尺寸，
 * 和同一段里不带 `[size]` 的表情一样大；否则放大时占位比图大一圈，缩小时图被挤进变小的占位。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SizedSmileySlotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `放大字号里的表情占位与表情图都按正文字号`() = assertSizedSmileyAtBaseSize("[size=32]哈(bgm38)[/size]")

    @Test
    fun `缩小字号里的表情占位与表情图都按正文字号`() = assertSizedSmileyAtBaseSize("[size=10]哈(bgm38)[/size]")

    @Test
    fun `遮罩里放大字号的表情揭开后占位与表情图都按正文字号`() = assertSizedSmileyAtBaseSize("[mask][size=32]哈(bgm38)[/size][/mask]")

    private fun inlineItemsOf(source: String) = BBCodeParser.parse(source).nodes
        .toBlocks(Color.Blue, darkTheme = false, onInAppLink = null, onUserClick = null)
        .toRows().filterIsInstance<Row.InlineFlow>().single().items

    private fun assertSizedSmileyAtBaseSize(source: String) {
        val plainItems = inlineItemsOf("哈(bgm38)")
        val sizedItems = inlineItemsOf(source)
        var plain: TextLayoutResult? = null
        var sized: TextLayoutResult? = null
        composeRule.setContent {
            ChogumiTheme {
                Column {
                    InlineFlow(plainItems, ReaderTypography.compact, onUserClick = null, revealSpoilers = true, onTextLayout = { plain = it })
                    InlineFlow(sizedItems, ReaderTypography.compact, onUserClick = null, revealSpoilers = true, onTextLayout = { sized = it })
                }
            }
        }
        composeRule.waitForIdle()
        val baseSlot = checkNotNull(checkNotNull(plain).placeholderRects.single()).height
        val sizedSlot = checkNotNull(checkNotNull(sized).placeholderRects.single()).height
        assertEquals("$source 的占位高 ${sizedSlot}px，正文表情占位高 ${baseSlot}px", baseSlot, sizedSlot, 1f)
        val images = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(2, images.size)
        images.forEach { image ->
            assertEquals("$source 的表情图高 ${image.size.height}px，正文表情占位高 ${baseSlot}px", baseSlot, image.size.height.toFloat(), 1f)
        }
    }
}
