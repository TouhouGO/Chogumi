package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 引用条：左侧 3dp 竖线 + 8dp 留白（原型 `.quote{border-left:3px;padding-left:8px}`）。
 * 竖线画在自己量出的高度里，随内容等高；评论里的引用条、正文里的 [quote] 块、[QuoteText] 都经这里。
 */
fun Modifier.quoteBar(color: Color): Modifier = this
    .drawBehind {
        val w = QuoteBarWidth.toPx()
        drawLine(color, start = Offset(w / 2, 0f), end = Offset(w / 2, size.height), strokeWidth = w)
    }
    .padding(start = QuoteBarWidth + QuoteBarGap)

/**
 * 引用：[quoteBar] 套在纯文字上。目录条目的作者评语、动态里的收藏短评共用。
 */
@Composable
fun QuoteText(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        style = ReaderTypography.compact,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.quoteBar(MaterialTheme.colorScheme.outlineVariant),
    )
}

private val QuoteBarWidth = 3.dp
private val QuoteBarGap = 8.dp
