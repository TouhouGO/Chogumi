package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Modifier.Node
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import kotlin.math.max
import kotlin.math.min

/*
 * 第一次进来的聚焦引导（全 App 唯一实现）：整页压上遮罩（[ModalScrim]），只挖出这一步指着的那一块，旁边一张富提示卡
 * （[RichTipCard]）说明。卡上的文字按钮是往下走、结束的唯一途径：点遮罩、按返回都不收起——一点就过去的引导会让人错过功能。
 * 页面里要被指着的地方挂 [spotlightTarget]，报来自己在窗口里的位置；还没量到（内容还在加载）的那一步什么也不画，量到才出现。
 */

/** 页面里能被引导指着的那几块在窗口里的位置（[spotlightTarget] 报来）。 */
@Stable
class SpotlightGuideState internal constructor() {
    internal val targets = mutableStateMapOf<String, Rect>()

    /** [step] 此刻盖在页面上（它指着的那一块量到了）：开着时下面的页面摸不到，页面据此也对读屏隐藏。 */
    fun covers(step: SpotlightStep?): Boolean = step != null && step.target in targets
}

@Composable
fun rememberSpotlightGuideState(): SpotlightGuideState = remember { SpotlightGuideState() }

/**
 * 引导的一步：亮出 [target] 那一块（形状 [shape]），卡上是 [title]、[text] 与按钮 [actions]（排在一行，从起点排起）。
 * 这一块上还要画别的东西（长按环的演示）时，[clearance] 是从它中心往外要留空的距离：卡开在这一圈之外，不压住它。
 */
@Immutable
class SpotlightStep(
    val target: String,
    val shape: CornerBasedShape,
    val title: String,
    val text: String,
    val actions: List<SpotlightAction>,
    val clearance: Dp = 0.dp,
)

/** 卡上的一个文字按钮。 */
@Immutable
class SpotlightAction(val label: String, val onClick: () -> Unit)

/**
 * 把这一块报给引导，叫作 [key]：每次放置记下它在窗口里的位置，离开组合就撤掉。[state] 为 null（这一页不出引导）时什么也不做。
 */
fun Modifier.spotlightTarget(state: SpotlightGuideState?, key: String): Modifier =
    if (state == null) this else this then SpotlightTargetElement(state, key)

/**
 * 引导的遮罩：压暗整页，只挖出这一步指着的那一块（与 [SpotlightGuide] 同一个 [state]、[step]，与页面同大）。换一步时洞挪到新的那一块
 * （空间弹簧）；洞左右不出页面边距 [ChogumiDimens.ScreenPadding]（占满整宽的标题行也是一块圆角，不贴着屏幕边）。
 * 与 [SpotlightGuide] 分开画，是为了中间能夹一层要露在遮罩上面、又要被卡盖住的东西（演示里的长按环）：一层遮罩，没有换手。
 */
@Composable
fun SpotlightScrim(state: SpotlightGuideState, step: SpotlightStep?, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    SpotlightLayer(state, step, modifier) { drawn, hole, shown ->
        val animatedHole by animateRectAsState(hole, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "spotlight_hole")
        val corner by animateFloatAsState(
            drawn.shape.topStart.toPx(hole.size, density),
            MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "spotlight_corner",
        )
        // 第一次出现也淡入：先以「没开」组合一帧
        var settled by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { settled = true }
        ModalScrim(
            shown = shown && settled,
            onDismiss = null,
            cutout = { Path().apply { addRoundRect(RoundRect(animatedHole, CornerRadius(corner))) } },
        )
    }
}

/**
 * 引导的卡与挡手的那一层，盖在 [SpotlightScrim] 之上（同一个 [state]、[step]，与页面同大）。[step] 为 null 时不出现；换一步时卡重新弹出。
 * 开着时下面的页面摸不到（点哪里都不穿下去），返回也不收起，只有卡上的按钮。
 */
@Composable
fun SpotlightGuide(state: SpotlightGuideState, step: SpotlightStep?, modifier: Modifier = Modifier) {
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.covers(step),
        onBackCompleted = {},
    )
    SpotlightLayer(state, step, modifier) { drawn, hole, shown ->
        if (shown) {
            Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
        }
        key(drawn.target) { GuideCard(drawn, hole, visible = shown) }
    }
}

/**
 * 遮罩与卡共用的一层：量出自己在窗口里的位置，算出这一步的洞（本层坐标，左右收进页面边距），把最后一步、它的洞、此刻开没开交给 [content]。
 * 退场时接着给最后一步（遮罩淡出、卡缩回）；还没有过任何一步时什么也不画。
 */
