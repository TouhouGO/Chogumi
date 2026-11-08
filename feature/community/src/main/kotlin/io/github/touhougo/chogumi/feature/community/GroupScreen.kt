package io.github.touhougo.chogumi.feature.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.ui.composables.ConfirmDialog
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.displayZone
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.GroupBo
import io.github.touhougo.chogumi.core.model.TopicType
import io.github.touhougo.chogumi.core.model.topicReactions
import io.github.touhougo.chogumi.core.reader.CollapsibleSynopsis
import io.github.touhougo.chogumi.core.reader.ComposerSheet
import io.github.touhougo.chogumi.core.reader.ComposerTitle
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.PagedListContent
import io.github.touhougo.chogumi.core.ui.common.navigation.rememberLinkOpener
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.composables.CollectCountButton
import io.github.touhougo.chogumi.core.ui.composables.FabClearance
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.GroupIcon
import io.github.touhougo.chogumi.core.ui.composables.PageLink
import io.github.touhougo.chogumi.core.ui.composables.PageMoreMenu
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SectionHeader
import io.github.touhougo.chogumi.core.ui.composables.SectionHeaderSkeleton
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import java.time.Instant
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 小组页（原型 `screenGroup`）：头部（图标、组名、建组年份、简介）→「讨论 N」→ 这个小组的帖子，按最后回复排、
 * 往下翻页。右上角：加入按钮（成员图标 + 成员数，与角色 / 人物页的收藏同一个 [CollectCountButton]）与菜单：置顶（已加入时）、
 * 屏蔽（社区 tab 里不显示这个小组的帖子）、在网页打开。右下「发帖」FAB 打开编辑器面板（标题 + 正文），发出后打开新帖。
 *
 * 加入、退出在 App 里完成（退出先确认）；App 里做不成时（没有网页会话、网页做了没生效）打开小组网页去做，
 * 回到这一页时重取头部，「已加入」与成员数跟着变。
 * 下拉刷新头部与帖子；FAB 往下滚时收成图标，往回滚展开（M3 extended FAB）。
 */
