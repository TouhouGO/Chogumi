package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.getVelocityFromNanos
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 选项卡与左右翻页的同步状态：**选中项是唯一真源**（页面状态里的那个序号），翻页只是它的样子。
 *
 * - 选中项变了（点了 tab、或页面自己换了一项）：翻页平滑跟过去，停下时整页停在选中的那一页上——
 *   连着快速点几个 tab 时，后一次点选从前一次翻页此刻的位置（可能在两页之间）以此刻的速度接着走，不先刹停再起步（[PageFollow]）；
 * - 手指翻页：落定之后才回写选中项（[rememberTabPagerState] 的 onSelect）。点选触发的滚动被下一次点选打断时
 *   会在半路短暂停下，那不算手指翻页，不回写，免得把刚点的选择抢回去。
 *
 * 选择器读 [shownPage] 决定高亮哪一项、读 [position] 让指示条跟手；不另存一份选中项。
 */
@Stable
class TabPagerState internal constructor(
    internal val pager: PagerState,
    private val selected: State<Int>,
    private val swipedByUser: MutableState<Boolean>,
) {
    /**
     * 选择器此刻高亮哪一项：手指翻页时跟着翻过一半的那一页（不等松手），其余时候就是选中项——
     * 点远处的 tab 时翻页会滚过中间几页，高亮不逐个闪过它们。
     * 页数可能在翻页途中变少（作品详情的人物页随数据隐藏），翻页位置要等下一次测量才收回来，这里先夹在页数以内。
     */
    val shownPage: Int get() = if (swipedByUser.value) minOf(pager.currentPage, pager.pageCount - 1) else selected.value

    /** 翻页的连续位置（页序 + 偏移比例）：指示条按它在两项之间插值，跟着手指走。 */
    val position: Float get() = pager.currentPage + pager.currentPageOffsetFraction
}

/** [selected] 是页面状态里的选中项；手指翻页落定在另一页时调 [onSelect]。页数变化（某一项被隐藏）由 [pageCount] 给出。 */
@Composable
fun rememberTabPagerState(selected: Int, pageCount: Int, onSelect: (Int) -> Unit): TabPagerState {
    val pager = rememberPagerState(initialPage = selected) { pageCount }
    val swipedByUser = remember { mutableStateOf(false) }
    val currentSelected = rememberUpdatedState(selected)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val motion = LocalAppMotion.current
    val follow = remember(pager) { PageFollow() }

    LaunchedEffect(selected) {
        swipedByUser.value = false
        // 到没到按翻页停的位置判（页序与偏移），不按离哪页最近：上一次点选的翻页被这一次打断时停在两页之间，
        // 离这次点的那页可能已经最近（currentPage 已是它），只比页序就不走了，翻页卡在两页之间。
        if (pager.currentPage != selected || pager.currentPageOffsetFraction != 0f) motion.scrollToPage(pager, selected, follow) else follow.stop()
    }
    LaunchedEffect(pager) {
        launch {
            pager.interactionSource.interactions.collect {
                if (it is DragInteraction.Start) {
                    swipedByUser.value = true
                    // 手指接手了：点选翻页留下的速度作废，下一次点选从静止起步
                    follow.stop()
                }
            }
        }
        // 手指翻页连同其后的惯性与吸附都停下、停在整页上，才算落定；拖了一下又回到原页也在这里收尾。
        // 只看「不在滚动」不够：松手到惯性接上之间翻页会有一刹那不在滚动、却还停在两页之间，
        // 在那里收尾会清掉手指翻页的标记，惯性停到下一页后就不回写了（真机上常见，测试的虚拟时钟里不出现）。
        snapshotFlow { !pager.isScrollInProgress && pager.currentPageOffsetFraction == 0f }.collect { atRest ->
            if (atRest && swipedByUser.value) {
                swipedByUser.value = false
                if (pager.currentPage != currentSelected.value) latestOnSelect(pager.currentPage)
            }
        }
    }
    return remember(pager) { TabPagerState(pager, currentSelected, swipedByUser) }
}

