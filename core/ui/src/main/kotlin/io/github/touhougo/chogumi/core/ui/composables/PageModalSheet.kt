package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.navigation.LocalPageSheetLayer
import io.github.touhougo.chogumi.core.ui.common.navigation.PageSheetSlotHost
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 页面内的模态底部面板：外观照 M3 modal bottom sheet（[BottomSheetDefaults] 的圆角、拖动条、遮罩、最大宽度），
 * 但**画在所在页的面板层里**（[io.github.touhougo.chogumi.core.ui.common.navigation.PageSheetLayer]），不开独立窗口——
 * 跳子页随页面离场、返回与页面同一帧出现，不会盖住别的页面，也没有建窗口的延迟。只能在导航项里用。
 *
 * - [fullHeight] = true：满高布局，首开半展开（M3：模态面板初始位置不超过屏高一半），可拖到展开；点拖动条也在两档间切换
 *   （M3：拖动条可点、在各档高度间切换，是拖动的单指替代），键盘弹出时自动展开。false：按内容高，只有展开一档
 *   （用户卡片这类小面板）。满高是产品设计决定，非 M3 高度 token。
 * - 非满高的面板顶边固定：高度在打开时按内容定下，这一次打开期间不再变；内容变高就在面板里滚，变矮就在底下留出空白
 *   （产品决定：面板上沿固定、向下长）。可用高度变了（转屏、改窗口）重新量。我的设计决定（not in M3）。
 *   [growsWithContent] = true 的面板例外，高度跟着内容走：只给回复框，它的表情面板占的是键盘让出的位置、输入框会长到最大行数，
 *   定住高度会把这些挤进打开时那一小块里。
 * - 展开也不顶到屏幕顶：上方留出 M3 的顶边距（containment.md Bottom sheets：72dp，窗口宽于 640dp 时 56dp、左右也各留 56dp），
 *   露出的那一截遮罩说明点空白处就能关，所以面板里不放关闭按钮（M3 只要求全屏的模态面板放）。
 * - 高度（停在哪一档）可保存：返回原样。真正关闭（拖到底 / 点遮罩 / 返回键 / 调用方要它收起，见 [hideRequested]）
 *   滑完后才回调 [onDismiss]，调用方据此把开关状态置回；重开是新周期，从隐藏滑上来。
 *   收起途中所在页离开（收起时下面的页面已经能点）：返回时直接关掉、回调 [onDismiss]，不会又滑上来。
 * - 内部列表滚到顶后继续下拉，交给面板往下拖；面板没到顶时上滑先把面板拉上去（与 M3 一致的嵌套滚动）。列表自己的甩动
 *   滚到顶剩下的速度不带动面板，只有手指已经把面板拖离档位时松手才按速度落档（快速往回甩列表不会把面板甩关）。
 * - 面板开着时页面的提示条在面板下面、看不见：会在面板里失败的（保存、发送）把页面的 [SnackbarHostState] 交给 [snackbar]，
 *   提示条画在面板底边（导航栏、键盘之上），页面那一处在面板开着时不画。面板里的提示条只在这里画。
 * - 面板与键盘不同时动（[inputFocus]）：打开时面板先滑上来停稳，才把焦点交给输入框、弹出键盘；收起时先收键盘，键盘落下
 *   （或等满 [KEYBOARD_SETTLE_MILLIS]）再滑走。键盘是系统另开的窗口、盖在页面上，各家键盘弹出的快慢、有没有动画都不一样：
 *   两者同时动，面板的动画就藏在键盘后面。分开动之后，键盘升落期间面板的底边逐帧跟着系统报的键盘高度走
 *   （[WindowInsets.ime]），键盘有动画就一起动，没有动画就在同一帧到位，哪一种都盖不住面板。
 *
 * 底部面板**一律**用它（或 [ScrollableModalSheet]），跳不跳页都一样：官方 ModalBottomSheet 是独立窗口，平板上按整个窗口居中、
 * 遮罩盖住导航轨，本组件在页面区里居中、遮罩只盖页面。会进子页的面板也不许用 Dialog / Popup，不许另做时序补丁；
 * 缺能力就扩展本组件。门禁：tools/guards/check_sheets.py（tools/ci/check.sh lint），规矩见 docs/code-standards.md。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageModalSheet(
    onDismiss: () -> Unit,
    fullHeight: Boolean,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    /** 调用方要它收起（如保存成功）：置 true 后滑下去，滑完照常回调 [onDismiss]。 */
    hideRequested: Boolean = false,
    /** 非满高面板的高度跟着内容走，不在打开时定住（见上）。 */
    growsWithContent: Boolean = false,
    /** 打开就要输入的面板：面板停稳后把焦点交给它（弹出键盘），收起前先收键盘（见上）。 */
    inputFocus: FocusRequester? = null,
    /** 面板开着时提示条画在面板上（见上）。 */
    snackbar: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val id = rememberSaveable { UUID.randomUUID().toString() }
    PageSheetSlotHost(id) {
        SheetLayer(id, onDismiss, fullHeight, !fullHeight && !growsWithContent, containerColor, hideRequested, inputFocus, snackbar, content)
    }
}

