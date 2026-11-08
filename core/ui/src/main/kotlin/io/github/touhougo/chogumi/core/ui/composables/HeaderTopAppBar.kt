package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.touhougo.chogumi.core.res.R

/**
 * 头部从屏幕顶边画起（铺到状态栏下面）的页面的顶栏：全仓**唯一**实现，作品详情、角色 / 人物详情、用户主页共用。
 *
 * 顶栏叠在页面头部之上、留在顶上，状态栏那一截也属于它：
 * - 头部还在顶栏下面时容器透明，头部底色透出来；
 * - 内容滚到顶栏下面时容器填上页面底色 `surface`、显出标题。填色连状态栏一起盖住，滚上去的内容不会和状态栏的时间、电量
 *   叠在一起；顶栏与页面同色，内容与顶栏之间只靠柔边分层（PinnedEdge.kt），不换成另一种颜色；
 * - 返回与操作按钮不随内容滚走（bars.md：内容滚动时仍要能用顶栏的操作）；下面吸着选项卡的页面，头部收满后顶栏让位给
 *   选项卡，一往回滚就回来（带 [CollapsibleHeaderState] 的重载）。
 *
 * [fill] 是填了几成（0 = 透明，1 = 填满），只在绘制阶段读，滚动时不重组。头部跟着列表一起滚的页面用 [rememberScrolledFill]；
 * 下面吸着选项卡的页面用带 [CollapsibleHeaderState] 的那个重载。
 *
 * @param onBack 非 null 时左边出返回按钮。
 */
@Composable
fun HeaderTopAppBar(
    title: String,
    fill: () -> Float,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    HeaderBar(title, fill, onBack, scrollBehavior = null, modifier, actions)
}

/**
 * [HeaderTabsFrame] 页面的顶栏：填色跟着 [frame] 的收起程度；头部收满之后它让位给吸在下面的选项卡
 * （往上滚收走、状态栏那一截留着，一往回滚就回来，见 [CollapsibleHeader] 的 `yieldingBar`），往下读时只剩选项卡一层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeaderTopAppBar(
    title: String,
    frame: CollapsibleHeaderState,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    HeaderBar(title, { frame.collapsedFraction }, onBack, yieldingBarScrollBehavior(frame), modifier, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeaderBar(
    title: String,
    fill: () -> Float,
    onBack: (() -> Unit)?,
    scrollBehavior: TopAppBarScrollBehavior?,
    modifier: Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = TitleEasing.transform(fill()) },
            )
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.arrow_back_24), stringResource(R.string.back))
                }
            }
        },
        actions = actions,
        modifier = modifier.headerBarFill(fill),
        colors = transparentBarColors(),
        scrollBehavior = scrollBehavior,
    )
}

/**
 * 头部从屏幕顶边画起（铺到 [HeaderTopAppBar] 下面）、选项卡吸在顶栏下面的页面外框：全仓**唯一**实现，作品详情与用户主页共用。
 *
 * - [header] 随内容往上滚收起，内容回到顶部再往下拉才展开（[HeaderReveal.AtContentTop]：大头部滚到半截时不挡内容）；
 * - 收满时留出顶栏那一截（状态栏 + 顶栏高），[tabs] 正好停在顶栏下面、一直点得到，与顶栏同为页面底色连成一块；
 *   顶栏用带 [state] 的 [HeaderTopAppBar]，填色与收起程度同步；
 * - 头部收满后再往上滚，顶栏让位、[tabs] 顶到状态栏下面，只剩一层；一往回滚顶栏先回来（[CollapsibleHeader] 的 `yieldingBar`）；
 * - [content] 是选项卡内容（左右翻页 [TabPager]，每页自己一个列表），竖向滚动冒泡到这里。
 *
 * 真实页面与它的整页骨架都经这里，未滚动时两者的头部与选项卡落在同一个位置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeaderTabsFrame(
    state: CollapsibleHeaderState,
    header: @Composable () -> Unit,
    tabs: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val topBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopAppBarDefaults.TopAppBarExpandedHeight
    CollapsibleHeader(
        collapsing = header,
        pinned = tabs,
        modifier = modifier,
        state = state,
        reveal = HeaderReveal.AtContentTop,
        keepVisible = topBarHeight,
        yieldingBar = TopAppBarDefaults.TopAppBarExpandedHeight,
        content = content,
    )
}

/**
 * 整摞吸顶的东西（状态栏那一截、顶栏、吸顶条、吸住的排序行）的颜色：全仓只有页面底色 `surface`，没有第二种。
 * 内容滚到它们下面时不换色，只靠下沿的柔边分层（PinnedEdge.kt）。顶栏、让位后留下的状态栏一截、吸顶条都不叠任何填色，
 * 页面自己也不写这一摞的颜色。M3 bars.md 的「滚动后容器换成 surface container」在这里不取：设计决定，not in M3。
 *
 * 头部从屏幕顶边画起的页面（[HeaderTopAppBar]）是唯一的例外：头部还在顶栏下面时顶栏透明，内容滚到顶栏下面后顶栏填上
 * `surface`（[headerBarFill]），之后与别的页面一样。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun pageBarColors(): TopAppBarColors {
    val surface = MaterialTheme.colorScheme.surface
    return TopAppBarDefaults.topAppBarColors(containerColor = surface, scrolledContainerColor = surface)
}

/**
 * 头部图页的顶栏用的颜色：M3 自己的滚动变色两头都设成透明，由 [headerBarFill] 画 `surface` 的填色。
 * 全仓只有这里写透明容器的 `topAppBarColors`（`tools/guards/check_status_bar.py`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun transparentBarColors(): TopAppBarColors =
    TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)

/**
 * 放送日历、社区的页头：M3 small 顶栏，页面底色（[pageBarColors]）。状态栏由外面留出（页面先留一段状态栏高度），这里不再避让。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageHeaderBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = navigationIcon,
        actions = actions,
        modifier = modifier,
        windowInsets = WindowInsets(0),
        colors = pageBarColors(),
    )
}

/**
 * 头部图页的顶栏填色：填上 [fill] 成的 `surface`（0 = 透明，下面是头部图，1 = 页面底色），只在绘制阶段读 [fill]。
 * 填色是页面底色改透明度，不从 `Color.Transparent`（透明黑）插值。
 */