/**
 * 「选项卡 + 可左右滑的内容」的翻页容器：全仓唯一实现（放送、进度、探索、搜索排序、动态、作品详情、用户主页、目录共用）。
 *
 * - 每页各自组合、各自保留滚动位置。一页第一次上屏才组合它的内容（[rememberPageShown]），之后翻走也留在组合里，
 *   翻回来是现成的：页离屏就拆、回来再整页重搭的话，重搭（一整页的列表项、图片、富文本）落在翻页动画中间的一帧里，
 *   连着切几个 tab 时每一下都掉帧。没上过屏的页只占一个空的外框；不在屏幕上的页不排版、不画（[holdWhileAway]）。
 * - **页内的横向滚动不外交**：货架、轮播、chip 行滑到头后剩下的横向滑动在页的边界上吃掉，不顺势翻页——
 *   在横滑的东西上滑，动的只是它自己（M3 navigation.md「Tabs」：避免手势在 tab 内容里互相抢）。
 *   竖向滚动照常往外传（可收起的头部、下拉刷新）。在页里横滑组件以外的地方滑才是翻页。
 * - [key] 是字符串（枚举传 `name`）：页键进入页里每个可保存状态的位置标识，枚举等对象的哈希每个进程都不一样，
 *   进程被回收后恢复时页里的可保存状态就全对不上号；字符串的哈希跨进程不变。不传时按页序。
 */
@Composable
fun TabPager(
    state: TabPagerState,
    modifier: Modifier = Modifier,
    key: ((page: Int) -> String)? = null,
    pageContent: @Composable (page: Int) -> Unit,
) {
    HorizontalPager(
        state = state.pager,
        modifier = modifier,
        // 所有页都留在组合里：上过屏的页翻走不拆（见上），内容是否组合仍由 rememberPageShown 管
        beyondViewportPageCount = state.pager.pageCount,
        key = key,
        verticalAlignment = Alignment.Top,
    ) { page ->
        val held = remember { HeldConstraints() }
        val outer = LocalPageOnScreen.current
        val onScreen = remember(state.pager, page, outer) { { outer() && !state.pager.isAway(page) } }
        Box(Modifier.holdWhileAway(state.pager, page, held).fillMaxSize().nestedScroll(KeepHorizontalScrollInPage)) {
            CompositionLocalProvider(LocalPageOnScreen provides onScreen) {
                if (rememberPageShown(state.pager, page)) pageContent(page)
            }
        }
    }
}

/**
 * 这里的内容此刻在不在屏幕上：[TabPager] 给每一页提供（嵌套的翻页器连同外层一起判），翻页器以外一直为 true。读的是快照状态，
 * 在组合之外（回调、绘制）读也跟着变。翻走的页留在组合里（见 [TabPager]），往整个页面登记的东西（吸顶条扣住顶栏、
 * 占柔边，再点 tab 回顶）只对在屏幕上的页算数。
 */
internal val LocalPageOnScreen = staticCompositionLocalOf<() -> Boolean> { { true } }

/** 一页最后一次在屏幕上时量它用的约束（[holdWhileAway]）。 */
private class HeldConstraints {
    var value: Constraints? = null
}

/**
 * 不在屏幕上的页不排版、不画：按它最后一次在屏幕上时的约束量（翻页器变高也不重新量），也不放置（不放置的节点不画、
 * 不报位置）。页都留在组合里（见 [TabPager]），头部随滚动收起时翻页器每帧变高、重画，不拦的话每一页的列表每帧都跟着
 * 重新排、重新画。页回到屏幕上就按此刻的约束量、照常放置。
 *
 * 放不放在放置阶段判：那时翻页器已经按这一帧的滚动更新了位置，刚滑进来的页当帧就画。量的时候读到的位置晚一帧，
 * 刚滑进来的页第一帧可能还按旧约束量（只在它不在屏幕上时头部动过才不同），下一帧就按此刻的。
 */
private fun Modifier.holdWhileAway(pager: PagerState, page: Int, held: HeldConstraints): Modifier = layout { measurable, constraints ->
    val used = held.value?.takeIf { pager.isAway(page) } ?: constraints.also { held.value = it }
    val placeable = measurable.measure(used)
    layout(constraints.constrainWidth(placeable.width), constraints.constrainHeight(placeable.height)) {
        if (!pager.isAway(page)) placeable.place(0, 0)
    }
}

/** 这一页此刻一点都不在屏幕上（与当前位置差一整页或更多）。 */
private fun PagerState.isAway(page: Int): Boolean = abs(page - currentPage - currentPageOffsetFraction) >= 1f

