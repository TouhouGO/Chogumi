package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.util.lerp
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 容器转换（M3 `transitions.md` 的 container transform）的唯一实现处：作品封面长成作品详情页，返回时缩回封面。
 *
 * 起点是被点中的封面（[expandOrigin]，由 `SubjectPoster` 自带），终点是整张详情页（[rememberPageExpandNavEntryDecorator]
 * 包住的整页，连同页面底色）。起点记下被按的是哪一个，导航时 [PageExpandRegistry.claim] 把它认领给目的页；
 * 两端用同一个键标出共享边界。从链接、通知、作品详情里的海报进入的页没人认领，终点不挂共享标记，走普通的前进后退
 * （容器转换只用于浅层级，`transitions.md`「深层级里别用」）。
 * 键里带的是被按的那一个起点，同一页同一作品出现两次时只认点中的那张。
 */
@Stable
class PageExpandRegistry {
    private var pressed by mutableStateOf<String?>(null)
    private val claims = mutableStateMapOf<PageSlot, String>()
    /** 认领的反向计数索引：只由 [replaceClaim] 维护，起点查询不遍历页面历史。 */
    private val activeTokens = mutableStateMapOf<String, Int>()

    /** 页面区的宽度（像素），由装饰器量出来：飞行中的圆角据此收到 0。 */
    internal var pageWidth = 0f

    /** 起点（按键）在列表里看得见的样子，起点挂着标记时写入；详情页那一端按它画圆角。 */
    internal val origins = HashMap<String, OriginLook>()

    /** 导航项（哪个 tab 的栈里、哪个内容键）→ 它的路由：转场和装饰器只拿得到导航项，认领按「tab + 路由」记。 */
    private val routes = HashMap<Pair<Route, Any>, Route>()

    /** [AppNavigator] 建导航项时登记；[tab] 是这一页所在的栈（两个 tab 里的同一作品是两页，各记各的认领）。 */
    internal fun register(tab: Route, contentKey: Any, route: Route) {
        routes[tab to contentKey] = route
    }

    internal fun press(token: String) {
        val previous = pressed
        pressed = token
        previous?.let(::forgetOriginIfInactive)
    }

    /** 抬手后两帧还没人认领（这次按下没有导航）就放弃，免得下一次不相干的导航认领到它。 */
    internal fun release(token: String) {
        if (pressed == token) pressed = null
        forgetOriginIfInactive(token)
    }

    /**
     * 在 [tab] 的栈里从 [from]（当前页，没有则为 null）导航到 [route]：目的地是作品详情、且不是从另一个作品详情里进的，
     * 才认领刚按下的封面；其余（链接、通知、详情里的海报）清掉这个目的页以前的认领。
     * 离开返回栈的页在退出转场完成前保留认领，装饰器的 onPop 同时确认离栈与离开组合后才回收。
     */
    fun claim(tab: Route, route: Route, from: Route?) {
        val token = pressed
        val slot = PageSlot(tab, route)
        replaceClaim(slot, token?.takeIf { route is Route.SubjectDetails && from !is Route.SubjectDetails })
        pressed = null
        token?.let(::forgetOriginIfInactive)
    }

    /** 同时离栈且退出组合后回收；仍有导航项使用同一路由时保留它的认领。 */
    internal fun remove(tab: Route, contentKey: Any) {
        val route = routes.remove(tab to contentKey) ?: return
        if (routes.any { (key, value) -> key.first == tab && value == route }) return
        replaceClaim(PageSlot(tab, route), null)
    }

    private fun replaceClaim(slot: PageSlot, token: String?) {
        val previous = claims[slot]
        if (previous == token) return
        if (token == null) claims.remove(slot) else claims[slot] = token
        previous?.let {
            val count = activeTokens.getValue(it) - 1
            if (count == 0) activeTokens.remove(it) else activeTokens[it] = count
            forgetOriginIfInactive(it)
        }
        token?.let { activeTokens[it] = (activeTokens[it] ?: 0) + 1 }
    }

