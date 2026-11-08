package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.navigation3.runtime.NavEntryDecorator

/**
 * 页面面板层：每个导航项自带一层，页面里的模态面板**画在所在页这一层里**，不是独立系统窗口。
 *
 * 为什么：官方 `ModalBottomSheet` 是独立窗口，永远压在所有页面之上——
 * 跳子页时旧页离场，窗口要么盖住新页，要么得提前撤（早一两帧消失）；
 * 返回时所在页重新组合，窗口要重建，系统建窗口加首帧约 100ms（实测），面板总是晚于页面突然冒出来。
 * 面板是页面的一部分以后，跳走时随页面一起离场、返回时与页面同一帧出现，以后不管转场怎么设计都跟着页面走。
 *
 * 机制：调用处（可以在列表项里）在**组合期间**把面板登记进本层，本层在页面内容之后、测量阶段才组合面板，
 * 所以连列表项（测量时才组合）里登记的面板也与页面同一帧画出；调用处的全部 CompositionLocal 原样带过来。
 * 面板开着时，下面的页面对读屏隐藏（模态）；面板开始收起就不再算开着（[setClosing]），读屏与底栏不等它滑完。
 *
 * 面板内部的可保存状态（停在哪一档、面板里列表滚到哪）一律存在本层的 [stateHolder] 里、按面板 id 分开，
 * 随页面一起保存恢复，与调用处写在哪无关：调用处可能是列表项（评论里的用户卡片），存在列表项的保存区里的话，
 * 列表项与面板谁先销毁不定，返回时没存住，面板又从底下弹上来。
 */
@Stable
class PageSheetLayer internal constructor() {
    internal val sheets = mutableStateListOf<PageSheetSlot>()
    internal var stateHolder: SaveableStateHolder? = null
    private val closingIds = mutableStateMapOf<String, Unit>()
    private val closedIds = HashSet<String>()

    /** 有面板开着：登记了、且没在收起。 */
    internal val hasOpenSheet: Boolean get() = sheets.any { it.id !in closingIds }

    /**
     * 面板正在收起（往隐藏位置去、已经松手）：这时已不算开着。所在页在收起途中离开（收起时下面的页面已经能点，
     * 点遮罩后紧接着进子页），这个标记跟着本层留下，返回时面板据此直接关掉，不从存下的位置恢复（[isClosing]）。
     */
    internal fun setClosing(id: String, closing: Boolean) {
        if (closing) closingIds[id] = Unit else closingIds.remove(id)
    }

    /** 面板在收起途中：返回时还在收起的面板不再恢复，直接关。 */
    internal fun isClosing(id: String): Boolean = id in closingIds

    /** 面板真正关闭（不是随页面离开）时丢掉它存的状态：重开是新周期，换新 id，旧状态不再有人用。 */
    internal fun forget(id: String) {
        stateHolder?.removeState(id)
        closedIds += id
    }

    /**
     * 面板撤出本层：真正关闭了（[forget] 过）才清它的「收起中」——标记留到面板真的不在了为止，中间不会有一帧又被算成开着；
     * 只是随页面离开的，收起中的标记留着给返回时用。
     */
    internal fun release(id: String) {
        if (closedIds.remove(id)) closingIds.remove(id)
    }
}

/** 一块登记进 [PageSheetLayer] 的面板：[id] 跨导航稳定（可保存），面板内的可保存状态靠它找回。 */
internal class PageSheetSlot(val id: String) {
    var locals: CompositionLocalContext? by mutableStateOf(null)
    var content: @Composable () -> Unit by mutableStateOf({})
}

/** 当前导航项的面板层；不在导航项里为 null。 */
val LocalPageSheetLayer = staticCompositionLocalOf<PageSheetLayer?> { null }

