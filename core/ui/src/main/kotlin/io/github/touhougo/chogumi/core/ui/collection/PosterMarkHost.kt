package io.github.touhougo.chogumi.core.ui.collection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CollectionMarks
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.PendingMark
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.common.LoginGate
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate

/** 「我」对一部作品的标记：收藏状态（已提交或正在提交的）与窗口里待提交的那一笔（[CollectionMarks.pendingOf]）。 */
internal data class SubjectMark(val type: CollectionType?, val pending: PendingMark?) {
    companion object {
        fun of(marks: CollectionMarks, subjectId: Int) = SubjectMark(marks.types[subjectId], marks.pendingOf(subjectId))
    }
}

/** 页面的长按标记层；不在 [PosterMarkHost] 里为 null（海报不能长按标记）。 */
internal val LocalPosterMarkLayer = staticCompositionLocalOf<PosterMarkLayer?> { null }

/**
 * 有海报的页面装一层长按标记（全 App 唯一实现）：
 * 按住任意海报，以手指为圆心展开一圈状态（[RingSlot]），朝方向一滑松手即标；标完封面上留 4 秒可撤销，之后安静提交。
 *
 * 浮层（遮罩、浮起的封面、环、预演胶囊）画在本层里、页面内容之上：随页面进出，遮罩只盖页面，不盖底栏 / 导航轨。
 * 状态与动作来自页面 ViewModel 的 [PosterMarkController]；环上每一格都是写操作，未登录时长按经 [LoginGate] 去登录，不弹环。
 * 「管理」打开与列表行长按同一个收藏面板（[CollectionSheetHost]）；[snackbar] 是页面的提示条，面板开着时画在面板上。
 */
@Composable
fun PosterMarkHost(
    state: PosterMarkState,
    controller: PosterMarkController,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val gate = rememberLoginGate(isLoggedIn, onLogin)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val stateRef = rememberUpdatedState(state)
    val layer = remember { PosterMarkLayer(stateRef) }
    layer.controller = controller
    layer.gate = gate
    layer.haptics = haptics
    layer.density = density
    val session = layer.session
    SideEffect { layer.publish(state.marks) }
    CompositionLocalProvider(LocalPosterMarkLayer provides layer) {
        // 页面内容那一层与本层同大（最小约束传下去），里面照常是一个 Box
        Box(modifier.onGloballyPositioned { layer.coordinates = it }, propagateMinConstraints = true) {
            // 环开着时下面的页面对读屏隐藏（模态）
            Box(if (session?.open == true) Modifier.clearAndSetSemantics {} else Modifier, content = content)
            if (session != null) key(session) { RingOverlay(session, layer, Modifier.matchParentSize()) }
        }
    }
    val today = state.today ?: return
    CollectionSheetHost(
        state = state.sheet,
        work = state.sheet.fetched.valueOrNull(),
        today = today,
        snackbar = snackbar,
        onRetryEpisodes = controller::retrySheetEpisodes,
        onSave = controller::saveSheet,
        onRemove = controller::removeSheet,
        onClose = controller::closeSheet,
    )
}

/**
 * 一次长按打开的环：从哪按下（[press]）、环心（[center]，贴边时平移过）、浮起的封面在哪（[cover]）、打开时的状态（[current]）。坐标都在本层里。
 * [cover] 是封面看得见的那一块（轮播收窄的那一项只露出中间一段，浮起的也只是这一段，落回时与原位一样）；
 * 录下的是整张封面，它的左上角在 [coverContent]（相对 [cover]，收窄时为负）。
 */
@Stable
internal class RingSession(
    val mark: PosterMark,
    override val press: Offset,
    override val center: Offset,
    val cover: Rect,
    val coverContent: Offset,
    override val current: CollectionType?,
    override val subjectType: SubjectType,
) : RingLook {
    override var open by mutableStateOf(true)

    override var hot by mutableStateOf<RingSlot?>(null)

    override var chosen by mutableStateOf<RingSlot?>(null)

    /** 这一笔手势出过死区：滑出去又回到中心再松手是取消，一直没出过是把环留着点选。 */
    var leftDeadZone = false

    /** 浮层已经画出过这张封面：原位此后才空出来，接手的那一帧两处都有，封面不会空一帧。 */
    var coverLifted by mutableStateOf(false)
}

/** 页面的长按标记层：手势、环的开合与选择都经这里，状态只有页面状态里的那一份（[stateRef]）。 */
@Stable
internal class PosterMarkLayer(private val stateRef: State<PosterMarkState>) {
    lateinit var controller: PosterMarkController
    lateinit var gate: LoginGate
    lateinit var haptics: HapticFeedback
    lateinit var density: Density
    var coordinates: LayoutCoordinates? = null

    var session by mutableStateOf<RingSession?>(null)
        private set

    val marks: CollectionMarks get() = stateRef.value.marks

    /**
     * 每部作品一份「我」的标记，海报只读自己那一份（[markOf]）：页面的标记状态别处变了（别的作品被标、收藏面板开合），
     * [publish] 只改结果变了的那几部，其余海报不重组。随页面存在，不随海报离屏删除（一部一个小对象）。
     */
    private val subjectMarks = HashMap<Int, MutableState<SubjectMark>>()

