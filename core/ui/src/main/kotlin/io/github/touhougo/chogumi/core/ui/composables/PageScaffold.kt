package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.navigation.LocalVisibleArea
import io.github.touhougo.chogumi.core.ui.common.navigation.ProvideTopReset
import io.github.touhougo.chogumi.core.ui.common.navigation.VisibleArea

/**
 * 页面通用脚手架：顶栏（可选返回）+ 可选下拉刷新 + 可选悬浮操作。顶栏与状态栏那一截永远是页面底色（[pageBarColors]），内容滚到它下面时只靠下沿的柔边分层。
 * [contentWindowInsets] 是内容区避让的系统栏；页面已在外层避让时传 `WindowInsets(0)`。
 *
 * 顶栏随内容往上滚让位、一往回滚就出现（M3 enterAlways，bars.md「Can hide when scrolling up and reveal when scrolling
 * down」），状态栏那一截留着。页面在顶栏下面吸住的一条（选项卡、评论区排序行）因此在往下读时独自留在最上面，
 * 不和顶栏叠成两层；返回与操作按钮往回一拉就在。例外是列表里吸住的 `stickyHeader`（评论区排序行）：它吸着时往回翻是在翻楼层，
 * 顶栏不出来，等它落回原位、上面的正文重新露出来才照常出现（[TopBarHold]）。整摞（状态栏那一截、顶栏、吸在下面的条）任何时候都是页面底色，
 * 与正文同色、不随滚动换色，只靠柔边分层；最下面那条下沿出柔边，页面里没有吸顶条时就是顶栏自己（PinnedEdge.kt）。
 *
 * [subtitle] 是标题下面一行小字（首页的日期）；给了 [titleLink] 时不用。
 *
 * [titleLink] 给了时标题是这一页上级的去处（帖子页的小组、作品）：[title] 是上级的名字，见 [TitleLink]。
 * 上级还没到时 [titleLoading] 为 true，顶栏画 [TitleLink] 的占位（同一个 [TitleLinkLayout]，小图格与名字条的位置都在）；
 * 加载失败时页面传一个通用页名当 [title]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    titleLink: TitleLink? = null,
    titleLoading: Boolean = false,
    floatingActionButton: (@Composable () -> Unit)? = null,
    snackbarHost: (@Composable () -> Unit)? = null,
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        state = rememberBarState(),
        snapAnimationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        flingAnimationSpec = TopAppBarDefaults.flingAnimationSpec,
    )
    val barState = scrollBehavior.state
    val hold = remember { TopBarHold() }
    val barScroll = remember(scrollBehavior, hold) { scrollBehavior.heldBy(hold) }
    val scrolledUnder = { barState.overlappedFraction > 0.01f }
    // 顶栏连状态栏那一截的高度（随让位变矮），顶栏下沿的柔边画在这里
    var barHeight by remember { mutableIntStateOf(0) }
    val barOcclusion = remember(barState) {
        VisibleArea {
            // 顶栏还没量出来时上限是个极大的负数，当作没有遮挡
            val barBottom = if (barState.heightOffsetLimit > -UNMEASURED_BAR_LIMIT) -barState.heightOffsetLimit + barState.heightOffset else 0f
            Rect(0f, barBottom, OCCLUSION_EXTENT, OCCLUSION_EXTENT)
        }
    }
    // 程序回顶（回到顶部按钮）不经嵌套滚动：顶栏在这里显式归位，让位的顶栏跟着回来
    ProvideTopReset(reset = {
        barState.heightOffset = 0f
        barState.contentOffset = 0f
    }) {
        Scaffold(
            modifier = modifier.nestedScroll(barScroll),
            contentWindowInsets = contentWindowInsets,
            topBar = {
                TopAppBar(
                    // M3 bars.md：小号顶栏不折行。标题多是作品名 / 目录名这类用户内容、长度不可控，只能单行省略
                    // （规范同时不建议截断，本该换 medium flexible 顶栏——设计决定，not in M3）。
                    title = {
                        when {
                            titleLink != null -> TitleLinkRow(title, titleLink)
                            titleLoading -> TitleLinkSkeleton()
                            else -> Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    subtitle = { if (subtitle != null) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    painter = painterResource(R.drawable.arrow_back_24),
                                    contentDescription = stringResource(R.string.back),
                                )
                            }
                        }
                    },
                    actions = actions,
                    modifier = Modifier.onSizeChanged { barHeight = it.height },
                    colors = pageBarColors(),
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = floatingActionButton ?: {},
            snackbarHost = snackbarHost ?: {},
        ) { padding ->
            // 顶栏画在内容上面、列表在它下面滚：封面半截藏在顶栏下时，容器转换只从没被顶栏盖住的那截长出（顶栏随滚动让出的高度跟着变）
            // 顶栏是最下面一条吸顶的东西时，正文滚到它下面就在它下沿出柔边；页面里自己的吸顶条把柔边要走（[BarEdgeFrame]）
            BarEdgeFrame(
                barBottom = { barHeight.toFloat() },
                scrolledUnder = scrolledUnder,
                modifier = Modifier.fillMaxSize().onPlaced { barOcclusion.coordinates = it },
            ) {
                CompositionLocalProvider(LocalVisibleArea provides barOcclusion, LocalTopBarHold provides hold) {
                    if (onRefresh != null) {
                        // 下拉刷新时 ChogumiPullToRefreshBox（唯一真源）包住内容：底部留白交给 Box 自身，
                        // 顶栏高度以 contentPadding 形式透传给内容（列表可在顶栏下方滚动）。
                        // 指示器从顶栏下沿拉出：顶栏不透明、画在内容上面，从屏幕顶边拉出的指示器在拉到阈值前一直被它盖着
                        ChogumiPullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = onRefresh,
                            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                            indicatorPadding = PaddingValues(top = padding.calculateTopPadding()),
                        ) {
                            content(
                                PaddingValues(
                                    top = padding.calculateTopPadding(),
                                    start = padding.calculateStartPadding(LocalLayoutDirection.current),
                                    end = padding.calculateEndPadding(LocalLayoutDirection.current),
                                ),
                            )
                        }
                    } else {
                        content(padding)
                    }
                }
            }
        }
    }
}

/**
 * 顶栏标题当作一个去处：[image]（上级的小图）+ 名字，整条可点（[onClick]）。名字单行省略，标题占满返回键与操作按钮之间，
 * 右边紧挨着页面的 ⋮（[PageMoreMenu]）。点按圆角 12、图文间距 12、首尾留白 4，设计决定（not in M3）。
 */
