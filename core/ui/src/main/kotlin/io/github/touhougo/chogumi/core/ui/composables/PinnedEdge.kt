package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.withoutVisualEffect
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.ui.common.navigation.ProvideTopReset

/**
 * 吸顶这一摞（状态栏那一截、顶栏、吸顶条、列表里吸住的 `stickyHeader`）的颜色与下沿柔边：全仓**唯一**实现。
 *
 * 颜色：这一摞永远是页面底色 `surface`（[pageBarColors]），不随滚动、顶栏让位换色：状态栏那一截、顶栏、吸住的条（选项卡、
 * 排序行）与正文同色，只靠下沿的柔边分层。所以这里没有「填几成」的状态可传，柔边与吸顶条的底色都直接取 `surface`。
 *
 * 柔边：正文滑到这一摞下面时，只在最下面那一条的下沿画一段从它的底色渐隐到透明的过渡，正文在那里慢慢淡掉，不留硬线。
 * 没有正文压在下面时不画，不盖住列表顶上的第一行。最下面那条可能是：
 * - 顶栏本身（[PageScaffold] 经 [BarEdgeFrame]，页面里没有自己的吸顶条时）；
 * - 列表外面、钉在内容区上方的吸顶条（[PinnedRowFrame]，[CollapsibleHeader] 也经它）；
 * - 列表里吸住的 `stickyHeader`（[stickyHeaderFade]）。
 * 下面那条在时，上面那条的柔边让给它（[PinnedEdgeHandoff]）。
 *
 * M3 没有这种做法（bars.md：滚动后靠容器换色分层、不加阴影），柔边与它的高度都是设计决定，not in M3。
 */
private val PinnedEdgeFadeHeight = 12.dp

/**
 * 顶栏下沿的柔边（[PageScaffold]）：[content] 滚到顶栏下面（[scrolledUnder]）、而页面里没有更靠下的吸顶条时，
 * 在 [barBottom]（相对 [content] 顶边的像素）处画 `surface` 渐隐的柔边。
 * 页面里的吸顶条与吸住的 `stickyHeader` 经 [LocalPinnedEdgeHandoff] 把柔边要走。
 */
@Composable
internal fun BarEdgeFrame(
    barBottom: () -> Float,
    scrolledUnder: () -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val handoff = remember { PinnedEdgeHandoff() }
    val color = MaterialTheme.colorScheme.surface
    val alpha = rememberEdgeAlpha { scrolledUnder() && !handoff.taken }
    Box(
        modifier.drawWithContent {
            drawContent()
            drawEdgeFade(top = barBottom(), color = color, alpha = alpha())
        },
    ) {
        CompositionLocalProvider(LocalPinnedEdgeHandoff provides handoff) { content() }
    }
}

/**
 * 吸顶条 [pinned] + 它下面滚动的 [content]：内容里任何列表、网格、左右翻页的竖向滚动都会冒泡到这里，
 * 一有正文滚到吸顶条下面，下沿就出柔边。[pinned] 里的几行自上而下排。吸顶条的底色由这里画：页面底色 `surface`，与上面的顶栏、状态栏那一截是一块颜色。
 */
@Composable
fun PinnedRowFrame(
    pinned: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val color = MaterialTheme.colorScheme.surface
    val under = rememberSaveable(saver = ScrolledUnder.Saver) { ScrolledUnder() }
    val handoff = remember { PinnedEdgeHandoff() }
    // 这条一直在上面那条（顶栏）的下面：上面那条的柔边一直让给它
    ClaimPinnedEdge { 1f }
    val alpha = rememberEdgeAlpha { under.isUnder && !handoff.taken }
    Column(modifier) {
        // 吸顶条可以有几行（搜索栏 + 排序行）：竖向叠放，不是叠在同一处。
        Column(Modifier.fillMaxWidth().background(color)) { pinned() }
        Box(
            Modifier
                .weight(1f)
                .nestedScroll(under)
                .drawWithContent {
                    drawContent()
                    drawEdgeFade(top = 0f, color = color, alpha = alpha())
                },
        ) {
            ProvideTopReset(reset = { under.offset = 0f }) {
                CompositionLocalProvider(LocalPinnedEdgeHandoff provides handoff) { content() }
            }
        }
    }
}