    private fun forgetOriginIfInactive(token: String) {
        if (!isActive(token)) origins.remove(token)
    }

    /** 起点在按下到返回之间要挂共享标记：按下的那一个，和已经认领给某个目的页的那一个。 */
    internal fun isActive(token: String): Boolean = pressed == token || token in activeTokens

    /** [tab] 的栈里内容键为 [contentKey] 的导航项对应的路由。 */
    internal fun routeOf(tab: Route, contentKey: Any): Route? = routes[tab to contentKey]

    internal fun tokenFor(tab: Route, contentKey: Any): String? = routeOf(tab, contentKey)?.let { claims[PageSlot(tab, it)] }

    /** [tab] 的栈里内容键为 [contentKey] 的这一页是不是从某张封面长出来的（转场选「容器转换」而不是滑动）。 */
    fun expandsPage(tab: Route, contentKey: Any): Boolean = tokenFor(tab, contentKey) != null
}

/** 一页在导航里的位置：所在 tab 的栈和路由。同一作品在两个 tab 里各开一页，认领互不覆盖。 */
private data class PageSlot(val tab: Route, val route: Route)

val LocalPageExpandRegistry = staticCompositionLocalOf { PageExpandRegistry() }

/** 外壳页面区的共享转场作用域，由 `AppEntries` 提供；没有时（测试、预览）共享标记都是空操作。 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
internal class PageExpandHost(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

/** 这一页的共享转场环境；页面不在导航里时为空。 */
internal val LocalPageExpandHost = compositionLocalOf<PageExpandHost?> { null }

/**
 * 祖先遮掉一部分时看得见的那一块：[area] 在 [coordinates]（遮罩所在的那一块）的坐标里。
 * 只由 `CarouselSlot` 提供，里面的起点（[expandOrigin]）据此只从看得见的那一块长出、落回那一块。
 */
class VisibleArea(internal val area: () -> Rect) {
    /** 遮罩那一块的坐标：排版之后才有，排版时读它的节点在它到位后重排一次，所以是状态。 */
    internal var coordinates by mutableStateOf<LayoutCoordinates?>(null)

    /** [here] 在遮罩那一块里的位置；那一块还没排版或已不在时为 null。 */
    internal fun offsetOf(here: LayoutCoordinates): Offset? = coordinates?.takeIf { it.isAttached }?.localPositionOf(here)

    /**
     * 看得见的那一块落在 [whole]（某个节点自己坐标里的整块）里的部分，[inSlot] 是那个节点在遮罩那一块里的位置（[offsetOf]）。
     * 一点也看不见时当作整块。
     */
    internal fun within(whole: IntRect, inSlot: Offset): IntRect =
        area().translate(-inSlot).roundToIntRect().intersect(whole).takeUnless { it.isEmpty } ?: whole
}

val LocalVisibleArea = compositionLocalOf<VisibleArea?> { null }

/** 起点在列表里真正可见的样子：形状和宽度（像素）。终点那一端按它画圆角，两端落地时同一个角。 */
internal class OriginLook(val shape: Shape, val width: Float)

/**
 * 飞行中的裁切：从起点的圆角过渡到一张浮着的页的圆角（[flight]），只在最后贴满整页的一小段收到直角，
 * 所以缩小的整页（预测性返回的预览）四角是圆的，落定成整页时正好是直角。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private class ContainerClip(
    private val origin: () -> OriginLook,
    private val flight: Shape,
    private val pageWidth: () -> Float,
) : SharedTransitionScope.OverlayClip {
    override fun getClipPath(
        sharedContentState: SharedTransitionScope.SharedContentState,
        bounds: Rect,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Path {
        val look = origin()
        val page = pageWidth()
        // 0 = 起点的大小，1 = 整页
        val grown = if (page > look.width) ((bounds.width - look.width) / (page - look.width)).coerceIn(0f, 1f) else 1f
        val start = look.shape.radiusAt(bounds, layoutDirection, density)
        val floating = flight.radiusAt(bounds, layoutDirection, density)
        val tail = ((1f - grown) / FullPageTail).coerceAtMost(1f)
        return Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(lerp(start, floating, grown) * tail))) }
    }
}

/** 贴满整页前的最后这一段（按长大的比例）里圆角收到直角。我的设计决定（not in M3）。 */
private const val FullPageTail = 0.1f

