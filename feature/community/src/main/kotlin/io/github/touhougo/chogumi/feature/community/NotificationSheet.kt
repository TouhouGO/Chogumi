package io.github.touhougo.chogumi.feature.community

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.common.bbcode.excerptOf
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.common.paging.asPagedList
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.foundation.businessDateOf
import io.github.touhougo.chogumi.core.model.NoticeBo
import io.github.touhougo.chogumi.core.model.PmConversationSummary
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.noticeKindOf
import io.github.touhougo.chogumi.core.model.noticeLinkOf
import io.github.touhougo.chogumi.core.model.userDisplayNameOf
import io.github.touhougo.chogumi.core.model.userKeyOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.PagedListContent
import io.github.touhougo.chogumi.core.ui.common.labelRes
import io.github.touhougo.chogumi.core.ui.common.navigation.InboxTab
import io.github.touhougo.chogumi.core.ui.common.navigation.rememberLinkOpener
import io.github.touhougo.chogumi.core.ui.composables.CircleAvatar
import io.github.touhougo.chogumi.core.ui.composables.ChoiceSheetTitleRow
import io.github.touhougo.chogumi.core.ui.composables.CountBadge
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.IconAvatar
import io.github.touhougo.chogumi.core.ui.composables.LocalNow
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.PriorityTextLine
import io.github.touhougo.chogumi.core.ui.composables.ScrollableModalSheet
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.TabPager
import io.github.touhougo.chogumi.core.ui.composables.formatPastDate
import io.github.touhougo.chogumi.core.ui.composables.rememberTabPagerState
import io.github.touhougo.chogumi.core.ui.composables.relativeTimeText
import java.time.LocalDate

/**
 * 社区铃铛面板：「通知 | 短信」两页，页面面板 [ScrollableModalSheet]（画在社区页的面板层里，与其他会进子页的面板同一套）。
 *
 * - 容器使用 Surface Container Low 色彩角色；顶部拖拽手柄由共用外壳的官方组件绘制；
 * - 标题行就是两页的页签（[ChoiceSheetTitleRow]，各带自己的未读数），左右滑也能换页（[TabPager]）；
 *   标题行右侧「全部标为已读」跟着当前页：通知页清通知（POST `/p1/clear-notify`），短信页清短信（网页的「全部已读」），
 *   那一页没有未读时变灰。成功不提示，各行置灰就是结果；失败通过 Snackbar 提示。
 * - 通知页列出最近的通知，已读与未读都在（App 里看通知只有这一处，已读的不能消失）；已读的置灰，未读的带圆点。
 *   头像可点进发送者主页；点一整条去它说的那一页并落到那层楼（[noticeLinkOf] 拼出带楼层锚点的地址，与正文里的站内链接同一条路），
 *   同时标为已读。
 * - 短信页列出会话（网页的会话列表，一页页往下翻），未读的带圆点与未读条数；点一个会话进会话页，会话读成功后这一行的未读数才去掉。
 *   最上面一行「写信」（与会话行同一框架，随列表滚走），点了进写信页选收件人。
 *   没有网页会话（浏览器授权、手动 Token 登录）时这一页是一句说明。
 * - 进子页时面板不关：画在页面的面板层里，返回时原样还在。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSheet(
    viewModel: NotificationViewModel,
    onDismissRequest: () -> Unit,
    onUser: (String) -> Unit,
    onConversation: (Int) -> Unit,
    onWrite: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadCount.collectAsStateWithLifecycle()
    val unreadMessages by viewModel.unreadMessages.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    FailureNoticeEffect(viewModel.failures, snackbarHostState)

    val tabs = InboxTab.entries
    // 停在哪一页（ViewModel 的 tab）是唯一真源：点页签只改它，翻页跟过去；手指翻页落定后才回写
    val pager = rememberTabPagerState(
        selected = tabs.indexOf(uiState.tab),
        pageCount = tabs.size,
        onSelect = { viewModel.selectTab(tabs[it]) },
    )

    ScrollableModalSheet(
        onDismiss = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        snackbar = snackbarHostState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
            val shown = tabs[pager.shownPage]
            NotificationHeader(
                shown = shown,
                unreadCount = unreadCount,
                unreadMessages = unreadMessages,
                markAllEnabled = when (shown) {
                    InboxTab.NOTICES -> unreadCount > 0 && uiState.notices is Load.Ready
                    InboxTab.MESSAGES -> unreadMessages > 0
                },
                onSelect = viewModel::selectTab,
                onMarkAll = {
                    when (shown) {
                        InboxTab.NOTICES -> viewModel.markAllAsRead()
                        InboxTab.MESSAGES -> viewModel.markAllMessagesRead()
                    }
                },
            )

            TabPager(pager, Modifier.fillMaxWidth().weight(1f), key = { tabs[it].name }) { index ->
                when (tabs[index]) {
                    InboxTab.NOTICES -> NoticeList(uiState.notices, viewModel, onUser)
                    InboxTab.MESSAGES -> ConversationList(uiState.conversations, viewModel, onConversation, onWrite)
                }
            }
        }
    }
}

/** 铃铛面板的标题/页签动作；用纯状态参数画出同一生产路径，测试与截图不需要构造 ViewModel。 */
@Composable
internal fun NotificationHeader(
    shown: InboxTab,
    unreadCount: Int,
    unreadMessages: Int,
    markAllEnabled: Boolean,
    onSelect: (InboxTab) -> Unit,
    onMarkAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChoiceSheetTitleRow(
        options = InboxTab.entries,
        selected = shown,
        label = { stringResource(it.labelRes) },
        onSelect = onSelect,
        badge = {
            when (it) {
                InboxTab.NOTICES -> unreadCount
                InboxTab.MESSAGES -> unreadMessages
            }
        },
        trailingText = stringResource(R.string.notification_mark_all_read),
        trailingEnabled = markAllEnabled,
        onTrailing = onMarkAll,
        modifier = modifier,
    )
}