    fun markOf(subjectId: Int): State<SubjectMark> = subjectMarks.getOrPut(subjectId) {
        // 取初值不算海报读了整份页面状态，否则页面状态一变它又跟着重组
        mutableStateOf(Snapshot.withoutReadObservation { SubjectMark.of(marks, subjectId) })
    }

    /** 每次组合之后跟上页面的标记：等值的写入不算变化，读它的海报不失效。 */
    fun publish(marks: CollectionMarks) {
        subjectMarks.forEach { (subjectId, mark) -> mark.value = SubjectMark.of(marks, subjectId) }
    }

    /**
     * 长按触发（[pressInBlock] 是按下点在整块里的位置；读屏的长按没有按下点，环以封面看得见的那一块的中心为心）：已登录就打开环、震一下，
     * 返回 true（这一笔手势接着跟）；未登录去登录页，返回 false。上一个环还没收完时不开新的。
     */
    fun begin(mark: PosterMark, pressInBlock: Offset?): Boolean {
        if (session != null) return false
        var opened = false
        gate.run {
            if (open(mark, pressInBlock) == null) return@run
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            opened = true
        }
        return opened
    }

    /**
     * 打开 [mark] 的环：按下点 [pressInBlock] 在整块里，null 时以封面看得见的那一块的中心为心。
     * 已有一个环、封面还没放置时打不开，返回 null。
     */
    private fun open(mark: PosterMark, pressInBlock: Offset?): RingSession? {
        if (session != null) return null
        val host = coordinates?.takeIf { it.isAttached } ?: return null
        val (coverRect, liftRect) = coverRectsOf(mark) ?: return null
        val block = mark.blockCoordinates?.takeIf { it.isAttached }
        val press = if (pressInBlock != null && block != null) host.localPositionOf(block, pressInBlock) else liftRect.center
        val extent = with(density) { (RingGeometry.Radius + RingGeometry.Push + RingGeometry.Button / 2 + RingGeometry.EdgeMargin).toPx() }
        val area = Rect(Offset.Zero, host.size.toSize())
        val id = mark.subjectId
        return RingSession(
            mark = mark,
            press = press,
            center = ringCenter(press, area, extent),
            cover = liftRect,
            coverContent = coverRect.topLeft - liftRect.topLeft,
            current = marks.pendingOf(id)?.type ?: marks.types[id],
            subjectType = mark.subjectType,
        ).also { session = it }
    }

    /**
     * [mark] 的整张封面与它看得见的那一块在本层里的位置（轮播收窄的封面只浮看得见的那一块，与角标、胶囊排进的是同一块，
     * 见 withinItemMask）；本层或封面还没放置时为 null。
     */
    fun coverRectsOf(mark: PosterMark): Pair<Rect, Rect>? {
        val host = coordinates?.takeIf { it.isAttached } ?: return null
        val cover = mark.coverCoordinates?.takeIf { it.isAttached } ?: return null
        val coverRect = host.localBoundingBoxOf(cover, clipBounds = false)
        val shown = mark.mask?.let { mask -> mask.offsetOf(cover)?.let { mask.within(IntRect(IntOffset.Zero, cover.size), it) } }
        val liftRect = shown?.let { Rect(host.localPositionOf(cover, it.topLeft.toOffset()), host.localPositionOf(cover, it.bottomRight.toOffset())) } ?: coverRect
        return coverRect to liftRect
    }

    /** 手指（仍按着）移到整块里的 [positionInBlock]：按方向换瞄准的那一格，每换一格轻震一下。 */
    fun drag(mark: PosterMark, positionInBlock: Offset) {
        val ring = session?.takeIf { it.open } ?: return
        val host = coordinates?.takeIf { it.isAttached } ?: return
        val block = mark.blockCoordinates?.takeIf { it.isAttached } ?: return
        val delta = host.localPositionOf(block, positionInBlock) - ring.press
        val slot = ringSlotAt(delta.x, delta.y, with(density) { RingGeometry.DeadZone.toPx() })
        if (slot != null) ring.leftDeadZone = true
        if (slot != ring.hot) {
            ring.hot = slot
            if (slot != null) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }

    /** 松手：指着一格就选它；滑出去又回到中心是取消；一直没离开中心就把环留着，点选或点遮罩 / 返回关闭。 */
    fun release() {
        val ring = session?.takeIf { it.open } ?: return
        val hot = ring.hot
        when {
            hot != null -> choose(hot)
            ring.leftDeadZone -> dismiss()
        }
    }

    /** 选 [slot]：管理打开收藏面板；选当前状态什么也不改；别的状态进待提交窗口。 */
    fun choose(slot: RingSlot) {
        val ring = session?.takeIf { it.open } ?: return
        ring.chosen = slot
        ring.open = false
        val id = ring.mark.subjectId
        when (val type = slot.type) {
            null -> controller.manage(id)
            ring.current -> Unit
            else -> controller.mark(id, type)
        }
    }

    fun dismiss() {
        session?.open = false
    }

    fun undo(subjectId: Int) = controller.undo(subjectId)

    /** 收起动画走完：撤掉浮层，原位的封面重新画出来。 */
    fun finish(ring: RingSession) {
        if (session === ring) session = null
    }
}