private fun Shape.radiusAt(bounds: Rect, layoutDirection: LayoutDirection, density: Density): Float =
    (createOutline(bounds.size, layoutDirection, density) as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f

/**
 * 点击的起点：记下被按的是哪一个，并在被认领后挂上共享标记。[shape] 是起点的形状（飞行中的圆角）。
 * 放在会被点的那一块的最外层，不影响点击和布局。起点在 [CoverPressBlock] 里时，按在块里任何地方也算按了它。
 *
 * 起点被祖先改了画法时（轮播把条目整体平移、用遮罩收窄，列表把滚到边上的那一项裁掉一截），共享边界量到的是排版的位置和大小，
 * 不是看得见的那一块，转换会从整张封面长出、盖到顶栏上，落在别处、结束时再跳回来。所以挂着标记时：标记只盖祖先遮罩
 * （[LocalVisibleArea]）与祖先裁切之后看得见的那一块；
 * 祖先图层的平移量（画出来的位置减去排版的位置）折进排版，再用同样大小的反向图层抵回去，画面不动，共享边界量到的正是画出来的位置。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.expandOrigin(shape: Shape): Modifier {
    val host = LocalPageExpandHost.current ?: return this
    val visible = LocalVisibleArea.current
    val registry = LocalPageExpandRegistry.current
    val token = rememberSaveable { UUID.randomUUID().toString() }
    LocalPressBlock.current?.let { block ->
        DisposableEffect(block, token) {
            block.covers += token
            onDispose { block.covers -= token }
        }
    }
    val press = Modifier.reportsPress(registry) { token }
    val active by remember(registry, token) { derivedStateOf { registry.isActive(token) } }
    if (!active) return then(press)
    val placement = remember { OriginPlacement() }
    return then(press)
        .then(placement.visible(host.shared, visible) { registry.origins[token] = OriginLook(shape, it) })
        .then(host.mark(token, shape, ContainerEnd.Cover))
        .then(placement.content())
}

/**
 * 整块可点、里面有一张封面的那一块（封面加标题的卡片、列表的一行）：按在块里任何地方都算按了块里的封面，
 * 点标题、点空白处和点封面一样由封面长出详情页，返回时缩回封面。[content] 收到 `presses`，挂在块的可点节点上；
 * 块里的封面（`SubjectPoster`）自己登记。块里不止一张封面时说不清按的是哪张，块不代按，只有按在封面上才算。
 */
@Composable
fun CoverPressBlock(content: @Composable (presses: Modifier) -> Unit) {
    if (LocalPageExpandHost.current == null) {
        content(Modifier)
        return
    }
    val registry = LocalPageExpandRegistry.current
    val block = remember { PressBlock() }
    val presses = Modifier.reportsPress(registry) { block.covers.singleOrNull() }
    CompositionLocalProvider(LocalPressBlock provides block) { content(presses) }
}

/** 块里登记的封面（按起点的键）。 */
private class PressBlock {
    val covers = LinkedHashSet<String>()
}

private val LocalPressBlock = compositionLocalOf<PressBlock?> { null }

/** 按下时把 [token] 给出的起点记为「刚按下的」（没有就不记），抬手两帧后没人认领就放弃（见 [PageExpandRegistry.release]）。 */
@Composable
private fun Modifier.reportsPress(registry: PageExpandRegistry, token: () -> String?): Modifier {
    val scope = rememberCoroutineScope()
    return pointerInput(registry) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val pressed = token() ?: return@awaitEachGesture
            registry.press(pressed)
            waitForUpOrCancellation(PointerEventPass.Initial)
            scope.launch {
                withFrameNanos { }
                withFrameNanos { }
                registry.release(pressed)
            }
        }
    }
}

