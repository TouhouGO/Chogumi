package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.ui.common.navigation.OnTabReselect
import kotlin.math.roundToInt

/** 收起的那部分什么时候重新出现。 */
enum class HeaderReveal {
    /** 一往上滚（手指下滑）就开始浮现，不用等内容回到顶部（M3 enterAlways）：搜索栏这类随时要用的条。 */
    OnScrollUp,

    /** 内容回到顶部再往下拉才展开（M3 exitUntilCollapsed）：作品详情这类大头部，滚到半截时不该挡内容。 */
    AtContentTop,
}

/**
 * 收起程度：页面据此给顶栏填色、显示标题。偏移可存档，从下一页返回时保持原样。
 * [topBar] 是头部收满之后还留着的那截顶栏让位了多少（只有传了 `yieldingBar` 的 [CollapsibleHeader] 才动它）。
 */
@Stable
class CollapsibleHeaderState @OptIn(ExperimentalMaterial3Api::class) internal constructor(
    internal val bar: TopAppBarState,
    internal val topBar: TopAppBarState,
) {
    /** 收起了几成：0 = 全开，1 = 收满。 */
    @OptIn(ExperimentalMaterial3Api::class)
    val collapsedFraction: Float get() = bar.collapsedFraction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberCollapsibleHeaderState(): CollapsibleHeaderState {
    val bar = rememberBarState()
    val topBar = rememberBarState()
    return remember(bar, topBar) { CollapsibleHeaderState(bar, topBar) }
}

/**
 * 「会收起的头部 + 固定栏 + 滚动内容」：全仓**唯一**实现（进度、探索的搜索栏，目录页的头部，作品详情与用户主页的大头部经 [HeaderTabsFrame] 共用）。
 *
 * 行为：
 * 1. [collapsing] 随内容滚动收起：手指上滑先收起它，收满后剩余滚动量才交给内容；何时重新出现见 [reveal]。
 *    收满时还留 [keepVisible] 高（[HeaderTabsFrame] 留出顶栏那一截，固定栏正好停在顶栏下面）。
 *    留着的那截里最下面 [yieldingBar] 高是叠在上面的顶栏：头部收满后它也让位（M3 enterAlways：往上滚收走，
 *    一往回滚先回来），固定栏随之顶到状态栏下面，往下读时只剩固定栏一层。内容里的列表有排序行吸住时，
 *    往回滚这截顶栏不出来，与 [PageScaffold] 的顶栏同一条规则（[TopBarHold]）。
 * 2. [pinned] 紧贴在收起部分下方，收起后自然顶到上面并固定在那里；正文滑到它下面时下沿出柔边（[PinnedRowFrame]）。
 *    它的底色是页面底色（[PinnedRowFrame]），与上面的顶栏、状态栏那一截是一块颜色。
 * 3. [content] 里的任何可滚动列表 / 网格 / 下拉刷新 / 左右翻页里的列表都会把竖向滚动冒泡到这里。
 * 4. 按在 [collapsing] 或 [pinned] 上拖也收起、展开头部（[headerDrag]）：头部再高（作品详情展开了标签行）也只在内容里拖得动的话，
 *    内容露出的那一截就是全部的触摸区域。这一下拖不滚内容，内容在顶部时往下拉照常交给外面的下拉刷新。
 *
 * **滚动机制不在这里重写**：直接复用 M3 的 [TopAppBarDefaults.enterAlwaysScrollBehavior] /
 * [TopAppBarDefaults.exitUntilCollapsedScrollBehavior] 与其 [TopAppBarState]（自己把 `heightOffsetLimit` 设为
 * 实测可收起的高度）。速度、吸附（松手停在半截时归位到全开 / 全收；头部比可用高度还高时不吸附，见 `fitsViewport`）全部走 M3 实现，吸附动画取
 * `MaterialTheme.motionScheme` 的 spatial 弹簧（`motion.md:20-26`：尺寸是几何变化 → spatial），不手写 tween 时长。
 *
 * 正在打字（软键盘弹出）时不收起；此时用户拖动列表就视为不打了：收起键盘、清掉焦点，
 * 之后的滚动照常收起头部。只看键盘、不看焦点或关键词——键盘收了、关键词还在，
 * 搜索栏也该能让出空间（往上一滚就回来，关键词还在里面）。
 *
 * @param resetKey 页面「重置内容」的信号（例如重新排序回到顶部）。变化时头部弹回全开——
 *   滚动位置被重置而收起偏移没跟着回到全开，会留下一条收起的头部压在列表顶部。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun CollapsibleHeader(
    collapsing: @Composable () -> Unit,
    pinned: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    state: CollapsibleHeaderState = rememberCollapsibleHeaderState(),
    reveal: HeaderReveal = HeaderReveal.OnScrollUp,
    keepVisible: Dp = 0.dp,
    yieldingBar: Dp = 0.dp,
    resetKey: Any? = Unit,
    content: @Composable () -> Unit,
) {
    val bar = state.bar
    val topBar = state.topBar
    val snapSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val typing by rememberUpdatedState(WindowInsets.isImeVisible)
    // 头部比留给它的高度还高时松手不吸附：全开露不出它的下半截、全收整块不见，吸到哪一头那截都够不着（小屏上展开了标签行）
    var fitsViewport by remember { mutableStateOf(true) }
    val barSnap = snapSpec.takeIf { fitsViewport }
    val scrollBehavior = when (reveal) {
        HeaderReveal.OnScrollUp -> TopAppBarDefaults.enterAlwaysScrollBehavior(
            state = bar,
            canScroll = { !typing },
            snapAnimationSpec = barSnap,
            flingAnimationSpec = TopAppBarDefaults.flingAnimationSpec,
        )
        HeaderReveal.AtContentTop -> TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            state = bar,
            canScroll = { !typing },
            snapAnimationSpec = barSnap,
            flingAnimationSpec = TopAppBarDefaults.flingAnimationSpec,
        )
    }
    // 顶栏让位接在头部之内：往上滚时头部先收满、剩下的才收顶栏；往回滚时头部不拦（它要等内容回到顶部），顶栏先回来
    val topBarScroll = yieldingBarScrollBehavior(state, typing = { typing }, snapSpec = snapSpec)
    // 内容里的列表有排序行吸住时，往回翻楼层不让这截顶栏出来（[TopBarHold]）；没有让位顶栏时交给外面的页面顶栏
    val hold = remember { TopBarHold() }
    val topBarConnection = remember(topBarScroll, hold) { topBarScroll.heldBy(hold) }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val endTypingOnDrag = remember(keyboard, focusManager) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (typing && source == NestedScrollSource.UserInput) {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }
                return Offset.Zero
            }
        }
    }
    // 内容被重置时回到全开。只认「真的变了」：从下一页返回时组合重建，存档恢复的偏移不许再被弹开一次
    // （列表还停在原处，头部却自己冒出来）。所以这里用 remember：组合重建时它取当前值，只有组合存活期间的变化才算数。
    var lastResetKey by remember { mutableStateOf(resetKey) }
    LaunchedEffect(resetKey) {
        if (resetKey != lastResetKey) {
            lastResetKey = resetKey
            bar.animateToOpen(snapSpec)
            topBar.animateToOpen(snapSpec)
        }
    }
    // 再点当前 tab（已在第一层）：列表滚回顶部，头部也回到全开
    OnTabReselect {
        bar.animateToOpen(snapSpec)
        topBar.animateToOpen(snapSpec)
    }

    // 内容与头部、吸顶条共用同一串滚动连接：哪里拖都经同一份收起状态
    val connections = Modifier
        .nestedScroll(scrollBehavior.nestedScrollConnection)
        .then(if (yieldingBar > 0.dp) Modifier.nestedScroll(topBarConnection) else Modifier)
        .nestedScroll(endTypingOnDrag)
    val headerDrag = connections.headerDrag()

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(headerDrag)
                .clipToBounds()
                .layout { measurable, constraints ->
                    // 头部按内容量、不受屏高限制：比屏幕还高时（小屏上展开了标签行）超出的部分也在里面，收起时从下面露出来
                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    val fullHeight = placeable.height
                    fitsViewport = fullHeight <= constraints.maxHeight
                    // 收起上限 = 可收起的高度（实测高度减去收满时还留的那一截）的负值。
                    // M3 对 heightOffset 的夹紧与松手吸附都以它为准。
                    bar.heightOffsetLimit = -(fullHeight - keepVisible.roundToPx()).coerceAtLeast(0).toFloat()
                    topBar.heightOffsetLimit = -yieldingBar.toPx()
                    // 顶栏让出的那截只在头部收满之后才有（滚动先后见上），加在头部偏移上
                    val offset = (bar.heightOffset + topBar.heightOffset).roundToInt()
                    // 槽位高度 = 实测高度 + 偏移（不超过可用高度），内容随偏移上移、超出部分裁掉；固定栏紧贴在槽位下方。
                    layout(placeable.width, constraints.constrainHeight((fullHeight + offset).coerceAtLeast(0))) {
                        placeable.place(0, offset)
                    }
                },
        ) {
            collapsing()
        }
        PinnedRowFrame(pinned = { Column(Modifier.fillMaxWidth().then(headerDrag)) { pinned() } }, modifier = Modifier.weight(1f)) {
            Box(Modifier.fillMaxSize().then(connections)) {
                if (yieldingBar > 0.dp) CompositionLocalProvider(LocalTopBarHold provides hold, content) else content()
            }
        }
    }
}

/**
 * 头部收满后留着的那截顶栏的让位（M3 enterAlways）。[CollapsibleHeader] 用它的滚动连接移动固定栏，
 * [HeaderTopAppBar] 用它把顶栏本身收走，两边读同一个 [CollapsibleHeaderState.topBar]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun yieldingBarScrollBehavior(
    state: CollapsibleHeaderState,
    typing: () -> Boolean = { false },
    snapSpec: AnimationSpec<Float> = MaterialTheme.motionScheme.fastSpatialSpec(),
): TopAppBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
    state = state.topBar,
    canScroll = { !typing() },
    snapAnimationSpec = snapSpec,
    flingAnimationSpec = TopAppBarDefaults.flingAnimationSpec,
)

/**
 * 头部与吸顶条上的竖向拖动：自己不滚任何东西（消耗 0），整段交给接在它前面的滚动连接，收起、展开、松手吸附与甩动都走 M3 的
 * 滚动行为，和在内容里拖是同一份状态、同一套规则。头部里的点按、长按照常：拖过触摸阈值才算拖。
 */
@Composable
private fun Modifier.headerDrag(): Modifier {
    val state = rememberScrollableState { 0f }
    return scrollable(state, Orientation.Vertical)
}

/** 把头部动画到全开（偏移 0）。 */
@OptIn(ExperimentalMaterial3Api::class)
private suspend fun TopAppBarState.animateToOpen(spec: AnimationSpec<Float>) {
    if (heightOffset == 0f) return
    animate(initialValue = heightOffset, targetValue = 0f, animationSpec = spec) { value, _ ->
        heightOffset = value
    }
}
