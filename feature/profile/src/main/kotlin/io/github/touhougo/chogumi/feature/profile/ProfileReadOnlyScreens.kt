package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.IndexCategory
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.model.SlimBlogBo
import io.github.touhougo.chogumi.core.model.TopicSubjectBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.reader.CommentThreadItem
import io.github.touhougo.chogumi.core.reader.CommentUserCard
import io.github.touhougo.chogumi.core.reader.FloorFocusOnArrival
import io.github.touhougo.chogumi.core.reader.ReplyComposerSheet
import io.github.touhougo.chogumi.core.reader.ReplyFab
import io.github.touhougo.chogumi.core.reader.ReplyTarget
import io.github.touhougo.chogumi.core.reader.SubjectEyebrow
import io.github.touhougo.chogumi.core.reader.SubjectEyebrowSkeleton
import io.github.touhougo.chogumi.core.reader.ThreadHead
import io.github.touhougo.chogumi.core.reader.ThreadPageSkeleton
import io.github.touhougo.chogumi.core.reader.floorCommentsSection
import io.github.touhougo.chogumi.core.reader.rememberFloorJump
import io.github.touhougo.chogumi.core.reader.rememberUserCardState
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.PagedListContent
import io.github.touhougo.chogumi.core.ui.common.PagedListPage
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.ConnectedChoiceGroup
import io.github.touhougo.chogumi.core.ui.composables.FabClearance
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.IndexCollect
import io.github.touhougo.chogumi.core.ui.composables.IndexListRow
import io.github.touhougo.chogumi.core.ui.composables.IndexListRowSkeleton
import io.github.touhougo.chogumi.core.ui.composables.PageLink
import io.github.touhougo.chogumi.core.ui.composables.PageMoreMenu
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold
import io.github.touhougo.chogumi.core.ui.composables.PinnedRowFrame
import io.github.touhougo.chogumi.core.ui.composables.PostListItem
import io.github.touhougo.chogumi.core.ui.composables.PostListItemSkeleton
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SkeletonCrossfade
import io.github.touhougo.chogumi.core.ui.composables.TabPager
import io.github.touhougo.chogumi.core.ui.composables.formatIsoDate
import io.github.touhougo.chogumi.core.ui.composables.rememberStickyHeaderEdge
import io.github.touhougo.chogumi.core.ui.composables.rememberTabPagerState
import io.github.touhougo.chogumi.core.ui.composables.stickyHeaderFade
import io.github.touhougo.chogumi.core.ui.moderation.ModerationHost
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 日志 / 目录只读页。
 *
 * 入口：资料头数字行。三个页面共用一个文件：
 * - 日志列表：标题、两行摘要、日期、留言数 → 详情；
 * - 日志详情：BBCode 正文与留言，右上角 ↗ 去网页；
 * - 目录列表：「创建的 / 收藏的」两页，左右滑切换；
 * （目录详情见 [IndexDetailsScreen]。）
 *
 * 列表都是原型 `.seglist`（[SegmentedItem]），左右 16dp（原型 `.sect`）。
 */