/** 起点挂着标记时的排版：标记只盖看得见的那一块，并按画出来的位置排（见 [expandOrigin]）。 */
@Stable
private class OriginPlacement {
    /** 祖先图层的平移：画出来的位置减去排版的位置。画一次才知道，变了要重排一次（前瞻排版也要跟着变），所以是状态。 */
    var shift by mutableStateOf(IntOffset.Zero)
    var frame = IntSize.Zero
    var area = IntRect.Zero

    /** 本身在遮罩那一块里的位置：同样画一次才知道。 */
    var inSlot by mutableStateOf(Offset.Zero)

    /** 祖先裁掉之后露出来的那一块（本身的坐标）：一半滚出列表可视区、被顶栏挡住的那一项只露出一截。同样画一次才知道。 */
    var unclipped by mutableStateOf<Rect?>(null)

    fun visible(scope: LookaheadScope, visible: VisibleArea?, onArea: (width: Float) -> Unit): Modifier = Modifier.layout { measurable, constraints ->
        // 封面的框由 coverRatio 定死，真正排版时这里拿到的就是整块的大小。外层问固有尺寸时（比如一行里取最高的那格）
        // 约束是无界的，那只是探测：原样往下问，不改看得见的那一块
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) return@layout passThrough(measurable, constraints)
        frame = IntSize(constraints.maxWidth, constraints.maxHeight)
        val whole = IntRect(IntOffset.Zero, frame)
        val masked = visible?.within(whole, inSlot) ?: whole
        area = unclipped?.roundToIntRect()?.intersect(masked)?.takeUnless { it.isEmpty } ?: masked
        onArea(area.width.toFloat())
        val placeable = measurable.measure(Constraints.fixed(area.width, area.height))
        layout(frame.width, frame.height) {
            if (!isLookingAhead) {
                coordinates?.let { here ->
                    with(scope) {
                        val root = lookaheadScopeCoordinates
                        shift = (root.localPositionOf(here) - root.localLookaheadPositionOf(here)).round()
                    }
                    visible?.offsetOf(here)?.let { inSlot = it }
                    val root = here.findRootCoordinates()
                    val seen = root.localBoundingBoxOf(here, clipBounds = true)
                    unclipped = Rect(here.localPositionOf(root, seen.topLeft), here.localPositionOf(root, seen.bottomRight))
                }
            }
            val by = shift
            placeable.placeWithLayer(area.left + by.x, area.top + by.y) {
                translationX = -by.x.toFloat()
                translationY = -by.y.toFloat()
            }
        }
    }

    /** 标记里面：内容仍按整块排，摆回原处（画面与没挂标记时一样）。 */
    fun content(): Modifier = Modifier.layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) return@layout passThrough(measurable, constraints)
        val placeable = measurable.measure(Constraints.fixed(frame.width, frame.height))
        layout(area.width, area.height) { placeable.place(-area.left, -area.top) }
    }
}

