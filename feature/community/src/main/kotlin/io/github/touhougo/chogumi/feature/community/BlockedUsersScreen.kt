package io.github.touhougo.chogumi.feature.community

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.common.paging.asPagedList
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.PagedListPage
import io.github.touhougo.chogumi.core.ui.composables.AvatarRowSkeleton
import io.github.touhougo.chogumi.core.ui.composables.CircleAvatar
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.ListAvatarSize
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import org.koin.compose.viewmodel.koinViewModel

/**
 * 设置 › 屏蔽的用户：当前账号的绝交名单（绝交即屏蔽：这些人的楼层、吐槽、动态、帖子、日志在 App 里都不显示）。
 * 一行一个人（头像 + 名字），点了进他的主页，行尾「取消绝交」。只知道 id、没有用户名的人按数字 id 进主页。
 */
@Composable
fun BlockedUsersScreen(
    onBack: () -> Unit,
    onUserClick: (String) -> Unit,
    viewModel: BlockedUsersViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    FailureNoticeEffect(viewModel.failures, snackbar)
    BlockedUsersPage(
        users = state.users,
        unblocking = state.unblocking,
        onBack = onBack,
        onRetry = viewModel::retry,
        onUserClick = onUserClick,
        onUnblock = viewModel::unblock,
        snackbarHost = { SnackbarHost(snackbar) },
    )
}

/** 屏蔽名单的纯界面：行、空态和失败态只读传入状态，点击目标仍由 [blockedUserKey] 统一。 */
@Composable
internal fun BlockedUsersPage(
    users: Load<List<UserBo>>,
    unblocking: Set<String>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onUserClick: (String) -> Unit,
    onUnblock: (UserBo) -> Unit,
    snackbarHost: (@Composable () -> Unit)? = null,
) {
    PagedListPage(
        title = stringResource(R.string.blocked_users),
        snackbarHost = snackbarHost,
        state = users.asPagedList(),
        onBack = onBack,
        onRetry = onRetry,
        onLoadMore = {},
        emptyText = stringResource(R.string.blocked_users_empty),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        skeletonRow = { index, count -> AvatarRowSkeleton(index, count, ListAvatarSize, Modifier.padding(horizontal = 16.dp)) },
    ) { users ->
        itemsIndexed(users, key = { _, user -> blockedUserKey(user) }) { index, user ->
            val name = displayNameOf(user)
            SegmentedItem(
                index = index,
                count = users.size,
                onClick = { onUserClick(blockedUserKey(user)) },
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                leadingContent = { CircleAvatar(user.avatarUrl, description = null, size = ListAvatarSize) },
                trailingContent = {
                    TextButton(onClick = { onUnblock(user) }, enabled = blockedUserKey(user) !in unblocking) {
                        Text(stringResource(R.string.blocked_users_unblock))
                    }
                },
            ) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
