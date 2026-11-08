package io.github.touhougo.chogumi.feature.monodetails

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.orEmpty
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.monoTypeSpec
import io.github.touhougo.chogumi.core.model.monoWorksPreview
import io.github.touhougo.chogumi.core.reader.CollapsibleSynopsis
import io.github.touhougo.chogumi.core.reader.CommentUserCard
import io.github.touhougo.chogumi.core.reader.ReplyComposerSheet
import io.github.touhougo.chogumi.core.reader.ReplyFab
import io.github.touhougo.chogumi.core.reader.ReplyTarget
import io.github.touhougo.chogumi.core.reader.floorCommentsBody
import io.github.touhougo.chogumi.core.reader.rememberFloorJump
import io.github.touhougo.chogumi.core.reader.rememberUserCardState
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkHost
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.ChogumiPullToRefreshBox
import io.github.touhougo.chogumi.core.ui.composables.CAROUSEL_SKELETON_COUNT
import io.github.touhougo.chogumi.core.ui.composables.CarouselPerson
import io.github.touhougo.chogumi.core.ui.composables.CarouselPersonSkeleton
import io.github.touhougo.chogumi.core.ui.composables.CarouselRow
import io.github.touhougo.chogumi.core.ui.composables.FabClearance
import io.github.touhougo.chogumi.core.ui.composables.FactRowsSheet
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.HeaderTopAppBar
import io.github.touhougo.chogumi.core.ui.composables.IndexCollect
import io.github.touhougo.chogumi.core.ui.composables.PageLink
import io.github.touhougo.chogumi.core.ui.composables.PageMoreMenu
import io.github.touhougo.chogumi.core.ui.composables.PosterCardSkeleton
import io.github.touhougo.chogumi.core.ui.composables.PosterCardStyle
import io.github.touhougo.chogumi.core.ui.composables.PosterCardWidth
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SectionHeader
import io.github.touhougo.chogumi.core.ui.composables.SkeletonCrossfade
import io.github.touhougo.chogumi.core.ui.composables.labelRes
import io.github.touhougo.chogumi.core.ui.composables.rememberScrolledFill
import io.github.touhougo.chogumi.core.ui.image.ImageViewer
import io.github.touhougo.chogumi.core.ui.moderation.ModerationHost
import io.github.touhougo.chogumi.feature.monodetails.ui.MonoHeader
import io.github.touhougo.chogumi.feature.monodetails.ui.MonoHeaderSkeleton
import io.github.touhougo.chogumi.feature.monodetails.ui.MonoTopBarActions
import io.github.touhougo.chogumi.feature.monodetails.ui.MonoWorkPosterCard
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 角色与人物详情（一套页面，[monoType] 决定端点）：透明顶栏随滚动变实（[HeaderTopAppBar]），右上角收藏与全部资料；
 * 往下是立绘头部、简介，然后各自加载的区块：关联人物 / 角色 → 出演 / 参与作品 → 代表角色（仅人物）→ 收录目录入口 → 吐槽箱。
 * 区块各是一拍 Load，三态只经 sectionStateOf 判定，单块失败只在这一块重试；详情本身取不到才整页报错。
 * 右下「回复」FAB 写新的一条吐槽，点吐槽箱的楼层回复那一层：与帖子页同一个编辑器面板（[ReplyComposerSheet]），未登录先去登录。
 */
/**
 * 吐槽箱第一层楼在列表里的下标（楼层跳转按它换算）：头部、简介、关联、作品、代表角色、目录入口、吐槽箱标题各占一项，
 * 没有内容的区块也占一项、不画东西，楼层的位置才固定。
 */
private const val MONO_FIRST_FLOOR_ITEM = 7

