package io.github.touhougo.chogumi.feature.community

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.common.paging.PagedListViewModel
import io.github.touhougo.chogumi.core.data.repository.GroupRepository
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.PagedListPage
import io.github.touhougo.chogumi.core.ui.composables.GroupIcon
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import org.koin.compose.viewmodel.koinViewModel

/** 全部小组：按成员数排、往下翻页（接口没有搜索、分类与活跃度排序）。 */
class AllGroupsViewModel(private val repository: GroupRepository) : PagedListViewModel<GroupSummaryBo>() {
    init {
        loadFirstPage()
    }

    override suspend fun fetchPage(offset: Int) = outcomeOf { repository.getAllGroups(offset) }

    // 按成员数排的列表在翻页途中会变（有人加入，两组换了位置），同一个小组可能在下一页又出现一次。只留先到的那一份
    override fun present(fetched: List<GroupSummaryBo>) = fetched.distinctBy { it.name }
}

/** 全部小组页（原型 `screenAllGroups`）：一行一个小组，点进小组页。 */
@Composable
fun AllGroupsScreen(onBack: () -> Unit, onGroupClick: (String) -> Unit) {
    val viewModel: AllGroupsViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PagedListPage(
        title = stringResource(R.string.all_groups),
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        emptyText = stringResource(R.string.all_groups_empty),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        endText = stringResource(R.string.community_list_end),
        skeletonRow = { index, count -> GroupRow(group = null, index = index, count = count, onClick = null) },
    ) { groups ->
        itemsIndexed(groups, key = { _, group -> group.name }) { index, group ->
            GroupRow(group = group, index = index, count = groups.size, onClick = { onGroupClick(group.name) })
        }
    }
}

/**
 * 一个小组（原型 `.grow-item`）：48 的圆角图标 + 组名（一行）+ 成员数。[group] 为 null 时画占位（同一个分段列表项）。
 * 全部小组与设置里的屏蔽列表共用；[trailingContent] 是行尾的操作（屏蔽列表的「取消屏蔽」）。
 */
@Composable
internal fun GroupRow(
    group: GroupSummaryBo?,
    index: Int,
    count: Int,
    onClick: (() -> Unit)?,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        trailingContent = trailingContent,
        leadingContent = {
            if (group == null) {
                SkeletonBox(Modifier.size(RowIconSize), shape = MaterialTheme.shapes.medium)
            } else {
                GroupIcon(group.icon, RowIconSize, MaterialTheme.shapes.medium)
            }
        },
        details = {
            if (group == null) {
                SkeletonText(MaterialTheme.typography.bodyMedium, widthFraction = 0.3f)
            } else {
                Text(pluralStringResource(R.plurals.timeline_group_members, group.members, group.members))
            }
        },
    ) {
        if (group == null) {
            SkeletonText(MaterialTheme.typography.titleMedium, widthFraction = 0.5f)
        } else {
            Text(group.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 列表里的小组图标 48（原型 `.grow-item img`）。设计决定（not in M3）。 */
private val RowIconSize = 48.dp
