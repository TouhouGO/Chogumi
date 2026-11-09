package io.github.touhougo.chogumi.core.ui.collection

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.CollectionTypeColors
import io.github.touhougo.chogumi.core.ui.composables.ModalScrim
import io.github.touhougo.chogumi.core.ui.composables.colors
import io.github.touhougo.chogumi.core.ui.composables.fullThenShort
import io.github.touhougo.chogumi.core.ui.composables.iconRes
import io.github.touhougo.chogumi.core.ui.composables.labelRes
import io.github.touhougo.chogumi.core.ui.composables.labelResFor
import io.github.touhougo.chogumi.core.ui.composables.neutralMarkColors
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 浮起的封面：放大 1.06、阴影 Level 3（tokens.md Elevation level 3 = 6dp）；放大与阴影是设计决定（not in M3）。 */
private const val LiftScale = 1.06f
internal val LiftElevation = 6.dp

/**
 * 按住预示时封面收到的圆角（CornerLarge），浮起时也是它（形状变化表达长按，interaction.md）；静止已比它圆的封面
 * （热门大图 28）不变。收到 16 是设计决定（not in M3），见 [posterCoverShape]。
 */
internal val PosterChargedCorner = 16.dp

/** 圆钮：平时圆形，瞄准时变按下形状（CornerMedium），当前状态是选中形状（CornerLarge）——component-tokens.md MediumIconButton。 */
private val SlotCornerRound = RingGeometry.Button / 2
private val SlotCornerPressed = 12.dp
private val SlotCornerSelected = 16.dp

/** 圆钮阴影 Level 2（3dp）、逐个弹出的间隔（设计决定，M3 不规定 stagger）。 */
internal val SlotElevation = 3.dp
private const val SlotStaggerMillis = 15L

/** 圆钮从中心弹出时的起始缩放、选中那一格原地缩到的比例（设计决定）。 */
private const val SlotCollapsedScale = 0.3f
private const val ChosenShrinkScale = 0.6f

/** 中心的死区圈：白描边 2、整体透明 0.7（设计决定，原型 `.hub`）。手势引导里的手指是同一个圈填实。 */
private val HubStroke = 2.dp
internal const val HubAlpha = 0.7f
internal const val HubStrokeAlpha = 0.75f

/** 瞄准线：粗 3、从死区外 4 画到圆钮边外 6（设计决定，原型 `.aim`）。 */
private val AimStroke = 3.dp
private val AimStartGap = 4.dp
private val AimEndGap = 6.dp

/** 预演胶囊：最小高 32、离环 8、图标 18（设计决定，原型 `.pv`）。 */
private val PreviewHeight = 32.dp
private val PreviewGap = 8.dp

/**
 * 环怎么画只看这几样：按下点、环心、打开时的状态、条目类型，以及此刻开着没有、指着哪一格、选了哪一格。
 * 真环是 [RingSession]；手势引导的演示环（tour 包）也给它，画法只有 [RingLayers] 一份。
 */
internal interface RingLook {
    val press: Offset
    val center: Offset
    val current: CollectionType?
    val subjectType: SubjectType

    /** false = 正在收起（动画走完才撤掉浮层）。 */
    val open: Boolean

    /** 手指此刻指着的那一格。 */
    val hot: RingSlot?

    /** 选中的那一格：收起时它原地缩掉，其余收回中心。 */
    val chosen: RingSlot?
}

@Composable
internal fun RingOverlay(ring: RingSession, layer: PosterMarkLayer, modifier: Modifier) {
    val density = LocalDensity.current
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = ring.open,
        onBackCompleted = layer::dismiss,
    )
    RingLayers(ring, onDismiss = layer::dismiss, onChoose = layer::choose, onClosed = { layer.finish(ring) }, modifier) { liftScale ->
        // 浮起的封面：画的是封面原位录下的同一份内容，只露出原位看得见的那一块（RingSession.cover），圆角与原位同一个动画（posterCoverShape）
        val cover = ring.cover
        Box(
            Modifier
                .offset { IntOffset(cover.left.roundToInt(), cover.top.roundToInt()) }
                .size(with(density) { cover.width.toDp() }, with(density) { cover.height.toDp() })
                .graphicsLayer {
                    scaleX = liftScale()
                    scaleY = liftScale()
                    shape = RoundedCornerShape(ring.mark.corner.value)
                    clip = true
                    shadowElevation = LiftElevation.toPx()
                }
                .drawBehind {
                    translate(ring.coverContent.x, ring.coverContent.y) { drawLayer(ring.mark.graphics) }
                    ring.coverLifted = true
                },
        )
    }
}

/**
 * 环的几层与它们的展开收起（真环与手势引导的演示环共用）：遮罩、浮起的封面（[cover]，拿到此刻的放大倍数）、中心圈与瞄准线、六格、
 * 瞄准时的预演胶囊。收起的动画走完调 [onClosed]。
 */