@Composable
fun MonoDetailsScreen(
    monoType: MonoType,
    id: Int,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    onSubjectClick: (Int) -> Unit,
    /** 点了某个角色或人物：关联、CV、代表角色都走这一个。 */
    onMonoClick: (MonoType, Int) -> Unit,
    onIndexClick: (Int) -> Unit,
    onUserClick: (String) -> Unit,
    onWorksSeeAll: () -> Unit,
    onRelationsSeeAll: () -> Unit,
    /** 人物的「代表角色、全部」；角色页没有这一区。 */
    onCharactersSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: MonoDetailsViewModel = koinViewModel { parametersOf(monoType, id) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val loginGate = rememberLoginGate(isLoggedIn, onLogin)
    val snackbarHostState = remember { SnackbarHostState() }
    FailureNoticeEffect(viewModel.failures, snackbarHostState)
    // 社区可见性真源（见 LocalAppFeatures）：专注模式下整块吐槽区不渲染。
    val features = LocalAppFeatures.current
    LaunchedEffect(features.community) { if (features.community) viewModel.startComments() }
    var showAllIndexesSheet by rememberSaveable { mutableStateOf(false) }
    var showInfoSheet by rememberSaveable { mutableStateOf(false) }
    // 立绘全屏看图开着没有：由屏幕持有，头部那一行滚出列表或重建时不丢
    var viewingPortrait by rememberSaveable { mutableStateOf(false) }
    var summaryExpanded by rememberSaveable { mutableStateOf(false) }
    // 海报横排露哪几部、什么顺序只由 monoWorksPreview 决定
    val works = uiState.works.orEmpty()
    val previewWorks = remember(works, uiState.monoType, features.publicRatings) {
        monoWorksPreview(uiState.monoType, works, features.publicRatings)
    }
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val lazyListState = rememberLazyListState()
    val topBarFill = rememberScrolledFill(lazyListState)
    val surface = CommentSurface.Mono(uiState.monoType, uiState.id)
    val jump = rememberFloorJump(lazyListState, firstReplyItem = MONO_FIRST_FLOOR_ITEM)
    val userCard = rememberUserCardState()
    val reply = loginGate.guard { target: ReplyTarget -> viewModel.composer.open(target) }
    val moderate = loginGate.guard(viewModel.moderation::perform)

    uiState.coverImage?.takeIf { viewingPortrait }?.let { portrait ->
        ImageViewer(listOf(portrait), startIndex = 0, onDismiss = { viewingPortrait = false })
    }

    // 参与作品的海报能长按标记
    PosterMarkHost(uiState.posterMarks, viewModel.posterMarks, isLoggedIn, onLogin, snackbarHostState, modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                HeaderTopAppBar(
                    title = uiState.title,
                    fill = topBarFill,
                    onBack = onBack,
                    actions = {
                        MonoTopBarActions(
                            loaded = uiState.name.isNotBlank(),
                            isCollected = uiState.isCollected,
                            collectCount = uiState.collectCount,
                            isCollectionLoading = uiState.isCollectionLoading,
                            onToggleCollection = loginGate.guard(viewModel::toggleCollection),
                        )
                        PageMoreMenu(
                            PageLink(uiState.title, BgmWeb.mono(monoType, id)),
                            IndexCollect(IndexTarget(monoTypeSpec(monoType).indexCategory, id), loginGate),
                        )
                    },
                )
            },
            // 收藏面板开着时提示条画在面板上
            snackbarHost = { if (!uiState.posterMarks.sheet.isShown) SnackbarHost(snackbarHostState) },
            // 吐槽箱在页面最末：「回复」写新的一条吐槽（专注模式下吐槽箱不画，也就没有它）
            floatingActionButton = {
                if (features.community && uiState.details is Load.Ready) ReplyFab { reply.action(ReplyTarget.Topic) }
            },
        ) { padding ->
            ChogumiPullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                // 首次加载画整页骨架（头部 + 简介占位），与作品详情同一个 SkeletonCrossfade 交叉淡入
                val pageState = sectionStateOf(uiState.details) { false }
                SkeletonCrossfade(
                    loading = pageState == SectionState.LOADING,
                    skeleton = { MonoDetailsPageSkeleton(topPadding = statusBarHeight) },
                ) {
                when (pageState) {
                    // 整页失败（只在没有内容时才有）：内容区本身铺到顶栏下面（边到边），只有错误块按 Scaffold 留白避开顶栏，与作品详情一致
                    SectionState.ERROR -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        uiState.details.failureOrNull()?.let { RetryError(it, onRetry = viewModel::refresh, Modifier.fillMaxWidth()) }
                    }

                    else -> {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = navBarHeight + if (features.community) FabClearance else 32.dp),
                        ) {
                            // 1. 头部：渐变底 + 拱形立绘 + 右列（名字 / 身份行 / 事实、CV 与资料 chip）；收藏与 ⋮ 在顶栏右上角
                            item(key = "header") {
                                MonoHeader(
                                    title = uiState.title,
                                    originalName = uiState.name.takeIf { it.isNotBlank() && it != uiState.title },
                                    coverImage = uiState.coverImage,
                                    role = uiState.billingRole?.let { stringResource(it.labelRes()) },
                                    identity = monoIdentityParts(uiState.careers),
                                    facts = uiState.facts,
                                    voiceActor = uiState.voiceActor,
                                    topPadding = statusBarHeight,
                                    onVoiceActorClick = { onMonoClick(MonoType.PERSON, it) },
                                    onAllInfoClick = { showInfoSheet = true },
                                    onPortraitClick = { viewingPortrait = true },
                                )
                            }

                            // 2. 简介（折叠 3 行）
                            item(key = "summary") {
                                if (uiState.summary.isNotBlank()) {
                                    CollapsibleSynopsis(
                                        text = uiState.summary,
                                        expanded = summaryExpanded,
                                        onToggle = { summaryExpanded = !summaryExpanded },
                                    )
                                }
                            }

                            // 3. 关联人物 / 角色（排在作品上方；三态经 sectionStateOf；加载完成且为空才隐藏）
                            val relationsState = sectionStateOf(uiState.relations)
                            item(key = "relations") {
                                if (relationsState != SectionState.EMPTY) {
                                    MonoSection(
                                        title = stringResource(R.string.mono_relations_title),
                                        count = null,
                                        state = relationsState,
                                        failure = uiState.relations.failureOrNull(),
                                        onRetry = { viewModel.retryRelations() },
                                        onSeeAll = onRelationsSeeAll,
                                        skeleton = { CarouselPersonSkeleton() },
                                    ) {
                                        CarouselRow {
                                            items(uiState.relations.orEmpty(), key = { it.id }) { relation ->
                                                CarouselPerson(
                                                    name = displayNameOf(relation),
                                                    caption = relation.relation,
                                                    imageUrl = relation.image,
                                                    onClick = { onMonoClick(uiState.monoType, relation.id) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 4. 出演 / 参与作品：海报横排（带定位 / 职位徽标），「全部」进子页的列表
                            val worksState = sectionStateOf(uiState.works)
                            item(key = "works") {
                                if (worksState != SectionState.EMPTY) {
                                    MonoSection(
                                        title = stringResource(uiState.monoType.worksTitleRes()),
                                        count = works.size,
                                        state = worksState,
                                        failure = uiState.works.failureOrNull(),
                                        onRetry = { viewModel.retryWorks() },
                                        onSeeAll = onWorksSeeAll,
                                        skeleton = {
                                            CarouselRow(userScrollEnabled = false) {
                                                items(CAROUSEL_SKELETON_COUNT) { PosterCardSkeleton(PosterCardStyle.CAROUSEL, Modifier.width(PosterCardWidth)) }
                                            }
                                        },
                                    ) {
                                        CarouselRow {
                                            items(previewWorks, key = { it.id }) { work ->
                                                MonoWorkPosterCard(
                                                    work = work,
                                                    onClick = { onSubjectClick(work.id) },
                                                    modifier = Modifier.width(PosterCardWidth),
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 5. 代表角色（只有人物有，见 MonoTypeSpec.hasCastCharacters；角色页的区块恒为空）
                            // 预览被过滤到一个不剩但还有后页时仍算有内容：保留标题与「全部」，让用户可续取后页
                            val charactersState = sectionStateOf(uiState.characters) { it.items.isEmpty() && !uiState.hasMoreCharacters }
                            val characterPage = uiState.characters.valueOrNull()
                            item(key = "characters") {
                                if (charactersState != SectionState.EMPTY) {
                                    MonoSection(
                                        title = stringResource(R.string.mono_representative_characters),
                                        count = characterPage?.total,
                                        state = charactersState,
                                        failure = uiState.characters.failureOrNull(),
                                        onRetry = { viewModel.retryCharacters() },
                                        onSeeAll = onCharactersSeeAll.takeIf { uiState.hasMoreCharacters },
                                        skeleton = { CarouselPersonSkeleton() },
                                    ) {
                                        CarouselRow {
                                            items(characterPage?.items.orEmpty(), key = { it.id }) { character ->
                                                CarouselPerson(
                                                    name = displayNameOf(character),
                                                    caption = character.subjectName,
                                                    imageUrl = character.avatarUrl,
                                                    onClick = { onMonoClick(MonoType.CHARACTER, character.id) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 6. 收录本页的目录：一行入口（在吐槽箱上方），计数用服务端总数；首屏失败在原位重试
                            item(key = "indexes") {
                                MonoIndexesEntry(
                                    state = uiState.indexes,
                                    onOpen = { showAllIndexesSheet = true },
                                    onRetry = viewModel::retryIndexes,
                                )
                            }
                            // 7. 两层树状吐槽箱：属于社区内容，专注模式下整块不渲染（标题、骨架、
                            //    错误、空态与楼层一并消失，不留"暂无吐槽"的残迹）。楼层与日志、目录的留言同一套
                            //    （[floorCommentsBody]）：接口一次返回全量，逐条进 LazyColumn，点楼层回复那一层。
                            if (features.community) {
                                item(key = "comments-header") {
                                    SectionHeader(
                                        title = stringResource(R.string.mono_comments_title),
                                        count = uiState.comments.reading.threads.size.takeIf { it > 0 },
                                    )
                                }
                                floorCommentsBody(
                                    comments = uiState.comments,
                                    surface = surface,
                                    jump = jump,
                                    emptyText = R.string.community_comments_empty,
                                    onUserClick = onUserClick,
                                    onAvatarClick = userCard::open,
                                    onRetry = viewModel::retryComments,
                                    onReply = reply,
                                    onModerate = moderate,
                                )
                            }
                        }
                    }
                }
                }
            }

            CommentUserCard(userCard, uiState.comments.reading.threads, onUserClick, moderate)

            ReplyComposerSheet(uiState.composer, viewModel.composer, surface, loginGate) { postId ->
                jump.jumpToPost(viewModel.uiState.value.comments.reading, postId)
            }
            ModerationHost(uiState.moderation, viewModel.moderation)

            if (showInfoSheet) {
                FactRowsSheet(
                    title = stringResource(R.string.mono_all_info),
                    rows = uiState.infoRows,
                    onDismiss = { showInfoSheet = false },
                )
            }

            if (showAllIndexesSheet) {
                MonoIndexesSheet(
                    state = uiState.indexes,
                    onDismiss = { showAllIndexesSheet = false },
                    onRetry = viewModel::retryIndexes,
                    onLoadMore = viewModel::loadMoreIndexes,
                    onIndexClick = onIndexClick,
                )
            }
        }
    }
}

/**
 * 页面上的一个区块：标题行（标题 + 计数 +「全部」）+ 三态（[sectionStateOf] 已判过，EMPTY 不会进来）。
 * 加载中画调用方传入的 [skeleton]：与该区块真实内容同几何的占位。
 */
@Composable
private fun MonoSection(
    title: String,
    count: Int?,
    state: SectionState,
    failure: Failure?,
    onRetry: () -> Unit,
    onSeeAll: (() -> Unit)?,
    skeleton: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = title,
            count = count?.takeIf { state == SectionState.CONTENT },
            trailingText = stringResource(R.string.detail_all),
            onTrailingClick = onSeeAll?.takeIf { state == SectionState.CONTENT },
        )
        when (state) {
            SectionState.LOADING -> skeleton()
            SectionState.ERROR -> failure?.let { RetryError(it, onRetry = onRetry, Modifier.fillMaxWidth(), compact = true) }
            SectionState.CONTENT -> content()
            SectionState.EMPTY -> Unit
        }
    }
}

/** 头部身份行的各段：人物的职业（「歌手」「声优」，由 [MonoHeader] 用 MetaText 连起来）；没有职业（角色）时为 null，不画这一行。出场身份与收藏数见 [MonoHeader]。 */
@Composable
private fun monoIdentityParts(careers: List<String>): List<String>? =
    careers.takeIf { it.isNotEmpty() }?.map { careerLabel(it) }

/** 职业代号 → 文案（全仓唯一）；不认识的代号原样显示。 */
@Composable
private fun careerLabel(code: String): String = when (code) {
    "seiyu" -> stringResource(R.string.mono_career_seiyu)
    "artist" -> stringResource(R.string.mono_career_artist)
    "actor" -> stringResource(R.string.mono_career_actor)
    "mangaka" -> stringResource(R.string.mono_career_mangaka)
    "writer" -> stringResource(R.string.mono_career_writer)
    "illustrator" -> stringResource(R.string.mono_career_illustrator)
    "producer" -> stringResource(R.string.mono_career_producer)
    else -> code
}

/**
 * 角色 / 人物页的整页骨架：头部骨架 [MonoHeaderSkeleton] + 简介占位，与作品详情的整页骨架同一做法——
 * 只画首屏必然有的块；关联、作品、吐槽各分区等页面出来后由各自的分区骨架接着画。
 */
@Composable
private fun MonoDetailsPageSkeleton(topPadding: Dp) {
    Column(Modifier.fillMaxSize()) {
        MonoHeaderSkeleton(topPadding = topPadding)
        CollapsibleSynopsis(text = null, expanded = false, onToggle = {})
    }
}