@Composable
fun GroupScreen(
    name: String,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: GroupViewModel = koinViewModel { parametersOf(name) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gate = rememberLoginGate(isLoggedIn, onLogin)
    val openLink = rememberLinkOpener()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var descriptionExpanded by rememberSaveable { mutableStateOf(false) }
    var leaving by rememberSaveable { mutableStateOf(false) }
    // 刚发出的新帖：面板滑走之后再打开它（面板还在滑时离开页面，回来会看到它停在半路）
    var created by remember { mutableStateOf<Int?>(null) }
    val group = (state.group as? Load.Ready)?.value
    FailureNoticeEffect(viewModel.failures, snackbar)
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshGroup()
        onPauseOrDispose { }
    }

    PageScaffold(
        title = "",
        onBack = onBack,
        isRefreshing = state.topics.isRefreshing,
        onRefresh = viewModel::refresh,
        actions = {
            CollectCountButton(
                visible = group != null,
                collected = group?.joined == true,
                count = group?.summary?.members ?: 0,
                icon = R.drawable.group_24,
                collectedIcon = R.drawable.group_filled_24,
                collectLabel = stringResource(R.string.group_join),
                collectedLabel = stringResource(R.string.group_joined),
                countLabel = pluralStringResource(R.plurals.timeline_group_members, group?.summary?.members ?: 0, group?.summary?.members ?: 0),
                onToggle = gate.guard {
                    if (group?.joined == true) leaving = true else viewModel.setJoined(true)
                },
                loading = state.membershipPending,
            )
            // 置顶只对已加入的小组，屏蔽在头部到了以后才有；两份名单都只在本机
            PageMoreMenu(
                link = PageLink(group?.summary?.title.orEmpty(), BgmWeb.group(name)),
                extra = group?.let {
                    {
                        if (it.joined) {
                            item(
                                text = if (state.pinned) R.string.group_unpin else R.string.group_pin,
                                icon = if (state.pinned) R.drawable.push_pin_filled_24 else R.drawable.push_pin_24,
                            ) { viewModel.setPinned(!state.pinned) }
                        }
                        item(
                            text = if (state.muted) R.string.muted_groups_unmute else R.string.group_mute,
                            icon = if (state.muted) R.drawable.visibility_24 else R.drawable.visibility_off_24,
                        ) { viewModel.setMuted(!state.muted) }
                    }
                },
            )
        },
        floatingActionButton = {
            if (group != null) {
                FloatingActionButton(onClick = { gate.run { viewModel.composer.open(Unit) } }) {
                    Icon(painterResource(R.drawable.edit_24), contentDescription = stringResource(R.string.group_new_topic))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val failure = (state.group as? Load.Failed)?.failure
        if (failure != null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                RetryError(failure, onRetry = viewModel::retry, Modifier.fillMaxWidth(), compact = true)
            }
        } else {
            PagedListContent(
                state = state.topics,
                onRetry = viewModel::retry,
                onLoadMore = viewModel::loadMore,
                emptyText = stringResource(R.string.community_feed_empty),
                contentPadding = PaddingValues(bottom = FabClearance),
                skeletonRow = { i, count -> CommunityTopicRowSkeleton(i, count, Modifier.padding(horizontal = 16.dp), kind = TopicListKind.IN_GROUP) },
                modifier = Modifier.fillMaxSize().padding(padding),
                endText = stringResource(R.string.community_list_end),
                headerSkeleton = {
                    Column {
                        GroupHeaderSkeleton()
                        SectionHeaderSkeleton()
                    }
                },
                header = {
                    if (group != null) {
                        item(key = "head") {
                            GroupHeader(
                                group = group,
                                descriptionExpanded = descriptionExpanded,
                                onToggleDescription = { descriptionExpanded = !descriptionExpanded },
                            )
                        }
                        item(key = "topics") { SectionHeader(title = stringResource(R.string.group_topics), count = group.topics) }
                    }
                },
                headerReady = group != null,
                listState = listState,
            ) { topics ->
                itemsIndexed(topics, key = { _, topic -> topic.key }) { i, topic ->
                    CommunityTopicRow(
                        topic = topic,
                        index = i,
                        count = topics.size,
                        onClick = { openLink(topic.webUrl) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                        kind = TopicListKind.IN_GROUP,
                    )
                }
            }
        }
    }

    if (leaving && group != null) {
        ConfirmDialog(
            target = group,
            confirmLabel = stringResource(R.string.group_leave_confirm_action),
            onConfirm = { gate.run { viewModel.setJoined(false) } },
            onDismiss = { leaving = false },
            title = stringResource(R.string.group_leave_confirm_title, group.summary.title),
            destructive = true,
        )
    }

    if (group != null && state.composer.target != null) {
        ComposerSheet(
            title = stringResource(R.string.composer_new_topic, group.summary.title),
            sending = state.composer.sending,
            sent = state.composer.sent,
            failures = viewModel.composer.failures,
            onDismiss = {
                viewModel.composer.close()
                created?.let { openLink(BgmWeb.topic(TopicType.GROUP, it)) }
                created = null
            },
            onSend = gate.guard { draft: ComposerDraft, token: String -> viewModel.composer.send(draft, token) { created = it } },
            initialTitle = state.composer.title,
            initialText = state.composer.text,
            onChange = viewModel.composer::edit,
            titleField = ComposerTitle.REQUIRED,
            pollPalette = topicReactions(),
        )
    }
}

/**
 * 小组页头部（原型 `.ghead`：上 8、左右 16、下 16，项间 12）：72 的圆角图标 + 组名（`headlineSmallEmphasized`）与
 * 建组年份 → 简介（折叠 3 行，[CollapsibleSynopsis]）。成员数在顶栏的加入按钮上。
 */
@Composable
private fun GroupHeader(
    group: GroupBo,
    descriptionExpanded: Boolean,
    onToggleDescription: () -> Unit,
) {
    GroupHeaderFrame(
        icon = { GroupIcon(group.summary.icon, HeaderIconSize, MaterialTheme.shapes.large) },
        title = {
            Text(
                text = group.summary.title,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        facts = { GroupCreatedYear(group) },
        description = if (group.description.isNotBlank()) {
            { CollapsibleSynopsis(text = group.description, expanded = descriptionExpanded, onToggle = onToggleDescription) }
        } else {
            null
        },
    )
}

/** 头部的占位：同一个 [GroupHeaderFrame]，简介按折叠后的最大高度占位。 */
@Composable
private fun GroupHeaderSkeleton() {
    GroupHeaderFrame(
        icon = { SkeletonBox(Modifier.size(HeaderIconSize), shape = MaterialTheme.shapes.large) },
        title = { SkeletonText(MaterialTheme.typography.headlineSmallEmphasized, widthFraction = 0.6f) },
        facts = { SkeletonText(MaterialTheme.typography.bodyMedium, widthFraction = 0.5f) },
        description = { CollapsibleSynopsis(text = null, expanded = false, onToggle = {}) },
    )
}

/** 头部的排版：真实头部与占位共用。简介自带左右 16、上 12（[CollapsibleSynopsis]）。 */
@Composable
private fun GroupHeaderFrame(
    icon: @Composable () -> Unit,
    title: @Composable () -> Unit,
    facts: @Composable () -> Unit,
    description: (@Composable () -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                title()
                facts()
            }
        }
        description?.invoke()
    }
}

/** 建组年份（不知道时不画）。 */
@Composable
private fun GroupCreatedYear(group: GroupBo) {
    if (group.createdAt > 0) {
        val year = Instant.ofEpochMilli(group.createdAt).atZone(displayZone()).year
        Text(
            stringResource(R.string.group_created_year, year),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 头部图标 72（原型 `.ghead img`）。设计决定（not in M3）。 */
private val HeaderIconSize = 72.dp