@Immutable
class TitleLink(val onClick: () -> Unit, val image: @Composable () -> Unit)

@Composable
private fun TitleLinkRow(title: String, link: TitleLink) {
    PressArea(onClick = link.onClick, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(), role = Role.Button) {
        TitleLinkLayout(
            image = link.image,
            name = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = it) },
        )
    }
}

/** [TitleLink] 还没到时的占位：同一个 [TitleLinkLayout]，小图画成色块、名字画成一条色条，不可点。 */
@Composable
private fun TitleLinkSkeleton() {
    TitleLinkLayout(
        image = { SkeletonBox(Modifier.size(TitleLinkImageSize), shape = MaterialTheme.shapes.extraSmall) },
        name = { SkeletonText(MaterialTheme.typography.titleLarge, it, widthFraction = 0.5f) },
    )
}

/** 顶栏标题去处的排法：小图、名字（占满剩下的宽度）。真实标题与占位共用。 */
@Composable
private fun TitleLinkLayout(image: @Composable () -> Unit, name: @Composable (Modifier) -> Unit) {
    Row(
        modifier = Modifier.heightIn(min = TitleLinkMinHeight).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        image()
        name(Modifier.weight(1f))
    }
}

private val TitleLinkMinHeight = 48.dp

/** [TitleLink] 的小图格：24×32（作品封面 3:4，小组图标宽 24 竖向居中），配顶栏的 `titleLarge` 字高。设计决定（not in M3）。 */
val TitleLinkImageSize = DpSize(24.dp, 32.dp)

/** 顶栏高度的上限读数超过它就是还没量出来。 */
private const val UNMEASURED_BAR_LIMIT = 100_000f

/** 没被顶栏盖住的那一块向下、向右伸多远（像素），大到盖住任何页面。 */
private const val OCCLUSION_EXTENT = 1_000_000f