/** 非满高 [PageModalSheet] 定住的高度：打开后第一次量出的面板高度（像素）与当时的可用高度；可用高度变了（转屏、改窗口）重新量。 */
private class HeldHeight {
    var height = -1
    var available = -1
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SheetLayer(
    id: String,
    onDismiss: () -> Unit,
    fullHeight: Boolean,
    holdHeight: Boolean,
    containerColor: Color,
    hideRequested: Boolean,
    inputFocus: FocusRequester?,
    snackbar: SnackbarHostState?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    val layer = LocalPageSheetLayer.current
    var closed by remember { mutableStateOf(false) }
    // 真正关闭：丢掉本面板存的状态，再通知调用处（只通知一次）
    val close: () -> Unit = {
        if (!closed) {
            closed = true
            layer?.forget(id)
            dismiss()
        }
    }
    // 所在页在面板收起途中离开、现在返回：关闭已经开始了，直接关掉，不从存下的位置恢复（那样会又滑上来）
    if (remember { layer?.isClosing(id) == true }) {
        LaunchedEffect(Unit) { close() }
        return
    }

    val scope = rememberCoroutineScope()
    val held = remember { HeldHeight() }
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val state = rememberSaveable(saver = AnchoredDraggableState.Saver()) { AnchoredDraggableState(SheetValue.Hidden) }
    // 这一周期里面板离开过隐藏位置没有：首开滑上来、或返回时恢复在某一档。之后停回隐藏位置才是真正关闭
    var leftHidden by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    // 还没滑起来就被关（锚点还没量出来、首开动画没开始）：没有可收的动画，直接撤。
    // 有输入框的面板先收键盘、等它落下再滑走，面板的退场不藏在键盘后面
    val hide: () -> Unit = {
        if (leftHidden) {
            scope.launch {
                if (inputFocus != null && ime.getBottom(density) > 0) {
                    keyboard?.hide()
                    withTimeoutOrNull(KEYBOARD_SETTLE_MILLIS) { snapshotFlow { ime.getBottom(density) }.first { it == 0 } }
                }
                state.animateTo(SheetValue.Hidden, spatialSpec)
            }
        } else {
            close()
        }
    }

    // 在半展开与展开之间切换（点拖动条、读屏点拖动条）
    val toggle: () -> Unit = {
        scope.launch {
            state.animateTo(if (state.targetValue == SheetValue.Expanded) SheetValue.PartiallyExpanded else SheetValue.Expanded, spatialSpec)
        }
    }
    LaunchedEffect(hideRequested) { if (hideRequested) hide() }
    // 键盘弹出时半展开的面板展开：半屏剩下的地方被键盘占去大半，正在输入的地方看不见
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && fullHeight && state.targetValue == SheetValue.PartiallyExpanded) state.animateTo(SheetValue.Expanded, spatialSpec)
    }

    // 首开：锚点量出来后从隐藏滑到首档；恢复出来的（返回时）已不在隐藏，原样不动
    LaunchedEffect(state) {
        snapshotFlow { state.anchors.size }.first { it > 0 }
        if (state.currentValue == SheetValue.Hidden) {
            state.animateTo(if (fullHeight) SheetValue.PartiallyExpanded else SheetValue.Expanded, spatialSpec)
        }
    }
    // 有输入框的面板：首开滑完停稳才把焦点交给输入框，键盘这时才弹出。只交这一次：重建（转屏、深浅色）或从子页返回后
    // 面板原样恢复，用户是否在打字由内容自己的状态决定（预览、表情、自己收起的键盘），不在这里替它交回焦点
    var focusHanded by rememberSaveable { mutableStateOf(false) }
    if (inputFocus != null && !focusHanded) {
        LaunchedEffect(state, inputFocus) {
            snapshotFlow { state.settledValue != SheetValue.Hidden && !state.isAnimationRunning }.first { it }
            focusHanded = true
            inputFocus.requestFocus()
        }
    }
    // 关闭看位置不看 settledValue：首开动画半路被打断（刚开就点遮罩 / 按返回）时 settledValue 从头到尾都是「隐藏」、
    // 不会再变，靠它判关闭的话面板会一直登记在页面层里，遮罩挡住整页的触摸，底栏也以为面板还开着
    LaunchedEffect(state) {
        snapshotFlow { state.distanceAboveHidden() }.first { it != null && it > 0f }
        leftHidden = true
        snapshotFlow { state.distanceAboveHidden() == 0f && !state.isAnimationRunning }.first { it }
        close()
    }

    // 正在收起（往隐藏位置去、已经松手）：不再算开着——底栏、读屏、下面页面的触摸在面板开始收起时就回来，不等滑完
    val closing by remember {
        derivedStateOf {
            leftHidden && state.targetValue == SheetValue.Hidden && (state.isAnimationRunning || state.distanceAboveHidden() == 0f)
        }
    }
    LaunchedEffect(closing) { layer?.setClosing(id, closing) }
    val shown = !closing
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        // 一登记就接管返回键：首开动画还没开始时按返回，关的是面板，不是退出所在页
        isBackEnabled = shown,
        onBackCompleted = hide,
    )

    val closeLabel = stringResource(R.string.close)
    val handleLabel = stringResource(R.string.sheet_handle)
    val expandLabel = stringResource(R.string.sheet_expand)
    val collapseLabel = stringResource(R.string.sheet_collapse)
    val statusBarTop = WindowInsets.statusBars
    val nestedScroll = remember(state) { SheetNestedScrollConnection(state) { velocity -> scope.launch { state.settleBy(velocity, spatialSpec) } } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > BottomSheetDefaults.SheetMaxWidth
        val topMargin = if (wide) WideSheetMargin else SheetTopMargin
        ModalScrim(shown = shown, onDismiss = hide)
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = if (wide) WideSheetMargin else 0.dp)
                .widthIn(max = BottomSheetDefaults.SheetMaxWidth)
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val layerHeight = constraints.maxHeight
                    val top = maxOf(statusBarTop.getTop(this), topMargin.roundToPx())
                    val available = layerHeight - top
                    val placeable = if (holdHeight && held.available == available) {
                        measurable.measure(constraints.copy(minHeight = held.height, maxHeight = held.height))
                    } else {
                        measurable.measure(constraints.copy(minHeight = if (fullHeight) available else 0, maxHeight = available)).also {
                            held.height = it.height
                            held.available = available
                        }
                    }
                    state.updateAnchors(
                        DraggableAnchors {
                            SheetValue.Hidden at layerHeight.toFloat()
                            if (fullHeight) {
                                SheetValue.PartiallyExpanded at layerHeight / 2f
                                SheetValue.Expanded at top.toFloat()
                            } else {
                                SheetValue.Expanded at (layerHeight - placeable.height).toFloat()
                            }
                        },
                        // 恢复出来的半展开在非满高面板上不存在时，落到展开
                        state.targetValue.takeIf { fullHeight || it != SheetValue.PartiallyExpanded } ?: SheetValue.Expanded,
                    )
                    layout(placeable.width, layerHeight) {
                        val y = state.offset.takeUnless { it.isNaN() } ?: layerHeight.toFloat()
                        placeable.place(0, y.roundToInt())
                    }
                }
                .nestedScroll(nestedScroll)
                .anchoredDraggable(
                    state = state,
                    orientation = Orientation.Vertical,
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(state, animationSpec = spatialSpec),
                )
                .semantics { paneTitle = closeLabel },
            shape = BottomSheetDefaults.ExpandedShape,
            color = containerColor,
        ) {
            // 内容决定面板多高（最小高度照传给它）；提示条叠在内容上，与内容同大、贴着底边
            Box(propagateMinConstraints = true) {
                // 底边让开导航栏与键盘：键盘弹出时内容区缩到键盘上面，正在输入的地方能滚到眼前
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(if (fullHeight) Modifier.visibleHeightOnly(state) else Modifier)
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
                ) {
                    if (fullHeight) {
                        // 顶上一整条 48dp 都能点（M3：有拖动条时面板顶部 48dp 可交互）；读屏只念拖动条，角色是按钮
                        val expanded = state.targetValue == SheetValue.Expanded
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(interactionSource = null, indication = null, onClick = toggle)
                                .clearAndSetSemantics {
                                    contentDescription = handleLabel
                                    role = Role.Button
                                    onClick(label = if (expanded) collapseLabel else expandLabel) { toggle(); true }
                                },
                            contentAlignment = Alignment.Center,
                        ) { BottomSheetDefaults.DragHandle() }
                    } else {
                        BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                    }
                    content()
                }
                // 同样让开导航栏与键盘；满高面板停在半展开时只占屏幕内的那一截
                if (snackbar != null) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .then(if (fullHeight) Modifier.visibleHeightOnly(state) else Modifier)
                            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
                        contentAlignment = Alignment.BottomCenter,
                    ) { SnackbarHost(snackbar) }
                }
            }
        }
    }
}