private val InboxTab.labelRes: Int
    get() = when (this) {
        InboxTab.NOTICES -> R.string.notification_title
        InboxTab.MESSAGES -> R.string.inbox_tab_messages
    }

/** 通知页：最近的通知一次取全，画法与短信页同一个 [PagedListContent]（占位行、错误、空态）。 */
@Composable
private fun NoticeList(notices: Load<List<NoticeBo>>, viewModel: NotificationViewModel, onUser: (String) -> Unit) {
    val openLink = rememberLinkOpener()
    PagedListContent(
        state = notices.asPagedList(),
        onRetry = viewModel::loadNotifications,
        onLoadMore = {},
        emptyText = stringResource(R.string.notification_empty),
        contentPadding = InboxListPadding,
        skeletonRow = { _, _ -> NotificationItemSkeleton() },
        modifier = Modifier.fillMaxSize(),
        stateAlignment = Alignment.TopCenter,
    ) { items ->
        items(items, key = { it.id }) { item ->
            NotificationItemRow(
                item = item,
                onUser = onUser,
                onOpen = noticeLinkOf(item)?.let { link ->
                    {
                        viewModel.markRead(item)
                        openLink(link)
                    }
                },
            )
        }
    }
}

/**
 * 短信页：网页的会话列表，一页页往下翻。没有网页会话时（取列表时就知道，不发请求）画一句说明，不画「重试」：
 * 重试不会变，要换成账号密码登录。
 */
@Composable
private fun ConversationList(
    conversations: PagedList<PmConversationSummary>,
    viewModel: NotificationViewModel,
    onConversation: (Int) -> Unit,
    onWrite: () -> Unit,
) {
    if (conversations.error == Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED)) {
        EmptyLine(stringResource(R.string.pm_web_login_required), Modifier.padding(InboxListPadding))
        return
    }
    PagedListContent(
        state = conversations,
        onRetry = viewModel::retryConversations,
        onLoadMore = viewModel::loadMoreConversations,
        emptyText = stringResource(R.string.pm_empty),
        contentPadding = InboxListPadding,
        skeletonRow = { _, _ -> ConversationRowSkeleton() },
        modifier = Modifier.fillMaxSize(),
        headerSkeleton = { WriteRow(onClick = null) },
        header = { item(key = "write") { WriteRow(onClick = onWrite) } },
    ) { items ->
        items(items, key = { it.id }) { conversation ->
            ConversationRow(
                conversation = conversation,
                onClick = { onConversation(conversation.id) },
            )
        }
    }
}

