package io.github.touhougo.chogumi.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.composables.SelectableChip
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.labelOrAllRes

/**
 * 「继续」chip 行的横滑配置——真实 chip 行（[ContinueFilterChips]）与骨架共用的唯一出处：
 * 首尾内边距、chip 间距、下方 8dp。
 */
@Composable
internal fun ContinueFilterChipRow(
    modifier: Modifier = Modifier,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        userScrollEnabled = userScrollEnabled,
        content = content,
    )
}


/**
 * chip 占位：布局高走官方 `minimumInteractiveComponentSize()`（与 [SelectableChip] 内的官方 FilterChip 同一条路径，
 * 触控区 48dp），视觉容器取 [FilterChipDefaults.Height]（32dp），在触控区里居中。宽度是设计决定（非 M3）。
 */
@Composable
internal fun ContinueFilterChipSkeleton() {
    Box(Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        SkeletonBox(
            modifier = Modifier.width(ContinueSkeletonChipWidth).height(FilterChipDefaults.Height),
            shape = FilterChipDefaults.shape,
        )
    }
}


private val ContinueSkeletonChipWidth = 72.dp


/**
 * 继续：类型过滤 Chip 行。
 *
 * [chips] 由 ViewModel 从「继续」实际落进的条目派生（顺序同 [ContentDomains]），界面不写分类清单；
 * 「全部」固定在最前。只剩一个分类时上层不渲染本行（「全部」与它内容相同，chip 是噪音）。
 */
@Composable
internal fun ContinueFilterChips(
    chips: List<SubjectType>,
    selectedFilter: SubjectType?,
    onFilterSelected: (SubjectType?) -> Unit,
) {
    val filters: List<SubjectType?> = listOf(null) + chips
    ContinueFilterChipRow(modifier = Modifier.testTag("home_continue_filter_chips")) {
        items(filters, key = { it?.name ?: "ALL" }) { type ->
            SelectableChip(
                label = stringResource(type.labelOrAllRes()),
                selected = selectedFilter == type,
                modifier = Modifier.testTag("home_continue_filter_chip_${type?.name ?: "ALL"}"),
                onClick = { onFilterSelected(type) },
            )
        }
    }
}
