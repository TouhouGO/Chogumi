package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getAlignmentLinePosition
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 帖子行 [PostListItem] 的论坛样式：左边头像；右边第一行发帖人，来源放得下时在这一行最右边、与发帖人同一条基线；
 * 第二行标题；最后一行时间与回复数。没有发帖人时标题顶格、来源在标题下面；左列是封面时文字列与头像行左对齐。
 * 行底是 `surfaceContainerLow`。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PostListItemTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var containerColor = Color.Unspecified
    private var authorLineHeight = 0f
    private var sourceLineHeight = 0f

    @Test
    fun `发帖人在头像右边_来源在同一行最右边_下面依次是标题与页脚`() {
        setRow(author = "发帖人", source = "小组名")
        val row = bounds(ROW)
        val author = text("发帖人")
        val source = text("小组名")
        val title = text("标题")
        val time = text("3 分钟前")
        val replies = text("12")

        assertTrue("发帖人左边让出头像列", author.left - row.left > ListAvatarSize)
        assertEquals("发帖人与标题左对齐", title.left.value, author.left.value, TOLERANCE)
        assertEquals("来源贴着回复数的右缘", replies.right.value, source.right.value, TOLERANCE)
        assertTrue("来源在这一行的右半边", source.left > (row.left + row.right) / 2)
        assertEquals("发帖人与来源同一条基线", baseline("发帖人"), baseline("小组名"), TOLERANCE)
        assertTrue("标题在发帖人下面", title.top >= author.bottom)
        assertTrue("时间在标题下面", time.top >= title.bottom)
        assertEquals("时间与回复数同一行", (time.top + time.bottom).value / 2, (replies.top + replies.bottom).value / 2, TOLERANCE)
    }

    @Test
    fun `发帖人与来源都很长时姓名先占整行_来源下一行`() {
        setRow(author = "很长的昵称".repeat(8), source = "很长的小组名".repeat(8))
        val row = bounds(ROW)
        val author = text("很长的昵称".repeat(8))
        val source = text("很长的小组名".repeat(8))
        assertTrue("来源在发帖人下一行", source.top >= author.bottom - TOLERANCE.dp)
        assertTrue("来源不出行", source.right <= row.right)
        assertEquals("来源与发帖人左对齐", source.left.value, author.left.value, TOLERANCE)
        // 中文的回退字体比行高略高（12sp 一行量出 18dp），所以只比「不到两行」
        assertTrue("发帖人截断后仍是一行", author.bottom.value - author.top.value < authorLineHeight * 2)
        assertTrue("来源截断后仍是一行", source.bottom.value - source.top.value < sourceLineHeight * 2)
    }

    @Test
    fun `没有发帖人时标题顶格_来源在标题下面`() {
        setRow(author = null, source = "很长的作品名".repeat(8), leading = PostLeading.Cover(null))
        val row = bounds(ROW)
        val title = text("标题")
        val source = text("很长的作品名".repeat(8))
        val time = text("3 分钟前")
        assertTrue("标题顶格：上面只有行的上内边距", title.top - row.top < SourceAboveTitleBound)
        assertTrue("来源在标题下面", source.top >= title.bottom)
        assertEquals("来源与标题左对齐", title.left.value, source.left.value, TOLERANCE)
        assertTrue("来源截断后仍是一行", source.bottom.value - source.top.value < sourceLineHeight * 2)
        assertTrue("来源不出行", source.right <= row.right)
        assertTrue("时间在来源下面", time.top >= source.bottom)
    }

    @Test
    fun `封面列与头像列同宽_文字列左缘对齐`() {
        composeRule.setContent {
            ChogumiTheme {
                Column(Modifier.width(360.dp)) {
                    listOf(PostLeading.Avatar(null) to "头像行", PostLeading.Cover(null) to "封面行").forEachIndexed { i, (leading, title) ->
                        PostListItem(
                            title = title,
                            replies = 1,
                            repliesDescription = null,
                            index = i,
                            count = 2,
                            onClick = {},
                            leading = leading,
                        )
                    }
                }
            }
        }
        assertEquals("两种左列，标题左缘相同", text("头像行").left.value, text("封面行").left.value, TOLERANCE)
    }

    @Test
    fun `行底比一般分段列表浅一档`() {
        setRow(author = "发帖人", source = "小组名")
        val row = bounds(ROW)
        val pixels = composeRule.onNodeWithTag(ROW).captureToImage().toPixelMap()
        val density = composeRule.density.density
        // 行左缘往里 4dp、上下正中：在起始内边距里，没有文字与头像，也不在圆角上
        val sample = pixels[(4.dp.value * density).toInt(), ((row.bottom - row.top).value / 2 * density).toInt()]
        assertEquals("行底是 surfaceContainerLow", containerColor, sample)
    }

    private fun setRow(author: String?, source: String?, leading: PostLeading = PostLeading.Avatar(null)) {
        composeRule.setContent {
            ChogumiTheme {
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                // 字体缩放 1×：sp 与 dp 相等
                authorLineHeight = MaterialTheme.typography.labelLarge.lineHeight.value
                sourceLineHeight = MaterialTheme.typography.labelMedium.lineHeight.value
                // 360dp 固定视口只是测试设计决定（not in M3）。
                Column(Modifier.width(360.dp)) {
                    PostListItem(
                        title = "标题",
                        replies = 12,
                        repliesDescription = "12 条回复",
                        index = 0,
                        count = 1,
                        onClick = {},
                        modifier = Modifier.testTag(ROW),
                        leading = leading,
                        author = author,
                        source = source,
                        time = "3 分钟前",
                    )
                }
            }
        }
    }

    private fun bounds(tag: String): DpRect = composeRule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun text(value: String): DpRect = composeRule.onNodeWithText(value, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun baseline(value: String): Float {
        val node = composeRule.onNodeWithText(value, useUnmergedTree = true)
        return (node.getUnclippedBoundsInRoot().top + node.getAlignmentLinePosition(FirstBaseline)).value
    }

    private companion object {
        const val ROW = "row"
        const val TOLERANCE = 0.5f

        /** 标题顶格时它上面至多是行的上内边距（M3 列表项 12dp 上下），留一点余量。 */
        val SourceAboveTitleBound = 16.dp
    }
}
