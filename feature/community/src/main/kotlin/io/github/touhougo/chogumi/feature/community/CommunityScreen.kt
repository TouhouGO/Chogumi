package io.github.touhougo.chogumi.feature.community

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.data.repository.RAKUEN_MAX_TOPICS
import io.github.touhougo.chogumi.core.glass.LocalGlassContentBottom
import io.github.touhougo.chogumi.core.model.RakuenFilter
import io.github.touhougo.chogumi.core.model.groupOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.PagedListContent
import io.github.touhougo.chogumi.core.ui.common.navigation.rememberLinkOpener
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.composables.ChogumiPullToRefreshBox
import io.github.touhougo.chogumi.core.ui.composables.AppDropdownMenu
import io.github.touhougo.chogumi.core.ui.composables.AppMenuItem
import io.github.touhougo.chogumi.core.ui.composables.CollapsibleHeader
import io.github.touhougo.chogumi.core.ui.composables.ConnectedChoiceGroup
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.PageHeaderBar
import io.github.touhougo.chogumi.core.ui.composables.SelectableChip
import io.github.touhougo.chogumi.core.ui.composables.TabPager
import io.github.touhougo.chogumi.core.ui.composables.rememberTabPagerState
import org.koin.compose.viewmodel.koinViewModel

/**
 * 社区页（顶级导航第 4 目的地）：「小组 | 超展开」两页，连接按钮组切换、内容区左右滑动切换。
 *
 * - 顶部一行：标题「社区」、我的帖子（登录后才有）与铃铛（有未读通知或未读短信时 M3 小圆点角标，点开「通知 | 短信」面板
 *   [NotificationSheet]，进页时拉一次未读数）。
 *   顶级页不留占位的顶栏，这一行就是页头。页头随列表往上滚收起、一往回滚就出现，「小组 / 超展开」紧贴其下、
 *   收起后独自吸在最上面（[CollapsibleHeader]，与进度、探索的搜索栏同一套），往下读时顶上只有一层。
 * - 小组页：顶部一行我加入的小组（置顶的在前，最后一格「全部小组」），下面是这些小组的新帖；
 *   未登录或没加入小组时那一行只有「全部小组」，帖子是全部小组的新帖。回到社区页时重取那一行（在网页上加入、退出之后）。
 * - 超展开：全站讨论按最后回复排，列表头是分类 chip（全部、小组、作品、单集、角色、人物），随列表滚走。
 *   屏蔽的小组的帖子不出现；长按一个小组帖弹出「屏蔽小组」，在设置的「屏蔽的小组」里取消。
 * - 两页都能下拉刷新；点一帖打开它的链接：App 里有这一页就在 App 里开（`inAppRoute` 判），没有去网页。
 */
