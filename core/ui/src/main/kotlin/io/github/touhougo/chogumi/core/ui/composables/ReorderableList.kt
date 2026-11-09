package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.touhougo.chogumi.core.res.R

/**
 * [reorderableItems] 里一行能用的东西。
 *
 * @property isDragging 这一行正被拖着
 * @property segmentIndex 交给 [SegmentedItem] 的位置：拖着的那一行单独成一段（四角都是组外角），其余按列表里的位置
 * @property segmentCount 交给 [SegmentedItem] 的总数
 */
interface ReorderableItemScope {
    val isDragging: Boolean
    val segmentIndex: Int
    val segmentCount: Int

    /** 把手：长按就抬起这一行，接着上下拖动排序。 */
    fun Modifier.dragHandle(): Modifier

    /**
     * 整行：长按之后手指一动就抬起这一行拖动排序（抬起时调 [rememberReorderState] 的 `onLift`）；长按不动、松手，
     * 这一行照旧是它自己的长按（菜单）。用在没有把手、长按另有用处的行上。
     */
    fun Modifier.dragOnLongPress(): Modifier
}

/** 拖动排序时行的底色（全仓唯一）：拖着的那一行升一级 surface container，其余是分段列表的默认底色。 */
val ReorderableItemScope.containerColor: Color
    @Composable get() = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer

/**
 * 一列可拖动排序的行（[rememberReorderState] 得到，交给同一个列表里的 [reorderableItems]）：显示的顺序、这一次拖动。
 * 拖动中的顺序只在这里；松手后交给 `onMove`，它接受了就照旧显示，直到条目换成新顺序；不接受就立刻回到条目的顺序。
 */
@Stable
class ReorderState<T> internal constructor(
    internal val shown: List<T>,
    internal val key: (T) -> String,
    internal val order: SnapshotStateList<String>,
    internal val drag: DragState,
    internal val commit: () -> Unit,
    internal val lift: () -> Unit,
)

/**
 * 拖动排序的状态。[items] 是外部的顺序；[onMove] 在松手时收到整列新的 key 顺序，返回 false 表示不接受（屏上回到 [items]
 * 的顺序）；[onLift] 在一行被 [ReorderableItemScope.dragOnLongPress] 抬起时调（收起这一行的长按菜单）。
 */
@Composable
fun <T> rememberReorderState(
    items: List<T>,
    key: (T) -> String,
    list: LazyListState,
    onMove: (keys: List<String>) -> Boolean,
    onLift: () -> Unit = {},
): ReorderState<T> {
    val keys = items.map(key)
    // 显示的顺序：[items] 一变就跟上；拖动中、松手后到 [items] 换成新顺序之前是本地的
    val order = remember(keys) { keys.toMutableStateList() }
    val drag = remember(list) { DragState(list) }
    val move by rememberUpdatedState(onMove)
    val currentKeys by rememberUpdatedState(keys)
    val byKey = items.associateBy(key)
    return ReorderState(
        shown = order.mapNotNull { byKey[it] },
        key = key,
        order = order,
        drag = drag,
        commit = {
            if (!move(order.toList())) {
                order.clear()
                order.addAll(currentKeys)
            }
        },
        lift = onLift,
    )
}

/**
 * 可拖动排序、可左滑移除的一列分段列表（M3 没有这个组件，my design decision (not in M3)）：[rememberReorderState] +
 * [reorderableItems] 占满一个列表，行间距是 M3 的 [ListItemDefaults.SegmentedGap]。每一行由调用方画成 [SegmentedItem]
 * （形状按 [ReorderableItemScope.segmentIndex] / [ReorderableItemScope.segmentCount]），排序用行里的把手。
 */
