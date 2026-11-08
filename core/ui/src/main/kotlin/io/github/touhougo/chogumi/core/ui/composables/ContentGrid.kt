package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.ui.common.navigation.ScrollToTopOnTabReselect

/**
 * 网格单元的种类——**全仓网格列数的唯一出处**。
 *
 * 列数不写死：每种单元给一个最小格宽与格距，列数按所在宽度推出（算法见 [gridColumnCount]）。
 * 小屏手机、大屏手机、平板都由这一处决定；页面里不得再写 `GridCells.Fixed(n)`、`GridCells.Adaptive(xx.dp)`
 * 或 `chunked(n)` 分行。各页各定一个（个人页 112、搜索 104、日历与单行本固定 3、好友固定 4）的话，
 * 380dp 小屏上个人页收藏只剩 2 列、好友头像被挤扁。页面里的网格只有两种画法：整页滚动的 [ContentGrid]，
 * 和夹在整页滚动列里的一小段 [AdaptiveGridRows]。
 *
 * 最小格宽是我的设计决定（not in M3）：layout.md 只规定页边距与列间距随断点变化，不规定内容格宽。
 */
enum class GridCell(val minWidth: Dp, val spacing: Dp) {
    /**
     * 海报卡（[PosterCard]）：3:4 封面 + 两行标题。
     * 100：360dp 手机减去两侧 16 留白后（328 + 间距 12）/（100 + 12）= 3.03，仍排 3 列。
     */
    Poster(100.dp, GridSpacing),

    /** 人物卡（[PersonGridItem]）：圆头像 + 名字。下限 = 头像直径 + 左右内边距，格子不会比头像窄。 */
    Person(PersonAvatarSize + PersonGridItemHorizontalPadding * 2, GridSpacing),

    /**
     * 章节方格：正方形格子，格距 4dp，一格连格距至少 48dp——与 M3 日期选择器同一节距（component-tokens.md DatePicker：
     * 360dp 宽排 7 天，每天 48dp 一列，正是触控区下限）。手机上 6 到 8 列（360dp 6 列、380dp 7 列、448dp 8 列），平板十几列。
     */
    EpisodeTile(44.dp, 4.dp),

    /**
     * 筛选格（[FilterTile]：一摞封面 + 名字 + 部数）：格距 8dp。160 让 380dp、448dp 手机排 2 列、800dp 宽屏面板排 3 列，
     * 两列时名字还放得下五六个字。
     */
    FilterTile(160.dp, 8.dp),

    /**
     * 只有封面、没有标题的格（统计页的「最爱」「最冷门」）：格距 8dp。80 让卡片里（屏宽减去两层 16 留白）的 360–448dp 手机排 3 到 4 列，
     * 平板更多。
     */
    Cover(80.dp, 8.dp),

    /** 编辑器表情面板的小表情（原生 20–21px）：40 见方、格距 4，与章节方格同一节距（一格连格距 44dp）。 */
    Smiley(40.dp, 4.dp),

    /** 编辑器表情面板的大表情（musume、blake，网页限宽 55px）：格距 8。我的设计决定（not in M3）。 */
    LargeSmiley(64.dp, 8.dp),

    /**
     * 设置里的配色格（方块色样 + 两个字的名字）：格距 8。64 让卡片里（屏宽减去两层 16 留白）的 360–380dp 手机排 4 列、
     * 448dp 排 5 列、800dp 一行排下十套。我的设计决定（not in M3）。
     */
    Swatch(64.dp, 8.dp),
}

/** 网格格间距（横向；列数算法要用，故全仓一个值）。 */
val GridSpacing = 12.dp

/** 网格外侧留白：M3 compact 窗口的页边距 16dp（layout.md:354），与页面其它区块（搜索栏、分区标题）的左右边距一致。 */
val GridPadding = 16.dp

/**
 * 在 [available] 宽度里排几列（像素）：(可用 + 间距) / (最小格宽 + 间距)，至少 1 列。
 * 与 Compose `GridCells.Adaptive` 同一算法；惰性网格（[ContentGrid]）、非惰性网格（[AdaptiveGridRows]）与章节方格都只调这里。
 */
internal fun gridColumnCount(available: Int, minCell: Int, spacing: Int): Int =
    maxOf((available + spacing) / (minCell + spacing), 1)