/**
 * 面板里一行的框架——两页的真实行（[NotificationItemRow]、[ConversationRow]）与占位行共用的唯一出处：
 * 未读圆点位、头像位、文字列的间距与行距只写在这里。未读状态也在这里进入读屏的 state description；正文与点击保持原样。
 * [onClick] 非 null 时整行可点，水波纹的圆角取 shapes.medium（我的设计决定，not in M3）。
 */
@Composable
private fun NotificationRowFrame(
    unread: Boolean,
    avatar: @Composable () -> Unit,
    onClick: (() -> Unit)?,
    text: @Composable ColumnScope.() -> Unit,
) {
    val unreadState = if (unread) stringResource(R.string.unread) else null
    PressArea(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.semantics {
            if (unreadState != null) stateDescription = unreadState
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = InboxRowPadding),
            verticalAlignment = Alignment.Top,
        ) {
            // 未读小圆点（6dp，M3 Error 角色；已读时保持 6dp 占位保持左对齐）
            Box(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .size(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (unread) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                }
            }

            Spacer(Modifier.width(8.dp))
            avatar()
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), content = text)
        }
    }
}

/** 占位行：头像画圆形占位；姓名/动作与真实行共用断行框架，时间随后。可有可无的标题行不画。 */
@Composable
internal fun NotificationItemSkeleton() {
    NotificationRowFrame(
        unread = false,
        avatar = { SkeletonBox(Modifier.size(NotificationAvatarSize), shape = CircleShape) },
        onClick = null,
    ) {
        NotificationNameActionLine(
            name = {
                SkeletonText(
                    MaterialTheme.typography.titleSmallEmphasized,
                    widthFraction = NOTIFICATION_SKELETON_NAME_FRACTION,
                    sample = NOTIFICATION_SKELETON_NAME_SAMPLE,
                    fillWidth = false,
                    maxLines = 1,
                )
            },
            action = {
                SkeletonText(
                    MaterialTheme.typography.bodyMedium,
                    widthFraction = 0.65f,
                    sample = stringResource(R.string.notify_type_unknown),
                    fillWidth = false,
                    maxLines = 1,
                )
            },
        )
        Spacer(Modifier.height(4.dp))
        SkeletonText(MaterialTheme.typography.labelSmall, widthFraction = NOTIFICATION_SKELETON_TIME_FRACTION)
    }
}

