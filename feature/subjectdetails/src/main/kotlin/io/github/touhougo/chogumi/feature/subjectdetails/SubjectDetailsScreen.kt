package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.orEmpty
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.glass.GlassHeaderBar
import io.github.touhougo.chogumi.core.glass.GlassHeaderTabsFrame
import io.github.touhougo.chogumi.core.glass.GlassTabsHeight
import io.github.touhougo.chogumi.core.glass.GlassTabsPlaceholder
import io.github.touhougo.chogumi.core.glass.LocalGlassContentTop
import io.github.touhougo.chogumi.core.glass.rememberGlassHeaderFrameState
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.IndexCategory
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.staffPositionRows
import io.github.touhougo.chogumi.core.reader.CommentUserCard
import io.github.touhougo.chogumi.core.reader.rememberUserCardState
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.CollectionSheetHost
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkHost
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.composables.ChogumiPullToRefreshBox
import io.github.touhougo.chogumi.core.ui.composables.CollapsibleHeaderState
import io.github.touhougo.chogumi.core.ui.composables.ContentLoadingIndicator
import io.github.touhougo.chogumi.core.ui.composables.FabClearance
import io.github.touhougo.chogumi.core.ui.composables.FactRowsSheet
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.HeaderTabsFrame
import io.github.touhougo.chogumi.core.ui.composables.HeaderTopAppBar
import io.github.touhougo.chogumi.core.ui.composables.IndexCollect
import io.github.touhougo.chogumi.core.ui.composables.PageLink
import io.github.touhougo.chogumi.core.ui.composables.PageMoreMenu
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.ScrollableModalSheet
import io.github.touhougo.chogumi.core.ui.composables.SkeletonCrossfade
import io.github.touhougo.chogumi.core.ui.composables.TabPager
import io.github.touhougo.chogumi.core.ui.composables.labelResFor
import io.github.touhougo.chogumi.core.ui.composables.rememberCollapsibleHeaderState
import io.github.touhougo.chogumi.core.ui.composables.rememberTabPagerState
import io.github.touhougo.chogumi.core.ui.moderation.ModerationHost
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 收藏管理 FAB：三种视觉状态（内部四态，见 [CollectionFabState]）在同一枚按钮内互斥呈现。
 *
 * - 忙（[CollectionFabState.busy]：LOADING / SAVING）：内嵌形变加载指示器，颜色与按钮内容一致；
 *   读屏念「不可用」。M3 的 FAB 没有禁用态，点击照常送到 ViewModel，由它按同一个 busy 判定不响应。
 *   SAVING 保留上一状态文案（collection 未变），填补保存网络往返的反馈空窗。
 * - READY / SETTLED_NO_DATA：加号（未收藏）或对勾（已收藏）+ 状态文案。
 *
 * 过渡按画出来的样子（[CollectionFabLook]）走：忙闲、图标、文案任何一项变了都换一次内容，
 * 「+ 收藏管理」→「✓ 在看」这种忙闲不变、只换文案的变化也一样过渡，不瞬间跳变。
 * 形变走 spatial spring：motion.md「Spatial」表列 size 属几何属性，overshoot 是预期的。
 */
@Composable
internal fun CollectionFab(
    state: CollectionFabState,
    collection: UserCollectionBo?,
    /** 条目类型：FAB 动词按类型分（labelResFor 唯一真源）；null 回落 ANIME 通用词 */
    subjectType: SubjectType?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier.then(if (state.busy) Modifier.semantics { disabled() } else Modifier),
    ) {
        CollectionFabLabel(state, collection, subjectType)
    }
}