/** 日志列表页。 */
@Composable
fun BlogListScreen(
    username: String,
    onBack: () -> Unit,
    onBlogClick: (Int) -> Unit,
) {
    val viewModel: BlogListViewModel = koinViewModel { parametersOf(username) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PagedListPage(
        title = stringResource(R.string.profile_blogs_title),
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        emptyText = stringResource(R.string.empty_blogs),
        // 原型日志列表 `.sect` 上边距只有 4px（没有页头）。
        contentPadding = PaddingValues(top = 4.dp),
        skeletonRow = { index, count ->
            PostListItemSkeleton(index, count, Modifier.padding(horizontal = ListHorizontalPadding), withSummary = true)
        },
        endText = stringResource(R.string.load_more_end_blogs),
    ) { blogs ->
        itemsIndexed(blogs, key = { _, blog -> blog.id }) { i, blog ->
            BlogRow(blog, index = i, count = blogs.size, onClick = { onBlogClick(blog.id) })
        }
    }
}

/**
 * 目录列表页：顶栏下「创建的 / 收藏的」页签，两页左右滑切换（[TabPager]），各自一份分页列表、各自的滚动位置。
 * 自己的目录列表（[isOwn]，由 `appEntries` 判定）右下是「新建目录」FAB：填好名称、介绍、是否私密，建好直接进入新目录。
 */
@Composable
fun IndexListScreen(
    username: String,
    counts: IndexListCounts,
    isOwn: Boolean,
    onBack: () -> Unit,
    onIndexClick: (Int) -> Unit,
) {
    val viewModel: IndexListViewModel = koinViewModel { parametersOf(username, counts) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    FailureNoticeEffect(viewModel.failures, snackbar)
    val pages = IndexListPage.entries
    // 选中页（ViewModel 的 page）是唯一真源：点页签只改它，翻页跟过去；手指翻页落定后才回写
    val pager = rememberTabPagerState(
        selected = pages.indexOf(state.page),
        pageCount = pages.size,
        onSelect = { viewModel.selectPage(pages[it]) },
    )
    PageScaffold(
        title = stringResource(R.string.profile_indexes_title),
        onBack = onBack,
        floatingActionButton = {
            if (isOwn) {
                FloatingActionButton(onClick = viewModel.editor::openNew) {
                    Icon(painterResource(R.drawable.add_24), contentDescription = stringResource(R.string.index_create))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PinnedRowFrame(
            modifier = Modifier.fillMaxSize().padding(padding),
            pinned = {
                ConnectedChoiceGroup(
                    options = pages,
                    selected = pages[pager.shownPage],
                    // 原型「创建的 2 / 收藏的 5」：数的来源见 [IndexListUiState.countOf]。
                    label = { stringResource(R.string.label_with_count, stringResource(it.labelRes), state.countOf(it)) },
                    onSelect = viewModel::selectPage,
                    modifier = Modifier.padding(horizontal = ListHorizontalPadding),
                )
            },
        ) {
            TabPager(pager, Modifier.fillMaxSize(), key = { pages[it].name }) { index ->
                val page = pages[index]
                PagedListContent(
                    state = state.listFor(page),
                    onRetry = { viewModel.retry(page) },
                    onLoadMore = { viewModel.loadMore(page) },
                    emptyText = stringResource(R.string.empty_indexes),
                    // 原型：`.cbg` 紧贴顶栏，下面的 `.sect` 上边距 16px；有 FAB 时底部给它让出位置。
                    contentPadding = PaddingValues(top = 16.dp, bottom = if (isOwn) FabClearance else 0.dp),
                    skeletonRow = { i, count -> IndexListRowSkeleton(i, count, Modifier.padding(horizontal = ListHorizontalPadding)) },
                    modifier = Modifier.fillMaxSize(),
                    endText = stringResource(R.string.load_more_end_indexes),
                ) { indexes ->
                    // 与作品社区、角色 / 人物页的目录行同一份
                    itemsIndexed(indexes, key = { _, index -> index.id }) { i, index ->
                        IndexListRow(
                            index = index,
                            position = i,
                            count = indexes.size,
                            onClick = { onIndexClick(index.id) },
                            modifier = Modifier.padding(horizontal = ListHorizontalPadding),
                        )
                    }
                }
            }
        }
    }
    state.editor?.let { editor ->
        IndexEditorSheet(
            state = editor,
            onChange = viewModel.editor::change,
            onSave = { viewModel.editor.save(onSaved = onIndexClick) },
            onDismiss = viewModel.editor::close,
        )
    }
}

/** 日志页 `LazyColumn` 里第一层留言之前的固定项数：眉题 + 标题、正文楼、吸顶排序行（引用跳转按它换算下标）。 */
private const val BLOG_HEADER_ITEMS = 3

/**
 * 日志详情页，与讨论版话题页同一套阅读骨架（不只把文字摆出来）：
 * 关联作品眉题 + 标题 → 正文画成楼主楼（头像、昵称、楼主、时间；整篇不折叠）→ 吸顶排序行 → 留言楼层 →「已经到底了」；
 * 右上角 ↗ 去网页。正文、关联作品、留言并行取，三路都到了整页一起出现。
 * 右下「回复」FAB 与点正文都是给日志留言，点留言回复那一层：与帖子页同一个编辑器面板（[ReplyComposerSheet]），未登录先去登录。
 * [focusPostId] 是从通知、带楼层锚点的链接点进来时要看的那层留言：进页就落到它。
 */
@Composable
fun BlogDetailsScreen(
    blogId: Int,
    focusPostId: Int?,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    onSubjectClick: (Int) -> Unit,
    onUserClick: (String) -> Unit,
) {
    val viewModel: BlogDetailsViewModel = koinViewModel { parametersOf(blogId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val details = state.details.valueOrNull()
    val pageState = sectionStateOf(state.details) { false }
    val listState = rememberLazyListState()
    val jump = rememberFloorJump(listState, firstReplyItem = BLOG_HEADER_ITEMS)
    val gate = rememberLoginGate(isLoggedIn, onLogin)
    val reply = gate.guard { target: ReplyTarget -> viewModel.composer.open(target) }
    val moderate = gate.guard(viewModel.moderation::perform)
    val surface = CommentSurface.Blog(blogId)
    // 正文楼与留言的用户卡片都由屏幕持有：楼滚出列表、重建后还是骨架时，卡片不丢
    val userCard = rememberUserCardState()
    val postThread = remember(details) { details?.openingPost() }
    val snackbar = remember { SnackbarHostState() }
    FailureNoticeEffect(viewModel.failures, snackbar)
    PageScaffold(
        title = stringResource(R.string.profile_blogs_title),
        onBack = onBack,
        actions = {
            PageMoreMenu(
                PageLink(details?.title.orEmpty(), BgmWeb.blog(blogId)),
                IndexCollect(IndexTarget(IndexCategory.BLOG, blogId), gate),
            )
        },
        floatingActionButton = {
            if (details != null) ReplyFab { reply.action(ReplyTarget.Topic) }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // 整页一套骨架：正文、关联作品、留言都到了才淡入，不走「转圈 → 正文 → 眉题插进来把正文往下挤 → 留言」
        SkeletonCrossfade(
            loading = pageState == SectionState.LOADING,
            modifier = Modifier.fillMaxSize().padding(padding),
            skeleton = { ThreadPageSkeleton(eyebrow = true) },
        ) {
            when {
                pageState == SectionState.ERROR -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    state.details.failureOrNull()?.let { RetryError(it, onRetry = viewModel::retry) }
                }
                details != null && postThread != null -> {
                    FloorFocusOnArrival(jump, state.comments.reading, focusPostId)
                    val sortEdge = rememberStickyHeaderEdge(listState)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().stickyHeaderFade(sortEdge),
                        contentPadding = PaddingValues(bottom = FabClearance),
                        overscrollEffect = sortEdge.overscrollEffect,
                    ) {
                        item(key = "head") { BlogHead(details.title, state.subjects, onSubjectClick, viewModel::retrySubjects) }
                        item(key = "post") {
                            CommentThreadItem(
                                thread = postThread,
                                floorNumber = "",
                                onAvatarClick = userCard::open,
                                onUserClick = onUserClick,
                                opCreatorId = details.author?.id,
                                asPost = true,
                                surface = surface,
                                onReply = reply,
                                onModerate = moderate,
                            )
                        }
                        floorCommentsSection(
                            comments = state.comments,
                            surface = surface,
                            jump = jump,
                            emptyText = R.string.index_comments_empty,
                            onSelectSort = viewModel::selectCommentSort,
                            onToggleOpOnly = viewModel::toggleCommentOpOnly,
                            onUserClick = onUserClick,
                            onAvatarClick = userCard::open,
                            onRetry = viewModel::retryComments,
                            onReply = reply,
                            onModerate = moderate,
                            sortEdge = sortEdge,
                        )
                    }
                }
            }
        }
    }
    CommentUserCard(userCard, listOfNotNull(postThread) + state.comments.reading.threads, onUserClick, moderate)
    ReplyComposerSheet(state.composer, viewModel.composer, surface, gate) { postId ->
        jump.jumpToPost(viewModel.uiState.value.comments.reading, postId)
    }
    ModerationHost(state.moderation, viewModel.moderation)
}

/**
 * 日志页头部：关联作品眉题 + 标题，几何见 [ThreadHead]。关联作品是自己的区块：还没到画眉题骨架，取不到原位重试（正文与留言照常），
 * 没有关联作品就没有眉题行。
 */
@Composable
private fun BlogHead(title: String, subjects: Load<List<TopicSubjectBo>>, onSubjectClick: (Int) -> Unit, onRetrySubjects: () -> Unit) {
    ThreadHead(
        title = title,
        eyebrows = when (sectionStateOf(subjects)) {
            SectionState.LOADING -> ({ SubjectEyebrowSkeleton() })
            SectionState.ERROR -> ({ RetryError(checkNotNull(subjects.failureOrNull()), onRetry = onRetrySubjects, Modifier.fillMaxWidth(), compact = true) })
            SectionState.EMPTY -> null
            SectionState.CONTENT -> ({
                subjects.valueOrNull().orEmpty().forEach { subject ->
                    SubjectEyebrow(
                        cover = subject.cover,
                        title = displayNameOf(subject),
                        onClick = { onSubjectClick(subject.id) },
                    )
                }
            })
        },
    )
}

// ---- 行组件 ----

/** 日志行：[PostListItem]（与讨论版、作品评论同一份），全是本人写的，不画头像与作者。 */
@Composable
private fun BlogRow(blog: SlimBlogBo, index: Int, count: Int, onClick: () -> Unit) {
    PostListItem(
        title = blog.title,
        replies = blog.replies,
        repliesDescription = pluralStringResource(R.plurals.comments_count, blog.replies, blog.replies),
        index = index,
        count = count,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = ListHorizontalPadding),
        summary = blog.summary,
        time = formatIsoDate(blog.createdAt),
    )
}

// ---- 共用 ----

/** 列表页左右边距：原型 `.sect{padding:16px 16px 0}`。 */
private val ListHorizontalPadding = 16.dp
