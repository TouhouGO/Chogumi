package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.ui.composables.ConfirmDialog
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.IndexCategory
import io.github.touhougo.chogumi.core.model.IndexDetailsBo
import io.github.touhougo.chogumi.core.model.IndexEntryBo
import io.github.touhougo.chogumi.core.model.IndexEntrySort
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.model.indexEntrySortOptions
import io.github.touhougo.chogumi.core.model.sortIndexEntries
import io.github.touhougo.chogumi.core.model.subjectInfoHead
import io.github.touhougo.chogumi.core.model.userKeyOf
import io.github.touhougo.chogumi.core.reader.CollapsibleSynopsis
import io.github.touhougo.chogumi.core.reader.CommentUserCard
import io.github.touhougo.chogumi.core.reader.QuoteText
import io.github.touhougo.chogumi.core.reader.ReplyComposerSheet
import io.github.touhougo.chogumi.core.reader.ReplyFab
import io.github.touhougo.chogumi.core.reader.ReplyTarget
import io.github.touhougo.chogumi.core.reader.UserCardSheet
import io.github.touhougo.chogumi.core.reader.floorCommentsSection
import io.github.touhougo.chogumi.core.reader.rememberFloorJump
import io.github.touhougo.chogumi.core.reader.rememberUserCardState
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.loadMoreFooter
import io.github.touhougo.chogumi.core.ui.common.navigation.indexLinkOf
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.AppDropdownMenu
import io.github.touhougo.chogumi.core.ui.composables.AppMenuItem
import io.github.touhougo.chogumi.core.ui.composables.CircleAvatar
import io.github.touhougo.chogumi.core.ui.composables.CollapsibleHeader
import io.github.touhougo.chogumi.core.ui.composables.CollectCountButton
import io.github.touhougo.chogumi.core.ui.composables.ConnectedButtonGroupSkeleton
import io.github.touhougo.chogumi.core.ui.composables.ConnectedChoiceGroup
import io.github.touhougo.chogumi.core.ui.composables.FabClearance
import io.github.touhougo.chogumi.core.ui.composables.FactChip
import io.github.touhougo.chogumi.core.ui.composables.FactChipSkeleton
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.HeaderReveal
import io.github.touhougo.chogumi.core.ui.composables.ListImageAvatarSize
import io.github.touhougo.chogumi.core.ui.composables.ListSortBar
import io.github.touhougo.chogumi.core.ui.composables.MetaText
import io.github.touhougo.chogumi.core.ui.composables.PageLink
import io.github.touhougo.chogumi.core.ui.composables.PageMoreMenu
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.ReorderState
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.ScrollToTopFab
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SkeletonCrossfade
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.StickyHeaderEdge
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.SubjectPosterSkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.TabPager
import io.github.touhougo.chogumi.core.ui.composables.containerColor
import io.github.touhougo.chogumi.core.ui.composables.iconRes
import io.github.touhougo.chogumi.core.ui.composables.labelRes
import io.github.touhougo.chogumi.core.ui.composables.numberColumnWidth
import io.github.touhougo.chogumi.core.ui.composables.relativeTimeText
import io.github.touhougo.chogumi.core.ui.composables.rememberCollapsibleHeaderState
import io.github.touhougo.chogumi.core.ui.composables.rememberReorderState
import io.github.touhougo.chogumi.core.ui.composables.rememberStickyHeaderEdge
import io.github.touhougo.chogumi.core.ui.composables.rememberTabPagerState
import io.github.touhougo.chogumi.core.ui.composables.reorderableItems
import io.github.touhougo.chogumi.core.ui.composables.starScoreText
import io.github.touhougo.chogumi.core.ui.composables.stickyHeaderFade
import io.github.touhougo.chogumi.core.ui.composables.subjectRatingText
import io.github.touhougo.chogumi.core.ui.moderation.ModerationHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// 原型 `.ihead` `.byrow` `.works .th` 的几何——设计决定（not in M3，原型值）。
private val Gutter = 16.dp
private val CreatorAvatarSize = 28.dp
private val RankWidth = 20.dp