@Composable
fun CommunityScreen(
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onUserClick: (String) -> Unit,
    onGroupClick: (String) -> Unit,
    onAllGroups: () -> Unit,
    onMyTopics: () -> Unit,
    onConversation: (Int) -> Unit,
    onWrite: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CommunityViewModel = koinViewModel(),
    notificationViewModel: NotificationViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pages = CommunityPage.entries
    // 选中页（ViewModel 的 page）是唯一真源：点按钮组只改它，翻页跟过去；手指翻页落定后才回写
    val pager = rememberTabPagerState(
        selected = pages.indexOf(state.page),
        pageCount = pages.size,
        onSelect = { viewModel.selectPage(pages[it]) },
    )
    val openLink = rememberLinkOpener()
    val snackbar = remember { SnackbarHostState() }
    var showNotificationSheet by rememberSaveable { mutableStateOf(false) }
    // 长按了哪一帖（超展开的小组帖）：它下面弹出「屏蔽小组」
    var pressedTopic by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { notificationViewModel.refreshUnreadCount() }
    FailureNoticeEffect(viewModel.failures, snackbar)
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshJoinedGroups()
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = LocalGlassContentBottom.current)) },
    ) { padding ->
        // 页头收起后内容在固定栏下面滚：页头、状态栏那一截、固定栏同为一块页面底色，只靠柔边分层（PinnedEdge.kt）
        Column(Modifier.fillMaxSize().padding(padding)) {
            Spacer(Modifier.fillMaxWidth().statusBarsPadding())
            CollapsibleHeader(
                modifier = Modifier.weight(1f),
                collapsing = {
                    CommunityHeader(
                        isLoggedIn = isLoggedIn,
                        onLogin = onLogin,
                        notificationViewModel = notificationViewModel,
                        onOpenNotifications = { showNotificationSheet = true },
                        onMyTopics = onMyTopics,
                    )
                },
                pinned = {
                    // 原型 `.cbg`：左右 16、下 8
                    ConnectedChoiceGroup(
                        options = pages,
                        selected = pages[pager.shownPage],
                        label = { stringResource(it.labelRes) },
                        onSelect = viewModel::selectPage,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                },
            ) {
                TabPager(pager, Modifier.fillMaxSize(), key = { pages[it].name }) { index ->
                    val page = pages[index]
                    // 这一页出现在屏幕上（打开时停的那页、点过去或翻过去）才取；登录状态变了按新身份重取
                    LaunchedEffect(page, isLoggedIn) { viewModel.open(page, isLoggedIn) }
                    val list = state.listFor(page)
                    val kind = if (page == CommunityPage.RAKUEN) topicListKindOf(state.rakuenFilter) else TopicListKind.POSTS
                    // 列表头：小组页是我的小组那一行，超展开是分类 chip。骨架里画的也是它（那一行没到时自己画占位格）
                    val listHead: @Composable () -> Unit = when (page) {
                        CommunityPage.GROUPS -> {
                            { MyGroupsRow(state.joinedGroups, state.myGroups, state.pinnedGroups, onGroupClick, onAllGroups) }
                        }
                        CommunityPage.RAKUEN -> {
                            { RakuenFilterChips(state.rakuenFilter, viewModel::selectRakuenFilter) }
                        }
                    }
                    ChogumiPullToRefreshBox(
                        isRefreshing = list.isRefreshing,
                        onRefresh = { viewModel.refresh(page) },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        PagedListContent(
                            state = list,
                            onRetry = { viewModel.retry(page) },
                            onLoadMore = { viewModel.loadMore(page) },
                            emptyText = stringResource(R.string.community_feed_empty),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp + LocalGlassContentBottom.current),
                            skeletonRow = { i, count -> CommunityTopicRowSkeleton(i, count, Modifier.padding(horizontal = 16.dp), kind = kind) },
                            modifier = Modifier.fillMaxSize(),
                            // 超展开除「小组」外接口不能翻页，到上限就是底：页脚说清楚只列到这么多
                            endText = if (page == CommunityPage.RAKUEN && state.rakuenFilter != RakuenFilter.GROUP) {
                                stringResource(R.string.rakuen_list_end, RAKUEN_MAX_TOPICS)
                            } else {
                                stringResource(R.string.community_list_end)
                            },
                            headerSkeleton = listHead,
                            header = { item(key = "head") { listHead() } },
                        ) { topics ->
                            itemsIndexed(topics, key = { _, topic -> topic.key }) { i, topic ->
                                // 只有超展开里的小组帖能长按屏蔽：小组页是我自己挑的小组
                                val group = groupOf(topic)?.takeIf { page == CommunityPage.RAKUEN }
                                Box(Modifier.padding(horizontal = 16.dp)) {
                                    CommunityTopicRow(
                                        topic = topic,
                                        index = i,
                                        count = topics.size,
                                        onClick = { openLink(topic.webUrl) },
                                        kind = kind,
                                        onLongClick = group?.let { { pressedTopic = topic.key } },
                                    )
                                    if (group != null) {
                                        AppDropdownMenu(expanded = pressedTopic == topic.key, onDismissRequest = { pressedTopic = null }) {
                                            AppMenuItem(
                                                stringResource(R.string.community_mute_group, group.title),
                                                onDismiss = { pressedTopic = null },
                                                icon = R.drawable.visibility_off_24,
                                            ) {
                                                viewModel.mute(group)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNotificationSheet) {
        NotificationSheet(
            viewModel = notificationViewModel,
            onDismissRequest = { showNotificationSheet = false },
            onUser = onUserClick,
            onConversation = onConversation,
            onWrite = onWrite,
        )
    }
}

/**
 * 页头：M3 small top app bar，标题 + 我的帖子 + 铃铛。状态栏由外面留出，这里不再避让。
 * 铃铛用 M3 Small Badge（6dp 圆点）表示有未读（通知与短信合计）。
 */
@Composable
private fun CommunityHeader(
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    notificationViewModel: NotificationViewModel,
    onOpenNotifications: () -> Unit,
    onMyTopics: () -> Unit,
) {
    val unreadCount by notificationViewModel.unreadTotal.collectAsStateWithLifecycle()
    val loginGate = rememberLoginGate(isLoggedIn, onLogin)
    PageHeaderBar(
        title = stringResource(R.string.community),
        actions = {
            // 我的帖子只有登录了才有（原型：未登录时顶栏只有铃铛）
            if (isLoggedIn) {
                IconButton(onClick = onMyTopics) {
                    Icon(painterResource(R.drawable.account_circle_filled_24), contentDescription = stringResource(R.string.my_topics))
                }
            }
            IconButton(
                onClick = {
                    loginGate.run {
                        notificationViewModel.open()
                        onOpenNotifications()
                    }
                },
            ) {
                BadgedBox(badge = { if (unreadCount > 0) Badge() }) {
                    Icon(
                        painter = painterResource(R.drawable.notifications_filled_24),
                        contentDescription = if (unreadCount > 0) {
                            stringResource(R.string.notification_bell_unread_cd)
                        } else {
                            stringResource(R.string.notification_bell_cd)
                        },
                    )
                }
            }
        },
    )
}

/** 超展开的分类：一排可横滑的 chip（原型 `.chips`：间距 8，左右 16，下 8），作为列表的第一项随列表滚走。 */
@Composable
private fun RakuenFilterChips(selected: RakuenFilter, onSelect: (RakuenFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RakuenFilter.entries.forEach { filter ->
            SelectableChip(
                label = stringResource(rakuenFilterLabel(filter)),
                selected = filter == selected,
                onClick = { onSelect(filter) },
            )
        }
    }
}

private fun rakuenFilterLabel(filter: RakuenFilter): Int = when (filter) {
    RakuenFilter.ALL -> R.string.all
    RakuenFilter.GROUP -> R.string.rakuen_filter_group
    RakuenFilter.SUBJECT -> R.string.rakuen_filter_subject
    RakuenFilter.EPISODE -> R.string.rakuen_filter_episode
    RakuenFilter.CHARACTER -> R.string.rakuen_filter_character
    RakuenFilter.PERSON -> R.string.rakuen_filter_person
}
