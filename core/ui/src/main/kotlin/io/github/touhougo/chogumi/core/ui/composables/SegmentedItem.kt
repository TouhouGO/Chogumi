package io.github.touhougo.chogumi.core.ui.composables

import androidx.annotation.DrawableRes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastMaxOfOrNull
import androidx.compose.ui.util.fastSumBy
import io.github.touhougo.chogumi.core.ui.common.navigation.CoverPressBlock

/**
 * 分段列表的一项：原型 `.seglist`（组外角 16dp、项内角 4dp、项间 2dp，项底 `surfaceContainer`）。
 *
 * 形状、间距与内边距全部取 M3 官方 [SegmentedListItem] / [ListItemDefaults.segmentedShapes]；
 * 只把项底从默认 `surface` 换成原型的 `surfaceContainer`——放在 `surface` 页面上，默认底色看不出分段。
 * 全仓的分段列表只此一处。
 *
 * 副行有两种，二选一：
 * - [supportingContent]：**只能是单个 `Text`**，原样交给 M3 的副行槽位。
 * - [details]：多行、带 chip / 图标的副信息块（Column、Row 嵌套）。
 *
 * 多层布局不能进 M3 副行槽位：M3 测量列表项时要读副行的首末基线判断「几行」，读基线会让副行的
 * 子布局在父节点还没摆放时先摆放孙节点；列表快滑复用行时 Compose 就崩在
 * `RectManager`「LayoutNode not found in RectList」（Compose 1.13.0-alpha01 与 alpha03 都会）。
 * 所以 [details] 走主内容槽位，接在标题下面，M3 不读它的基线；颜色、字体与最小高度按 M3 副行的
 * 规则补齐，排出来与放在副行槽位一样，见 [ContentWithDetails]。
 *
 * [onClick] 为 null 时是不可点的展示行；[onLongClick] 只在可点时生效；[enabled] 为 false 时可点行置灰且不响应点击。
 * [containerColor] 是项底，默认原型的 `surfaceContainer`；帖子行（[PostListItem]）浅一档。
 * 项间距由调用方按 [ListItemDefaults.SegmentedGap] 排布。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SegmentedItem(
    index: Int,
    count: Int,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    verticalAlignment: Alignment.Vertical = ListItemDefaults.verticalAlignment(),
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    details: @Composable (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable () -> Unit,
) {
    require(supportingContent == null || details == null) { "supportingContent 与 details 只能给一个" }
    val shapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    val colors = ListItemDefaults.segmentedColors(containerColor = containerColor)
    val mainContent: @Composable () -> Unit = if (details == null) {
        content
    } else {
        { ContentWithDetails(colors, verticalAlignment, content, details) }
    }
    if (onClick == null) {
        SegmentedListItem(
            shapes = shapes,
            modifier = modifier,
            verticalAlignment = verticalAlignment,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            supportingContent = supportingContent,
            colors = colors,
            content = mainContent,
        )
    } else {
        // 行里有封面时，点行里任何地方都由封面长出详情页
        CoverPressBlock { presses ->
            SegmentedListItem(
                onClick = onClick,
                onLongClick = onLongClick,
                enabled = enabled,
                shapes = shapes,
                modifier = modifier.then(presses),
                verticalAlignment = verticalAlignment,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                supportingContent = supportingContent,
                colors = colors,
                content = mainContent,
            )
        }
    }
}

/**
 * 分段列表里「新建」的那一行（收录面板的「新建目录」，探索页添加货架的「新作」「格子」）：主色容器
 * `primaryContainer`、行首 [icon]，文字 `onPrimaryContainer`，[supporting] 是可选的一句说明；形状、间距仍是 [SegmentedItem]。
 * 全仓的「新建」行只此一种画法。
 */
@Composable
fun SegmentedCreateItem(
    index: Int,
    count: Int,
    title: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    val content = MaterialTheme.colorScheme.onPrimaryContainer
    SegmentedItem(
        index = index,
        count = count,
        onClick = onClick,
        enabled = enabled,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        leadingContent = { Icon(painterResource(icon), contentDescription = null, tint = content) },
        supportingContent = supporting?.let { text -> { Text(text, color = content) } },
        modifier = modifier,
    ) {
        Text(title, color = content)
    }
}

/**
 * 标题 + [details]，整块放进 M3 主内容槽位，按 M3 副行的规则补齐：
 * - 颜色取 [ListItemColors.supportingContentColor]，字体取副行字阶 `bodyMedium`（`ListTokens.ItemSupportingTextFont`）。
 * - 最小高度：M3 按副行行数取二行 72 / 三行 88（`component-tokens.md` 的 `ItemTwoLineContainerHeight` /
 *   `ItemThreeLineContainerHeight`），减去上下内边距后作为本块的最小高度；「副行是否多行」用 M3 自己在
 *   intrinsic 测量里的判据（副行高于 30sp 即多行），不读基线。
 * - 块内按 [verticalAlignment] 摆放：M3 再按同一对齐把本块摆进整行，两次对齐合起来与 M3 原排法一致。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContentWithDetails(
    colors: ListItemColors,
    verticalAlignment: Alignment.Vertical,
    content: @Composable () -> Unit,
    details: @Composable () -> Unit,
) {
    val padding = ListItemDefaults.ContentPadding
    val decoratedDetails: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalContentColor provides colors.supportingContentColor(enabled = true, selected = false, dragged = false),
        ) {
            ProvideTextStyle(MaterialTheme.typography.bodyMedium, details)
        }
    }
    Layout(contents = listOf(content, decoratedDetails)) { (contentMeasurables, detailsMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val contentPlaceables = contentMeasurables.map { it.measure(loose) }
        val contentHeight = contentPlaceables.fastSumBy { it.height }
        val detailsPlaceables = detailsMeasurables.map { it.measure(loose.offset(vertical = -contentHeight)) }
        val detailsHeight = detailsPlaceables.fastSumBy { it.height }
        val mainHeight = contentHeight + detailsHeight
        val itemMinHeight = if (detailsHeight > SupportingMultilineThreshold.roundToPx()) ThreeLineHeight else TwoLineHeight
        val minHeight = (itemMinHeight - padding.calculateTopPadding() - padding.calculateBottomPadding()).roundToPx()
        val height = maxOf(mainHeight, minHeight).coerceIn(constraints.minHeight, constraints.maxHeight)
        val width = (contentPlaceables + detailsPlaceables).fastMaxOfOrNull { it.width } ?: 0
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), height) {
            var y = verticalAlignment.align(mainHeight, height)
            (contentPlaceables + detailsPlaceables).forEach {
                it.place(0, y)
                y += it.height
            }
        }
    }
}

/** M3 判定副行多行的高度门槛（`ListItem.kt` 的 `isSupportingMultilineHeuristic`）。 */
private val SupportingMultilineThreshold = 30.sp

/** M3 二行 / 三行列表项高度（`component-tokens.md`：`ItemTwoLineContainerHeight` / `ItemThreeLineContainerHeight`）。 */
private val TwoLineHeight = 72.dp
private val ThreeLineHeight = 88.dp