@Composable
internal fun RingLayers(
    ring: RingLook,
    onDismiss: () -> Unit,
    onChoose: (RingSlot) -> Unit,
    onClosed: () -> Unit,
    modifier: Modifier,
    cover: @Composable (liftScale: () -> Float) -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val appMotion = LocalAppMotion.current
    val open = ring.open
    val slots = RingSlot.entries
    val progress = remember { slots.map { Animatable(0f) } }
    val fade = remember { Animatable(0f) }
    val hub = remember { Animatable(0.4f) }
    val liftScale = remember { Animatable(1f) }
    val shrink = remember { Animatable(0f) }

    LaunchedEffect(open) {
        coroutineScope {
            if (open) {
                slots.indices.forEach { i ->
                    launch {
                        delay(appMotion.stagger(SlotStaggerMillis * i))
                        progress[i].animateTo(1f, appMotion.expressive.defaultSpatialSpec())
                    }
                }
                launch { fade.animateTo(1f, motion.fastEffectsSpec()) }
                launch { hub.animateTo(1f, appMotion.expressive.fastSpatialSpec()) }
                launch { liftScale.animateTo(LiftScale, motion.defaultSpatialSpec()) }
            } else {
                // 展开时圆钮与中心钮弹出（expressive，会过冲）；收起不回弹：其余圆钮收回中心、选中的原地缩掉，封面落回原位（standard spatial）
                slots.forEachIndexed { i, slot ->
                    if (slot != ring.chosen) launch { progress[i].animateTo(0f, motion.defaultSpatialSpec()) }
                }
                launch { shrink.animateTo(1f, motion.defaultEffectsSpec()) }
                launch { fade.animateTo(0f, motion.fastEffectsSpec()) }
                launch { hub.animateTo(0.4f, motion.fastSpatialSpec()) }
                launch { liftScale.animateTo(1f, motion.defaultSpatialSpec()) }
            }
        }
        if (!open) onClosed()
    }

    Box(modifier) {
        ModalScrim(shown = open, onDismiss = onDismiss)
        cover { liftScale.value }
        AimAndHub(ring, fade = { fade.value }, hubScale = { hub.value })
        slots.forEachIndexed { i, slot ->
            RingButton(
                ring = ring,
                slot = slot,
                progress = { progress[i].value },
                fade = { fade.value },
                shrink = { if (slot == ring.chosen) shrink.value else 0f },
                onClick = { onChoose(slot) },
            )
        }
        if (open) ring.hot?.let { hot -> key(hot) { RingPreview(ring, hot) } }
    }
}

/** 中心的死区圈与从中心指向瞄准那一格的线。 */
@Composable
private fun AimAndHub(ring: RingLook, fade: () -> Float, hubScale: () -> Float) {
    val hot = ring.hot
    var lastHot by remember { mutableStateOf(hot) }
    if (hot != null) lastHot = hot
    // 线是指着的那一格的实色
    val lineColor = lastHot?.let { markColors(it.type).color } ?: Color.Transparent
    val aimAlpha by animateFloatAsState(if (hot != null && ring.open) 1f else 0f, MaterialTheme.motionScheme.fastEffectsSpec(), label = "aim")
    val push by animateFloatAsState(if (hot != null) 1f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "aim_push")
    Canvas(Modifier.fillMaxSize()) {
        val dead = RingGeometry.DeadZone.toPx()
        drawCircle(
            color = Color.White.copy(alpha = HubStrokeAlpha),
            radius = dead * hubScale(),
            center = ring.press,
            alpha = HubAlpha * fade().coerceIn(0f, 1f),
            style = Stroke(HubStroke.toPx()),
        )
        val target = lastHot ?: return@Canvas
        val tip = ringSlotCenter(ring.center, target, RingGeometry.Radius.toPx() + RingGeometry.Push.toPx() * push)
        val delta = tip - ring.press
        val length = hypot(delta.x, delta.y)
        if (length <= 0f) return@Canvas
        val unit = delta / length
        val start = ring.press + unit * (dead + AimStartGap.toPx())
        val end = tip - unit * (RingGeometry.Button.toPx() / 2 + AimEndGap.toPx())
        drawLine(lineColor, start, end, strokeWidth = AimStroke.toPx(), cap = StrokeCap.Round, alpha = aimAlpha)
    }
}

/**
 * 环上的一格（Medium icon button 的几何，component-tokens.md MediumIconButton：56、图标 24，平时圆形）。每格是自己状态的颜色
 * （[markColors]）：平时浅底色 + 阴影 Level 2；瞄准时变按下形状（CornerMedium）、换成实色、往外探 8；当前状态是选中形状
 * （CornerLarge）加实心图标，颜色同平时。钮里图标在上、两个字在下（labelSmall，瞄准或选中时 Emphasized）。
 */