private fun MeasureScope.passThrough(measurable: Measurable, constraints: Constraints): MeasureResult {
    val placeable = measurable.measure(constraints)
    return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * 容器的两端。M3 容器转换是「一个不透明的容器变形，里面的内容淡入接替」：封面一直不透明地垫在下面，详情页盖在上面，
 * 展开时在前段淡入、缩回时在后段淡出，所以任何一帧容器里都不会透出底下的页面或黑底。叠放次序与方向无关：
 * 转换半路掉头（展开途中返回）时只有详情页的透明度接着变，封面不会突然跳到上面。
 */
private enum class ContainerEnd {
    /** 列表里的封面：按封面自己的比例裁切铺进容器（不拉伸），始终不透明、在下面。 */
    Cover,

    /**
     * 详情整页：等比缩放到铺满容器、贴左上对齐（缩小的整页），在上面；展开时在前段淡入，缩回时在后段淡出。
     * 竖屏的页比容器瘦，铺满就是按宽度；横屏（平板）的页比容器宽，按高度铺满、裁掉右边，底下的封面才不会从页面下方露出一截。
     */
    Page,
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PageExpandHost.mark(token: String, shape: Shape, end: ContainerEnd): Modifier = with(shared) {
    val registry = LocalPageExpandRegistry.current
    val page = LocalAppMotion.current.page
    val flight = PagePreviewCorner
    // 手指拖出来的预测性返回时整个转换跟着手势进度走（被拖动），这时边界先只缩成预览大小，见 PageMotion.containerGesture；
    // 返回键触发的没有手指可跟，直接缩回封面
    val back = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val bounds = remember(page, back) {
        BoundsTransform { initial, target ->
            val gesture = back?.transitionState?.value as? NavigationEventTransitionState.InProgress
            val dragging = gesture != null && followsFinger(gesture.latestEvent.swipeEdge)
            if (dragging && initial.width > target.width) page.containerGesture(initial.scaledBy(page.previewScale)) else page.container
        }
    }
    val state = rememberSharedContentState(token)
    // 封面一直不透明地垫在下面，详情页盖在上面淡入淡出：哪个方向、半路掉头，叠放的次序都不变，只有详情页的透明度接着变
    Modifier.sharedBounds(
        state, visibility,
        enter = if (end == ContainerEnd.Page) fadeIn(page.containerFadeIn) else EnterTransition.None,
        exit = fadeOut(if (end == ContainerEnd.Page) page.containerReturnFadeOut else page.containerHold),
        boundsTransform = bounds,
        resizeMode = when (end) {
            ContainerEnd.Cover -> SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Crop, Alignment.Center)
            ContainerEnd.Page -> SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Crop, Alignment.TopStart)
        },
        clipInOverlayDuringTransition = ContainerClip({ registry.origins[token] ?: OriginLook(shape, 0f) }, flight) { registry.pageWidth },
        zIndexInOverlay = if (end == ContainerEnd.Page) 1f else 0f,
    )
}

/** 以中心缩放的矩形。 */
private fun Rect.scaledBy(scale: Float): Rect =
    Rect(center.x - width * scale / 2f, center.y - height * scale / 2f, center.x + width * scale / 2f, center.y + height * scale / 2f)

/**
 * 给每一页配上共享转场环境，并把被某张封面认领的整页挂上展开的终点标记（页面底色在标记之内，所以长大的是整张不透明的页）。
 * 读 `LocalNavAnimatedContentScope` 只为拿共享元素需要的可见性作用域，不碰面板的显隐（门禁 check_sheets.py）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun <T : Any> rememberPageExpandNavEntryDecorator(tab: Route): NavEntryDecorator<T> {
    val registry = LocalPageExpandRegistry.current
    return remember(tab, registry) {
        NavEntryDecorator(onPop = { contentKey -> registry.remove(tab, contentKey) }) { entry ->
            val shared = LocalSharedTransitionScope.current
            if (shared == null) {
                entry.Content()
            } else {
                val visibility = LocalNavAnimatedContentScope.current
                val host = remember(shared, visibility) { PageExpandHost(shared, visibility) }
                val token = registry.tokenFor(tab, entry.contentKey)
                CompositionLocalProvider(LocalPageExpandHost provides host, LocalCornersFromContainer provides (token != null)) {
                    val mark = if (token != null) host.mark(token, MaterialTheme.shapes.medium, ContainerEnd.Page) else Modifier
                    Box(Modifier.fillMaxSize().onSizeChanged { registry.pageWidth = it.width.toFloat() }.then(mark)) { entry.Content() }
                }
            }
        }
    }
}