@Composable
fun <T> ReorderableList(
    items: List<T>,
    key: (T) -> String,
    onMove: (keys: List<String>) -> Unit,
    onRemove: (T) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    itemContent: @Composable ReorderableItemScope.(item: T) -> Unit,
) {
    val reorder = rememberReorderState(items, key, state, onMove = { keys -> onMove(keys); true })
    LazyColumn(
        state = state,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        modifier = modifier.fillMaxSize(),
    ) {
        reorderableItems(reorder, onRemove = onRemove) { _, item -> itemContent(item) }
    }
}

/**
 * 可拖动排序的行（全仓唯一的拖动排序实现），放进调用方自己的列表，和别的项（吸顶行、页脚）排在一起；行的 key 就是
 * [state] 的 key，换位只在这些行之间发生。
 *
 * - 排序：长按把手（[ReorderableItemScope.dragHandle]），或长按整行后一动（[ReorderableItemScope.dragOnLongPress]），
 *   上下拖，拖着的行中线越过别的行就换位，松手才交出整列新顺序；拖到列表上下边缘外，列表跟着滚。拖着的那一行抬起：阴影升到
 *   M3 elevation level 3（tokens.md Elevation；containment.md Cards「Pick up & move: increase elevation while moving」，
 *   取第几级是我的设计决定），其余行让位走 motionScheme 的 spatial 规格。[enabled] 为 false 时行照常显示，不能拖。
 * - 移除（给了 [onRemove] 才有）：一行往左滑停在删除按钮处，点击按钮才移除，往回滑收起。
 *   只开从尾到头一个方向（containment.md Cards「One swipe action per card only」）。
 * - 读屏：能拖时每行有「上移」「下移」，能移除时还有「移除」，不拖也能排序和移除。
 * - [rowModifier]：第几行的外边距（左右留白、行间距），在抬起的阴影外面，阴影只贴着行本身。
 *
 * 手势：拖动监听只以行的 key 为键，拖动途中列表换位不会换掉它（docs/code-standards.md「手势只认开始时的修饰符」）。监听走 Initial 一轮：行里自己的点按、长按（菜单）照常，长按之后的移动归拖动，列表与左右翻页不再接手。
 */