/** 通知姓名与动作的唯一排法：真实行与骨架都从这里取得断行和基线。 */
@Composable
private fun NotificationNameActionLine(name: @Composable () -> Unit, action: @Composable () -> Unit) {
    PriorityTextLine(
        modifier = Modifier.fillMaxWidth(),
        primary = name,
        trailing = action,
        gap = 4.dp,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NotificationItemRow(
    item: NoticeBo,
    onUser: (String) -> Unit,
    onOpen: (() -> Unit)?,
) {
    // 已读的行置灰、留在列表里：文字各降一级颜色（昵称 onSurface → onSurfaceVariant，其余 → outline），
    // 未读圆点同时消失。降几级是我的设计决定（not in M3）。
    val colors = MaterialTheme.colorScheme
    val nameColor = if (item.unread) colors.onSurface else colors.onSurfaceVariant
    val detailColor = if (item.unread) colors.onSurfaceVariant else colors.outline
    val senderName = userDisplayNameOf(item.sender.username, item.sender.nickname, item.sender.id)
    NotificationRowFrame(
        unread = item.unread,
        onClick = onOpen,
        // 发送者头像（可点，进入用户页）
        avatar = {
            CircleAvatar(
                url = item.sender.avatar.ifBlank { null },
                description = senderName,
                size = NotificationAvatarSize,
                onClick = { onUser(userKeyOf(item.sender.username, item.sender.id)) },
            )
        },
    ) {
        // 发送者昵称优先；放不下时类型文案下一行。
        NotificationNameActionLine(
            name = {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = nameColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            action = {
                Text(
                    text = stringResource(noticeKindOf(item.type).labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = detailColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )

        // 标题（如条目/话题名，仅在非空时渲染）
        if (item.title.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodySmall,
                color = detailColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(4.dp))

        // 相对时间
        Text(
            text = relativeTimeText(item.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 头像直径与行内上下留白：真实行与占位行共用，占位行因此与真实行同高。两行文字之间 20dp = 上下各 9 + 列表的
 * `SegmentedGap` 2（[PagedListContent]）。占位行色条宽度是我的设计决定（not in M3）。
 */
private val NotificationAvatarSize = 40.dp
private val InboxRowPadding = 9.dp
private val InboxListPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
private const val NOTIFICATION_SKELETON_NAME_FRACTION = 0.5f
private const val NOTIFICATION_SKELETON_TIME_FRACTION = 0.2f
private const val CONVERSATION_SKELETON_PREVIEW_FRACTION = 0.8f
private const val NOTIFICATION_SKELETON_NAME_SAMPLE = "通知发送者姓名"

/**
 * 短信页最上面的「写信」：与会话行同一框架，头像位是编辑图标（[IconAvatar]），字与头像垂直居中。
 * 加载中的骨架里画同一行（[onClick] 为 null，不可点），内容到了这一行不动。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WriteRow(onClick: (() -> Unit)?) {
    NotificationRowFrame(
        unread = false,
        onClick = onClick,
        avatar = { IconAvatar(R.drawable.edit_24, NotificationAvatarSize) },
    ) {
        Box(Modifier.height(NotificationAvatarSize), contentAlignment = Alignment.CenterStart) {
            Text(
                text = stringResource(R.string.pm_write),
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 会话占位行：头像画圆形占位，文字列画名字行与摘要行，与 [ConversationRow] 同一框架。 */
@Composable
internal fun ConversationRowSkeleton() {
    NotificationRowFrame(
        unread = false,
        avatar = { SkeletonBox(Modifier.size(NotificationAvatarSize), shape = CircleShape) },
        onClick = null,
    ) {
        SkeletonText(MaterialTheme.typography.titleSmallEmphasized, widthFraction = NOTIFICATION_SKELETON_NAME_FRACTION)
        Spacer(Modifier.height(2.dp))
        SkeletonText(MaterialTheme.typography.bodyMedium, widthFraction = CONVERSATION_SKELETON_PREVIEW_FRACTION)
    }
}

/**
 * 一个短信会话：对方头像、名字、右侧日期（网页只给日期：今天写「今天」，今年的不写年份），第二行是「主题 / 最近一条的开头」
 * （网页给的是截断的原始 BBCode，取成一行纯文字），未读的带圆点、尾部是未读条数。读过的置灰，同通知行。
 * 头像不单独可点：列表行读不出对方的用户名，整行进会话页，会话页的顶栏通向对方主页。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ConversationRow(conversation: PmConversationSummary, onClick: () -> Unit) {
    val unread = conversation.unread > 0
    val colors = MaterialTheme.colorScheme
    val nameColor = if (unread) colors.onSurface else colors.onSurfaceVariant
    val detailColor = if (unread) colors.onSurfaceVariant else colors.outline
    val name = displayNameOf(conversation.peer)
    val preview = remember(conversation.preview) { excerptOf(conversation.preview) }
    NotificationRowFrame(
        unread = unread,
        onClick = onClick,
        avatar = {
            CircleAvatar(url = conversation.peer.avatarMedium.ifBlank { null }, description = name, size = NotificationAvatarSize)
        },
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).alignByBaseline(),
            )
            conversation.date?.let { date ->
                Spacer(Modifier.width(8.dp))
                Text(
                    text = conversationDateText(date),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.outline,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = detailColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (unread) {
                Spacer(Modifier.width(8.dp))
                CountBadge(conversation.unread)
            }
        }
    }
}

/**
 * 会话列表的日期：网页按北京时间只写日子，「今天」也按北京时间的今天比（[businessDateOf]）；其余同 App 别处已经过去的日子
 * （[formatPastDate]：今年不写年份）。随 [LocalNow] 过零点变。
 */
@Composable
private fun conversationDateText(date: LocalDate): String {
    val today = businessDateOf(LocalNow.current.value)
    if (date == today) return stringResource(R.string.day_offset_today)
    val locale = LocalConfiguration.current.locales[0]
    return remember(date, today, locale) { formatPastDate(date, today, locale) }
}
