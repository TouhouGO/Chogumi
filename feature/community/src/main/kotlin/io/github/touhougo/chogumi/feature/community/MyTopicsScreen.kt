package io.github.touhougo.chogumi.feature.community

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.common.paging.asPagedList
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.TopicReplyDraft
import io.github.touhougo.chogumi.core.reader.replyTarget
import io.github.touhougo.chogumi.core.reader.title
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.PagedListContent
import io.github.touhougo.chogumi.core.ui.common.navigation.rememberLinkOpener
import io.github.touhougo.chogumi.core.ui.composables.AppDropdownMenu
import io.github.touhougo.chogumi.core.ui.composables.AppMenuItem
import io.github.touhougo.chogumi.core.ui.composables.ConnectedChoiceGroup
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold
import io.github.touhougo.chogumi.core.ui.composables.PinnedRowFrame
import io.github.touhougo.chogumi.core.ui.composables.PostListItem
import io.github.touhougo.chogumi.core.ui.composables.PostListItemSkeleton
import io.github.touhougo.chogumi.core.ui.composables.TabPager
import io.github.touhougo.chogumi.core.ui.composables.relativeTimeText
import io.github.touhougo.chogumi.core.ui.composables.rememberTabPagerState
import org.koin.compose.viewmodel.koinViewModel

/**
 * 我的帖子（社区页头进来的二级页）：「我发起的 | 我回复的 | 草稿」三页，连接按钮组切换、内容区左右滑动切换。
 *
 * - 我发起的、我回复的：服务端的小组帖子（接口只有小组的），行与社区帖子流同一个 [CommunityTopicRow]，点了打开帖子。
 * - 草稿：本机没发出去的话题回复，最近改过的在前。点了回到那个话题、接着写；长按删除。
 */
@Composable
fun MyTopicsScreen(
    onBack: () -> Unit,
    onOpenDraft: (TopicReplyDraft) -> Unit,
    viewModel: MyTopicsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    FailureNoticeEffect(viewModel.failures, snackbar)
    val pages = MyTopicsPage.entries
    // 选中页（ViewModel 的 page）是唯一真源：点按钮组只改它，翻页跟过去；手指翻页落定后才回写
    val pager = rememberTabPagerState(
        selected = pages.indexOf(state.page),
        pageCount = pages.size,
        onSelect = { viewModel.selectPage(pages[it]) },
    )
    val openLink = rememberLinkOpener()

    PageScaffold(title = stringResource(R.string.my_topics), onBack = onBack, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        PinnedRowFrame(
            modifier = Modifier.fillMaxSize().padding(padding),
            pinned = {
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
                // 这一页出现在屏幕上（打开时停的那页、点过去或翻过去）才取
                LaunchedEffect(page) { viewModel.open(page) }
                when (val scope = page.scope) {
                    null -> DraftList(state, onOpenDraft, viewModel::deleteDraft, viewModel::retryDrafts)
                    else -> PagedListContent(
                        state = state.listFor(scope),
                        onRetry = { viewModel.retry(scope) },
                        onLoadMore = { viewModel.loadMore(scope) },
                        emptyText = stringResource(page.emptyRes),
                        contentPadding = ListPadding,
                        skeletonRow = { i, count -> CommunityTopicRowSkeleton(i, count, Modifier.padding(horizontal = 16.dp)) },
                        modifier = Modifier.fillMaxSize(),
                        endText = stringResource(R.string.community_list_end),
                    ) { topics ->
                        itemsIndexed(topics, key = { _, topic -> topic.key }) { i, topic ->
                            CommunityTopicRow(
                                topic = topic,
                                index = i,
                                count = topics.size,
                                onClick = { openLink(topic.webUrl) },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 草稿页：一份草稿一行，与帖子流同一个 [PostListItem]：全是自己写的，不画头像与发帖人；标题是话题（顶格），
 * 下面是回复的对象（「回复 #7 某某」「回复楼主」），摘要是写了的内容，页脚是最后改动的时间。长按弹出「删除草稿」。
 */
@Composable
private fun DraftList(state: MyTopicsUiState, onOpen: (TopicReplyDraft) -> Unit, onDelete: (TopicReplyDraft) -> Unit, onRetry: () -> Unit) {
    // 长按了哪一份：它下面弹出「删除草稿」
    var pressed by remember { mutableStateOf<TopicReplyDraft?>(null) }
    PagedListContent(
        state = state.drafts.asPagedList(),
        onRetry = onRetry,
        onLoadMore = {},
        emptyText = stringResource(MyTopicsPage.DRAFTS.emptyRes),
        contentPadding = ListPadding,
        skeletonRow = { i, count -> PostListItemSkeleton(i, count, Modifier.padding(horizontal = 16.dp), withSummary = true, withSource = true) },
        modifier = Modifier.fillMaxSize(),
    ) { drafts ->
        itemsIndexed(drafts, key = { _, d -> "${d.topicType}-${d.topicId}-${d.postId}" }) { i, draft ->
            Box(Modifier.padding(horizontal = 16.dp)) {
                PostListItem(
                    title = draft.topicTitle,
                    replies = null,
                    repliesDescription = null,
                    index = i,
                    count = drafts.size,
                    onClick = { onOpen(draft) },
                    onLongClick = { pressed = draft },
                    summary = draft.text,
                    time = relativeTimeText(draft.updatedAt),
                    source = draft.replyTarget().title(CommentSurface.Topic(draft.topicType, draft.topicId)),
                )
                AppDropdownMenu(expanded = pressed == draft, onDismissRequest = { pressed = null }) {
                    AppMenuItem(stringResource(R.string.draft_delete), onDismiss = { pressed = null }, icon = R.drawable.delete_24) {
                        onDelete(draft)
                    }
                }
            }
        }
    }
}

/** 列表上下留白：按钮组下面已经有 8，列表顶再留 4、底 16（同社区帖子流）。 */
private val ListPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