/**
 * 列表里 `stickyHeader` 的柔边：吸顶的那一项被后面的项压到下面时（只有吸住了才会这样），在它下沿画柔边。
 * 它下沿成了这一摞最下面那条，外面那条（顶栏或 [PinnedRowFrame] 的吸顶条）的柔边让给它。
 *
 * 接法（两处都要接，缺一处柔边就和吸顶项错开）：
 * `LazyColumn(state = list, modifier = Modifier.stickyHeaderFade(edge), overscrollEffect = edge.overscrollEffect)`，
 * 吸顶项自己的底色取 [StickyHeaderEdge.containerColor]。
 * 到底再拖时的回弹拉伸会把吸顶项一起拉走，柔边要跟着走，所以回弹不由列表自己画，改由 [stickyHeaderFade] 画在柔边外面
 * （foundation `withoutVisualEffect` 的用法），柔边与列表内容同在一层拉伸里。
 *
 * @param color 吸顶项的固定底色（底部面板里是面板色）。null（页面里）时是页面底色 `surface`，吸住与否都一样，
 *   与状态栏那一截、顶栏连成一块。
 */
@Composable
fun rememberStickyHeaderEdge(list: LazyListState, color: Color? = null): StickyHeaderEdge {
    val bottom = remember(list) { derivedStateOf { stuckHeaderBottom(list.layoutInfo) } }
    val alpha = rememberEdgeAlpha { bottom.value != null }
    // 翻走的页（TabPager 留在组合里的）不占柔边、不扣顶栏：它的列表停在离开时的样子，吸住的那条看不见
    val onScreen = LocalPageOnScreen.current
    ClaimPinnedEdge { if (onScreen()) alpha() else 0f }
    HoldTopBar { onScreen() && bottom.value != null }
    val currentColor = rememberUpdatedState(color ?: MaterialTheme.colorScheme.surface)
    val stretch = rememberOverscrollEffect()
    return remember(bottom, alpha, currentColor, stretch) { StickyHeaderEdge(bottom, alpha, currentColor, stretch) }
}

/** 一个列表的吸顶项柔边（[rememberStickyHeaderEdge]）。 */
@Stable
class StickyHeaderEdge internal constructor(
    private val bottom: State<Int?>,
    private val alpha: () -> Float,
    private val color: State<Color>,
    internal val stretch: OverscrollEffect?,
) {
    /** 交给 `LazyColumn(overscrollEffect = …)`：照常接收滚动与回弹，但不自己画，画由 [stickyHeaderFade] 负责。 */
    val overscrollEffect: OverscrollEffect? = stretch?.withoutVisualEffect()

    /** 吸顶项此刻的底色，只在绘制阶段读；柔边从它渐隐。 */
    val containerColor: () -> Color = { color.value }

    internal fun ContentDrawScope.drawFade() {
        bottom.value?.let { drawEdgeFade(top = it.toFloat(), color = containerColor(), alpha = alpha()) }
    }
}

/** 挂在列表自己身上：回弹拉伸包住列表内容与柔边，柔边画在列表的范围之内。 */
fun Modifier.stickyHeaderFade(edge: StickyHeaderEdge): Modifier =
    overscroll(edge.stretch).drawWithContent {
        drawContent()
        with(edge) { drawFade() }
    }

/**
 * 吸住的那一项的下沿（相对列表顶边）；没有项吸住时为 null。列表里的项本不重叠，
 * 只有吸顶项会压在序号更大的项上面，所以「有后面的项伸到它下沿之上」就是吸住了、正文在它下面。
 */
private fun stuckHeaderBottom(info: LazyListLayoutInfo): Int? = stuckHeaderBottom(info.visibleItemsInfo, info.viewportStartOffset)

internal fun stuckHeaderBottom(items: List<LazyListItemInfo>, viewportStartOffset: Int): Int? {
    val header = items.firstOrNull { h -> items.any { it.index > h.index && it.offset < h.offset + h.size } } ?: return null
    return header.offset + header.size - viewportStartOffset
}

/**
 * 同一处只有最下面那条画柔边：外面那条（[BarEdgeFrame] 的顶栏、[PinnedRowFrame] 的吸顶条）提供它，
 * 里面更靠下的条在下面时（[ClaimPinnedEdge]）外面那条让出柔边。左右翻页时可能同时有两页的列表在报，任一页吸住都算。
 * [presence] 是下面有几成的条在（0..1，吸顶行出现与消失走效果动效）。
 */
@Stable
private class PinnedEdgeHandoff {
    val claims = mutableStateListOf<() -> Float>()
    val presence: Float get() = claims.maxOfOrNull { it() } ?: 0f
    val taken: Boolean get() = presence > 0f
}

private val LocalPinnedEdgeHandoff = staticCompositionLocalOf<PinnedEdgeHandoff?> { null }

/** [present]（0..1）大于 0 时这里的条在外面那条的下面，外面那条的柔边让给它。 */
@Composable
private fun ClaimPinnedEdge(present: () -> Float) {
    val handoff = LocalPinnedEdgeHandoff.current
    val current = rememberUpdatedState(present)
    if (handoff != null) {
        DisposableEffect(handoff) {
            val claim = { current.value() }
            handoff.claims += claim
            onDispose { handoff.claims -= claim }
        }
    }
}

