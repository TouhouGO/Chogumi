package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.em
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InlineSmileyLineHeightTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun largeInlinePlaceholderStaysInsideItsOwnLine() {
        var layoutResult: TextLayoutResult? = null
        val text = buildAnnotatedString {
            append("普通行\n前")
            appendInlineContent("large-smiley")
            append("后")
        }

        composeRule.setContent {
            ChogumiTheme {
                val inlineStyle = styleForInlineContent(
                    MaterialTheme.typography.bodyMedium,
                    listOf(Placed("large-smiley", InlineItem.Smiley("musume_13"))),
                )
                Text(
                    text = text,
                    style = inlineStyle,
                    inlineContent = mapOf(
                        "large-smiley" to InlineTextContent(
                            placeholder = Placeholder(
                                width = 3.4f.em,
                                height = 3.4f.em,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Bottom,
                            ),
                        ) {},
                    ),
                    modifier = Modifier.testTag("inline-smiley-text"),
                    onTextLayout = { layoutResult = it },
                )
            }
        }
        composeRule.waitForIdle()

        val layout = checkNotNull(layoutResult)
        val placeholder = checkNotNull(layout.placeholderRects.single())
        val lineTop = layout.getLineTop(1)
        val lineBottom = layout.getLineBottom(1)
        assertTrue(
            "大表情顶部不得越过所在行顶部: rect=[$placeholder], lineTop=$lineTop, lineBottom=$lineBottom, lines=${layout.lineCount}",
            placeholder.top >= lineTop,
        )
        assertTrue(
            "大表情底部不得越过所在行底部: bottom=${placeholder.bottom}, lineBottom=$lineBottom",
            placeholder.bottom <= lineBottom,
        )
    }

    @Test
    fun timelineLargeInlinePlaceholderStaysInsideItsOwnLine() {
        var layoutResult: TextLayoutResult? = null
        val text = buildAnnotatedString {
            append("普通行\n前")
            appendInlineContent("large-smiley")
            append("后")
        }

        composeRule.setContent {
            ChogumiTheme {
                val inlineStyle = SmileySizes.inlineTextStyleOf(
                    MaterialTheme.typography.bodyMedium,
                    listOf("musume_13"),
                )
                Text(
                    text = text,
                    style = inlineStyle,
                    inlineContent = mapOf(
                        "large-smiley" to InlineTextContent(
                            placeholder = Placeholder(
                                width = 3.4f.em,
                                height = 3.4f.em,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Bottom,
                            ),
                        ) {},
                    ),
                    onTextLayout = { layoutResult = it },
                )
            }
        }
        composeRule.waitForIdle()

        val layout = checkNotNull(layoutResult)
        val placeholder = checkNotNull(layout.placeholderRects.single())
        val lineTop = layout.getLineTop(1)
        val lineBottom = layout.getLineBottom(1)
        assertTrue(
            "时间线大表情顶部不得越过所在行顶部: rect=[$placeholder], lineTop=$lineTop, lineBottom=$lineBottom",
            placeholder.top >= lineTop,
        )
        assertTrue(
            "时间线大表情底部不得越过所在行底部: bottom=${placeholder.bottom}, lineBottom=$lineBottom",
            placeholder.bottom <= lineBottom,
        )
    }
}
