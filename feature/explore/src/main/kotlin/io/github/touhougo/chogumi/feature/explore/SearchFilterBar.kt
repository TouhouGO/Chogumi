package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.isTagBrowse
import io.github.touhougo.chogumi.core.ui.composables.ConnectedChoiceGroup

/**
 * 搜索页筛选栏：排序（无关键词时展示）。
 * 高亮哪一项由 [shown] 给（左右翻页时跟着翻过一半的那一页，见 SearchContent），点选回到 [SearchActions.onSortChange]。
 * 已生效的条件不在本页列出：筛选面板里本来就逐项显示着选中态，面板入口按钮有条件时转为实心并报条数。
 * 排序被缩减时不另加说明：只渲染可用的几项，本身就看得出能怎么排。
 */
@Composable
fun SearchFilterBar(
    criteria: SearchCriteria,
    /** 可选的排序（core:model `availableSorts` 按排序条顺序排好，调用处算一次，与翻页共用）。 */
    sorts: List<SearchSort>,
    /** 高亮的排序；没有可选排序时是 null，整栏不显示。 */
    shown: SearchSort?,
    actions: SearchActions,
    modifier: Modifier = Modifier,
) {
    if (shown != null) {
        // 上下不另留白：与搜索栏、海报的距离由调用处的外边距与网格顶部内边距给出（见 SearchContent）。
        SortGroup(
            current = shown,
            available = sorts,
            isTagBrowse = criteria.isTagBrowse(),
            onSelect = actions::onSortChange,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

/**
 * 排序：connected button group，与网页排序条一致，单选、点即生效。
 * From M3（buttons.md:336、:345、:415）：连接按钮组用于「选项 / 排序」，应占满所在宽度；分段按钮已不推荐。
 *
 * 形状、配色、按压弹性、可用项增减动画、小屏放不下时的排法（不截成「…」）、文案切换的淡入淡出
 * 全走 core:ui 的 [ConnectedChoiceGroup]；这里只决定有哪些项：只渲染 [available] 里的项
 * （由 core/model 的 availableSorts 决定），不可用项不占位、不置灰。标签浏览下「收藏」改叫「标注数」。
 */
@Composable
private fun SortGroup(
    current: SearchSort,
    available: List<SearchSort>,
    isTagBrowse: Boolean,
    onSelect: (SearchSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    ConnectedChoiceGroup(
        options = SearchSort.entries,
        selected = current,
        label = { stringResource(it.labelRes(isTagBrowse = isTagBrowse)) },
        onSelect = onSelect,
        visible = { it in available },
        modifier = modifier.testTag("SearchSortGroup"),
        itemModifier = { Modifier.testTag("SearchSort-${it.name}") },
    )
}