/**
 * 会让位的顶栏（[PageScaffold] 的顶栏、[CollapsibleHeader] 的 `yieldingBar`）能不能往回拉出来：列表里有 `stickyHeader` 吸住时（[rememberStickyHeaderEdge]）不能。
 * 吸住说明读者在它下面那段（评论区）里，往回翻是在翻楼层，顶栏这时冒出来只会压在吸住的那条上面；
 * 等那条落回原位、它上面的正文重新露出来，顶栏才照常往回一拉就出现。钉在列表外面的条（[PinnedRowFrame] 的选项卡）
 * 上面没有可回去的正文，不拦。设计决定（not in M3：bars.md 只有 enterAlways 一种「往回滚就出现」）。
 * 左右翻页时可能同时有两页的列表在报，任一页吸住都算。
 */
@Stable
internal class TopBarHold {
    private val holds = mutableStateListOf<() -> Boolean>()
    val held: Boolean get() = holds.any { it() }

    fun add(hold: () -> Boolean) {
        holds += hold
    }

    fun remove(hold: () -> Boolean) {
        holds -= hold
    }
}

internal val LocalTopBarHold = staticCompositionLocalOf<TopBarHold?> { null }

/**
 * 让位顶栏的 enterAlways 滚动，[hold] 拦着时往回滚不让顶栏出来：内容照滚，顶栏的高度不动，
 * 只记下内容滚过的量（顶栏判断「内容在它下面」用）；往回甩剩下的速度也不交给顶栏，半收的顶栏只按就近归位。
 * 往上滚（往下读）照常让顶栏让位。会让位的顶栏都经它接滚动：[PageScaffold] 的顶栏、[CollapsibleHeader] 的 `yieldingBar`。
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun TopAppBarScrollBehavior.heldBy(hold: TopBarHold): NestedScrollConnection = HeldBarScroll(this, hold)

@OptIn(ExperimentalMaterial3Api::class)
private class HeldBarScroll(private val bar: TopAppBarScrollBehavior, private val hold: TopBarHold) : NestedScrollConnection {
    private val inner get() = bar.nestedScrollConnection

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y > 0f && hold.held) Offset.Zero else inner.onPreScroll(available, source)

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (consumed.y > 0f && hold.held) {
            bar.state.contentOffset += consumed.y
            return Offset.Zero
        }
        return inner.onPostScroll(consumed, available, source)
    }

    override suspend fun onPreFling(available: Velocity): Velocity = inner.onPreFling(available)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        inner.onPostFling(consumed, if (available.y > 0f && hold.held) available.copy(y = 0f) else available)
}

/** [held] 为 true 时外面的页面顶栏不随往回滚出现（[TopBarHold]）。 */
@Composable
private fun HoldTopBar(held: () -> Boolean) {
    val hold = LocalTopBarHold.current
    val current = rememberUpdatedState(held)
    if (hold != null) {
        DisposableEffect(hold) {
            val entry = { current.value() }
            hold.add(entry)
            onDispose { hold.remove(entry) }
        }
    }
}

/** 柔边出现与消失是透明度变化，走主题的效果动效（motion.md：color / opacity → effects）。只在绘制阶段读。 */
@Composable
private fun rememberEdgeAlpha(visible: () -> Boolean): () -> Float {
    val currentVisible by rememberUpdatedState(visible)
    val target by remember { derivedStateOf { currentVisible() } }
    val alpha = animateFloatAsState(
        targetValue = if (target) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "pinnedEdgeFade",
    )
    return remember(alpha) { { alpha.value } }
}

private fun ContentDrawScope.drawEdgeFade(top: Float, color: Color, alpha: Float) {
    if (alpha <= 0f) return
    val height = PinnedEdgeFadeHeight.toPx()
    drawRect(
        brush = Brush.verticalGradient(listOf(color, color.copy(alpha = 0f)), startY = top, endY = top + height),
        topLeft = Offset(0f, top),
        size = Size(size.width, height),
        alpha = alpha,
    )
}

/**
 * 有没有正文滚到吸顶条下面：累计内容自己滚掉的量（往上为负），回到顶部再往下拉时归零。
 * 与 M3 顶栏判断「内容在它下面」的累计方式相同；偏移随存档恢复，从下一页返回时首帧就对。
 */
@Stable
private class ScrolledUnder(initial: Float = 0f) : NestedScrollConnection {
    var offset by mutableFloatStateOf(initial)

    val isUnder: Boolean get() = offset < -0.5f

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        offset = if (consumed.y == 0f && available.y > 0f) 0f else (offset + consumed.y).coerceAtMost(0f)
        return Offset.Zero
    }

    companion object {
        val Saver = Saver<ScrolledUnder, Float>(save = { it.offset }, restore = { ScrolledUnder(it) })
    }
}