/** 收藏按钮里的那一行（图标 + 状态文案）：M3 的 [CollectionFab] 与玻璃版的 [GlassCollectionButton] 共用，过渡见 [CollectionFab]。 */
@Composable
internal fun CollectionFabLabel(state: CollectionFabState, collection: UserCollectionBo?, subjectType: SubjectType?) {
    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val sizeSpec = MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()
    AnimatedContent(
        targetState = CollectionFabLook(
            busy = state.busy,
            collected = collection != null,
            label = collection?.type?.labelResFor(subjectType ?: SubjectType.ANIME) ?: R.string.edit_collection,
        ),
        transitionSpec = {
            (scaleIn(animationSpec = spatialSpec) togetherWith scaleOut(animationSpec = spatialSpec))
                .using(SizeTransform(clip = false) { _, _ -> sizeSpec })
        },
        label = "collectionFabContent",
    ) { look ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (look.busy) {
                ContentLoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current)
            } else {
                Icon(
                    painter = painterResource(if (look.collected) R.drawable.check_24 else R.drawable.add_24),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = stringResource(look.label),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** 收藏 FAB 画出来的样子：[CollectionFab] 的过渡以它为目标，每一帧的内容只从它取。 */
private data class CollectionFabLook(val busy: Boolean, val collected: Boolean, @StringRes val label: Int)

/**
 * 详情页的真实首屏：[SubjectDetailsHeader] 进收起部分、[SubjectDetailsTabGroup] 吸在顶栏下面、[content] 是选项卡内容，
 * 都经 [HeaderTabsFrame]。骨架 [SubjectDetailsPageSkeleton] 与它对应，几何同源。
 */
@Composable
internal fun SubjectDetailsFirstScreen(
    subject: SubjectBo,
    topPadding: Dp,
    features: AppFeatures,
    sections: List<SubjectDetailsSection>,
    selectedSection: SubjectDetailsSection,
    onSelectSection: (SubjectDetailsSection) -> Unit,
    onInfoClick: () -> Unit,
    onTagClick: (SearchCriteria) -> Unit,
    headerState: CollapsibleHeaderState,
    glass: SubjectGlass?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val header: @Composable () -> Unit = {
        SubjectDetailsHeader(
            subject = subject,
            topPadding = topPadding,
            features = features,
            // 评分小标点了去统计 tab；统计 tab 不可见（评分不足 10 人）时小标不可点
            onRatingClick = { onSelectSection(SubjectDetailsSection.STATS) }
                .takeIf { SubjectDetailsSection.STATS in sections },
            onInfoClick = onInfoClick,
            onTagClick = onTagClick,
        )
    }
    // 玻璃效果开着时换成玻璃外框（SubjectDetailsGlass.kt），头部与内容不变
    if (glass != null) {
        GlassHeaderTabsFrame(
            state = glass.frame,
            glass = glass.layer,
            header = header,
            tabs = { GlassSubjectTabs(glass.layer, sections, selectedSection, onSelectSection) },
            modifier = modifier,
            content = content,
        )
        return
    }
    HeaderTabsFrame(
        state = headerState,
        header = header,
        tabs = {
            SubjectDetailsTabGroup(
                sections = sections,
                selected = selectedSection,
                onSelect = onSelectSection,
            )
        },
        modifier = modifier,
        content = content,
    )
}

/**
 * 详情页整页骨架：经同一个外框 [HeaderTabsFrame]，头部与切换栏各取自真实组件的同一个框架，
 * 见 [SubjectDetailsHeaderSkeleton]、[SubjectDetailsTabGroupSkeleton]。
 */
@Composable
internal fun SubjectDetailsPageSkeleton(
    topPadding: Dp,
    features: AppFeatures,
    glass: SubjectGlass?,
    modifier: Modifier = Modifier,
) {
    if (glass != null) {
        GlassHeaderTabsFrame(
            state = rememberGlassHeaderFrameState(),
            glass = glass.layer,
            header = { SubjectDetailsHeaderSkeleton(topPadding = topPadding) },
            tabs = { GlassTabsPlaceholder(glass.layer, GlassTabsHeight) },
            modifier = modifier,
        ) {}
        return
    }
    HeaderTabsFrame(
        state = rememberCollapsibleHeaderState(),
        header = { SubjectDetailsHeaderSkeleton(topPadding = topPadding) },
        tabs = { SubjectDetailsTabGroupSkeleton(features = features) },
        modifier = modifier,
    ) {}
}

/**
 * 作品详情页（原型 `detailScreen`）。
 *
 * 布局层级：
 * - 透明顶栏，头部收起时渐渐填上页面 `surface`、显示标题（[HeaderTabsFrame]）；
 * - 头部：封面模糊底色、封面 + 标题 / 原名 / 元信息行 / 评分与资料小标，一行标签；
 * - 选项卡：概览 / 第 2 个 tab（章节 / 单行本 / 曲目，按规格表）/ 人物 / 社区 / 统计；吸在顶栏下面，
 *   内容左右滑切换（[TabPager]），每个选项卡自己一个列表、各自保留滚动位置；
 * - 右下角收藏管理 FAB（[CollectionFab]）：点按打开收藏面板（[CollectionSheetHost]），滚动时保持可见。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SubjectDetailsScreen(
    subjectId: Int,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    onSubjectClick: (Int) -> Unit,
    onCharacterClick: (Int) -> Unit,
    onPersonClick: (Int) -> Unit,
    onTagClick: (SearchCriteria) -> Unit,
    onRelationsSeeAll: () -> Unit,
    onUserClick: (String) -> Unit,
    onOpenTopics: () -> Unit,
    onOpenReviews: () -> Unit,
    onOpenIndexes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SubjectDetailsViewModel = koinViewModel { parametersOf(subjectId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 写操作（收藏、标进度、贴贴）的登录门：未登录点了去登录页
    val loginGate = rememberLoginGate(isLoggedIn, onLogin)
    // 社区 tab 的数据（吐槽第一页、三个入口数量、谁在看头像）：社区页第一次出现在屏幕上才发请求（见下方翻页）。
    val communityViewModel: SubjectCommunityViewModel = koinViewModel { parametersOf(subjectId) }
    val community by communityViewModel.uiState.collectAsStateWithLifecycle()
    // 吐槽楼层的长按菜单与用户卡片共用的写操作通道（报告、加好友、解除好友、绝交）
    val moderate = loginGate.guard(communityViewModel.moderation::perform)

    val snackbarHostState = remember { SnackbarHostState() }
    // 一个提示条宿主接两个来源：详情（保存 / 刷新 / 章节写入失败）与社区 tab（贴贴失败）
    FailureNoticeEffect(viewModel.failures, snackbarHostState)
    FailureNoticeEffect(communityViewModel.failures, snackbarHostState)
    var synopsisExpanded by rememberSaveable(subjectId) { mutableStateOf(false) }
    var peopleGroup by rememberSaveable(subjectId) { mutableStateOf(PeopleGroup.CHARACTERS) }
    // 人物 tab 哪几组已「展开全部」、制作人员哪些职位已「+N」展开（行逐项懒排，状态跨列表项共用，所以提到这里）
    var peopleShowAll by rememberSaveable(subjectId) { mutableStateOf(emptySet<PeopleGroup>()) }
    var openStaffPositions by rememberSaveable(subjectId) { mutableStateOf(emptySet<String>()) }
    var infoSheetOpen by rememberSaveable(subjectId) { mutableStateOf(false) }
    var collectorsSheetOpen by rememberSaveable(subjectId) { mutableStateOf(false) }
    val commentCard = rememberUserCardState()
    // 网格长按弹出的单集信息弹层（纯 UI 开关，无 VM 对应物）
    // 长按打开的单集面板：存集 id（可保存），从面板进用户主页再返回时面板还在
    var detailEpisodeId by rememberSaveable(subjectId) { mutableStateOf<Int?>(null) }
    // 「今天」向下传：EpisodeTracking 是纯函数、不读时钟。
    val today by viewModel.today.collectAsStateWithLifecycle()

    // 头部收起程度：顶栏据此填色、显示标题，切换栏收起后与顶栏同色
    val headerState = rememberCollapsibleHeaderState()
    // 设置里的「玻璃效果」开着时顶栏、切换栏、收藏按钮换成玻璃版（SubjectDetailsGlass.kt）；关着时是 null，下面都走原来的分支
    val glass = rememberSubjectGlass()
    // 每个选项卡自己的列表位置，切走再切回停在原处。由页面持有：从别处跳进某个 tab（「全部角色」）时能把它拉回顶部
    // 同一组列表状态时沿用同一张表：选项卡的页翻走也留在组合里，每次重组换一张新表会让每一页都跟着重组
    val sectionLists = SubjectDetailsSection.entries.associateWith { rememberLazyListState() }.let { lists -> remember(lists) { lists } }

    // 保存成功不弹提示条：收藏面板退场、FAB 显示新状态，界面本身已表达结果；只有失败走 failures。
    // 收藏面板开着时提示条画在面板上（页面的在面板下面，看不见）：同一个提示条状态，只换画的位置。
    val collectionSheetShown = uiState.collectionSheet.isShown || uiState.posterMarks.sheet.isShown

    val subject = uiState.subject
    val title = subject?.let { displayNameOf(it) }.orEmpty()
    // 入口判定、资料面板与人物 tab 共享状态实例已派生的分类结果。
    val info = uiState.infoPartition
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 各区块的数据跟着各自的读取换实例，与它无关的状态变化（点 tab、开面板）不让读它的页重组
    val episodes = remember(uiState.episodes) { AsyncList(uiState.episodes, viewModel::retryEpisodes) }
    val characters = remember(uiState.characters) { AsyncList(uiState.characters, viewModel::retryCharacters) }
    val persons = remember(uiState.persons) { AsyncList(uiState.persons, viewModel::retryPersons) }
    // 人物 tab 的列表项与首块共用这一份职位分行
    val staffRows = remember(persons.items, subject?.type) { staffPositionRows(persons.items, subject?.type) }
    val relations = remember(uiState.relations) { AsyncList(uiState.relations, viewModel::retryRelated) }

    // 关联作品的海报能长按标记（标的是关联的那一部，不是本页的作品）
    PosterMarkHost(uiState.posterMarks, viewModel.posterMarks, isLoggedIn, onLogin, snackbarHostState, modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                val actions: @Composable RowScope.() -> Unit = {
                    PageMoreMenu(
                        PageLink(title, BgmWeb.subject(subjectId)),
                        IndexCollect(IndexTarget(IndexCategory.SUBJECT, subjectId), loginGate),
                    )
                }
                if (glass != null) {
                    GlassHeaderBar(title = title, glass = glass.layer, frame = glass.frame, onBack = onBack, actions = actions)
                } else {
                    HeaderTopAppBar(title = title, frame = headerState, onBack = onBack, actions = actions)
                }
            },
            floatingActionButton = {
                // 进页即出：只要条目已到就渲染 FAB，收藏未到位时内嵌 loading。
                // 点了开不开弹层由 ViewModel.openEditSheet 按 FAB 状态决定。
                if (subject != null && glass != null) {
                    GlassCollectionButton(
                        layer = glass.layer,
                        state = uiState.fabState,
                        collection = uiState.collection.valueOrNull(),
                        subjectType = subject.type,
                        onClick = { loginGate.run { viewModel.openEditSheet() } },
                    )
                } else if (subject != null) {
                    CollectionFab(
                        state = uiState.fabState,
                        collection = uiState.collection.valueOrNull(),
                        subjectType = subject.type,
                        onClick = { loginGate.run { viewModel.openEditSheet() } },
                    )
                }
            },
            snackbarHost = { if (!collectionSheetShown) SnackbarHost(snackbarHostState) },
        ) { padding ->
            ChogumiPullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = {
                    viewModel.refresh()
                    communityViewModel.refresh()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                // 整页骨架 → 内容之间快速淡入，不硬切（M3 transitions.md:43）。
                SkeletonCrossfade(
                    loading = uiState.isLoading && subject == null,
                    skeleton = {
                        SubjectDetailsPageSkeleton(
                            topPadding = statusBarHeight,
                            features = uiState.features,
                            glass = glass,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                ) {
                when {
                    subject != null -> {
                        val sections = uiState.visibleSections
                        // 选中项（ViewModel 的 selectedSection）是唯一真源：点 tab、点评分小标、「全部角色」都只改它，翻页跟过去；
                        // 手指左右翻页落定后才回写
                        val pager = rememberTabPagerState(
                            selected = sections.indexOf(uiState.selectedSection),
                            pageCount = sections.size,
                            onSelect = { viewModel.selectSection(sections[it]) },
                        )
                        SubjectDetailsFirstScreen(
                            subject = subject,
                            topPadding = statusBarHeight,
                            features = uiState.features,
                            sections = sections,
                            selectedSection = sections[pager.shownPage],
                            onSelectSection = viewModel::selectSection,
                            onInfoClick = { infoSheetOpen = true },
                            onTagClick = onTagClick,
                            headerState = headerState,
                            glass = glass,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            // 每页一个选项卡；页里的局部状态（章节区段选到哪一段等）按选项卡存档，翻走再翻回还在
                            TabPager(pager, Modifier.fillMaxSize(), key = { sections[it].name }) { page ->
                                val section = sections[page]
                                if (section == SubjectDetailsSection.COMMUNITY) {
                                    LaunchedEffect(communityViewModel) { communityViewModel.start() }
                                }
                                LazyColumn(
                                    state = sectionLists.getValue(section),
                                    modifier = Modifier.fillMaxSize(),
                                    // 玻璃外框里内容从 tab 胶囊下面滚过，上边留出它（LocalGlassContentTop）；不在玻璃外框里时是 0
                                    contentPadding = PaddingValues(top = LocalGlassContentTop.current, bottom = navBarHeight + FabClearance),
                                ) {
                                    when (section) {
                                        SubjectDetailsSection.OVERVIEW -> item(key = "section-overview") {
                                            SubjectOverviewSection(
                                                synopsis = firstScreenSlots(subject).synopsis,
                                                synopsisExpanded = synopsisExpanded,
                                                onSynopsisToggle = { synopsisExpanded = !synopsisExpanded },
                                                characters = characters,
                                                relations = relations,
                                                subject = subject,
                                                series = uiState.series,
                                                onCharacterClick = onCharacterClick,
                                                onSubjectClick = onSubjectClick,
                                                onCharactersSeeAll = {
                                                    peopleGroup = PeopleGroup.CHARACTERS
                                                    sectionLists.getValue(SubjectDetailsSection.PEOPLE).requestScrollToItem(0)
                                                    viewModel.selectSection(SubjectDetailsSection.PEOPLE)
                                                },
                                                onRelationsSeeAll = onRelationsSeeAll,
                                            )
                                        }

                                        SubjectDetailsSection.EPISODES -> item(key = "section-episodes") {
                                            EpisodesSection(
                                                subjectType = subject.type,
                                                episodes = episodes,
                                                watchStates = uiState.episodeWatchStates,
                                                today = today,
                                                pendingCommit = uiState.pendingCommit,
                                                expectedEpisodes = subject.eps,
                                                offerMarkDone = uiState.offersMarkDone,
                                                onWatchUpTo = loginGate.guard(viewModel::onWatchUpTo),
                                                onToggleSpecial = loginGate.guard(viewModel::onToggleSpecial),
                                                onMarkDone = loginGate.guard(viewModel::markDone),
                                                onEpisodeLongPress = { detailEpisodeId = it.id },
                                            )
                                        }

                                        SubjectDetailsSection.VOLUMES -> item(key = "section-volumes") {
                                            VolumesSection(relations = relations, onSubjectClick = onSubjectClick)
                                        }

                                        SubjectDetailsSection.TRACKS -> tracksSection(episodes = episodes)

                                        SubjectDetailsSection.PEOPLE -> peopleSection(
                                            characters = characters,
                                            persons = persons,
                                            staffRows = staffRows,
                                            otherStaffFacts = info.otherStaffFacts,
                                            selected = peopleGroup,
                                            onSelect = { peopleGroup = it },
                                            showAll = peopleShowAll,
                                            onShowAll = { peopleShowAll = peopleShowAll + it },
                                            openPositions = openStaffPositions,
                                            onTogglePosition = { position ->
                                                openStaffPositions = if (position in openStaffPositions) openStaffPositions - position else openStaffPositions + position
                                            },
                                            onCharacterClick = onCharacterClick,
                                            onPersonClick = onPersonClick,
                                        )

                                        // 吐槽楼层逐条进列表，滑到底续取（见 communitySection）
                                        SubjectDetailsSection.COMMUNITY -> communitySection(
                                            subject = subject,
                                            community = community,
                                            onRetryComments = communityViewModel::retry,
                                            onLoadMoreComments = communityViewModel::loadMore,
                                            onOpenReviews = onOpenReviews,
                                            onOpenTopics = onOpenTopics,
                                            onOpenIndexes = onOpenIndexes,
                                            onOpenCollectors = { collectorsSheetOpen = true },
                                            onUserClick = onUserClick,
                                            onAvatarClick = commentCard::open,
                                            onReact = loginGate.guard(communityViewModel::react),
                                            onModerate = moderate,
                                            onSelectCommentFilter = communityViewModel::selectCommentFilter,
                                        )

                                        SubjectDetailsSection.STATS -> item(key = "section-stats") {
                                            // 只在统计 tab 组合时收集：各张卡只在这时计算
                                            val stats by viewModel.stats.collectAsStateWithLifecycle()
                                            StatsSection(
                                                tab = stats,
                                                onSubjectClick = onSubjectClick,
                                                onRetryRatingSplit = viewModel::retryRatingSplit,
                                                onRetryRecs = viewModel::retryRecs,
                                                onRetryRecentCollectTimes = viewModel::retryRecentCollectTimes,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        RetryError(
                            // 条目流落定且没有内容才走到这里，error 必有值；兜底只防流异常结束
                            uiState.error ?: Failure.Unknown,
                            onRetry = viewModel::refresh,
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
                }
            }
        }
    }

    // 资料面板只读取同一次分类的作品资料。
    if (infoSheetOpen && subject != null) {
        FactRowsSheet(
            title = stringResource(R.string.detail_info),
            rows = info.workFacts,
            onDismiss = { infoSheetOpen = false },
        )
    }

    CommentUserCard(commentCard, community.comments.items, onUserClick, moderate)

    // 谁在看面板：收藏者按状态筛、翻页（面板自己的 ViewModel）
    if (collectorsSheetOpen && subject != null) {
        CollectorsSheet(
            subject = subject,
            recentTotal = community.overview.valueOrNull()?.collectorsTotal,
            onDismiss = { collectorsSheetOpen = false },
            onUserClick = onUserClick,
        )
    }

    // 收藏面板：保存成功后滑下去；不管怎么关的，滑完都回到 closeEditSheet
    CollectionSheetHost(
        state = uiState.collectionSheet,
        work = uiState.collectionWork,
        today = today,
        snackbar = snackbarHostState,
        onRetryEpisodes = viewModel::retryEpisodes,
        onSave = viewModel::onSaveCollection,
        onRemove = viewModel::removeCollection,
        onClose = viewModel::closeEditSheet,
    )

    // 网格长按：单集面板（正文 + 评论区）。共用 ScrollableModalSheet：进子页不关面板，返回原样还在。
    uiState.episodes.orEmpty().firstOrNull { it.id == detailEpisodeId }?.let { episode ->
        ScrollableModalSheet(onDismiss = { detailEpisodeId = null }, contentKey = episode.id) {
            EpisodeDetailSheet(episode = episode, loginGate = loginGate, onUserClick = onUserClick)
        }
    }

    // 吐槽楼层长按「加好友」「绝交」经它（作品吐槽没有报告类型，面板在这里不会打开，接上是同一种写法）
    ModerationHost(community.moderation, communityViewModel.moderation)
}

