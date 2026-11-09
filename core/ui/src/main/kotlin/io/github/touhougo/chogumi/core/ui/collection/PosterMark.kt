package io.github.touhougo.chogumi.core.ui.collection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.COLLECTION_MARK_WINDOW_NANOS
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.PendingMark
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.navigation.VisibleArea
import io.github.touhougo.chogumi.core.ui.composables.CollectionTypeBadge
import io.github.touhougo.chogumi.core.ui.composables.LocalItemMask
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.colors
import io.github.touhougo.chogumi.core.ui.composables.commitCountdownBar
import io.github.touhougo.chogumi.core.ui.composables.iconRes
import io.github.touhougo.chogumi.core.ui.composables.labelResFor
import io.github.touhougo.chogumi.core.ui.composables.rememberCommitRemaining
import io.github.touhougo.chogumi.core.ui.composables.withinItemMask
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 一张代表作品的海报在长按标记里的那一份（全 App 唯一入口：海报卡 [io.github.touhougo.chogumi.core.ui.composables.PosterCard]、
 * 每日放送的两种卡、探索的热门大图都接它）。由 [rememberPosterMark] 取得，三处用法：
 * - 整块（封面 + 标题）挂 [posterMarkGesture]：按住、滑、松手；
 * - 封面交给 [io.github.touhougo.chogumi.core.ui.composables.SubjectPoster] 的 `mark`：环打开时封面浮到遮罩上方（画的是同一份内容），
 *   原位空出来；右上角是「我」的收藏状态、窗口里的待提交胶囊；
 * - 封面与整块水波纹的形状取 [posterCoverShape]：按住、浮起、落回都是这一个圆角。
 */
@Stable
class PosterMark internal constructor(
    internal val layer: PosterMarkLayer,
    internal val graphics: GraphicsLayer,
    internal val subjectId: Int,
) {
    internal var subjectType: SubjectType = SubjectType.UNKNOWN

    /** 封面此刻的圆角（[posterCoverShape] 里那一个动画）：原位的封面与浮起的那一份都读它，按住、浮起、落回是同一条变形。 */
    internal var corner: State<Dp> = mutableStateOf(0.dp)
    internal var actionLabel: String = ""
    /** 整块与封面的坐标：放置时记下的是活的坐标对象，长按时才从它读位置，滚动途中不必每帧更新。 */
    internal var blockCoordinates: LayoutCoordinates? = null
    internal var coverCoordinates: LayoutCoordinates? = null

    /** 封面在轮播里时轮播把它收窄成的那一块（[LocalItemMask]）；不在轮播里为 null。浮起的封面只浮这一块。 */
    internal var mask: VisibleArea? = null

    private val mark = layer.markOf(subjectId)

    /** 这一部在窗口里待提交的那一笔；没有就是 null（分数这类压在封面底部的角标据此让位）。 */
    val pending: PendingMark? get() = mark.value.pending

    /** 「我」对这一部的收藏状态（已提交或正在提交的）；没收藏是 null。 */
    internal val type: CollectionType? get() = mark.value.type

    /** 环这一轮是这张封面的（开着或正在收起），且浮层已画出它：封面浮在遮罩上方，原位不画。 */
    internal val lifted: Boolean get() = ring?.coverLifted == true

    /** 这张封面的那一轮环；不是这张的、没开环是 null。 */
    internal val ring: RingSession? get() = layer.session?.takeIf { it.mark === this }
}

/** 在 [PosterMarkHost] 里的海报取得它的 [PosterMark]；不在（没装长按标记的页面）是 null，海报照常可点、不能长按标记。 */
@Composable
fun rememberPosterMark(subjectId: Int, subjectType: SubjectType): PosterMark? {
    val layer = LocalPosterMarkLayer.current ?: return null
    val graphics = rememberGraphicsLayer()
    val mark = remember(layer, graphics, subjectId) { PosterMark(layer, graphics, subjectId) }
    mark.subjectType = subjectType
    mark.actionLabel = stringResource(R.string.poster_mark)
    mark.mask = LocalItemMask.current
    return mark
}

/**
 * 封面的形状（海报卡、每日放送卡、热门大图共用；封面本身与整块的按下水波纹都用它）：静止是 [restCorner]。
 * 可长按标记的封面按住时圆角收到 [PosterChargedCorner]（比它更圆的封面不变），作「要出东西了」的预示（形状变化表达长按，
 * interaction.md）；环开着时保持，收起时随封面落回原位回到 [restCorner]。原位与浮起的那一份读同一个动画（[PosterMark.corner]），
 * 浮起、落回的那一帧圆角不会跳。按住变形走主题的 fast spatial；落回与浮层的缩放同一种不回弹的 standard default spatial（motion.md）。
 */