/**
 * 满高面板停在半展开时，面板的下半截在屏幕外：内容区只取屏幕内的高度，列表滚到底时最后一行才在屏幕里，
 * 不会躲在屏幕边缘或手势条后面。随拖动逐帧在布局阶段读偏移，不触发重组；拖到半展开位置以下（去关闭）不再缩，
 * 展开时恒为 0。
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.visibleHeightOnly(state: AnchoredDraggableState<SheetValue>): Modifier = layout { measurable, constraints ->
    val anchors = state.anchors
    val offset = state.offset
    val hiddenBelow = if (offset.isNaN() || !anchors.hasPositionFor(SheetValue.PartiallyExpanded)) {
        0
    } else {
        (offset - anchors.minPosition()).coerceIn(0f, anchors.positionOf(SheetValue.PartiallyExpanded) - anchors.minPosition()).roundToInt()
    }
    val height = (constraints.maxHeight - hiddenBelow).coerceAtLeast(0)
    val placeable = measurable.measure(constraints.copy(minHeight = minOf(constraints.minHeight, height), maxHeight = height))
    // 报满高、内容贴顶：面板的 Surface 会把比它矮的内容居中
    layout(placeable.width, constraints.maxHeight) { placeable.place(0, 0) }
}

/** 松手后按速度落档：快速上甩 / 下甩去相邻一档，慢速就近（与 M3 面板一致的手感）。 */
@OptIn(ExperimentalMaterial3Api::class)
private suspend fun AnchoredDraggableState<SheetValue>.settleBy(velocity: Float, spec: androidx.compose.animation.core.AnimationSpec<Float>) {
    val positions = SheetValue.entries.filter { anchors.hasPositionFor(it) }.sortedBy { anchors.positionOf(it) }
    if (positions.isEmpty()) return
    val target = if (abs(velocity) > FLING_VELOCITY) {
        // 位置从上到下排；上甩（速度为负）找当前位置之上最近的一档，下甩找之下最近的一档
        if (velocity < 0) positions.lastOrNull { anchors.positionOf(it) < offset } ?: positions.first()
        else positions.firstOrNull { anchors.positionOf(it) > offset } ?: positions.last()
    } else {
        positions.minBy { abs(anchors.positionOf(it) - offset) }
    }
    animateTo(target, spec)
}