@Composable
private fun SpotlightLayer(
    state: SpotlightGuideState,
    step: SpotlightStep?,
    modifier: Modifier,
    content: @Composable BoxScope.(drawn: SpotlightStep, hole: Rect, shown: Boolean) -> Unit,
) {
    var origin by remember { mutableStateOf<Rect?>(null) }
    val density = LocalDensity.current
    val target = step?.let { state.targets[it.target] }
    val here = origin
    // 只在组合里读写，不是界面状态
    val last = remember { LastStep() }
    if (step != null && target != null && here != null) {
        last.step = step
        val margin = with(density) { ChogumiDimens.ScreenPadding.toPx() }
        val hole = target.translate(-here.topLeft)
        last.hole = Rect(max(hole.left, margin), hole.top, min(hole.right, here.width - margin), hole.bottom)
    }
    val shown = state.covers(step) && here != null
    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInWindow() }) {
        val drawn = last.step ?: return@Box
        content(drawn, last.hole, shown)
    }
}

/**
 * 卡放在哪只由 [tipPosition] 定（富提示：开在洞的右下，放不下按 8dp 一档往左挪，离洞 [TipSpacing]；左右不出页面边距
 * [ChogumiDimens.ScreenPadding]）；进出场与别的提示同一套（从 0.8 倍缩放，fast spatial + fast effects）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuideCard(step: SpotlightStep, hole: Rect, visible: Boolean) {
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = visible
    val motion = MaterialTheme.motionScheme
    val density = LocalDensity.current
    Layout(
        content = {
            AnimatedVisibility(
                visibleState = shown,
                enter = scaleIn(motion.fastSpatialSpec(), initialScale = TIP_FROM_SCALE) +
                    fadeIn(motion.fastEffectsSpec()),
                exit = scaleOut(motion.fastSpatialSpec(), targetScale = TIP_FROM_SCALE) +
                    fadeOut(motion.fastEffectsSpec()),
            ) {
                RichTipCard(maxWidth = TooltipDefaults.richTooltipMaxWidth) {
                    Text(step.title, style = MaterialTheme.typography.titleSmall)
                    Text(step.text, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        step.actions.forEach { action -> TextButton(onClick = action.onClick) { Text(action.label) } }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val margin = with(density) { ChogumiDimens.ScreenPadding.roundToPx() }
        val host = IntRect(margin, 0, constraints.maxWidth - margin, constraints.maxHeight)
        val card = measurables.firstOrNull()?.measure(Constraints(maxWidth = host.width.coerceAtLeast(0), maxHeight = constraints.maxHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            card ?: return@layout
            val position = tipPosition(
                anchor = keepClear(hole, with(density) { step.clearance.toPx() }).roundToIntRect(),
                tip = IntSize(card.width, card.height),
                host = host,
                windowHeight = constraints.maxHeight,
                spacing = with(density) { TipSpacing.roundToPx() },
                step = with(density) { TipStep.roundToPx() },
                kind = TipKind.Rich,
            )
            card.place(position)
        }
    }
}

/** [hole] 加上从它中心往外 [reach] 的一圈（卡要避开的范围）。 */
private fun keepClear(hole: Rect, reach: Float): Rect {
    val center = hole.center
    return Rect(
        min(hole.left, center.x - reach),
        min(hole.top, center.y - reach),
        max(hole.right, center.x + reach),
        max(hole.bottom, center.y + reach),
    )
}

private class LastStep {
    var step: SpotlightStep? = null
    var hole: Rect = Rect.Zero
}

private class SpotlightTargetElement(val state: SpotlightGuideState, val key: String) : ModifierNodeElement<SpotlightTargetNode>() {
    override fun create() = SpotlightTargetNode(state, key)

    override fun update(node: SpotlightTargetNode) {
        if (node.state !== state || node.key != key) {
            node.forget()
            node.state = state
            node.key = key
        }
    }

    override fun equals(other: Any?) = other is SpotlightTargetElement && other.state === state && other.key == key

    override fun hashCode() = 31 * state.hashCode() + key.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "spotlightTarget"
        properties["key"] = key
    }
}

private class SpotlightTargetNode(var state: SpotlightGuideState, var key: String) : Node(), GlobalPositionAwareModifierNode {
    /** 这个节点报过的位置：撤掉时只撤自己报的那一份，别的节点接手了同一个名字就不动。 */
    private var reported: Rect? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val bounds = coordinates.boundsInWindow()
        reported = bounds
        state.targets[key] = bounds
    }

    override fun onDetach() = forget()

    fun forget() {
        if (reported != null && state.targets[key] == reported) state.targets.remove(key)
        reported = null
    }
}