@Composable
fun posterCoverShape(mark: PosterMark?, interactionSource: InteractionSource, restCorner: Dp): Shape {
    if (mark == null) return remember(restCorner) { RoundedCornerShape(restCorner) }
    val pressed by interactionSource.collectIsPressedAsState()
    val ring = mark.ring
    val corner = animatePosterCorner(charged = pressed || ring?.open == true, landing = ring != null && !ring.open, restCorner = restCorner)
    mark.corner = corner
    return RoundedCornerShape(corner.value)
}

/**
 * 可长按封面的圆角（[posterCoverShape] 的那段动画，手势引导的演示封面也用它）：[charged]（按着、环开着）时收到 [PosterChargedCorner]，
 * 否则是 [restCorner]；[landing]（环正在收起、封面落回）时走 standard default spatial，其余走 fast spatial。
 */
@Composable
internal fun animatePosterCorner(charged: Boolean, landing: Boolean, restCorner: Dp): State<Dp> {
    val spec = if (landing) MaterialTheme.motionScheme.defaultSpatialSpec<Dp>() else MaterialTheme.motionScheme.fastSpatialSpec()
    return animateDpAsState(if (charged) maxOf(restCorner, PosterChargedCorner) else restCorner, spec, label = "poster_corner")
}

/**
 * 长按时还不完整的封面（封面流侧边那张侧转着、书脊立着只露一条）：[pending] 为真时先 [complete]（转到正中、抽出整张），
 * 完整了再开环。由画这种封面的样式给 [posterMarkGesture]；`remember` 住，手势节点按它取键，按下途中不换。
 */
@Stable
class PosterMarkPrelude(
    /** 长按触发的那一刻封面是否还不完整。 */
    val pending: () -> Boolean,
    /** 把封面变完整，动画走完才返回。 */
    val complete: suspend () -> Unit,
)

/**
 * 长按手势（全 App 唯一一份）：按住到系统长按时长打开环；手指不松开接着滑、松手即选（[PosterMarkLayer.drag] / [PosterMarkLayer.release]）。
 * 长按触发后这一笔手势的事件在 Initial 阶段就吃掉：外层的滚动不会动、整块的点击也不会触发。手势节点始终挂着，
 * 按下途中不增删（docs/code-standards.md「手势只认开始时的修饰符」）。读屏的长按动作打开同一个环（不跟手势，环留着点选）。
 *
 * 封面还不完整（[prelude] 的 `pending`）时先把它变完整，再以封面中心开环：这一笔手势照样吃掉，但不跟手（变完整途中封面挪了位置，
 * 按下点对不上了），环留着点选，与读屏打开的一样。
 */
fun Modifier.posterMarkGesture(mark: PosterMark?, prelude: PosterMarkPrelude? = null): Modifier = if (mark == null) this else this
    .onPlaced { mark.blockCoordinates = it }
    .semantics { onLongClick(mark.actionLabel) { mark.layer.begin(mark, pressInBlock = null) } }
    .pointerInput(mark, prelude) {
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                val tracking = if (prelude?.pending() == true) {
                    launch {
                        prelude.complete()
                        mark.layer.begin(mark, pressInBlock = null)
                    }
                    false
                } else {
                    mark.layer.begin(mark, longPress.position)
                }
                longPress.consume()
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == longPress.id } ?: break
                    event.changes.forEach { it.consume() }
                    if (!change.pressed) {
                        if (tracking) mark.layer.release()
                        break
                    }
                    if (tracking) mark.layer.drag(mark, change.position)
                }
            }
        }
    }

/**
 * 封面那一块：内容录进 [PosterMark.graphics]，环打开时由浮层画这一份（同一张图、同样的角标），原位不画。
 * 只由 [io.github.touhougo.chogumi.core.ui.composables.SubjectPoster] 挂在封面的容器上（包住封面图与压在上面的内容，裁切在录的内容里）。
 */
internal fun Modifier.posterMarkCover(mark: PosterMark?): Modifier = if (mark == null) this else this
    .onPlaced { mark.coverCoordinates = it }
    .drawWithContent {
        mark.graphics.record { this@drawWithContent.drawContent() }
        if (!mark.lifted) drawLayer(mark.graphics)
    }

/**
 * 压在封面上的两样（全 App 唯一画法，由 [io.github.touhougo.chogumi.core.ui.composables.SubjectPoster] 画）：右上角「我」的收藏状态（[CollectionTypeBadge]，内缩 6），窗口里换成封面底部的待提交胶囊
 * （[PendingMarkChip]）；提交成功胶囊收起、角标以新状态弹出，失败或撤销回到原来的角标。
 * 两样都排在封面看得见的那一块里（[withinItemMask]）：轮播收窄的封面只露出中间一段，角标贴着露出来的右上角，胶囊随它变窄。
 */
@Composable
internal fun BoxScope.PosterMarkBadges(mark: PosterMark?) {
    if (mark == null) return
    MarkBadges(mark.type, mark.pending, mark.subjectType, onUndo = { mark.layer.undo(it.subjectId) })
}

