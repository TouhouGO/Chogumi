package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.statistics.FlowCard
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.subjectTypeSpec
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.chart.ChartMark
import io.github.touhougo.chogumi.core.ui.chart.chartFractions
import io.github.touhougo.chogumi.core.ui.chart.chartFraction
import androidx.compose.ui.geometry.Rect
import io.github.touhougo.chogumi.core.ui.chart.ChartLayout
import io.github.touhougo.chogumi.core.ui.chart.TapChart
import io.github.touhougo.chogumi.core.ui.composables.colors
import io.github.touhougo.chogumi.core.ui.composables.formatOneDecimal
import io.github.touhougo.chogumi.core.ui.composables.labelResFor
import io.github.touhougo.chogumi.core.ui.composables.rootRes
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * 去向流向（统计 tab「开始看的人去了哪」）：左边一根「开始看」，分出四条带流到看过、在看、搁置、抛弃，
 * 带宽按人数；右边是各去向的名字、人数与占比；想看的人还没开始，用虚线小块挂在下面。只在这里用，放在本模块。
 * 几何是我的设计决定（not in M3）：右侧节点按人数分 [FLOW_NODE_BUDGET] 高、每个至少 6dp、行距 10dp，
 * 一行至少一行字高（字放大时行跟着变高）；带子取去向色的 32% 不透明度。
 */

private val FLOW_NODE_BUDGET = 102.dp
private val FLOW_NODE_MIN = 6.dp
private val FLOW_ROW_GAP = 10.dp
private val FLOW_NODE_WIDTH = 8.dp
private val FLOW_TRUNK_WIDTH = 14.dp
private val FLOW_FOOTER_GAP = 12.dp
private const val FLOW_RIBBON_ALPHA = 0.32f

/** 右侧节点从画布宽的这个比例处开始。 */
private const val FLOW_NODES_AT = 0.42f

/** 右侧一行：行顶、行高、节点高（节点在行里竖直居中）。 */
internal class FlowLane(val top: Float, val height: Float, val node: Float)

/** 右侧各行的位置：节点高按占比分 [budget]、至少 [minNode]；行高至少 [line]，行间 [gap]。 */
internal fun flowRows(shares: List<Double>, budget: Float, minNode: Float, line: Float, gap: Float): List<FlowLane> {
    var top = 0f
    return shares.map { share ->
        val node = max(minNode, (share * budget).toFloat())
        val height = max(node, line)
        FlowLane(top, height, node).also { top += height + gap }
    }
}

/** 占比的写法：10% 及以上取整，以下留一位小数（0.8%）。 */
internal fun formatShare(share: Double): String {
    val percent = share * 100
    return if (percent >= 10) percent.roundToInt().toString() else formatOneDecimal(percent)
}

/** 整张图的高：右侧各行，加下面「开始看」与想看那一行。 */
@Composable
private fun flowHeight(card: FlowCard): Dp = with(LocalDensity.current) {
    val line = MaterialTheme.typography.labelLarge.lineHeight.toPx()
    val rows = flowRows(card.branches.map { it.share }, FLOW_NODE_BUDGET.toPx(), FLOW_NODE_MIN.toPx(), line, FLOW_ROW_GAP.toPx())
    val last = rows.last()
    (last.top + last.height + FLOW_FOOTER_GAP.toPx() + line).toDp()
}

/** 这张图的标题（「开始看的人去了哪」，动词随作品类型）：卡片标题与图的读屏描述共用。 */
@Composable
internal fun flowTitle(subjectType: SubjectType): String =
    stringResource(R.string.stats_flow_title, stringResource(subjectTypeSpec(subjectType).verb.rootRes()))