/**
 * 这一页的内容组合了没有：这一页是当前页、或已经出现在屏幕上，才组合；组合过就一直留着（[TabPager] 不让页离开组合）。
 *
 * 翻页途中（点远处的 tab、连续翻页）翻页器会把前方的页预先组合好。预先组合的页里，嵌套的懒列表（每个 tab 自己的列表）
 * 给列表项记的可保存状态用的位置标识，与重建后直接组合这一页时算出来的不一样：Activity 重建（换密度、进程被回收后恢复）时，
 * 列表项里的可保存状态（开着的用户卡片、展开的楼层……）全部对不上号而丢失。所以页内容不在预先组合时组合，等它真要上屏再组合；
 * 当前页在第一帧就组合，不闪空白。
 */
@Composable
private fun rememberPageShown(pager: PagerState, page: Int): Boolean {
    var shown by remember(page) { mutableStateOf(false) }
    if (!shown) {
        val onScreen by remember(pager, page) {
            derivedStateOf { page == pager.currentPage || pager.layoutInfo.visiblePagesInfo.any { it.index == page } }
        }
        if (onScreen) shown = true
    }
    return shown
}

/** 页内横向滚动剩下的量（滑到头之后）与剩下的惯性都留在页里，不交给外面的翻页。 */
private object KeepHorizontalScrollInPage : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = Offset(available.x, 0f)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity(available.x, 0f)
}

/**
 * 随选中项的翻页（[io.github.touhougo.chogumi.core.ui.theme.AppMotion.scrollToPage]）：连着点 tab 时，后一段翻页打断前一段，
 * 从前一段最后一帧的位置、速度和时刻接着走。官方 `animateScrollToPage` 每段都从静止起步，而且新一段的第一帧只定起点、
 * 不动：打断一次，页面先刹停、再空一帧才起步，连点时一顿一顿。
 *
 * 离目标还有三页以上时先跳到离目标一屏的地方再滑（与 `animateScrollToPage` 同一条规则，中间的页不上屏）。
 */
internal class PageFollow {
    /** 被打断的上一段最后一帧：速度（像素 / 秒，沿滚动方向）与帧时刻。走完、被手指接手、或已被下一段接过时没有。 */
    private var carry: Carry? = null

    private class Carry(val velocity: Float, val frameNanos: Long)

    fun stop() {
        carry = null
    }

    suspend fun animateTo(pager: PagerState, page: Int, spec: AnimationSpec<Float>) {
        val from = carry
        carry = null
        val visible = pager.layoutInfo.visiblePagesInfo
        if (visible.isNotEmpty()) {
            val first = visible.first().index
            val last = visible.last().index
            val forward = page > first
            if (((forward && page > last) || (!forward && page < first)) && abs(page - first) >= FAR_PAGES) {
                pager.scrollToPage(if (forward) (page - visible.size).coerceAtLeast(first) else (page + visible.size).coerceAtMost(first))
            }
        }
        // 系统的动画时长缩放（开发者选项）同样作用于这里，与 Compose 自带的动画一致；缩放为 0 时 AppMotion 不走这里
        val durationScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        pager.scroll {
            val pageSize = pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing
            val distance = (page - pager.currentPage - pager.currentPageOffsetFraction) * pageSize
            var animation: TargetBasedAnimation<Float, AnimationVector1D>? = null
            var startNanos = 0L
            var playNanos = 0L
            var scrolled = 0f
            do {
                withFrameNanos { now ->
                    val running = animation ?: run {
                        // 紧接着被打断的那一段：时钟从它的最后一帧起算、带着它的速度，这一段的第一帧就往前走，不空一帧
                        val carried = from?.takeIf { now - it.frameNanos <= CARRY_WINDOW_NANOS }
                        startNanos = carried?.frameNanos ?: now
                        TargetBasedAnimation(spec, Float.VectorConverter, 0f, distance, carried?.velocity ?: 0f).also { animation = it }
                    }
                    playNanos = ((now - startNanos) / durationScale).toLong()
                    scrolled += scrollBy(running.getValueFromNanos(playNanos) - scrolled)
                    carry = Carry(running.getVelocityFromNanos(playNanos), now)
                }
            } while (animation?.isFinishedFromNanos(playNanos) != true)
        }
        carry = null
    }

    private companion object {
        /** 离目标这么多页以上就先跳近再滑：同 Compose `animateScrollToPage` 的 MaxPagesForAnimateScroll。 */
        const val FAR_PAGES = 3

        /** 上一段的最后一帧离这一段的第一帧不超过这么久才接着走（连点时是相邻的两帧），否则从静止起步。 */
        const val CARRY_WINDOW_NANOS = 100_000_000L
    }
}
