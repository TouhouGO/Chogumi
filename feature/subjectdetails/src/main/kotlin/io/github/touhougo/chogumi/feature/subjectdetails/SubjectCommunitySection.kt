package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectCollectorBo
import io.github.touhougo.chogumi.core.model.collectionCountOf
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.userKeyOf
import io.github.touhougo.chogumi.core.reader.CommentFloorDivider
import io.github.touhougo.chogumi.core.reader.CommentFloorsSkeleton
import io.github.touhougo.chogumi.core.reader.CommentThreadItem
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.Gated
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.loadMoreFooter
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.AdaptiveGridSkeleton
import io.github.touhougo.chogumi.core.ui.composables.ContentGrid
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.GridCell
import io.github.touhougo.chogumi.core.ui.composables.MenuChip
import io.github.touhougo.chogumi.core.ui.composables.PersonGridItem
import io.github.touhougo.chogumi.core.ui.composables.PersonGridItemSkeleton
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.ScrollableModalSheet
import io.github.touhougo.chogumi.core.ui.composables.SectionHeader
import io.github.touhougo.chogumi.core.ui.composables.SelectableChip
import io.github.touhougo.chogumi.core.ui.composables.SheetTitleRow
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.StackedAvatars
import io.github.touhougo.chogumi.core.ui.composables.labelResFor
import io.github.touhougo.chogumi.core.ui.composables.othersRatingStars
import io.github.touhougo.chogumi.core.ui.composables.relativeTimeText
import io.github.touhougo.chogumi.core.ui.composables.rememberTextLinesHeight
import io.github.touhougo.chogumi.core.ui.composables.stackedAvatarsFitting
import io.github.touhougo.chogumi.core.ui.moderation.ModerationAction
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** 「谁在看」一行里的三种状态与顺序（「N 在看　N 看过　N 想看」）。 */
private val WatchingTypes = listOf(CollectionType.DOING, CollectionType.DONE, CollectionType.WISH)

/** 按收藏状态筛的 chip 顺序（谁在看面板、吐槽箱共用）：null =「最近 / 全部」，其后五态（原型 `tabs`）。 */
private val CollectionFilters: List<CollectionType?> =
    listOf(null, CollectionType.DOING, CollectionType.DONE, CollectionType.WISH, CollectionType.ON_HOLD, CollectionType.DROPPED)

// 原型 `.entry3` `.tile` `.whorow` `.stack` 的几何——设计决定（not in M3，原型值）。
private val CommunityGutter = 16.dp
private val TileGap = 8.dp
private val StackAvatarSize = 32.dp

/** 「谁在看」三组人数之间的间隔：不用分隔号，靠留白分组（分隔点在窄屏折行时会悬在行尾）。我的设计决定（not in M3）。 */
private val WatchingPairGap = 12.dp

/**
 * 加载占位的量（我的设计决定，not in M3）：入口块数量行的占位约占块内文字列的六成；谁在看面板铺满 3 行人物卡
 * （列数随宽度，末行不留半行），手机上超出面板首屏；每张占一行副行（「1 小时前在看」必有，星级不一定有）。
 */
private const val TILE_COUNT_SKELETON_FRACTION = 0.6f
private const val COLLECTOR_SKELETON_ROWS = 3
private const val COLLECTOR_SUBTITLE_LINES = 1

/**
 * 社区 tab（原型截图 06）：三个入口块 →「谁在看」一行 → 吐槽箱。吐槽楼层逐条进详情页的懒加载列表，
 * 滑到底经统一页脚 [loadMoreFooter] 自动续取，没有「查看全部」子页。
 * 专注模式下整个 tab 不出现（[visibleSectionsFor]）。
 */