@Composable
internal fun StatusFlow(card: FlowCard, subjectType: SubjectType, modifier: Modifier = Modifier) {
    val progress = chartFractions()
    val startedLabel = stringResource(R.string.stats_flow_started, stringResource(subjectTypeSpec(subjectType).verb.rootRes()))
    val names = card.branches.map { stringResource(it.type.labelResFor(subjectType)) }
    val shares = card.branches.map { stringResource(R.string.stats_share_percent, formatShare(it.share)) }
    // 画布上的数字读屏拿不到：每一行的人数与占比并进整张图的描述
    val branchTips = card.branches.mapIndexed { i, branch -> stringResource(R.string.stats_flow_branch_desc, names[i], branch.count, shares[i]) }
    val colors = card.branches.map { it.type.colors().color }
    val wish = CollectionType.WISH.colors().color
    val wishName = stringResource(CollectionType.WISH.labelResFor(subjectType))
    val trunk = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val nameStyle = MaterialTheme.typography.labelLargeEmphasized
    val countStyle = MaterialTheme.typography.labelLarge
    val shareStyle = MaterialTheme.typography.labelMedium
    val startedTip = stringResource(R.string.stats_flow_count_desc, startedLabel, card.started)
    val wishTip = stringResource(R.string.stats_flow_count_desc, wishName, card.wish)
    val description = (listOf(flowTitle(subjectType)) + branchTips + startedTip + wishTip).joinToString(stringResource(R.string.list_separator))
    TapChart(description, modifier.fillMaxWidth().height(flowHeight(card))) {
        val line = countStyle.lineHeight.toPx()
        val rows = flowRows(card.branches.map { it.share }, FLOW_NODE_BUDGET.toPx(), FLOW_NODE_MIN.toPx(), line, FLOW_ROW_GAP.toPx())
        val total = rows.last().let { it.top + it.height }
        val trunkRight = FLOW_TRUNK_WIDTH.toPx()
        val nodeLeft = widthPx * FLOW_NODES_AT
        val textLeft = nodeLeft + FLOW_NODE_WIDTH.toPx() + 8.dp.toPx()
        val space = 4.dp.toPx()
        val corner = CornerRadius(3.dp.toPx())
        fun baselineAt(center: Float) = center + countStyle.fontSize.toPx() / 3
        ChartLayout(rows.mapIndexed { i, row -> ChartMark(Rect(nodeLeft, row.top, widthPx, row.top + row.height), branchTips[i]) }) {
            drawRoundRect(trunk, Offset.Zero, Size(trunkRight, total), CornerRadius(4.dp.toPx()))
            var fromTop = 0f
            card.branches.forEachIndexed { i, branch ->
                val row = rows[i]
                val from = (branch.share * total).toFloat()
                val toTop = row.top + (row.height - row.node) / 2
                val mid = (trunkRight + nodeLeft) / 2
                val ribbon = Path().apply {
                    moveTo(trunkRight, fromTop)
                    cubicTo(mid, fromTop, mid, toTop, nodeLeft, toTop)
                    lineTo(nodeLeft, toTop + row.node)
                    cubicTo(mid, toTop + row.node, mid, fromTop + from, trunkRight, fromTop + from)
                    close()
                }
                drawPath(ribbon, colors[i].copy(alpha = FLOW_RIBBON_ALPHA * progress.chartFraction(i)))
                drawRoundRect(colors[i], Offset(nodeLeft, toTop), Size(FLOW_NODE_WIDTH.toPx(), row.node * progress.chartFraction(i)), corner)
                val baseline = baselineAt(row.top + row.height / 2)
                val shareWidth = textWidth(shares[i], shareStyle)
                val nameWidth = textWidth(names[i], nameStyle)
                val room = widthPx - shareWidth - space * 2 - textLeft
                label(names[i], textLeft, baseline, nameStyle, colors[i], ha = 0f, maxWidthPx = room)
                label("${branch.count}", textLeft + nameWidth + space, baseline, countStyle, ink, ha = 0f, maxWidthPx = room - nameWidth - space)
                label(shares[i], widthPx, baseline, shareStyle, muted, ha = 1f)
                fromTop += from
            }
            // 下面一行：左边开始看的人数，右边想看（虚线块：还没开始）
            val footer = total + FLOW_FOOTER_GAP.toPx()
            val footerBaseline = baselineAt(footer + line / 2)
            val startedWidth = textWidth(startedLabel, countStyle)
            label(startedLabel, 0f, footerBaseline, countStyle, muted, ha = 0f)
            label("${card.started}", startedWidth + space, footerBaseline, nameStyle, ink, ha = 0f)
            drawRoundRect(
                wish,
                Offset(nodeLeft, footer),
                Size(FLOW_NODE_WIDTH.toPx(), line),
                corner,
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
            )
            label(wishName, textLeft, footerBaseline, nameStyle, wish, ha = 0f)
            label("${card.wish}", textLeft + textWidth(wishName, nameStyle) + space, footerBaseline, countStyle, ink, ha = 0f)
        }
    }
}
