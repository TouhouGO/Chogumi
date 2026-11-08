package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import io.github.touhougo.chogumi.core.ui.composables.AnchoredTip
import io.github.touhougo.chogumi.core.ui.composables.PlainTip
import io.github.touhougo.chogumi.core.ui.composables.RichTip
import io.github.touhougo.chogumi.core.ui.composables.TipKind
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 所有统计图共用的点按层：图上不写确切的数，点一下由同一个气泡给出（生涯统计与条目统计都走这里）。
 */

/**
 * 一个可点的标记：[hit] 命中区，[label] 气泡文字，[anchor] 气泡指着的那一块（画出来的柱、段、格），
 * 气泡开在它上方，上方放不下时开在它下方，不盖住它。[multiline] 为长说明启用可折行的富提示。
 */
class ChartMark(val hit: Rect, val label: String, val anchor: Rect = hit, val multiline: Boolean = false)

class ChartLayout(val marks: List<ChartMark>, val draw: DrawScope.() -> Unit)

/** 图表的作图环境：画布像素尺寸 + 文字测量。 */
class ChartScope internal constructor(
    val widthPx: Float,
    val heightPx: Float,
    private val measurer: TextMeasurer,
    density: Density,
) : Density by density {
    /**
     * 画一行文字。[x] 是对齐点、[ha] 为 0 / 0.5 / 1 表示左 / 中 / 右对齐于它，[baseline] 是基线 y。
     * 横向不出画布：贴边的字整行挪回画布里，不被裁掉。
     */
    fun DrawScope.label(
        text: String,
        x: Float,
        baseline: Float,
        style: TextStyle,
        color: Color,
        ha: Float = 0.5f,
        maxWidthPx: Float = Float.POSITIVE_INFINITY,
    ) {
        val bounded = if (maxWidthPx.isFinite()) Constraints(maxWidth = maxWidthPx.toInt().coerceAtLeast(0)) else Constraints()
        val laid = measurer.measure(text, style, TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = bounded)
        val left = (x - laid.size.width * ha).coerceIn(0f, max(0f, widthPx - laid.size.width))
        drawText(laid, color, Offset(left, baseline - laid.firstBaseline))
    }

    /** 一行文字排出来有多宽（像素），给要按字宽排位的图（两段字接在一行、标签互相让位）。 */
    fun textWidth(text: String, style: TextStyle): Float =
        measurer.measure(text, style, softWrap = false, maxLines = 1).size.width.toFloat()
}

/** 气泡停留多久：与 M3 普通提示的自动消失时长一致（1.5 秒）。 */
private const val CHART_TIP_MILLIS = 1500L

/**
 * 手指落在两块之间的缝里时，离它不超过这么远的那块算点中（格子、柱子之间常有 2–4dp 的缝，窄屏上一格只有十几 dp）。
 * 我的设计决定（not in M3）。
 */
private val CHART_TAP_SLOP = 8.dp

/**
 * 点中的标记：命中区里有它就取它（后画的在上层，与绘制顺序一致，取最后一个）；
 * 落在缝里时取 [slop] 以内最近的一块；都没有为 null（点空白处）。
 */
internal fun markAt(marks: List<ChartMark>, point: Offset, slop: Float): ChartMark? =
    marks.lastOrNull { it.hit.contains(point) }
        ?: marks.map { it to it.hit.distanceTo(point) }.filter { it.second <= slop }.minByOrNull { it.second }?.first

/** 点到矩形的距离（在里面为 0）。 */
private fun Rect.distanceTo(point: Offset): Float {
    val dx = max(max(left - point.x, 0f), point.x - right)
    val dy = max(max(top - point.y, 0f), point.y - bottom)
    return hypot(dx, dy)
}

/** 全 App 的图表气泡共用一把锁：新点出的气泡收走上一个（不论在哪张图上），同一时刻只有一个。 */
private val ChartTipMutex = MutatorMutex()

/**
 * 可点图表：[build] 在画布尺寸已知时给出绘制与标记。点到标记就在它上方 4dp 出一个气泡（放不下时在下方），
 * 1.5 秒后消失；点空白处、或别处点出新气泡时收起。气泡的外观取 M3 普通提示（容器色、内容色、外形、
 * bodySmall、最小 40×24dp、内边距 8 / 4dp），进出场同 M3 提示（缩放 0.8 走 fastSpatial、淡入淡出走 fastEffects）。
 * 气泡由 [AnchoredTip] 开在自己的不可点窗口里，浮在版面之上、不占位置，横向不出图表两边，也不挡住对旁边那块的点按。
 * 手机上点按触发（M3 写的是长按），是我的设计决定（not in M3）：图上不写数，点一下就该看到。
 * [onTap] 给不用气泡、自己处理点击的图。
 */
@Composable
fun TapChart(
    description: String,
    modifier: Modifier = Modifier,
    onTap: ((point: Offset, widthPx: Float) -> Unit)? = null,
    build: ChartScope.() -> ChartLayout,
) {
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    var tip by remember { mutableStateOf<ChartMark?>(null) }
    var shown by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val layout = ChartScope(widthPx, heightPx, measurer, density).build()
        val marks by rememberUpdatedState(layout.marks)
        val slop = with(density) { CHART_TAP_SLOP.toPx() }
        val tapped by rememberUpdatedState(onTap)
        val showTip: (ChartMark) -> Unit = { hit ->
            scope.launch {
                // 拿锁就收走正在显示的气泡（它的 finally 把它藏起）
                ChartTipMutex.mutate {
                    tip = hit
                    shown = true
                    try {
                        delay(CHART_TIP_MILLIS)
                    } finally {
                        shown = false
                    }
                }
            }
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics { contentDescription = description }
                .pointerInput(widthPx, slop) {
                    detectTapGestures { point ->
                        tapped?.invoke(point, widthPx)
                        val hit = markAt(marks, point, slop)
                        if (hit != null) {
                            showTip(hit)
                        } else {
                            // 点空白处收起正在显示的气泡
                            scope.launch { ChartTipMutex.mutate { } }
                        }
                    }
                },
        ) { layout.draw(this) }
        // 每个标记在读屏里是自己的一个节点，盖在它的命中区上：读出气泡文字，双击出气泡。看不见图的人同样读得到每一块的数。
        // 不做成图上的一串自定义动作：一个节点的自定义动作有上限（格子图、点阵动辄上百块），超过时读屏一来就崩。
        layout.marks.forEach { mark ->
            Box(
                Modifier
                    .offset { IntOffset(mark.hit.left.roundToInt(), mark.hit.top.roundToInt()) }
                    .size(with(density) { mark.hit.width.toDp() }, with(density) { mark.hit.height.toDp() })
                    .clearAndSetSemantics {
                        contentDescription = mark.label
                        onClick {
                            showTip(mark)
                            true
                        }
                    },
            )
        }
        tip?.let { current ->
            AnchoredTip(visible = shown, kind = if (current.multiline) TipKind.Rich else TipKind.Plain, anchor = { current.anchor.roundToIntRect() }) {
                if (current.multiline) RichTip(current.label) else PlainTip(current.label)
            }
        }
    }
}
