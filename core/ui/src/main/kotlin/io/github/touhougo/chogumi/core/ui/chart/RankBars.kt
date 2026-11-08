package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 排名横条的一行。 */
class RankBar(val label: String, val count: Int)

/**
 * 排名横条：名称、条、数三列，按调用方给的顺序（从大到小）一行一项。名称列取最长的名称、最多占整行的 40%，
 * 再长的名称截成「…」；数右对齐；条的满长是最大的那个数。名称与数都写在行上，不用点按气泡。
 * 条高 12dp、全圆角、行距 8dp、列距 10dp，是我的设计决定（not in M3）。
 */
@Composable
fun RankBars(bars: List<RankBar>, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyMedium
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val peak = (bars.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = constraints.maxWidth
        val (nameWidth, countWidth) = remember(bars, style, available, measurer) {
            fun widthOf(text: String) = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
            val longestName = bars.maxOfOrNull { widthOf(it.label) } ?: 0
            minOf(longestName, (available * NAME_MAX_SHARE).toInt()) to (bars.maxOfOrNull { widthOf("${it.count}") } ?: 0)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            bars.forEach { bar ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        bar.label,
                        style = style,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.exactWidth(nameWidth).alignByBaseline(),
                    )
                    Box(Modifier.weight(1f).height(12.dp).clip(CircleShape).background(track)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(bar.count / peak.toFloat()).clip(CircleShape).background(fill))
                    }
                    Text(
                        "${bar.count}",
                        style = style,
                        color = muted,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.exactWidth(countWidth).alignByBaseline(),
                    )
                }
            }
        }
    }
}

private const val NAME_MAX_SHARE = 0.4f

/** 按量好的像素宽排这一列，每行同宽，三列上下对齐。 */
private fun Modifier.exactWidth(px: Int) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = px, maxWidth = px))
    layout(px, placeable.height) { placeable.place(0, 0) }
}