/**
 * 收起有输入框的面板时，最多等键盘落下多久（毫秒）：键盘落下有动画的，系统逐帧报高度、通常远短于此；不报高度的
 * （外接键盘、浮动键盘）键盘高度本来就是 0，不等。这只是兜底，免得系统一直不报时面板收不走。我的设计决定（not in M3）。
 */
private const val KEYBOARD_SETTLE_MILLIS = 500L

/** 展开时离窗口顶边的距离（containment.md Bottom sheets：Top margin 72dp）。 */
private val SheetTopMargin = 72.dp

/** 窗口宽于 640dp 时的顶边距与左右边距（containment.md Bottom sheets：56dp）。 */
private val WideSheetMargin = 56.dp

/** 甩动判定速度（px/s），设计决定（not in M3，取值与 M3 面板手感相近）。 */
private const val FLING_VELOCITY = 1200f

/** 面板与内部列表的嵌套滚动：列表到顶后手指下拉交给面板，面板未到顶时上滑先拉面板（照 M3 面板的做法）；列表自己的甩动不带动面板。 */
@OptIn(ExperimentalMaterial3Api::class)
private class SheetNestedScrollConnection(
    private val state: AnchoredDraggableState<SheetValue>,
    private val onFling: (Float) -> Unit,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0 && source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(available.y)) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        val top = state.anchors.minPosition()
        return if (available.y < 0 && state.offset > top) {
            onFling(available.y)
            available
        } else {
            Velocity.Zero
        }
    }

    // 内容自己甩到头剩下的速度不带动面板：手指先把面板拖离了档位（onPostScroll 里），松手时才按速度落档。
    // 否则在面板里的列表上快速往回甩，列表一滚到顶，剩下的速度就把整个面板甩下去关掉
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (!state.offAnchor()) return Velocity.Zero
        onFling(available.y)
        return available
    }
}