fun <T> LazyListScope.reorderableItems(
    state: ReorderState<T>,
    enabled: Boolean = true,
    onRemove: ((T) -> Unit)? = null,
    rowModifier: (index: Int) -> Modifier = { Modifier },
    itemContent: @Composable ReorderableItemScope.(index: Int, item: T) -> Unit,
) {
    itemsIndexed(state.shown, key = { _, item -> state.key(item) }) { index, item ->
        ReorderableRow(state, index, item, enabled, onRemove, rowModifier(index), itemContent)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> LazyItemScope.ReorderableRow(
    state: ReorderState<T>,
    index: Int,
    item: T,
    enabled: Boolean,
    onRemove: ((T) -> Unit)?,
    rowModifier: Modifier,
    itemContent: @Composable ReorderableItemScope.(index: Int, item: T) -> Unit,
) {
    val itemKey = state.key(item)
    val drag = state.drag
    // 行与手势节点保持身份，外部顺序更新后仍从同一入口读到当前列表与回调。
    val current by rememberUpdatedState(state)
    val scope = remember(itemKey, drag) {
        RowScope(itemKey, drag, order = { current.order }, commit = { current.commit() }, lift = { current.lift() })
    }
    val dragging = drag.key == itemKey
    val count = state.shown.size
    scope.isDragging = dragging
    scope.enabled = enabled
    scope.segmentIndex = if (dragging) 0 else index
    scope.segmentCount = if (dragging) 1 else count
    val shape = ListItemDefaults.segmentedShapes(index = scope.segmentIndex, count = scope.segmentCount).shape
    val moveUp = stringResource(R.string.reorder_move_up)
    val moveDown = stringResource(R.string.reorder_move_down)
    val remove = stringResource(R.string.reorder_remove)
    val removeItem: (() -> Unit)? = onRemove?.let { { it(item) } }
    Box(
        rowModifier
            .zIndex(if (dragging) 1f else 0f)
            .then(
                if (dragging) {
                    Modifier.graphicsLayer {
                        translationY = drag.translation
                        shadowElevation = DraggedElevation.toPx()
                        this.shape = shape
                    }
                } else {
                    Modifier.animateItem(
                        fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                        placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                    )
                },
            )
            .semantics {
                customActions = listOfNotNull(
                    CustomAccessibilityAction(moveUp) { scope.step(-1) }.takeIf { enabled && index > 0 },
                    CustomAccessibilityAction(moveDown) { scope.step(1) }.takeIf { enabled && index < count - 1 },
                    removeItem?.let { CustomAccessibilityAction(remove) { it(); true } },
                )
            },
    ) {
        if (removeItem != null) {
            SwipeRevealRow(enabled = drag.key == null, onRemove = removeItem) {
                scope.itemContent(index, item)
            }
        } else {
            scope.itemContent(index, item)
        }
    }
}

/** 用与面板相同的锚点拖动和空间动画，在关闭与露出删除按钮之间停靠。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SwipeRevealRow(enabled: Boolean, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val state = remember { AnchoredDraggableState(false) }
    val width = with(LocalDensity.current) { DeleteActionWidth.toPx() }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) -1 else 1
    SideEffect {
        state.updateAnchors(DraggableAnchors {
            false at 0f
            true at width * direction
        })
    }
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    Box(
        Modifier.fillMaxWidth().clipToBounds().anchoredDraggable(
            state = state,
            orientation = Orientation.Horizontal,
            enabled = enabled,
            reverseDirection = false,
            flingBehavior = AnchoredDraggableDefaults.flingBehavior(state, animationSpec = spatialSpec),
        ),
    ) {
        if (!state.offset.isNaN() && state.offset != 0f) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.width(DeleteActionWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    FilledIconButton(
                        onClick = onRemove,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(painterResource(R.drawable.delete_24), stringResource(R.string.reorder_remove))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().graphicsLayer { translationX = state.offset.takeUnless { it.isNaN() } ?: 0f }) {
            content()
        }
    }
}

/** 删除操作的停靠宽度，my design decision (not in M3)。 */
private val DeleteActionWidth = 64.dp

/** 拖着的行抬起的阴影：M3 elevation level 3（tokens.md Elevation）。 */
internal val DraggedElevation = 6.dp

/** 一次拖动：拖的是哪一行、手指走了多远。只有一个手指在拖（同一时刻只有一行）。 */
internal class DragState(private val list: LazyListState) {
    var key: String? by mutableStateOf(null)
        private set

    /** 开始拖时那一行的位置与之后手指走过的距离：行的位置 = 两者之和，与它在列表里换到了哪一格无关。 */
    private var startOffset = 0
    private var dragged by mutableFloatStateOf(0f)

    /** 拖着的那一行画在哪：手指的位置减去它在列表里现在那一格的位置。 */
    val translation: Float
        get() = current()?.let { startOffset + dragged - it.offset } ?: 0f

    private fun current(): LazyListItemInfo? = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    fun start(itemKey: String) {
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey } ?: return
        key = itemKey
        startOffset = info.offset
        dragged = 0f
    }

    /** 手指又走了 [delta]：拖着的行中线越过哪一行就和它换位（[order] 里），越出列表上下边缘就滚列表。 */
    fun drag(delta: Float, order: MutableList<String>) {
        val info = current() ?: return
        dragged += delta
        val top = startOffset + dragged
        val middle = top + info.size / 2f
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull { other ->
            other.key != key && other.key in order && middle.toInt() in other.offset..(other.offset + other.size)
        }
        if (target != null) {
            val from = order.indexOf(key)
            val to = order.indexOf(target.key)
            // 换到首个可见项的位置时，列表默认按首项锚定会跟着跳一格：先把滚动位置钉住
            if (info.index == list.firstVisibleItemIndex || target.index == list.firstVisibleItemIndex) {
                list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
            }
            order.add(to, order.removeAt(from))
            return
        }
        val viewportTop = list.layoutInfo.viewportStartOffset
        val viewportBottom = list.layoutInfo.viewportEndOffset
        val overscroll = when {
            delta < 0 && top < viewportTop -> top - viewportTop
            delta > 0 && top + info.size > viewportBottom -> top + info.size - viewportBottom
            else -> 0f
        }
        if (overscroll != 0f) list.dispatchRawDelta(overscroll)
    }

    fun end() {
        key = null
        dragged = 0f
    }
}

/** 一行的 [ReorderableItemScope]：把手与整行的拖动、读屏的上移下移。 */
private class RowScope(
    private val itemKey: String,
    private val drag: DragState,
    private val order: () -> MutableList<String>,
    private val commit: () -> Unit,
    private val lift: () -> Unit,
) : ReorderableItemScope {
    override var isDragging: Boolean by mutableStateOf(false)
    override var segmentIndex: Int by mutableStateOf(0)
    override var segmentCount: Int by mutableStateOf(1)

    /** 这一列现在能不能拖（[reorderableItems] 的 `enabled`）。 */
    var enabled: Boolean by mutableStateOf(true)

    override fun Modifier.dragHandle(): Modifier = longPressDrag(liftOnPress = true)

    override fun Modifier.dragOnLongPress(): Modifier = longPressDrag(liftOnPress = false)

    private fun Modifier.longPressDrag(liftOnPress: Boolean): Modifier = if (!enabled) {
        this
    } else {
        pointerInput(itemKey, liftOnPress) {
            detectLongPressDrag(
                liftOnPress = liftOnPress,
                onLift = {
                    if (!liftOnPress) lift()
                    drag.start(itemKey)
                },
                onDrag = { drag.drag(it, order()) },
                onDrop = {
                    drag.end()
                    commit()
                },
            )
        }
    }

    /** 读屏的上移（-1）、下移（1）一格，当即交出新顺序。 */
    fun step(by: Int): Boolean {
        val order = order()
        val from = order.indexOf(itemKey)
        val to = from + by
        if (from < 0 || to !in order.indices) return false
        order.add(to, order.removeAt(from))
        commit()
        return true
    }
}

/**
 * 长按后拖动的手势（[RowScope] 的把手与整行共用）：在 Initial 一轮看事件，长按之前什么都不消费（点按、长按菜单、列表滚动照常），
 * 手指在长按前抬起或移出触摸阈值就作罢。长按之后这一手指的事件都归这里：[liftOnPress] 时长按当即抬起，否则等手指移出
 * 触摸阈值才抬起（长按不动就松手，只是行自己的长按）；抬起后每次移动交给 [onDrag]，松手或手势被打断时 [onDrop]。
 */
private suspend fun PointerInputScope.detectLongPressDrag(
    liftOnPress: Boolean,
    onLift: () -> Unit,
    onDrag: (Float) -> Unit,
    onDrop: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    val slop = viewConfiguration.touchSlop
    // 长按之前作罢就等下一次按下；超时（null）才是长按
    if (withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitAbandon(down, slop) } != null) return@awaitEachGesture
    var lifted = false
    var travelled = Offset.Zero
    try {
        if (liftOnPress) {
            onLift()
            lifted = true
        }
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (change.changedToUpIgnoreConsumed()) break
            val delta = change.positionChangeIgnoreConsumed()
            if (lifted) {
                onDrag(delta.y)
            } else {
                travelled += delta
                if (travelled.getDistance() > slop) {
                    onLift()
                    lifted = true
                    onDrag(travelled.y)
                }
            }
        }
    } finally {
        if (lifted) onDrop()
    }
}

/** 等到这次按下作罢：手指抬起、被别处消费，或移出触摸阈值 [slop]。 */
private suspend fun AwaitPointerEventScope.awaitAbandon(down: PointerInputChange, slop: Float) {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return
        if (change.changedToUpIgnoreConsumed() || change.isConsumed) return
        if ((change.position - down.position).getDistance() > slop) return
    }
}