/** [PosterMarkBadges] 的画法本身（手势引导的演示封面也画它）：[type] 是右上角的状态，[pending] 是窗口里的待提交胶囊。 */
@Composable
internal fun BoxScope.MarkBadges(type: CollectionType?, pending: PendingMark?, subjectType: SubjectType, onUndo: (PendingMark) -> Unit) {
    val motion = MaterialTheme.motionScheme
    // 退场动画期间值已为 null：沿用最后一次的，免得退场时内容先变没
    var shownType by remember { mutableStateOf(type) }
    if (type != null) shownType = type
    var shownPending by remember { mutableStateOf(pending) }
    if (pending != null) shownPending = pending
    Box(Modifier.matchParentSize().withinItemMask()) {
        AnimatedVisibility(
            visible = pending == null && type != null,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            enter = scaleIn(motion.fastSpatialSpec(), initialScale = 0.4f, transformOrigin = TransformOrigin(1f, 0f)) +
                fadeIn(motion.fastEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
        ) {
            shownType?.let { CollectionTypeBadge(it, subjectType, tinted = true, labeled = false) }
        }
        AnimatedVisibility(
            visible = pending != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = scaleIn(motion.fastSpatialSpec(), initialScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                fadeIn(motion.fastEffectsSpec()),
            exit = scaleOut(motion.defaultEffectsSpec(), targetScale = 0.85f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                fadeOut(motion.defaultEffectsSpec()),
        ) {
            shownPending?.let { PendingMarkChip(it, subjectType, onUndo = { onUndo(it) }) }
        }
    }
}

/**
 * 待提交胶囊（原型 `.chip`）：封面底部内缩 6、高 32、全圆角，底色是新状态的浅底色（[colors]，不透明：压在任意封面图上要保对比，
 * 理由同分数徽章），和环上那一格、提交后的角标同一种颜色；
 * 填充的状态图标 18 + `labelLargeEmphasized` 的新状态 + 撤销图标；底边是倒计时细条（[commitCountdownBar]，按真实时间走 4 秒）。
 * 整块点一下就撤销（触控区由 Compose 补到 48）。尺寸是设计决定（not in M3）。
 *
 * 胶囊随封面看得见的宽度变窄（轮播里收窄的那一项），放不下时按次序丢：整个状态名放得下才画（不画半截或「…」），
 * 再窄丢撤销图标，只剩状态图标居中；不挤扁图标。整块仍是撤销，读屏的说明不变。
 */
@Composable
private fun PendingMarkChip(pending: PendingMark, subjectType: SubjectType, onUndo: () -> Unit) {
    val label = stringResource(pending.type.labelResFor(subjectType))
    val undoLabel = stringResource(R.string.poster_mark_undo, label)
    val remaining = rememberCommitRemaining(pending.commitAtNanos, COLLECTION_MARK_WINDOW_NANOS)
    val colors = pending.type.colors()
    val container = colors.container
    val content = colors.onContainer
    PressArea(
        onClick = onUndo,
        shape = CircleShape,
        modifier = Modifier
            .padding(6.dp)
            .fillMaxWidth()
            .semantics { contentDescription = undoLabel },
    ) {
        Layout(
            content = {
                Icon(painterResource(pending.type.iconRes(filled = true)), contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = content,
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics {},
                )
                Icon(painterResource(R.drawable.undo_24), contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PendingChipHeight)
                .clip(CircleShape)
                .background(container)
                .commitCountdownBar(remaining, content)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val icon = measurables[0].measure(loose)
            val undo = measurables[2].measure(loose)
            val gap = PendingChipGap.roundToPx()
            val labelWidth = measurables[1].maxIntrinsicWidth(loose.maxHeight)
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else icon.width + undo.width + labelWidth + gap * 2
            val room = width - icon.width - undo.width - gap * 2
            val text = if (labelWidth <= room) measurables[1].measure(Constraints(maxWidth = room, maxHeight = loose.maxHeight)) else null
            val withUndo = text != null || icon.width + gap + undo.width <= width
            val height = constraints.constrainHeight(maxOf(icon.height, undo.height, text?.height ?: 0))
            layout(width, height) {
                if (!withUndo) {
                    icon.place((width - icon.width) / 2, (height - icon.height) / 2)
                    return@layout
                }
                icon.place(0, (height - icon.height) / 2)
                text?.place(icon.width + gap, (height - text.height) / 2)
                undo.place(width - undo.width, (height - undo.height) / 2)
            }
        }
    }
}

/** 待提交 chip 的最小高（字大时随行高长高；我的设计决定，not in M3）。 */
private val PendingChipHeight = 32.dp

/** 胶囊里图标与状态名之间的间距（设计决定，not in M3）。 */
private val PendingChipGap = 4.dp
