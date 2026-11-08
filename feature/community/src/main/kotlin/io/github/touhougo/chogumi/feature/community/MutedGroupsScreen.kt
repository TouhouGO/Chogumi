package io.github.touhougo.chogumi.feature.community

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.PagedListPage
import io.github.touhougo.chogumi.core.common.paging.asPagedList
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import org.koin.compose.viewmodel.koinViewModel

/**
 * 设置 › 屏蔽的小组：本机屏蔽名单，最近屏蔽的在前。一行一个小组（与全部小组同一个 [GroupRow]），点了进小组页，
 * 行尾「取消屏蔽」。名单只在本机；取消之后社区 tab 马上恢复显示这个小组的帖子。
 */
@Composable
fun MutedGroupsScreen(
    onBack: () -> Unit,
    onGroupClick: (String) -> Unit,
    viewModel: MutedGroupsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    FailureNoticeEffect(viewModel.failures, snackbar)
    PagedListPage(
        title = stringResource(R.string.muted_groups),
        snackbarHost = { SnackbarHost(snackbar) },
        state = state.groups.asPagedList(),
        onBack = onBack,
        onRetry = viewModel::retry,
        onLoadMore = {},
        emptyText = stringResource(R.string.muted_groups_empty),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        skeletonRow = { index, count -> GroupRow(group = null, index = index, count = count, onClick = null) },
    ) { groups ->
        itemsIndexed(groups, key = { _, group -> group.name }) { index, group ->
            GroupRow(
                group = group,
                index = index,
                count = groups.size,
                onClick = { onGroupClick(group.name) },
                trailingContent = {
                    TextButton(onClick = { viewModel.unmute(group) }) { Text(stringResource(R.string.muted_groups_unmute)) }
                },
            )
        }
    }
}
