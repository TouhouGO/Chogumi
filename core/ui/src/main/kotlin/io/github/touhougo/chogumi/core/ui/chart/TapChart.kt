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
 *
 * 画与点只有一份几何：
 * - 一个标记（ChartMark）就是图上画出来的一块，自己画自己：anchor 是这一块，draw 拿着它画，气泡也指着它。
 *   图不另算一份「命中区」去对画出来的东西。
 * - 点中谁按画出来的那一块判（markAt）：手指落在哪一块上就是哪一块。hit 只是把这一块周围的空白也算给它
 *   （整列、整行、点周围一圈）；几块都要这一处时归画出来离手指最近的那一块，与声明的先后无关。
 * - 画出来的形状不是矩形、给不出「这一块」的（环的段、几条交叠的折线）：标记是图例里它的那一行，形状本身画在底图里。
 * - 不是标记的东西（轴、刻度、参考线）画在 ChartLayout 的 backdrop（标记之下）或 foreground（标记之上），点不到。
 */

/**
 * 一个可点的标记，自己画自己。[anchor] 是它画出来的那一块（柱、段、格，点的外接方块），[draw] 拿到的就是它；
 * 气泡开在这一块上方，上方放不下时开在它下方，不盖住它。[label] 气泡文字，[multiline] 为长说明启用可折行的富提示。
 * [hit] 是算作点中它的范围：默认就是画出来的那一块，要把旁边的空白也算给它（整列、整行、点周围一圈）才另给；
 * 点中谁仍按 [anchor] 判（[markAt]）。
 */
class ChartMark(
    val anchor: Rect,
    val label: String,
    val hit: Rect = anchor,
    val multiline: Boolean = false,
    val draw: DrawScope.(anchor: Rect) -> Unit,
)

/** 一张图：[marks] 按顺序各画各的（后面的在上层），[backdrop] 画在它们之下，[foreground] 画在它们之上，后两者点不到。 */
class ChartLayout(
    val marks: List<ChartMark>,
    val backdrop: DrawScope.() -> Unit = {},
    val foreground: DrawScope.() -> Unit = {},
)

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
 * 点中的标记，按画出来的那一块判：
 * 1. 要这一处的几块（手指在它画出来的那一块 [ChartMark.anchor] 上，或在算给它的范围 [ChartMark.hit] 里）之中，
 *    取画出来离手指最近的那一块：手指就在某一块上，就是它；几块画得叠着（密的点、密的柱）取中心离手指最近的；
 *    再一样近取后画的（在上层）。不看谁后声明——柱再密、各自要的那一列互相叠着，点到的也是手指底下的那一根；
 * 2. 哪一块都不要这一处时取 [slop] 以内 [ChartMark.hit] 最近的一块；再没有为 null（点空白处）。
 */
internal fun markAt(marks: List<ChartMark>, point: Offset, slop: Float): ChartMark? =
    marks.asReversed().filter { it.anchor.contains(point) || it.hit.contains(point) }
        .minWithOrNull(compareBy({ it.anchor.distanceTo(point) }, { (it.anchor.center - point).getDistance() }))
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
 */
@Composable
fun TapChart(
    description: String,
    modifier: Modifier = Modifier,
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
                .pointerInput(slop) {
                    detectTapGestures { point ->
                        val hit = markAt(marks, point, slop)
                        if (hit != null) {
                            showTip(hit)
                        } else {
                            // 点空白处收起正在显示的气泡
                            scope.launch { ChartTipMutex.mutate { } }
                        }
                    }
                },
        ) {
            layout.backdrop(this)
            layout.marks.forEach { it.draw(this, it.anchor) }
            layout.foreground(this)
        }
        // 每个标记在读屏里是自己的一个节点，盖在算作点中它的范围上：读出气泡文字，双击出气泡。看不见图的人同样读得到每一块的数。
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