internal fun LazyListScope.communitySection(
    subject: SubjectBo,
    community: SubjectCommunityUiState,
    onRetryComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onOpenReviews: () -> Unit,
    onOpenTopics: () -> Unit,
    onOpenIndexes: () -> Unit,
    onOpenCollectors: () -> Unit,
    onUserClick: (String) -> Unit,
    onAvatarClick: (commentId: Int) -> Unit,
    onReactionUsers: (commentId: Int, value: Int) -> Unit,
    onReact: Gated<(itemId: Int, value: Int?) -> Unit>,
    onModerate: Gated<(ModerationAction) -> Unit>,
    onSelectCommentFilter: (CollectionType?) -> Unit,
) {
    // 概览不会「取不到」（各项失败只是数量为空），等待时按空概览画、头像位画占位
    val overview = community.overview.valueOrNull() ?: CommunityOverview()
    val overviewLoading = community.overview is Load.Pending
    val comments = community.shownComments
    item(key = "community-head") {
        Column(Modifier.fillMaxWidth()) {
            CommunityEntryTiles(overview, overviewLoading, onOpenReviews, onOpenTopics, onOpenIndexes)
            WatchingRow(
                subject = subject,
                overview = overview,
                loading = overviewLoading,
                onClick = onOpenCollectors,
                modifier = Modifier.padding(start = CommunityGutter, end = CommunityGutter, top = 4.dp, bottom = 4.dp),
            )
            // 吐槽箱按收藏状态筛：服务端 `type` 参数筛。筛选是标题右侧的下拉 chip（标题下
            // 一整排 chip 太占地方）；不带数字——条目的收藏数不是吐槽数，筛后的吐槽数在标题里。
            val allLabel = stringResource(R.string.all)
            SectionHeader(
                title = stringResource(R.string.comment),
                count = comments.total.takeIf { !comments.isLoading && it > 0 },
                trailing = {
                    MenuChip(
                        selected = community.commentFilter,
                        options = CollectionFilters,
                        label = { type -> collectionFilterLabel(type, subject, allLabel) },
                        icon = R.drawable.filter_list_24,
                        onSelect = onSelectCommentFilter,
                    )
                },
            )
        }
    }
    val commentsError = comments.error
    when (sectionStateOf(comments)) {
        // 吐槽还没到：画几层楼的占位（与楼层同一组几何），不转圈
        SectionState.LOADING -> item(key = "community-comments-loading") { CommentFloorsSkeleton() }
        SectionState.ERROR -> if (commentsError != null) {
            item(key = "community-comments-error") {
                RetryError(commentsError, onRetry = onRetryComments, Modifier.fillMaxWidth(), compact = true)
            }
        }
        SectionState.EMPTY -> item(key = "community-comments-empty") {
            EmptyLine(stringResource(R.string.community_comments_empty))
        }
        SectionState.CONTENT -> {
            itemsIndexed(comments.items, key = { _, thread -> "community-comment-${thread.anchor.id}" }) { index, thread ->
                Column(Modifier.fillMaxWidth()) {
                    if (index > 0) CommentFloorDivider()
                    CommentThreadItem(
                        thread = thread,
                        floorNumber = "",
                        onAvatarClick = onAvatarClick,
                        onReactionUsers = onReactionUsers,
                        subjectType = subject.type,
                        onUserClick = onUserClick,
                        surface = CommentSurface.SubjectComments(subject.id),
                        onReact = onReact,
                        onModerate = onModerate,
                    )
                }
            }
            loadMoreFooter(comments, onLoadMoreComments)
        }
    }
}

/** 社区页头的三块入口（评论、讨论版、目录）：数量取自 [overview]，[loading] 时数量那一行画占位。 */
@Composable
internal fun CommunityEntryTiles(
    overview: CommunityOverview,
    loading: Boolean,
    onOpenReviews: () -> Unit,
    onOpenTopics: () -> Unit,
    onOpenIndexes: () -> Unit,
) {
    CommunityTiles(
        tiles = listOf(
            TileSpec(R.string.community_reviews, R.drawable.article_24, R.plurals.community_reviews_count, overview.reviews, onOpenReviews),
            TileSpec(R.string.community_board, R.drawable.forum_24, R.plurals.community_board_count, overview.topics, onOpenTopics),
            TileSpec(R.string.community_indexes, R.drawable.list_alt_24, R.plurals.community_indexes_count, overview.indexes, onOpenIndexes),
        ),
        loading = loading,
    )
}