/** 条目行（真实与骨架）序号列的 testTag：几何测试据此对位。 */
internal const val IndexRankColumnTag = "index_rank_column"

/** 目录页的两个页签。 */
private enum class IndexPane { ENTRIES, COMMENTS }

/** 留言页签的列表里第一层楼之前的固定项数：吸顶排序行（引用跳转按它换算下标）。 */
private const val COMMENTS_HEADER_ITEMS = 1

/**
 * 目录页（原型截图 26–27、`indexScreen()`）：头部 →「收录 / 留言」页签（两页左右滑切换，[TabPager]）→
 * - 收录：「N 个条目」+ 排序 →（收录不止一类时）作品 / 角色 / 人物切换 → 收录列表（序号、封面或头像、副行、评分、收录理由）；
 * - 留言：与讨论版话题同一套阅读骨架（[floorCommentsSection]）。在这一页签时右下是「回复」FAB（写新的一条留言），
 *   点留言回复那一层，都打开与帖子页同一个编辑器面板（[ReplyComposerSheet]）；收录页签右下是「回到顶部」。
 *
 * 留言单独成页签：收录动辄上千条，排在收录后面等于看不到。头部随内容往上滚收起、内容回到顶部再往下拉才展开，
 * 页签收起后吸在顶栏下面；两页各自一个列表、各自的滚动位置。顶栏标题在头部收满后才显示。
 *
 * 自己的目录（[IndexDetailsBo.isOwn]，取数时判定）：收录页签右下是「收录链接」FAB（贴 bgm.tv 链接，七类都能收），
 * 长按一行弹出「编辑评语」「移出目录」；收录顺序视图里长按一行后手指一动就拖起来排序（[reorderableItems]，菜单随之收起）。
 * 顶栏 ⋮ 在网页、分享之后多出「编辑」「删除」。
 */