/** 面板被手指拖离了档位（停在两档之间）：差不到半个像素算在档位上。 */
@OptIn(ExperimentalMaterial3Api::class)
private fun AnchoredDraggableState<SheetValue>.offAnchor(): Boolean {
    if (offset.isNaN()) return false
    val nearest = anchors.closestAnchor(offset) ?: return false
    return abs(anchors.positionOf(nearest) - offset) > ANCHOR_TOLERANCE_PX
}

/** 判定「在档位上」的容差（像素）：布局取整的误差，不是手势阈值。 */
private const val ANCHOR_TOLERANCE_PX = 0.5f

/** 面板离隐藏位置还有多远（像素，0 = 停在隐藏位置）；锚点还没量出来时为 null。 */
@OptIn(ExperimentalMaterial3Api::class)
private fun AnchoredDraggableState<SheetValue>.distanceAboveHidden(): Float? =
    if (anchors.hasPositionFor(SheetValue.Hidden) && !offset.isNaN()) anchors.positionOf(SheetValue.Hidden) - offset else null

/**
 * 页面里模态浮层下的遮罩（全 App 唯一画法：页面面板、海报长按环）：`scrim` 色 32%（M3 foundations.md「Scrim: use the
 * `scrim` color role at 32% opacity」，即 [BottomSheetDefaults.ScrimColor]），随 [shown] 以 effects 弹簧淡入淡出；
 * 开着时点一下就 [onDismiss]，读屏读作「关闭」。收起中不挂触摸：空的触摸节点也会挡住下面的页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModalScrim(
    shown: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrimAlpha by animateFloatAsState(if (shown) 1f else 0f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "scrim")
    val scrimColor = BottomSheetDefaults.ScrimColor
    val closeLabel = stringResource(R.string.close)
    val dismiss by rememberUpdatedState(onDismiss)
    Canvas(
        modifier
            .fillMaxSize()
            .then(if (shown) Modifier.pointerInput(Unit) { detectTapGestures { dismiss() } } else Modifier)
            .semantics {
                if (shown) {
                    contentDescription = closeLabel
                    onClick { dismiss(); true }
                }
            },
    ) {
        drawRect(scrimColor.copy(alpha = scrimColor.alpha * scrimAlpha))
    }
}