/** 一块入口：标题、图标、数量文案与数量、点击。 */
private class TileSpec(
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
    @PluralsRes val countRes: Int,
    val count: Int?,
    val onClick: () -> Unit,
)

/**
 * 三块等宽的入口（原型 `.entry3`）。每块是「图标在左、标题 / 数量两行在右」；窄屏或大字号下这样放不下
 * 任何一块的标题或数量时，**三块一起**改成「图标在上、标题 / 数量两行居中在下」，文字不被切（三块高度才一致）。
 */
@Composable
private fun CommunityTiles(tiles: List<TileSpec>, loading: Boolean) {
    val measurer = rememberTextMeasurer()
    val titleStyle = MaterialTheme.typography.titleSmall
    val countStyle = MaterialTheme.typography.bodySmall
    val titles = tiles.map { stringResource(it.titleRes) }
    val counts = tiles.map { tile -> tile.count?.let { pluralStringResource(tile.countRes, it, it) }.orEmpty() }
    BoxWithConstraints(Modifier.padding(start = CommunityGutter, end = CommunityGutter, top = 4.dp, bottom = 8.dp)) {
        val shape = with(LocalDensity.current) {
            val tileWidth = (constraints.maxWidth - TileGap.roundToPx() * (tiles.size - 1)) / tiles.size
            val sideBySideRoom = tileWidth - (TILE_PADDING * 2 + TILE_ICON + TILE_ICON_GAP).roundToPx()
            fun widthOf(text: String, style: TextStyle) = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
            val stacked = titles.indices.any { widthOf(titles[it], titleStyle) > sideBySideRoom || widthOf(counts[it], countStyle) > sideBySideRoom }
            // 竖排时文字有整块的宽度；再放不下就折行（不截断），三块的行数取最多的那块，高度才一致
            val room = if (stacked) tileWidth - (TILE_PADDING * 2).roundToPx() else sideBySideRoom
            fun linesOf(text: String, style: TextStyle) = measurer.measure(text, style, constraints = Constraints(maxWidth = room)).lineCount
            TileShape(stacked, titleLines = titles.maxOf { linesOf(it, titleStyle) }, countLines = counts.maxOf { linesOf(it, countStyle) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
            tiles.forEachIndexed { index, tile ->
                CommunityTile(titles[index], tile.iconRes, counts[index], loading, shape, tile.onClick)
            }
        }
    }
}

/** 三块入口共用的排法：并排还是竖排，以及标题、数量各占几行（三块取最多的，高度一致）。 */
private class TileShape(val stacked: Boolean, val titleLines: Int, val countLines: Int)

/**
 * 入口块（原型 `.entry3 .tile`）：图标 20 `primary` + 标题 / 数量两行；[shape] 竖排时图标在上、文字居中在下，文字放不下就折行而不截断。
 * 数量还在取（[loading]）时那一行画同字阶的占位，与下方吐槽区的楼层占位同屏；取不到时留空占住高度。入口照常可点。
 */
@Composable
private fun RowScope.CommunityTile(
    title: String,
    @DrawableRes iconRes: Int,
    count: String,
    loading: Boolean,
    shape: TileShape,
    onClick: () -> Unit,
) {
    PressArea(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.weight(1f),
    ) {
        val icon: @Composable () -> Unit = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(TILE_ICON),
            )
        }
        val textAlign = if (shape.stacked) TextAlign.Center else TextAlign.Start
        val texts: @Composable (Alignment.Horizontal) -> Unit = { alignment ->
            Column(horizontalAlignment = alignment) {
                val titleStyle = MaterialTheme.typography.titleSmall
                Text(
                    title,
                    Modifier.heightIn(min = rememberTextLinesHeight(titleStyle, shape.titleLines)),
                    style = titleStyle,
                    textAlign = textAlign,
                    maxLines = shape.titleLines,
                    overflow = TextOverflow.Ellipsis,
                )
                val countStyle = MaterialTheme.typography.bodySmall
                if (loading) {
                    SkeletonText(countStyle, lines = shape.countLines, widthFraction = TILE_COUNT_SKELETON_FRACTION)
                } else {
                    Text(
                        text = count,
                        modifier = Modifier.heightIn(min = rememberTextLinesHeight(countStyle, shape.countLines)),
                        style = countStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = textAlign,
                        maxLines = shape.countLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (shape.stacked) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = TILE_PADDING, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                icon()
                texts(Alignment.CenterHorizontally)
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = TILE_PADDING, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(TILE_ICON_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon()
                texts(Alignment.Start)
            }
        }
    }
}

/** 入口块的左右内边距、图标边长、图标与文字的间距（原型值）。 */
private val TILE_PADDING = 12.dp
private val TILE_ICON = 20.dp
private val TILE_ICON_GAP = 8.dp

/**
 * 「谁在看」一行（原型 `.whorow`）：头像叠放 +「N 在看　N 看过　N 想看」+ ›。人数来自条目详情，不另发请求。
 *
 * 一行放下：三组人数按自身宽度排，头像只用剩下的地方——最多 [RECENT_COLLECTORS] 个，放不下就少画，
 * 再放不下就不画；字号极大连人数都放不下时，按整组换行（组内不断开、也没有悬在行尾的分隔号）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchingRow(
    subject: SubjectBo,
    overview: CommunityOverview,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pairs = watchingPairs(subject)
    val style = MaterialTheme.typography.bodySmall
    val measurer = rememberTextMeasurer()
    val avatars = if (loading) List(RECENT_COLLECTORS) { null } else overview.recentCollectors.map { it.user.avatarUrl }
    PressArea(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(Modifier.padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            val avatarCount = with(LocalDensity.current) {
                val pairsWidth = pairs.sumOf { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width } +
                    WatchingPairGap.roundToPx() * (pairs.size - 1)
                // 行内依次是：头像、间距 12、人数、间距 12、› 20
                val room = constraints.maxWidth - pairsWidth - 20.dp.roundToPx() - 12.dp.roundToPx() * 2
                stackedAvatarsFitting(room, StackAvatarSize.roundToPx()).coerceAtMost(avatars.size)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StackedAvatars(avatars.take(avatarCount), StackAvatarSize, ringColor = MaterialTheme.colorScheme.surfaceContainer)
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(WatchingPairGap),
                ) {
                    pairs.forEach { pair ->
                        Text(pair, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                    }
                }
                Icon(
                    painter = painterResource(R.drawable.chevron_right_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}


/**
 * 三组「人数 + 状态」：「1134 在看」「13199 看过」「3596 想看」，每组一个不折行的 [Text]，只在组与组之间换行。
 * 数字 `onSurface` 500，动词按条目类型（看 / 读 / 听 / 玩）。
 */
@Composable
private fun watchingPairs(subject: SubjectBo): List<AnnotatedString> {
    val emphasis = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    return WatchingTypes.map { type ->
        val number = subject.collectionCountOf(type).toString()
        val raw = stringResource(R.string.community_watching_pair, number, stringResource(type.labelResFor(subject.type)))
        val start = raw.indexOf(number)
        buildAnnotatedString {
            append(raw)
            if (start >= 0) addStyle(emphasis, start, start + number.length)
        }
    }
}

/**
 * 谁在看面板（原型截图 07、`sheetHtml('collects')`）：标题 → 状态 chip 横排 → 收藏者分段列表（翻页）。
 * 「最近」的计数来自社区 tab 已经取过的不筛总数（[CommunityOverview.collectorsTotal]），五态来自条目详情。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectorsSheet(
    subject: SubjectBo,
    recentTotal: Int?,
    onDismiss: () -> Unit,
    onUserClick: (String) -> Unit,
) {
    val viewModel: SubjectCollectorsViewModel = koinViewModel { parametersOf(subject.id) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    ScrollableModalSheet(
        onDismiss = onDismiss,
        contentKey = subject.id,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        SheetTitleRow(stringResource(R.string.community_collectors_title))
        CollectionFilterChips(
            subject = subject,
            selected = state.type,
            allLabel = stringResource(R.string.community_collectors_recent),
            countOf = { type -> type?.let { subject.collectionCountOf(it) } ?: recentTotal },
            onSelect = viewModel::selectType,
        )
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            CollectorList(
                state = state.collectors,
                gridState = gridState,
                subject = subject,
                onRetry = viewModel::retry,
                onLoadMore = viewModel::loadMore,
                onUserClick = onUserClick,
            )
        }
    }
}

/**
 * 收藏者头像网格（与个人页收藏的角色、好友同一种样式 [PersonGridItem]）：
 * 列数同个人页的人物网格（[GridCell.Person]），头像 + 昵称 +「1 小时前在看」+「★ 8」（星级受公共评分开关控制）。
 */
@Composable
private fun CollectorList(
    state: PagedList<SubjectCollectorBo>,
    gridState: LazyGridState,
    subject: SubjectBo,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onUserClick: (String) -> Unit,
) {
    val endText = stringResource(R.string.community_list_end)
    val error = state.error
    when (sectionStateOf(state)) {
        // 收藏者还没到：同一套列数铺满几行人物卡占位（与真实卡同一布局、同样画必有的副行），不转圈
        SectionState.LOADING -> AdaptiveGridSkeleton(
            rows = COLLECTOR_SKELETON_ROWS,
            cell = GridCell.Person,
        ) { PersonGridItemSkeleton(subtitleLines = COLLECTOR_SUBTITLE_LINES) }
        SectionState.ERROR -> if (error != null) RetryError(error, onRetry = onRetry, Modifier.fillMaxWidth(), compact = true)
        SectionState.EMPTY -> EmptyLine(stringResource(R.string.community_collectors_empty))
        SectionState.CONTENT -> ContentGrid(
            cell = GridCell.Person,
            modifier = Modifier.fillMaxSize(),
            state = gridState,
            bottom = 16.dp,
        ) {
            items(state.items, key = { it.user.id }) { collector ->
                CollectorCell(collector, subject, onUserClick)
            }
            loadMoreFooter(state, onLoadMore, endText)
        }
    }
}

@Composable
private fun CollectorCell(collector: SubjectCollectorBo, subject: SubjectBo, onUserClick: (String) -> Unit) {
    val user = collector.user
    val action = stringResource(
        R.string.community_collector_action,
        relativeTimeText(collector.updatedAt),
        stringResource(collector.type.labelResFor(subject.type)),
    )
    val rate = collector.rate.takeIf { othersRatingStars(it, LocalAppFeatures.current) != null }
    PersonGridItem(
        title = displayNameOf(user),
        imageUrl = user.avatarUrl,
        onClick = { onUserClick(userKeyOf(user.username, user.id)) },
        // 动作与星级各占一行（拼成「1 小时前在看、★ 8」一行的话，三列时会被截断）
        subtitles = listOfNotNull(action, rate?.let { "★ $it" }),
        userId = user.id,
    )
}

/** 收藏状态筛选项 → 文案（谁在看、吐槽箱共用）：null 是 [allLabel]，五态的动词随条目类型（[labelResFor]）。 */
@Composable
private fun collectionFilterLabel(type: CollectionType?, subject: SubjectBo, allLabel: String): String =
    type?.let { stringResource(it.labelResFor(subject.type)) } ?: allLabel

/**
 * 谁在看面板按收藏状态筛的一排 chip：[allLabel] + 五态（文案见 [collectionFilterLabel]），
 * [countOf] 给出数字就跟在文字后；横向可滑。吐槽箱用同一组选项的下拉 chip（[MenuChip]）。
 */
@Composable
private fun CollectionFilterChips(
    subject: SubjectBo,
    selected: CollectionType?,
    allLabel: String,
    countOf: (CollectionType?) -> Int?,
    onSelect: (CollectionType?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = CommunityGutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CollectionFilters.forEach { type ->
            val label = collectionFilterLabel(type, subject, allLabel)
            val count = countOf(type)
            SelectableChip(
                label = count?.let { stringResource(R.string.label_with_count, label, it) } ?: label,
                selected = type == selected,
                onClick = { onSelect(type) },
            )
        }
    }
}