/**
 * 各导航项的面板层与「开没开面板」（app 级）。两样都按导航项记、**页面离开组合也不清**，只在出栈时清：
 *
 * - 面板层对象跨离开 / 返回复用：返回那次组合里调用处登记面板时写的是**已有**对象，页面层能被通知到；
 *   若每次返回新建一层，同一次组合里新建又写入的状态不会触发重组，页面会一直以为「没有面板」。
 * - 底栏据「开没开」让位（模态面板盖在导航栏之上，M3 modal bottom sheet）；从面板跳子页再返回，
 *   这个值一直是「开」，底栏不会在返回那一帧先冒出来。
 */
@Stable
class PageSheetRegistry {
    private val layers = HashMap<Any, PageSheetLayer>()
    private val open = mutableStateMapOf<Any, Boolean>()

    /** 有导航项开着面板。 */
    val anyOpen: Boolean get() = open.values.any { it }

    internal fun layerFor(entryKey: Any): PageSheetLayer = layers.getOrPut(entryKey) { PageSheetLayer() }

    internal fun setOpen(entryKey: Any, isOpen: Boolean) {
        if (open[entryKey] != isOpen) open[entryKey] = isOpen
    }

    internal fun remove(entryKey: Any) {
        layers.remove(entryKey)
        open.remove(entryKey)
    }
}

/** 给每个导航项包一层 [PageSheetLayer]；须排在可保存状态装饰器之后，面板里的可保存状态才随页面保存。 */
@Composable
fun <T : Any> rememberPageSheetNavEntryDecorator(registry: PageSheetRegistry): NavEntryDecorator<T> =
    remember(registry) {
        NavEntryDecorator(onPop = { contentKey -> registry.remove(contentKey) }) { entry ->
            val layer = remember(entry.contentKey) { registry.layerFor(entry.contentKey) }
            // 组合提交后再读：调用处在本次组合里刚登记的面板已在表里，不会先报一帧「没开」
            LaunchedEffect(layer) {
                snapshotFlow { layer.hasOpenSheet }.collect { registry.setOpen(entry.contentKey, it) }
            }
            val hasSheet = layer.hasOpenSheet
            CompositionLocalProvider(LocalPageSheetLayer provides layer) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().then(if (hasSheet) Modifier.clearAndSetSemantics {} else Modifier)) {
                        entry.Content()
                    }
                    SheetSlots(layer)
                }
            }
        }
    }

/**
 * 把面板登记进当前页的面板层，由面板层在页面之上画 [content]。[id] 须跨导航稳定（调用处 `rememberSaveable` 一个）。
 * 只能在导航项里用。
 */
@Composable
internal fun PageSheetSlotHost(id: String, content: @Composable () -> Unit) {
    val layer = checkNotNull(LocalPageSheetLayer.current) { "页面面板只能用在导航项里" }
    val slot = remember(id) { PageSheetSlot(id) }
    slot.locals = currentCompositionLocalContext
    slot.content = content
    // 组合期间就登记（不是等副作用）：面板层在本帧稍后才组合面板，返回时面板与页面同一帧出现
    if (slot !in layer.sheets) layer.sheets.add(slot)
    DisposableEffect(layer, slot) {
        onDispose {
            layer.sheets.remove(slot)
            layer.release(id)
        }
    }
}

/**
 * 面板层：测量阶段才组合面板（[SubcomposeLayout]）。页面内容先测量，列表项里的调用处此时才组合、登记，
 * 本层随后测量时读到的已是本帧的登记表。每次测量换一个内容 lambda，登记表变了就在本帧重新组合。
 */
@Composable
private fun SheetSlots(layer: PageSheetLayer) {
    val holder = rememberSaveableStateHolder()
    layer.stateHolder = holder
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val current = layer.sheets.toList()
        val placeables = subcompose(Unit) {
            current.forEach { slot ->
                key(slot.id) {
                    // 调用处的 CompositionLocal 原样带过来，但可保存状态存进本层（内层提供者覆盖调用处的保存区）
                    val content: @Composable () -> Unit = { holder.SaveableStateProvider(slot.id) { slot.content() } }
                    val locals = slot.locals
                    if (locals != null) CompositionLocalProvider(locals, content) else content()
                }
            }
        }.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}