@Composable
private fun RingButton(
    ring: RingLook,
    slot: RingSlot,
    progress: () -> Float,
    fade: () -> Float,
    shrink: () -> Float,
    onClick: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val colors = markColors(slot.type)
    val isHot = ring.hot == slot
    val isCurrent = slot.type != null && slot.type == ring.current
    val push by animateFloatAsState(if (isHot) 1f else 0f, motion.defaultSpatialSpec(), label = "slot_push")
    val corner by animateDpAsState(
        when {
            isHot -> SlotCornerPressed
            isCurrent -> SlotCornerSelected
            else -> SlotCornerRound
        },
        motion.fastSpatialSpec(),
        label = "slot_corner",
    )
    val container by animateColorAsState(
        when {
            isHot -> colors.color
            else -> colors.container
        },
        motion.fastEffectsSpec(),
        label = "slot_container",
    )
    val content by animateColorAsState(
        when {
            isHot -> colors.onColor
            else -> colors.onContainer
        },
        motion.fastEffectsSpec(),
        label = "slot_content",
    )
    val type = slot.type
    val fullLabel = stringResource(type?.labelResFor(ring.subjectType) ?: R.string.poster_mark_manage)
    val shortLabel = stringResource(type?.labelRes() ?: R.string.poster_mark_manage)
    val labelStyle = if (isHot || isCurrent) MaterialTheme.typography.labelSmallEmphasized else MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val slotWidth = with(LocalDensity.current) { RingGeometry.Button.roundToPx() }
    // 槽位定宽：全名放得下写全名，放不下退到短名（与收藏面板的状态格同一个判定），短名再放不下才在句尾省略
    val label = fullThenShort(fullLabel, shortLabel) { measurer.measure(it, labelStyle, maxLines = 1, softWrap = false).size.width <= slotWidth }
        ?: shortLabel
    val icon = type?.iconRes(filled = isCurrent) ?: R.drawable.edit_24
    Box(
        Modifier
            .layout { measurable, constraints ->
                val size = RingGeometry.Button.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(size, size))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val radius = RingGeometry.Radius.toPx() + RingGeometry.Push.toPx() * push
                    val position = lerp(ring.center, ringSlotCenter(ring.center, slot, radius), progress())
                    placeable.place((position.x - size / 2f).roundToInt(), (position.y - size / 2f).roundToInt())
                }
            }
            .graphicsLayer {
                val scale = (SlotCollapsedScale + (1f - SlotCollapsedScale) * progress()) * (1f - (1f - ChosenShrinkScale) * shrink())
                scaleX = scale
                scaleY = scale
                alpha = (fade() * (1f - shrink())).coerceIn(0f, 1f)
                shape = RoundedCornerShape(corner)
                clip = true
                shadowElevation = SlotElevation.toPx()
            }
            .background(container)
            .semantics { selected = isCurrent }
            .clickable(enabled = ring.open, role = Role.Button, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
            Text(
                text = label,
                style = labelStyle,
                color = content,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 瞄准预演（原型 `.pv`）：环的正上方（放不下就正下方）浮出一枚胶囊，写着松手后会变成什么，拇指挡不到它。
 * 底色是那一格的浅底色（[markColors]），和松手后封面上的待提交胶囊、提交后的角标同一种颜色。
 * 换一格就重新弹出一次。
 */
@Composable
private fun RingPreview(ring: RingLook, slot: RingSlot) {
    val motion = MaterialTheme.motionScheme
    val pop = remember { Animatable(0.6f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { pop.animateTo(1f, motion.fastSpatialSpec()) }
        launch { alpha.animateTo(1f, motion.fastEffectsSpec()) }
    }
    val type = slot.type
    val label = stringResource(type?.labelResFor(ring.subjectType) ?: R.string.poster_mark_manage)
    val icon = type?.iconRes(filled = true) ?: R.drawable.edit_24
    val colors = markColors(type)
    val container = colors.container
    val content = colors.onContainer
    Row(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints())
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val extent = (RingGeometry.Radius + RingGeometry.Push + RingGeometry.Button / 2).toPx()
                    val area = Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                    val top = ringPreviewTop(ring.center, area, extent, placeable.height.toFloat(), PreviewGap.toPx())
                    val edge = RingGeometry.EdgeMargin.roundToPx()
                    val x = (ring.center.x - placeable.width / 2f).roundToInt()
                        .coerceIn(edge, (constraints.maxWidth - placeable.width - edge).coerceAtLeast(edge))
                    placeable.place(x, top.roundToInt())
                }
            }
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
                this.alpha = alpha.value
                shape = CircleShape
                clip = true
                shadowElevation = SlotElevation.toPx()
            }
            .background(container)
            .heightIn(min = PreviewHeight)
            .padding(start = 10.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(text = label, style = MaterialTheme.typography.labelLargeEmphasized, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 环上一格的颜色：状态格读收藏状态色（[colors]），「管理」不是状态，用中性色。 */
@Composable
private fun markColors(type: CollectionType?): CollectionTypeColors =
    type?.colors() ?: neutralMarkColors(MaterialTheme.colorScheme)
