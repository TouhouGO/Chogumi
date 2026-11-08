package io.github.touhougo.chogumi.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.common.bbcode.excerptOf
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.orEmpty
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.AvatarRowSkeleton
import io.github.touhougo.chogumi.core.ui.composables.CircleAvatar
import io.github.touhougo.chogumi.core.ui.composables.ContainedSearchField
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.IconAvatar
import io.github.touhougo.chogumi.core.ui.composables.ListAvatarSize
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SectionHeader
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import org.koin.compose.viewmodel.koinViewModel

/**
 * 写信页：[username] 为 null 时先在页内选收件人（[PmRecipientsPage]），选好了这一页就地变成和该用户的会话
 * （[PmConversationScreen]，打开即弹出编辑器）；从他人主页进来时收件人已定，直接是会话。
 * 选人不另开一页：发出去之后返回就回到来的地方，不回到选人那一步。选好的人随页面保存（进程被回收后回来还是那段会话）。
 */
@Composable
fun PmWriteScreen(
    username: String?,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    onUserClick: (String) -> Unit,
) {
    var chosen by rememberSaveable(stateSaver = PmChatKeySaver) { mutableStateOf(username?.let { PmChatKey.Peer(it) }) }
    when (val key = chosen) {
        null -> {
            val viewModel: PmRecipientsViewModel = koinViewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            PmRecipientsPage(
                state = state,
                onBack = onBack,
                onRetryRecent = viewModel::loadRecent,
                onRetryFriends = viewModel::loadFriends,
                onPick = { chosen = it },
            )
        }
        else -> PmConversationScreen(key, isLoggedIn, onLogin, onBack, onUserClick)
    }
}

/** 选好的人存成一段字：`c/会话 id`（从最近联系人选的，读到就弹出回复面板）或 `u/用户名`。 */
internal val PmChatKeySaver = Saver<PmChatKey?, String>(
    save = { key ->
        when (key) {
            is PmChatKey.Conversation -> "c/${key.id}/${key.compose}"
            is PmChatKey.Peer -> "u/${key.username}"
            null -> null
        }
    },
    restore = { saved ->
        val parts = saved.split('/', limit = 3)
        when (parts[0]) {
            "c" -> PmChatKey.Conversation(parts[1].toInt(), compose = parts[2].toBoolean())
            else -> PmChatKey.Peer(saved.removePrefix("u/"))
        }
    },
)

/**
 * 选收件人：顶上搜索框（按名字、用户名筛），下面「最近联系人」（会话列表第一页，点了进那段会话）与「好友」，
 * 输入像用户名时最上面多一行「写给 @输入」。筛的规则只在 [pmRecipientsOf]。两组各自加载、各自出错重试；
 * 都读到了却谁也没有时是一句空态。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PmRecipientsPage(
    state: PmRecipientsUiState,
    onBack: () -> Unit,
    onRetryRecent: () -> Unit,
    onRetryFriends: () -> Unit,
    onPick: (PmChatKey) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val recipients = remember(query, state) { pmRecipientsOf(query, state.recent.orEmpty(), state.friends.orEmpty(), state.blocked) }
    PageScaffold(title = stringResource(R.string.pm_write), onBack = onBack) { padding ->
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item(key = "search") {
                ContainedSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.pm_recipient_hint),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            recipients.typed?.let { typed ->
                item(key = "typed") {
                    SegmentedItem(
                        index = 0,
                        count = 1,
                        onClick = { onPick(PmChatKey.Peer(typed)) },
                        modifier = RowModifier,
                        leadingContent = { IconAvatar(R.drawable.edit_24, ListAvatarSize) },
                    ) {
                        Text(stringResource(R.string.pm_write_to_username, typed), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            recipientSection(
                key = "recent",
                title = R.string.pm_recent_contacts,
                load = state.recent,
                shown = recipients.recent,
                onRetry = onRetryRecent,
                rowKey = { it.id },
            ) { conversation, index, count ->
                RecipientRow(conversation.peer, excerptOf(conversation.preview), index, count) {
                    onPick(PmChatKey.Conversation(conversation.id, compose = true))
                }
            }
            recipientSection(
                key = "friends",
                title = R.string.friends,
                load = state.friends,
                shown = recipients.friends,
                onRetry = onRetryFriends,
                rowKey = { it.id },
            ) { user, index, count ->
                RecipientRow(user, "@${user.username}", index, count) { onPick(PmChatKey.Peer(user.username)) }
            }
            val nobody = state.recent is Load.Ready && state.friends is Load.Ready &&
                recipients.typed == null && recipients.recent.isEmpty() && recipients.friends.isEmpty()
            if (nobody) {
                item(key = "nobody") {
                    EmptyLine(stringResource(if (query.isBlank()) R.string.pm_recipients_empty else R.string.pm_recipients_none))
                }
            }
        }
    }
}

/**
 * 一组收件人：标题加各行。加载中是标题加几行占位；出错是标题加重试；读到了但筛完一个也没有时整组不画。
 */
private fun <T> LazyListScope.recipientSection(
    key: String,
    title: Int,
    load: Load<List<T>>,
    shown: List<T>,
    onRetry: () -> Unit,
    rowKey: (T) -> Any,
    row: @Composable (item: T, index: Int, count: Int) -> Unit,
) {
    if (load is Load.Ready && shown.isEmpty()) return
    item(key = "$key/title") { SectionHeader(stringResource(title)) }
    when (load) {
        Load.Pending -> items(SKELETON_ROWS, key = { "$key/skeleton/$it" }) { index ->
            AvatarRowSkeleton(index, SKELETON_ROWS, ListAvatarSize, RowModifier)
        }
        is Load.Failed -> item(key = "$key/error") { RetryError(load.failure, onRetry = onRetry, Modifier.fillMaxWidth(), compact = true) }
        is Load.Ready -> itemsIndexed(shown, key = { _, item -> "$key/${rowKey(item)}" }) { index, item -> row(item, index, shown.size) }
    }
}

/** 一个收件人：头像、名字，副行是最近一条的开头（最近联系人）或用户名（好友）。 */
@Composable
private fun RecipientRow(user: UserBo, supporting: String, index: Int, count: Int, onClick: () -> Unit) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = onClick,
        modifier = RowModifier,
        leadingContent = { CircleAvatar(user.avatarSmall.ifBlank { null }, description = null, size = ListAvatarSize) },
        supportingContent = { Text(supporting, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    ) {
        Text(displayNameOf(user), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val RowModifier = Modifier.padding(horizontal = 16.dp)

/** 一组还在加载时的占位行数。 */
private const val SKELETON_ROWS = 3
