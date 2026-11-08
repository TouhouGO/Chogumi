package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

/**
 * 主文字优先的两段行：放得下时同一行按首基线对齐；放不下时尾部移到下一行，主文字先用完整一行。
 *
 * 主文字仍受一行宽度限制，所以只有自己一行也放不下时才在自己的 [androidx.compose.material3.Text] 上省略。
 * 两段可以是任意内容；[trailing] 为 null 时只排主文字。段间 [gap] 只在同一行时占用。
 * [trailingToEnd] 为 true 时，同一行的尾部贴到可用宽度的逻辑 end，否则按阅读方向紧跟主文字。
 */
@Composable
fun PriorityTextLine(
    primary: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    gap: Dp,
    trailingToEnd: Boolean = false,
) {
    Layout(
        content = {
            primary()
            trailing?.invoke()
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val primaryPlaceable = measurables[0].measure(loose)
        val trailingPlaceable = measurables.getOrNull(1)?.measure(loose)
        if (trailingPlaceable == null) {
            val width = constraints.constrainWidth(primaryPlaceable.width)
            val height = constraints.constrainHeight(primaryPlaceable.height)
            return@Layout layout(width, height) {
                primaryPlaceable.placeRelative(0, 0)
            }
        }

        val gapPx = gap.roundToPx()
        val fitsOneLine = primaryPlaceable.width + gapPx + trailingPlaceable.width <= constraints.maxWidth
        if (fitsOneLine) {
            val primaryBaseline = primaryPlaceable[FirstBaseline]
            val trailingBaseline = trailingPlaceable[FirstBaseline]
            val sharedBaseline = maxOf(primaryBaseline, trailingBaseline)
            val primaryY = sharedBaseline - primaryBaseline
            val trailingY = sharedBaseline - trailingBaseline
            val contentWidth = if (trailingToEnd && constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                primaryPlaceable.width + gapPx + trailingPlaceable.width
            }
            val contentHeight = maxOf(primaryY + primaryPlaceable.height, trailingY + trailingPlaceable.height)
            val width = constraints.constrainWidth(contentWidth)
            val height = constraints.constrainHeight(contentHeight)
            layout(width, height) {
                primaryPlaceable.placeRelative(0, primaryY)
                val trailingX = if (trailingToEnd) {
                    width - trailingPlaceable.width
                } else {
                    primaryPlaceable.width + gapPx
                }
                trailingPlaceable.placeRelative(trailingX, trailingY)
            }
        } else {
            val contentWidth = maxOf(primaryPlaceable.width, trailingPlaceable.width)
            val contentHeight = primaryPlaceable.height + trailingPlaceable.height
            val width = constraints.constrainWidth(contentWidth)
            val height = constraints.constrainHeight(contentHeight)
            layout(width, height) {
                primaryPlaceable.placeRelative(0, 0)
                trailingPlaceable.placeRelative(0, primaryPlaceable.height)
            }
        }
    }
}
