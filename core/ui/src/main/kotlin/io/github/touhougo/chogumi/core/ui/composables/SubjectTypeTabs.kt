package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabIndicatorScope
import androidx.compose.material3.TabPosition
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.SubjectType
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 主分类用指示条 tab，筛选 chip 只用于下一层状态。下面的内容用 [TabPager] 左右翻页：
 * 高亮哪一项读 [TabPagerState.shownPage]，指示条按 [TabPagerState.position] 在两项之间插值、跟着手指走。
 *
 * 怎么排按屏宽定（[tabMinWidthsOf]）。From M3（navigation.md Tabs）：固定 tab 把容器等分、一次显示全部；
 * 等分放不下时用可滚动 tab，每项宽度由文字决定。
 * 按文字宽时第一项顶格、不留起始边距，总宽放得下时把余下的宽均分给各项、铺满一行：my design decision (not in M3)。
 * M3 要首项离起始边 52dp 提示后面还有，在手机上那是 tab 栏左边一整块空档；顶格时第一项文字自带的 16dp 留白
 * 正好与上面搜索栏的左缘对齐，放不下时每项按文字宽，屏幕右缘多半切在某个 tab 的文字里，照样看得出能滑。
 *
 * @param types 这一组 tab 的分类（[subjectTypeTabsOf]），与翻页的页序一一对应。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectTypeTabs(
    types: List<SubjectType?>,
    pager: TabPagerState,
    onSelect: (SubjectType?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = types.map { stringResource(it.labelOrAllRes()) }
    val shown = pager.shownPage
    val tabs: @Composable (minWidths: List<Dp>?) -> Unit = { minWidths ->
        types.forEachIndexed { index, type ->
            Tab(
                selected = index == shown,
                onClick = { onSelect(type) },
                modifier = if (minWidths == null) Modifier else Modifier.widthIn(min = minWidths[index]),
                text = { Text(labels[index], maxLines = 1) },
            )
        }
    }
    val indicator: @Composable TabIndicatorScope.() -> Unit = {
        TabRowDefaults.PrimaryIndicator(
            modifier = Modifier.tabIndicatorLayout { measurable, constraints, positions ->
                followPager(measurable, constraints, positions, pager.position)
            },
            width = Dp.Unspecified,
        )
    }
    BoxWithConstraints(modifier) {
        val minWidths = tabMinWidthsOf(labels, constraints.maxWidth)
        if (minWidths == null) {
            PrimaryTabRow(selectedTabIndex = shown, modifier = Modifier.fillMaxWidth(), indicator = indicator) { tabs(null) }
        } else {
            PrimaryScrollableTabRow(
                selectedTabIndex = shown,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 0.dp,
                indicator = indicator,
                minTabWidth = 0.dp,
            ) { tabs(minWidths) }
        }
    }
}

/** 一组主分类 tab 的分类：五个内容分类，[includeAll] 时最前面加「全部」（null）。页序与之一一对应。 */
fun subjectTypeTabsOf(includeAll: Boolean): List<SubjectType?> = if (includeAll) listOf(null) + ContentDomains else ContentDomains

/**
 * 指示条跟着翻页走：位置落在第 i、i+1 两项之间时，宽度（文字宽，同官方 matchContentSize）与中心都按比例插值，
 * 贴在 tab 栏底边。翻页停下时正好落在选中项上。
 *
 * [positions] 是 tab 栏内容坐标，指示条节点自己被摆在哪由 tab 栏定，两种栏不一样：等宽栏摆在起点，
 * 可滚动栏摆在选中项里居中。所以按节点实际所在的位置（[Placeable.PlacementScope.coordinates]）换算，
 * 不依赖哪一种栏的摆法；读了位置，栏挪动节点时摆放会重跑。
 */
private fun MeasureScope.followPager(
    measurable: Measurable,
    constraints: Constraints,
    positions: List<TabPosition>,
    position: Float,
): MeasureResult {
    val clamped = position.coerceIn(0f, positions.lastIndex.toFloat())
    val index = floor(clamped).toInt()
    val fraction = clamped - index
    val from = positions[index]
    val to = positions[minOf(index + 1, positions.lastIndex)]
    val width = lerp(from.contentWidth, to.contentWidth, fraction).roundToPx()
    val center = lerp(from.left + from.width / 2, to.left + to.width / 2, fraction).toPx()
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width, minHeight = 0))
    return layout(constraints.maxWidth, constraints.maxHeight) {
        val origin = coordinates?.positionInParent()?.x ?: 0f
        placeable.place((center - width / 2f - origin).roundToInt(), constraints.maxHeight - placeable.height)
    }
}

/**
 * 一行 tab 的排法。每项要的宽是文字宽 + 左右各 16（官方 [Tab] 文字两侧的留白），字阶同官方 [Tab]（titleSmall）。
 * 等分后每一格都放得下时返回 null：用等宽的固定 tab。否则按文字宽，返回每一项的最小宽：总宽放得下时
 * 把余下的宽均分给各项、铺满一行，不在右边空出一块；放不下时就是文字宽，可滚动。
 */
@Composable
private fun tabMinWidthsOf(labels: List<String>, maxWidth: Int): List<Dp>? {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleSmall
    val density = LocalDensity.current
    return remember(labels, maxWidth, style, density) {
        val padding = with(density) { TabTextHorizontalPadding.roundToPx() } * 2
        val needed = labels.map { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width + padding }
        val widths = tabWidthsOf(needed, maxWidth) ?: return@remember null
        with(density) { widths.map { it.toDp() } }
    }
}

/** 等分放不下就按文字宽排；能铺满时逐像素分完余宽，整数除法的余数也留在各项里。 */
private fun tabWidthsOf(needed: List<Int>, maxWidth: Int): List<Int>? {
    if (needed.all { it <= maxWidth / needed.size }) return null
    val spare = maxOf(maxWidth - needed.sum(), 0)
    val perTab = spare / needed.size
    val remainder = spare % needed.size
    return needed.mapIndexed { index, width -> width + perTab + if (index < remainder) 1 else 0 }
}

/** 官方 [Tab] 文字两侧的留白（material3 Tab.kt 的 HorizontalTextPadding，组件内部值，未公开）。 */
private val TabTextHorizontalPadding = 16.dp
