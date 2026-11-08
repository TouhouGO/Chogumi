package io.github.touhougo.chogumi.core.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.BarIconButton
import io.github.touhougo.chogumi.core.ui.composables.LocalBarIconButton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 玻璃外框里内容区的上内边距：[GlassHeaderTabsFrame] 放进来，页面的列表把它加在 `contentPadding` 的上边，
 * 第一行内容停在 tab 胶囊下面，往上滚时从胶囊、顶边下面滚过。不在玻璃外框里时是 0。
 */
val LocalGlassContentTop = staticCompositionLocalOf { 0.dp }

/**
 * 玻璃效果开着时 core:ui `HeaderTabsFrame` 的替身（作品详情）：内容从钉住的 tab 胶囊、顶边与顶上的圆钮下面滚过去。
 *
 * 和 `HeaderTabsFrame` 同样随内容往上滚收起头部、回到内容顶部再往下拉才展开，不同在于：
 * - 头部收到 0，不留顶栏那一截；tab 胶囊钉住之后内容区不再上移，头部最后那一截从顶边下面退走；
 *   内容区的上内边距是 [LocalGlassContentTop]（状态栏 + 顶栏 + tab 胶囊），列表在顶部时第一行永远在胶囊下面，
 *   往上滚才从胶囊、顶边下面滚过；
 * - [tabs] 浮在内容上面（[GlassTabs]，高 [GlassTabsHeight]），停在顶栏下面不再上移；
 * - 顶栏不让位（玻璃圆钮不占布局，一直够得着），由读同一个 [state] 的 [GlassHeaderBar] 画；
 * - 头部与内容一起录进 [glass]：顶边、圆钮、tab 胶囊、主操作都从这里采样，[tabs] 不在里面（玻璃不采自己）；
 * - 顶边（[GlassTopEdge]）画在这里，垫在 [tabs] 下面，盖住状态栏、顶栏与胶囊所在的那一段，头部收起时淡入。
 *
 * 内容区高度固定（屏幕高），收起头部时只挪位置、不重新量列表，滚动时不重组。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassHeaderTabsFrame(
    state: GlassHeaderFrameState,
    glass: GlassLayer,
    header: @Composable () -> Unit,
    tabs: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topBar = statusBar + TopAppBarDefaults.TopAppBarExpandedHeight
    val pinned = GlassTabsHeight + GlassTabsMargin * 2
    val bar = state.bar
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = bar,
        snapAnimationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        flingAnimationSpec = TopAppBarDefaults.flingAnimationSpec,
    )
    Box(modifier) {
        Layout(
            content = {
                Box { header() }
                Box(Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)) {
                    CompositionLocalProvider(LocalGlassContentTop provides topBar + pinned, content = content)
                }
            },
            modifier = Modifier.fillMaxSize().glassSource(glass),
        ) { measurables, constraints ->
            val topBarPx = topBar.roundToPx()
            val headerPlaceable = measurables[0].measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val headerHeight = headerPlaceable.height
            bar.heightOffsetLimit = -headerHeight.toFloat()
            val offset = bar.heightOffset.roundToInt()
            val slot = (headerHeight + offset).coerceAtLeast(0)
            state.slot = slot
            state.pinTop = topBarPx
            state.pinBottom = topBarPx + pinned.roundToPx()
            val contentPlaceable = measurables[1].measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight))
            layout(constraints.maxWidth, constraints.maxHeight) {
                // 先放内容再放头部：头部画在内容上面，盖住的只是内容区空着的上内边距，头部里的小标与标签也点得到
                // 内容跟着 tab 胶囊走：胶囊钉住后不再上移，列表在顶部时第一行不钻到胶囊和顶栏下面
                contentPlaceable.place(0, max(slot, topBarPx) - topBarPx)
                headerPlaceable.place(0, offset)
            }
        }
        // 顶边垫在 tab 胶囊下面、内容上面：状态栏、顶栏与胶囊四周滚上去的内容都被它模糊、压淡
        GlassTopEdge(glass, visible = { state.edge }, height = topBar + pinned + GlassEdgeFade, fade = GlassEdgeFade)
        Box(
            Modifier
                .offset { IntOffset(0, max(state.slot, state.pinTop)) }
                .fillMaxWidth()
                .padding(horizontal = GlassTabsMargin * 2, vertical = GlassTabsMargin),
        ) {
            tabs()
        }
    }
}

/** [GlassHeaderTabsFrame] 的收起状态：偏移可存档；排版时记下头部还剩多高，顶边与标题据此出现，只在排版、绘制阶段读。 */
@Stable
class GlassHeaderFrameState @OptIn(ExperimentalMaterial3Api::class) internal constructor(internal val bar: TopAppBarState) {
    internal var slot by mutableIntStateOf(Int.MAX_VALUE)
    internal var pinTop by mutableIntStateOf(0)
    internal var pinBottom by mutableIntStateOf(0)

    /** 顶边出现了几成：头部底边退到 tab 胶囊下沿时开始，退到胶囊钉住的位置（内容开始从胶囊下面滚过）时全出来。 */
    internal val edge: Float get() = ((pinBottom - slot).toFloat() / (pinBottom - pinTop).coerceAtLeast(1)).coerceIn(0f, 1f)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberGlassHeaderFrameState(): GlassHeaderFrameState {
    val bar = rememberSaveable(saver = TopAppBarState.Saver) { TopAppBarState(-Float.MAX_VALUE, 0f, 0f) }
    return remember(bar) { GlassHeaderFrameState(bar) }
}

/**
 * [GlassHeaderTabsFrame] 页面的顶栏（玻璃效果开着时替代 core:ui `HeaderTopAppBar`）：没有容器，返回与 [actions] 是玻璃圆钮
 * （[GlassIconButton]；页面的 ⋮ 菜单经 core:ui `LocalBarIconButton` 自己画成圆钮）。背后的顶边由 [GlassHeaderTabsFrame] 画，
 * 标题跟着 [frame] 的顶边一起淡入，直接写在顶边上。不随滚动让位。
 */
@Composable
fun GlassHeaderBar(
    title: String,
    glass: GlassLayer,
    frame: GlassHeaderFrameState,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barHeight = TopAppBarDefaults.TopAppBarExpandedHeight
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .padding(top = statusBar)
                .height(barHeight)
                .fillMaxWidth()
                .padding(horizontal = GlassBarPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                GlassIconButton(glass, painterResource(R.drawable.arrow_back_24), stringResource(R.string.back), onBack)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = GlassBarPadding)
                    // 顶边出来一大半之后才显：头部自己的标题滑到顶栏下面之前，不会两个标题同时看得见
                    .graphicsLayer { alpha = frame.edge * frame.edge },
            )
            val button = remember(glass) { glassBarIconButton(glass) }
            CompositionLocalProvider(LocalBarIconButton provides button) {
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
    }
}

/** 顶栏动作的玻璃圆钮，交给 core:ui 的 `LocalBarIconButton`。 */
private fun glassBarIconButton(glass: GlassLayer): BarIconButton =
    { painter, contentDescription, onClick -> GlassIconButton(glass, painter, contentDescription, onClick) }

/** 顶边在 tab 胶囊下沿之后继续淡出的那一段，内容进出顶边时没有硬边。 */
private val GlassEdgeFade = 24.dp

/** 玻璃顶栏左右的边距（圆钮的 48dp 触控区里玻璃 44dp，合起来与 M3 顶栏的视觉边距相当）。 */
private val GlassBarPadding = 8.dp