/**
 * 整页滚动的内容网格——全仓唯一的 `LazyVerticalGrid`（门禁 check_adaptive.py）。左右留白、格距、列数都在这里定，
 * 页面只给上下留白与行距：左右两缘的留白恒为 [GridPadding]，与页面其它区块对齐，两侧相等。
 *
 * 惰性网格按格宽给每一项定宽约束，项目自己写的 `width` 会被约束改回格宽，所以一项总是铺满一格；
 * 唯一能绕过的是 `wrapContentWidth` / `requiredWidth` 一类，门禁不许在主代码里用（自带定宽、靠起始边的卡片
 * 会把每格多出的宽都堆在右边，最右一列外的留白就比左边大）。
 * 整行项目（`GridItemSpan(maxLineSpan)`）同样吃左右留白；要铺满整宽的满色块不放进网格，放在网格外（如 [HeaderTabsFrame] 的头部）。
 */
@Composable
fun ContentGrid(
    cell: GridCell,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    rowSpacing: Dp = GridSpacing,
    userScrollEnabled: Boolean = true,
    content: LazyGridScope.() -> Unit,
) {
    ScrollToTopOnTabReselect(state)
    LazyVerticalGrid(
        columns = AdaptiveCells(cell),
        modifier = modifier,
        state = state,
        contentPadding = PaddingValues(start = GridPadding, top = top, end = GridPadding, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(rowSpacing),
        horizontalArrangement = Arrangement.spacedBy(cell.spacing),
        userScrollEnabled = userScrollEnabled,
        content = content,
    )
}

/** 惰性网格的列定义：列数由 [gridColumnCount] 决定，余下的像素从左往右逐格多分 1。 */
private class AdaptiveCells(private val cell: GridCell) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = gridColumnCount(availableSize, cell.minWidth.roundToPx(), spacing)
        val gridSize = availableSize - spacing * (count - 1)
        val slot = gridSize / count
        val remainder = gridSize % count
        return List(count) { slot + if (it < remainder) 1 else 0 }
    }

    override fun equals(other: Any?): Boolean = other is AdaptiveCells && other.cell == cell

    override fun hashCode(): Int = cell.hashCode()
}

/**
 * 非惰性网格：放在整页滚动的列里的一小段（如关联作品、单行本——一组最多几十张，要和头部、分组标题一起滚）。
 * 列数与惰性网格同源（[gridColumnCount]）。末行不满时默认补空位，格宽与上面各行一致；[fillLastRow] 时末行的格子
 * 平分这一行，每一行都铺满（筛选格：一行一行读的选项，末尾不留落单的空位；我的设计决定，not in M3）。
 * 每一项由网格定成格宽（与 [ContentGrid] 一样铺满一格），项目不接宽度。左右留白是一个值 [sideMargin]，两侧恒等：
 * 页面里取 [GridPadding]，面板托盘里的筛选格随托盘取 8dp。[modifier] 只给上下留白。
 */
@Composable
fun <T> AdaptiveGridRows(
    items: List<T>,
    cell: GridCell,
    modifier: Modifier = Modifier,
    sideMargin: Dp = GridPadding,
    rowSpacing: Dp = GridSpacing,
    fillLastRow: Boolean = false,
    itemContent: @Composable (item: T) -> Unit,
) {
    WithGridColumns(cell, modifier.padding(horizontal = sideMargin)) { columns ->
        GridRows(columns = columns, count = items.size, spacing = cell.spacing, rowSpacing = rowSpacing, fillLastRow = fillLastRow) { index ->
            itemContent(items[index])
        }
    }
}

/** [AdaptiveGridRows] 的骨架：同一套列数、间距与左右留白，画满 [rows] 行占位。 */
@Composable
fun AdaptiveGridSkeleton(
    rows: Int,
    cell: GridCell,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit,
) {
    WithGridColumns(cell, modifier.padding(horizontal = GridPadding)) { columns ->
        GridRows(columns = columns, count = rows * columns, spacing = cell.spacing, rowSpacing = GridSpacing, fillLastRow = false) { _ ->
            placeholder()
        }
    }
}

/** 按所在宽度算出 [cell] 的列数再画内容（列数算法只在 [gridColumnCount]）。 */
@Composable
internal fun WithGridColumns(cell: GridCell, modifier: Modifier, content: @Composable (columns: Int) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = with(LocalDensity.current) {
            gridColumnCount(constraints.maxWidth, cell.minWidth.roundToPx(), cell.spacing.roundToPx())
        }
        content(columns)
    }
}

@Composable
private fun GridRows(
    columns: Int,
    count: Int,
    spacing: Dp,
    rowSpacing: Dp,
    fillLastRow: Boolean,
    itemContent: @Composable (index: Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
        (0 until count).chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                // 格宽由这里定死（最小约束传给项目），项目画不窄于一格：与惰性网格同一种「铺满一格」。
                row.forEach { index -> Box(Modifier.weight(1f), propagateMinConstraints = true) { itemContent(index) } }
                if (!fillLastRow) repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
