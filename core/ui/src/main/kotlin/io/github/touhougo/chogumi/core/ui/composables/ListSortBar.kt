package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/**
 * 吸顶排序行（原型 `.sortbar`）：左侧标题 [heading]（区块名或数量，`titleMedium`）+ 排序 [MenuChip]（只有一种排序时不出）+ [trailing]。
 * 底色 [containerColor] 盖住滚过的列表项，只在绘制阶段读：传吸顶项柔边的 [StickyHeaderEdge.containerColor]，页面里吸住时与顶栏
 * 连成一块颜色，底部面板里是面板底色。
 * 讨论版话题、日志 / 目录留言、单集评论、目录收录共用这一行。
 */
@Composable
fun <T> ListSortBar(
    heading: String,
    sort: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    containerColor: () -> Color,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    SortBarFrame(modifier, containerColor) {
        // 与区块标题 SectionHeader 同一字阶 titleMedium（labelLarge 当标题太小）
        Text(
            text = heading,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (options.size > 1) {
            MenuChip(selected = sort, options = options, label = label, icon = R.drawable.sort_24, onSelect = onSelect)
        }
        trailing()
    }
}

/**
 * 排序行的占位（骨架用）：与 [ListSortBar] 同一个外框，标题画一段 `titleMedium` 字条，排序 chip 画成同高同形的色块
 * （FilterChip 32 高、外面有 48 的最小触控区，`component-tokens.md` Chips）。真实行在没有楼层时不出 chip、会比占位矮，
 * 只会往上收。chip 占位宽度是设计决定（not in M3）。
 */
@Composable
fun ListSortBarSkeleton(modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    SortBarFrame(modifier, { surface }) {
        SkeletonText(MaterialTheme.typography.titleMedium, Modifier.weight(1f), widthFraction = HEADING_SKELETON_FRACTION)
        Box(Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
            SkeletonBox(Modifier.size(width = CHIP_SKELETON_WIDTH, height = FilterChipDefaults.Height), shape = FilterChipDefaults.shape)
        }
    }
}

/** 排序行的外框：左右与列表同为 16、上下 8，底色盖住滚过的列表项。真实行与占位共用。 */
@Composable
private fun SortBarFrame(modifier: Modifier, containerColor: () -> Color, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind { drawRect(containerColor()) }
            .padding(horizontal = ChogumiDimens.ScreenPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private const val HEADING_SKELETON_FRACTION = 0.3f
private val CHIP_SKELETON_WIDTH = 88.dp