@Composable
fun IndexDetailsScreen(
    indexId: Int,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    onEntryClick: (IndexTarget) -> Unit,
    onUserClick: (String) -> Unit,
) {
    val viewModel: IndexDetailsViewModel = koinViewModel { parametersOf(indexId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 目录删掉了就离开这一页，只在它处于前台时：回包时用户已进了子页，不代替用户弹掉子页，等回到这一页再离开
    if (state.deleted) {
        LifecycleResumeEffect(Unit) {
            onBack()
            onPauseOrDispose { }
        }
    }
    val loginGate = rememberLoginGate(isLoggedIn, onLogin)
    val details = state.details.valueOrNull()
    val headerState = rememberCollapsibleHeaderState()
    val scrolledPastHead by remember { derivedStateOf { headerState.collapsedFraction >= 1f } }
    val snackbar = remember { SnackbarHostState() }
    val panes = IndexPane.entries
    var pane by rememberSaveable { mutableStateOf(IndexPane.ENTRIES) }
    // 创建者卡片与留言楼层的头像卡片都由屏幕持有（头部与列表行会离开组合、重建后先是骨架）
    val creatorCard = rememberUserCardState()
    val commentCard = rememberUserCardState()
    // 自己的目录：长按哪一行弹出了菜单（行会滚出列表，由屏幕持有）、删除确认是否开着
    var entryMenuFor by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val isOwn = details?.isOwn == true
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // 打开「收录链接」：剪贴板里有能收录的链接就预填（只取链接那一段）
    val openLink: () -> Unit = {
        scope.launch {
            val clip = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
            viewModel.openLink(indexLinkOf(clip)?.url.orEmpty())
        }
    }
    // 选中页签 pane 是唯一真源：点页签只改它，翻页跟过去；手指翻页落定后才回写
    val pager = rememberTabPagerState(
        selected = panes.indexOf(pane),
        pageCount = panes.size,
        onSelect = { pane = panes[it] },
    )
    val shownPane = panes[pager.shownPage]
    // 每个页签自己的列表，由页面持有：「回到顶部」跟着此刻高亮的页签，楼层跳转用留言页的列表
    val paneLists = panes.associateWith { rememberLazyListState() }
    val jump = rememberFloorJump(paneLists.getValue(IndexPane.COMMENTS), firstReplyItem = COMMENTS_HEADER_ITEMS)
    val surface = CommentSurface.Index(indexId)
    val reply = loginGate.guard { target: ReplyTarget -> viewModel.composer.open(target) }
    val moderate = loginGate.guard(viewModel.moderation::perform)
    val publicRatings = LocalAppFeatures.current.publicRatings
    val sortOptions = indexEntrySortOptions(state.category, publicRatings)
    // 收录项只交出目录目标；目标到页面的映射由 appEntries 统一完成。
    val openEntry: (IndexEntryBo) -> Unit = { entry -> onEntryClick(entry.target) }
    // 显示顺序由纯函数派生，不另存一份
    val shownEntries = remember(state.entries.items, state.sort, publicRatings) {
        sortIndexEntries(state.entries.items, state.sort, publicRatings)
    }
    FailureNoticeEffect(viewModel.failures, snackbar)

    PageScaffold(
        title = details?.title?.takeIf { scrolledPastHead }.orEmpty(),
        onBack = onBack,
        actions = {
            CollectCountButton(
                visible = details != null,
                collected = details?.isCollected == true,
                count = details?.collects ?: 0,
                icon = R.drawable.bookmark_24,
                collectedIcon = R.drawable.bookmark_filled_24,
                collectLabel = stringResource(R.string.index_collect),
                onToggle = loginGate.guard(viewModel::toggleCollection),
                loading = state.isCollectionLoading,
            )
            PageMoreMenu(
                link = PageLink(details?.title.orEmpty(), BgmWeb.index(indexId)),
                extra = details?.takeIf { it.isOwn }?.let { own ->
                    {
                        item(R.string.index_edit_action, R.drawable.edit_24) { viewModel.editor.openEdit(own) }
                        item(R.string.delete, R.drawable.delete_24) { confirmingDelete = true }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (details != null && shownPane == IndexPane.COMMENTS) {
                ReplyFab { reply.action(ReplyTarget.Topic) }
            } else if (isOwn) {
                FloatingActionButton(onClick = openLink) {
                    Icon(painterResource(R.drawable.add_link_24), contentDescription = stringResource(R.string.index_add_link))
                }
            } else {
                ScrollToTopFab(paneLists.getValue(shownPane))
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // 整页一套骨架：目录信息与第一页条目都到了才交叉淡入，不分「整屏转圈 → 条目区再转圈」两段
            SkeletonCrossfade(loading = sectionStateOf(state.details) { false } == SectionState.LOADING, skeleton = { IndexDetailsPageSkeleton() }) {
            when {
                details == null ->
                    RetryError(state.details.failureOrNull() ?: Failure.Unknown, onRetry = viewModel::retry, Modifier.align(Alignment.Center))
                else -> CollapsibleHeader(
                    collapsing = { IndexHead(details = details, onCreatorClick = creatorCard::open) },
                    pinned = {
                        ConnectedChoiceGroup(
                            options = panes,
                            selected = shownPane,
                            // 留言数并进「留言」页签，不单独一枚 chip
                            label = { option ->
                                val title = stringResource(option.labelRes())
                                if (option == IndexPane.COMMENTS) stringResource(R.string.label_with_count, title, details.replies) else title
                            },
                            icon = { option, selected -> option.iconRes(selected) },
                            onSelect = { pane = it },
                            modifier = Modifier.padding(start = Gutter, end = Gutter, top = 20.dp, bottom = 8.dp),
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                    state = headerState,
                    reveal = HeaderReveal.AtContentTop,
                ) {
                    TabPager(pager, Modifier.fillMaxSize(), key = { panes[it].name }) { page ->
                        val pagePane = panes[page]
                        val list = paneLists.getValue(pagePane)
                        val sortEdge = rememberStickyHeaderEdge(list)
                        // 收录行的显示顺序与拖动排序（拖动中、松手后到新顺序回来之前的顺序在这里）；能不能拖只看 canReorder
                        val reorder = rememberReorderState(
                            items = shownEntries,
                            key = ::entryKeyOf,
                            list = list,
                            onMove = { keys -> viewModel.moveEntries(keys.map(::entryIdOf)) },
                            onLift = { entryMenuFor = null },
                        )
                        LazyColumn(
                            state = list,
                            modifier = Modifier.fillMaxSize().stickyHeaderFade(sortEdge),
                            // 留言页签与自己目录的收录页签右下一直有 FAB，列表底部给它让出位置
                            contentPadding = PaddingValues(bottom = if (pagePane == IndexPane.COMMENTS || isOwn) FabClearance else 24.dp),
                            overscrollEffect = sortEdge.overscrollEffect,
                        ) {
                            when (pagePane) {
                                IndexPane.ENTRIES -> entriesSection(
                                    state = state,
                                    details = details,
                                    reorder = reorder,
                                    shownCount = shownEntries.size,
                                    options = sortOptions,
                                    onEntryClick = openEntry,
                                    menu = if (details.isOwn) {
                                        EntryMenu(
                                            openFor = entryMenuFor,
                                            onOpen = { entryMenuFor = it.id },
                                            onClose = { entryMenuFor = null },
                                            onComment = viewModel::openComment,
                                            onRemove = viewModel::removeEntry,
                                        )
                                    } else {
                                        null
                                    },
                                    viewModel = viewModel,
                                    sortEdge = sortEdge,
                                )
                                IndexPane.COMMENTS -> floorCommentsSection(
                                    comments = state.comments,
                                    surface = surface,
                                    jump = jump,
                                    emptyText = R.string.index_comments_empty,
                                    onSelectSort = viewModel::selectCommentSort,
                                    onToggleOpOnly = viewModel::toggleCommentOpOnly,
                                    onUserClick = onUserClick,
                                    onAvatarClick = commentCard::open,
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
            }
        }
    }
    details?.creator?.takeIf { creatorCard.openId == it.id }?.let { creator ->
        UserCardSheet(
            nickname = displayNameOf(creator),
            avatarUrl = creator.avatarUrl,
            sign = creator.sign,
            username = creator.username,
            userId = creator.id,
            isOwn = isOwn,
            onDismiss = creatorCard::close,
            onOpenProfile = { onUserClick(userKeyOf(creator.username, creator.id)) },
            onModerate = moderate,
        )
    }
    CommentUserCard(commentCard, state.comments.reading.threads, onUserClick, moderate)
    ReplyComposerSheet(state.composer, viewModel.composer, surface, loginGate) { postId ->
        jump.jumpToPost(viewModel.uiState.value.comments.reading, postId)
    }
    ModerationHost(state.moderation, viewModel.moderation)
    state.editor?.let { editor ->
        IndexEditorSheet(
            state = editor,
            onChange = viewModel.editor::change,
            onSave = { viewModel.editor.save() },
            onDismiss = viewModel.editor::close,
        )
    }
    state.entryComment?.let { panel ->
        IndexCommentSheet(
            state = panel,
            onChange = viewModel::changeComment,
            onSave = viewModel::saveComment,
            onDismiss = viewModel::closeComment,
        )
    }
    state.link?.let { link ->
        IndexLinkSheet(
            state = link,
            target = remember(link.text) { indexLinkOf(link.text)?.target },
            onTextChange = viewModel::changeLink,
            onAdd = viewModel::addLink,
            onDismiss = viewModel::closeLink,
        )
    }
    if (confirmingDelete && details != null) {
        // 删除不可撤销：确认后才发请求
        ConfirmDialog(
            target = details,
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { viewModel.deleteIndex() },
            onDismiss = { confirmingDelete = false },
            title = stringResource(R.string.index_delete_confirm_title),
            text = stringResource(R.string.index_delete_confirm_body, details.title),
            destructive = true,
        )
    }
}

/**
 * 自己目录里收录行的长按菜单：[openFor] 是弹出菜单的那一行（[IndexEntryBo.id]），状态由屏幕持有。
 * 别人的目录没有这个菜单（传 null）。
 */
private class EntryMenu(
    val openFor: Int?,
    val onOpen: (IndexEntryBo) -> Unit,
    val onClose: () -> Unit,
    val onComment: (IndexEntryBo) -> Unit,
    val onRemove: (IndexEntryBo) -> Unit,
)

/** 收录行在列表里的 key（也是拖动排序的 key）与收录行 id 互换。 */
private const val ENTRY_KEY_PREFIX = "entry-"

private fun entryKeyOf(entry: IndexEntryBo): String = "$ENTRY_KEY_PREFIX${entry.id}"

private fun entryIdOf(key: String): Int = key.removePrefix(ENTRY_KEY_PREFIX).toInt()

/** 头部（原型 `.ihead`）：标题 → 创建者行 → 分类计数 chip → 简介。收藏在顶栏右上角，留言数在「留言」页签上。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndexHead(
    details: IndexDetailsBo,
    onCreatorClick: (userId: Int) -> Unit,
) {
    var descriptionExpanded by rememberSaveable(details.id) { mutableStateOf(false) }
    Column(Modifier.padding(start = Gutter, end = Gutter)) {
        Text(
            text = details.title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
        )
        details.creator?.let { creator ->
            PressArea(
                onClick = { onCreatorClick(creator.id) },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleAvatar(creator.avatarUrl, description = null, size = CreatorAvatarSize)
                    val updatedStyle = MaterialTheme.typography.bodySmall
                        .copy(color = MaterialTheme.colorScheme.onSurfaceVariant).toSpanStyle()
                    MetaText(
                        parts = listOfNotNull(
                            AnnotatedString(displayNameOf(creator)),
                            relativeTimeText(details.updatedAt).takeIf { it.isNotBlank() }?.let {
                                AnnotatedString(stringResource(R.string.index_updated, it), updatedStyle)
                            },
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        // 昵称可能很长：只截昵称，保住「更新于 X」
                        shrinkPart = 0,
                    )
                }
            }
        }
        FlowRow(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (details.isPrivate) {
                FactChip(text = stringResource(R.string.index_private), icon = R.drawable.lock_24)
            }
            details.typeCounts.forEach { (type, count) ->
                FactChip(text = stringResource(R.string.label_with_count, stringResource(type.labelRes()), count), icon = type.iconRes())
            }
            // 作品已按类型逐个列出，这里只补其余类别的数量。
            details.categoryCounts.filter { it.first != IndexCategory.SUBJECT }.forEach { (category, count) ->
                FactChip(text = stringResource(R.string.label_with_count, stringResource(category.labelRes()), count), icon = category.iconRes())
            }
        }
        if (details.description.isNotBlank()) {
            CollapsibleSynopsis(
                text = details.description,
                expanded = descriptionExpanded,
                onToggle = { descriptionExpanded = !descriptionExpanded },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * 吸顶排序行（[ListSortBar]：「N 个条目」+ 排序，与留言页签的排序行同一个组件）+（不止一类时）类别切换 + 收录列表。
 * 首屏没到、或「按评分」还没取完全量（在一页上排是错的）时画条目行的占位。「N 个条目」是当前类别的数量。
 * 收录行经 [reorderableItems]：显示顺序来自 [reorder]，自己的目录在能拖时（[IndexDetailsUiState.canReorder]）长按后一动即可拖动排序。
 */
private fun LazyListScope.entriesSection(
    state: IndexDetailsUiState,
    details: IndexDetailsBo,
    reorder: ReorderState<IndexEntryBo>,
    shownCount: Int,
    options: List<IndexEntrySort>,
    onEntryClick: (IndexEntryBo) -> Unit,
    menu: EntryMenu?,
    viewModel: IndexDetailsViewModel,
    sortEdge: StickyHeaderEdge,
) {
    // 「N 个条目」以收录接口的分页 total 为准：目录头部的 stats 可能是空的（实测 95284：stats 为 {}、total 253）；
    // 第一页到之前先用 stats 的数。
    val entries = state.entries
    val categoryTotal = entries.total.takeIf { it > 0 } ?: details.categoryCounts.firstOrNull { it.first == state.category }?.second ?: 0
    stickyHeader(key = "entries-sort") {
        ListSortBar(
            heading = pluralStringResource(R.plurals.index_items_count, categoryTotal, categoryTotal),
            sort = state.sort,
            options = options,
            label = { stringResource(it.labelRes()) },
            onSelect = viewModel::selectSort,
            containerColor = sortEdge.containerColor,
        )
    }
    if (details.categoryCounts.size > 1) {
        item(key = "entries-categories") {
            ConnectedChoiceGroup(
                options = details.categoryCounts.map { it.first },
                selected = state.category,
                label = { category -> stringResource(R.string.label_with_count, stringResource(category.labelRes()), details.categoryCounts.first { it.first == category }.second) },
                icon = { category, selected -> category.iconRes(selected) },
                onSelect = viewModel::selectCategory,
                modifier = Modifier.padding(start = Gutter, end = Gutter, bottom = 8.dp),
            )
        }
    }
    // 「按评分」排的是全量：还有没取的页时先不画列表（取完再排，不在一半上排）
    val ratingPending = state.sort == IndexEntrySort.RATING && entries.hasMore
    val entriesError = entries.error
    val loadAllError = entries.loadMoreError
    val section = sectionStateOf(entries)
    when {
        ratingPending && loadAllError != null -> item(key = "entries-load-all-error") {
            RetryError(loadAllError, onRetry = viewModel::retryLoadAll, Modifier.fillMaxWidth())
        }
        // 条目还没到、或「按评分」还在取剩下的页：画条目行的占位（与真实行同一个框架），不转圈
        section == SectionState.LOADING || ratingPending -> items(ENTRY_SKELETON_ROWS, key = { "entry-skeleton-$it" }) { index ->
            EntryRowSkeleton(index = index, count = ENTRY_SKELETON_ROWS, maxRank = categoryTotal, modifier = entryRowModifier(index))
        }
        section == SectionState.ERROR -> if (entriesError != null) {
            item(key = "entries-error") {
                RetryError(entriesError, onRetry = viewModel::retryEntries, Modifier.fillMaxWidth())
            }
        }
        else -> {
            reorderableItems(reorder, enabled = state.canReorder, rowModifier = ::entryRowModifier) { index, entry ->
                Box(Modifier.dragOnLongPress()) {
                    EntryRow(
                        entry = entry,
                        rank = index + 1,
                        segmentIndex = segmentIndex,
                        segmentCount = segmentCount,
                        maxRank = maxOf(categoryTotal, shownCount),
                        onClick = { onEntryClick(entry) },
                        onLongClick = menu?.let { { it.onOpen(entry) } },
                        containerColor = containerColor,
                    )
                    if (menu != null) {
                        AppDropdownMenu(expanded = menu.openFor == entry.id, onDismissRequest = menu.onClose) {
                            AppMenuItem(stringResource(R.string.index_comment_edit), onDismiss = menu.onClose, icon = R.drawable.edit_24) {
                                menu.onComment(entry)
                            }
                            AppMenuItem(stringResource(R.string.index_remove_entry), onDismiss = menu.onClose, icon = R.drawable.playlist_remove_24) {
                                menu.onRemove(entry)
                            }
                        }
                    }
                }
            }
            loadMoreFooter(entries, viewModel::loadMoreEntries)
        }
    }
}

/** 收录列表第 [index] 行的外边距：左右留白，分段列表项间距 2（`ListItemDefaults.SegmentedGap`），首项之前不留。真实行与占位行共用。 */
private fun entryRowModifier(index: Int): Modifier =
    Modifier.padding(start = Gutter, end = Gutter, top = if (index > 0) ListItemDefaults.SegmentedGap else 0.dp)

/**
 * 收录一行（原型 `.slist.works .it`）：序号 +（作品是 3:4 封面，角色 / 人物是圆形头像，其余类别不带图）+
 * 标题 / 副行 / 评分 / 收录理由。副行：作品取 `info` 前两段；章节是集号与所属作品；日志、话题是作者或所属的小组、作品。
 * 评分只有作品有。[rank] 是显示的序号，[segmentIndex] / [segmentCount] 是分段形状（拖着的那一行单独成一段）。
 */
@Composable
internal fun EntryRow(
    entry: IndexEntryBo,
    rank: Int,
    segmentIndex: Int,
    segmentCount: Int,
    maxRank: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    val title = displayNameOf(entry)
    val rankWidth = numberColumnWidth(maxRank.toString().length, MaterialTheme.typography.labelMedium, RankWidth)
    val picture = entry.category.picture()
    val episodeLabel = entry.episodeSort?.let { stringResource(R.string.progress_episode, formatEpisodeSort(it)) }
    val meta = when (entry.category) {
        IndexCategory.SUBJECT -> subjectInfoHead(entry.info)
        else -> listOfNotNull(episodeLabel, entry.parentName.takeIf { it.isNotBlank() })
    }
    // 作品行有副行、评分，顶对齐；其余类别通常只有一两行，与左侧居中。
    val alignment = if (picture == EntryPicture.POSTER) Alignment.Top else Alignment.CenterVertically
    SegmentedItem(
        index = segmentIndex,
        count = segmentCount,
        onClick = onClick,
        modifier = modifier,
        onLongClick = onLongClick,
        verticalAlignment = alignment,
        containerColor = containerColor,
        leadingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = alignment) {
                Text(
                    text = rank.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.width(rankWidth).padding(top = 2.dp).testTag(IndexRankColumnTag),
                )
                when (picture) {
                    EntryPicture.POSTER -> SubjectPoster(entry.cover, Modifier.width(ChogumiDimens.ListThumbWidth), description = null)
                    EntryPicture.AVATAR -> CircleAvatar(url = entry.cover, description = null, size = ListImageAvatarSize)
                    EntryPicture.NONE -> Unit
                }
            }
        },
        details = {
            Column {
                if (meta.isNotEmpty()) {
                    MetaText(parts = meta, style = MaterialTheme.typography.bodySmall)
                }
                subjectRatingText(entry.ratingScore, LocalAppFeatures.current, ::starScoreText)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                }
                if (entry.comment.isNotBlank()) {
                    QuoteText(entry.comment, Modifier.padding(top = 4.dp))
                }
            }
        },
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 收录行左侧的图。 */
private enum class EntryPicture { POSTER, AVATAR, NONE }

/** 收录类别 → 行左侧的图（全仓唯一）：作品封面、角色 / 人物头像；章节、日志、话题没有自己的图。 */
private fun IndexCategory.picture(): EntryPicture = when (this) {
    IndexCategory.SUBJECT -> EntryPicture.POSTER
    IndexCategory.CHARACTER, IndexCategory.PERSON -> EntryPicture.AVATAR
    IndexCategory.EPISODE, IndexCategory.BLOG, IndexCategory.GROUP_TOPIC, IndexCategory.SUBJECT_TOPIC -> EntryPicture.NONE
}

/**
 * 目录页的整页骨架：标题、创建者行、两枚分类 chip、页签、排序行与几行条目，几何取自真实的同一组件
 * （[FactChipSkeleton]、条目行走同一个 [SegmentedItem] 框架），内容出现时大体落在骨架位置上。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndexDetailsPageSkeleton() {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = Gutter, end = Gutter)) {
            SkeletonText(MaterialTheme.typography.headlineSmallEmphasized, widthFraction = 0.7f)
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBox(Modifier.size(CreatorAvatarSize), shape = CircleShape)
                SkeletonText(MaterialTheme.typography.labelLarge, Modifier.width(CREATOR_SKELETON_WIDTH))
            }
            FlowRow(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) { FactChipSkeleton() }
            }
        }
        ConnectedButtonGroupSkeleton(
            weights = IndexPane.entries.map { 1f },
            modifier = Modifier.padding(start = Gutter, end = Gutter, top = 20.dp, bottom = 8.dp),
        )
        SkeletonText(
            MaterialTheme.typography.titleMedium,
            Modifier.padding(horizontal = Gutter, vertical = 20.dp),
            widthFraction = 0.25f,
        )
        // 目录还没到，不知道有几条：序号列取最窄的一档
        repeat(ENTRY_SKELETON_ROWS) { index ->
            EntryRowSkeleton(index = index, count = ENTRY_SKELETON_ROWS, maxRank = 0, modifier = entryRowModifier(index))
        }
    }
}

/**
 * 作品条目行的骨架：与 [EntryRow] 同一个 [SegmentedItem]、同样的序号列（宽度同样由 [numberColumnWidth] 按 [maxRank] 实测）
 * 与 3:4 缩略图，标题与副行画占位。
 */
@Composable
internal fun EntryRowSkeleton(index: Int, count: Int, maxRank: Int, modifier: Modifier = Modifier) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = null,
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        leadingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.width(numberColumnWidth(maxRank.toString().length, MaterialTheme.typography.labelMedium, RankWidth)).testTag(IndexRankColumnTag))
                SubjectPosterSkeletonBox(Modifier.width(ChogumiDimens.ListThumbWidth))
            }
        },
        details = {
            SkeletonText(MaterialTheme.typography.bodySmall, widthFraction = 0.6f)
            SkeletonText(MaterialTheme.typography.labelMedium, Modifier.padding(top = 4.dp), widthFraction = 0.2f)
        },
    ) {
        SkeletonText(MaterialTheme.typography.bodyLarge, widthFraction = 0.7f)
    }
}

/** 骨架里画几行条目、创建者名字占位多宽（设计决定，not in M3）。 */
private const val ENTRY_SKELETON_ROWS = 6
private val CREATOR_SKELETON_WIDTH = 72.dp

/** 页签 → 文案（全仓唯一）。 */
private fun IndexPane.labelRes(): Int = when (this) {
    IndexPane.ENTRIES -> R.string.index_entries_title
    IndexPane.COMMENTS -> R.string.index_comments_title
}

/** 页签 → 图标（全仓唯一）；[filled] 是选中的那一格。 */
private fun IndexPane.iconRes(filled: Boolean = false): Int = when (this) {
    IndexPane.ENTRIES -> if (filled) R.drawable.list_alt_filled_24 else R.drawable.list_alt_24
    IndexPane.COMMENTS -> if (filled) R.drawable.forum_filled_24 else R.drawable.forum_24
}

/** 收录类别 → 文案（全仓唯一）。 */
internal fun IndexCategory.labelRes(): Int = when (this) {
    IndexCategory.SUBJECT -> R.string.index_category_subject
    IndexCategory.CHARACTER -> R.string.index_category_character
    IndexCategory.PERSON -> R.string.index_category_person
    IndexCategory.EPISODE -> R.string.index_category_episode
    IndexCategory.BLOG -> R.string.index_category_blog
    IndexCategory.GROUP_TOPIC -> R.string.index_category_group_topic
    IndexCategory.SUBJECT_TOPIC -> R.string.index_category_subject_topic
}

/** 收录类别 → 图标（全仓唯一）；[filled] 是选中的那一格。 */
private fun IndexCategory.iconRes(filled: Boolean = false): Int = when (this) {
    IndexCategory.SUBJECT -> if (filled) R.drawable.grid_view_filled_24 else R.drawable.grid_view_24
    IndexCategory.CHARACTER -> if (filled) R.drawable.face_filled_24 else R.drawable.face_24
    IndexCategory.PERSON -> if (filled) R.drawable.person_filled_24 else R.drawable.person_24
    IndexCategory.EPISODE -> if (filled) R.drawable.play_circle_filled_24 else R.drawable.play_circle_24
    IndexCategory.BLOG -> if (filled) R.drawable.article_filled_24 else R.drawable.article_24
    IndexCategory.GROUP_TOPIC -> if (filled) R.drawable.group_filled_24 else R.drawable.group_24
    IndexCategory.SUBJECT_TOPIC -> if (filled) R.drawable.chat_filled_24 else R.drawable.chat_24
}

/** 排序 → 文案（全仓唯一）。 */
private fun IndexEntrySort.labelRes(): Int = when (this) {
    IndexEntrySort.ORDER -> R.string.index_sort_order
    IndexEntrySort.RATING -> R.string.index_sort_rating
}