@Composable
private fun Modifier.headerBarFill(fill: () -> Float): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    return drawBehind { drawRect(surface.copy(alpha = fill())) }
}

/** 列表是否已离开顶部，即有内容滚到了列表顶边之上（顶栏据此填色）。 */
fun isScrolledFromTop(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int): Boolean =
    firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0

/** [HeaderTopAppBar] 的填色：[list] 离开顶部即填满、回到顶部即透明（[isScrolledFromTop]）。 */
@Composable
fun rememberScrolledFill(list: LazyListState): () -> Float =
    rememberScrolledFill { isScrolledFromTop(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }

/** 同 [rememberScrolledFill]，用于整页 `verticalScroll` 的页面（登录页）。 */
@Composable
fun rememberScrolledFill(scroll: ScrollState): () -> Float = rememberScrolledFill { scroll.value > 0 }

/** 同 [rememberScrolledFill]，用于网格。 */
@Composable
fun rememberScrolledFill(grid: LazyGridState): () -> Float =
    rememberScrolledFill { isScrolledFromTop(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) }

/**
 * 两态之间的过渡走主题的效果动效：填色是颜色变化，不是几何（motion.md：color / opacity → effects）。
 * 滚动位置随存档恢复，从下一页返回时首帧就是对的那一态，不重播填色。
 */
@Composable
internal fun rememberScrolledFill(scrolled: () -> Boolean): () -> Float {
    val currentScrolled by rememberUpdatedState(scrolled)
    val target by remember { derivedStateOf { currentScrolled() } }
    val fill = animateFloatAsState(
        targetValue = if (target) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "topBarFill",
    )
    return remember(fill) { { fill.value } }
}

/**
 * 标题随填色淡入的进度曲线：前半程几乎不显，页面头部自己的标题滑到顶栏下面之后才显出来，不会两个标题同时看得见。
 * 曲线取 M3 emphasized accelerate（tokens.md「Legacy easing」），这里当进度映射用；标题跟着填色淡入是设计决定，not in M3。
 */
private val TitleEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
