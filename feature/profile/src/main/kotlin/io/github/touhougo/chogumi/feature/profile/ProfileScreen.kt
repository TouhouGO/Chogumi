package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.ui.composables.ConfirmDialog
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.glass.AboveGlassBar
import io.github.touhougo.chogumi.core.glass.LocalGlassContentBottom
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.FloorReading
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.userKeyOf
import io.github.touhougo.chogumi.core.reader.TimelineDetailSheet
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkHost
import io.github.touhougo.chogumi.core.ui.color.rememberImageColor
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.common.LoginGate
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.composables.ChogumiPullToRefreshBox
import io.github.touhougo.chogumi.core.ui.composables.ContentGrid
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.GridCell
import io.github.touhougo.chogumi.core.ui.composables.GridPadding
import io.github.touhougo.chogumi.core.ui.composables.GridSpacing
import io.github.touhougo.chogumi.core.ui.composables.HeaderTabsFrame
import io.github.touhougo.chogumi.core.ui.composables.PageLink
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SelectionFabMenu
import io.github.touhougo.chogumi.core.ui.composables.TabPager
import io.github.touhougo.chogumi.core.ui.composables.formatIsoDate
import io.github.touhougo.chogumi.core.ui.composables.iconRes
import io.github.touhougo.chogumi.core.ui.composables.labelRes
import io.github.touhougo.chogumi.core.ui.composables.rememberCollapsibleHeaderState
import io.github.touhougo.chogumi.core.ui.composables.rememberTabPagerState
import io.github.touhougo.chogumi.core.ui.moderation.BlockConfirmDialog
import io.github.touhougo.chogumi.core.ui.moderation.ModerationHost
import io.github.touhougo.chogumi.core.ui.moderation.UnfriendConfirmDialog
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 「我的」主 tab：图标 + 文字（原型 `.tabsbar .cbg`），由 [ProfileTabGroup] 渲染成连通分段按钮。
 *
 * 次序以作品为中心：概览（简介与生涯统计）之后先是收藏的作品，再是动态，最后是好友。
 * 图标与全 App 同义：概览 info（同详情页「概览」）、收藏 bookmark（同「想看」、目录的收藏；星标在本 App 只表示评分）、
 * 动态 forum（同底栏「社区」）、好友 group。
 * 未选用描边图标、选中用实心图标（M3 toggle 按钮规定：选中至少两种属性变化，不只靠颜色）。
 */
internal enum class ProfileTab(val iconRes: Int, val selectedIconRes: Int, val labelRes: Int) {
    OVERVIEW(R.drawable.info_24, R.drawable.info_filled_24, R.string.profile_overview),
    SHELF(R.drawable.bookmark_24, R.drawable.bookmark_filled_24, R.string.profile_shelf),
    TIMELINE(R.drawable.forum_24, R.drawable.forum_filled_24, R.string.activity),
    FRIENDS(R.drawable.group_24, R.drawable.group_filled_24, R.string.profile_social),
}


/**
 * 功能可见性 → 可见的分段 tab（唯一判定点）。
 *
 * 专注模式隐藏「好友」[ProfileTab.FRIENDS]（那是别人的账号列表，属社区）；
 * **「动态」[ProfileTab.TIMELINE] 保留** —— 它是自己的记录，与公共评分/社区无关。
 * 界面只渲染本函数的结果，不得再叠一层筛除。
 */
private fun profileTabsFor(features: AppFeatures): List<ProfileTab> =
    if (features.community) ProfileTab.entries.toList()
    else ProfileTab.entries.filter { it != ProfileTab.FRIENDS }


/**
 * 个人页网格此刻排的是哪种单元（决定列数，见 [GridCell]）：好友是人物卡，收藏随分类的内容形态（[shelfContentOf]）；
 * 概览、动态都是整行占满的块，不受列数影响。
 */
private fun profileGridCellOf(tab: ProfileTab, shelf: ShelfState): GridCell = when (tab) {
    ProfileTab.FRIENDS -> GridCell.Person
    ProfileTab.SHELF -> shelfContentOf(shelf).cell
    ProfileTab.OVERVIEW, ProfileTab.TIMELINE -> GridCell.Poster
}

/** 个人页网格的行距：动态页的日期头、行各自带自己的上下间距（见 `timelineDay`），其余页用网格的行距。 */
private fun profileGridRowSpacingOf(tab: ProfileTab): Dp = if (tab == ProfileTab.TIMELINE) 0.dp else GridSpacing


/**
 * 我的：顶部背景 + 资料行 + 图标分段 tab + 各自独立的内容区。
 *
 * ## 顶部背景用头像取色
 *
 * Bangumi 没有用户横幅图——`chii_members` 与 `chii_memberfields` 都没有 banner/background 列，
 * 网页版那些背景图是全站静态装饰，不是用户数据。所以顶部用**头像取色**生成背景：把头像降采样后求均值
 * （详见 `rememberImageColor`），得到用户自己的代表色，再做成「色 → surface」的纵向渐变——
 * 占据顶部视觉、把资料行托起来，且数据真实。
 *
 * 取色未就绪或失败时回退到主题色，不会出现空白或闪烁。
 */
@Composable
fun ProfileScreen(
    /** 目标用户；null = 当前登录用户自己。 */
    username: String?,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onSettingsClick: () -> Unit,
    /** 顶栏「返回」：从别处推进来的页面有（看别人、或从头像 / 链接进自己的主页）；「我的」tab 本身为 null。 */
    onBack: (() -> Unit)?,
    onSubjectClick: (Int) -> Unit,
    /** 动态的作者、社交列表里的人、回复的作者。 */
    onUserClick: (String) -> Unit,
    onCharacterClick: (Int) -> Unit,
    onPersonClick: (Int) -> Unit,
    /** 数字行「日志」→ 日志列表页。参数是**目标用户键**：有用户名用用户名，没有时用 uid。 */
    onOpenBlogs: (String) -> Unit,
    /** 数字行「目录」→ 目录列表页，参数是目标用户键，语义同 [onOpenBlogs]。 */
    onOpenIndexes: (String, IndexListCounts) -> Unit,
    /** 生涯卡「完整统计」：参数是目标用户键（null = 自己）与卡上选中的类型。 */
    onOpenCareer: (String?, SubjectType) -> Unit,
    /** 他人主页「短消息」→ 站内写信给该用户，参数是目标用户键。 */
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ProfileViewModel = koinViewModel { parametersOf(username) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val user = uiState.user
    val targetUserKey = userKeyOf(user?.username ?: username, user?.id ?: 0)

    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    val detail = uiState.timelineDetail
    val loginGate = rememberLoginGate(isLoggedIn, onLogin)
    // 生涯卡的数据（本人：Room 统计流 + 同步；他人：服务端摘要 + 现拉汇总）。
    val careerViewModel: CareerViewModel = koinViewModel {
        parametersOf(username, SubjectType.ANIME, false)
    }
    val career by careerViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    FailureNoticeEffect(viewModel.failures, snackbarHostState)
    // 生涯同步失败（自动同步也算）：内容一直在，同一个提示条说明
    FailureNoticeEffect(careerViewModel.failures, snackbarHostState)
    // 收藏网格的海报能长按标记（看别人的收藏也能顺手标自己的）
    PosterMarkHost(uiState.posterMarks, viewModel.posterMarks, isLoggedIn, onLogin, snackbarHostState, modifier.fillMaxSize()) {
        ProfileContent(
            user = user,
            isProfileRefreshing = uiState.isRefreshing,
            error = uiState.error,
            timeline = timeline,
            onRefreshTimeline = viewModel::refreshTimeline,
            onLoadMoreTimeline = viewModel::loadMoreTimeline,
            onOpenTimeline = viewModel.timelineDetail::open,
            onDeleteTimeline = viewModel::deleteTimeline,
            shelf = uiState.shelf,
            social = uiState.social,
            onOpenShelf = viewModel::openShelf,
            onTimelineShown = viewModel::openTimeline,
            onSelectShelf = { viewModel.selectShelf(it) },
            onRefreshShelf = { viewModel.selectShelf(it, refresh = true) },
            onLoadMoreShelf = viewModel::loadMoreShelf,
            onOpenSocial = viewModel::openSocial,
            onSelectSocial = viewModel::selectSocial,
            onLoadMoreSocial = viewModel::loadMoreSocial,
            onRefreshSocial = viewModel::refreshSocial,
            career = career,
            onRetryCareer = careerViewModel::retryOwner,
            onUserClick = onUserClick,
            onRetry = viewModel::retry,
            onRefreshProfile = viewModel::refreshProfile,
            isSelf = viewModel.isSelf,
            isLoggedIn = isLoggedIn,
            loginGate = loginGate,
            socialOps = uiState.socialOps,
            snackbarHostState = snackbarHostState,
            snackbarOnSheet = uiState.posterMarks.sheet.isShown,
            onAddFriend = viewModel::addFriend,
            onRemoveFriend = viewModel::removeFriend,
            onBlock = viewModel::blockUser,
            onUnblock = viewModel::unblockUser,
            onOpenReport = viewModel::openReport,
            onMessage = { onMessage(targetUserKey) },
            onSettingsClick = onSettingsClick,
            onBack = onBack,
            onOpenBlogs = { onOpenBlogs(targetUserKey) },
            onOpenIndexes = { counts -> onOpenIndexes(targetUserKey, counts) },
            // 本人传 null：统计页自己决定看自己的数据；他人传用户键。
            onOpenCareer = { type -> onOpenCareer(if (viewModel.isSelf) null else targetUserKey, type) },
            onSubjectClick = onSubjectClick,
            onCharacterClick = onCharacterClick,
            onPersonClick = onPersonClick,
        )
    }

    detail.id?.let { id ->
        TimelineDetailSheet(
            item = timeline.items.firstOrNull { it.id == id },
            state = detail,
            onDismiss = viewModel.timelineDetail::close,
            onReact = loginGate.guard(viewModel.timelineDetail::react),
            composer = viewModel.timelineDetail.composer,
            onReply = loginGate.guard(viewModel.timelineDetail::reply),
            onSendReply = loginGate.guard { draft: ComposerDraft, token: String, land: (FloorReading, Int) -> Unit -> viewModel.timelineDetail.send(draft, token, land) },
            onModerate = loginGate.guard(viewModel.moderation::perform),
            onRetry = viewModel.timelineDetail::retry,
            onSelectSort = viewModel.timelineDetail::selectSort,
            onToggleOpOnly = viewModel.timelineDetail::toggleOpOnly,
            onUser = onUserClick,
            onSubject = onSubjectClick,
        )
    }
    // 报告面板（⋮「报告」与动态回复楼层的「报告」共用一份），排在动态详情之后，从详情里打开时叠在它上面
    ModerationHost(uiState.moderation, viewModel.moderation)
}

@Composable
internal fun ProfileContent(
    user: UserBo?,
    isProfileRefreshing: Boolean,
    error: Failure?,
    timeline: PagedList<TimelineItemBo>,
    onRefreshTimeline: () -> Unit,
    onLoadMoreTimeline: () -> Unit,
    onOpenTimeline: (TimelineItemBo) -> Unit,
    onDeleteTimeline: (Long) -> Unit,
    shelf: ShelfState,
    social: SocialState,
    /** 第一次打开收藏 / 好友 tab 时取数（重复调用不重复取）。 */
    onOpenShelf: () -> Unit,
    /** 「动态」第一次出现在屏幕上：取这个人的动态。 */
    onTimelineShown: () -> Unit,
    onSelectShelf: (ShelfCategory) -> Unit,
    onRefreshShelf: (ShelfCategory) -> Unit,
    onLoadMoreShelf: () -> Unit,
    onOpenSocial: () -> Unit,
    onSelectSocial: (SocialDirection) -> Unit,
    onLoadMoreSocial: () -> Unit,
    onRefreshSocial: () -> Unit,
    career: CareerState,
    /** 他人：生涯的资料取不到时，卡片位置上的「重试」。 */
    onRetryCareer: () -> Unit,
    onUserClick: (String) -> Unit,
    /** 收藏网格的三个点击出口：条目 / 角色 / 人物；null = 该详情页还没有，网格据此不挂点击。 */
    onSubjectClick: (Int) -> Unit,
    onCharacterClick: (Int) -> Unit,
    onPersonClick: (Int) -> Unit,
    /** 失败后重试当前用户的资料加载。 */
    onRetry: () -> Unit,
    onRefreshProfile: () -> Unit,
    /** 看的是不是自己；决定收藏、动态与社交内容的语义和权限。 */
    isSelf: Boolean,
    /** 观看者是否已登录；未登录时操作行消失、⋮ 的写操作带去登录。 */
    isLoggedIn: Boolean,
    loginGate: LoginGate,
    /** 社交操作唯一状态源；看自己时不参与渲染。 */
    socialOps: SocialOpsState,
    /** 操作提示的统一宿主。 */
    snackbarHostState: SnackbarHostState,
    /** 收藏面板（收藏网格海报长按的「收藏管理」）开着：提示条画在面板上。 */
    snackbarOnSheet: Boolean,
    onAddFriend: () -> Unit,
    onRemoveFriend: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onOpenReport: () -> Unit,
    /** 他人主页「短消息」：站内写信给该用户。 */
    onMessage: () -> Unit,
    modifier: Modifier = Modifier,
    onSettingsClick: () -> Unit,
    /** 顶栏「返回」；「我的」tab 本身为 null。 */
    onBack: (() -> Unit)?,
    /** 数字行「日志」。 */
    onOpenBlogs: () -> Unit,
    /** 数字行「目录」。 */
    onOpenIndexes: (IndexListCounts) -> Unit,
    /** 生涯卡「完整统计」，参数是卡上当前选中的类型。 */
    onOpenCareer: (SubjectType) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // 待确认删除的动态 ID；非空即弹确认框。**纯 UI 状态，无 VM 对应物**——
    // 它只表示"用户点了删除但还没确认"，不是任何真源的副本。
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    // 收藏分类 FAB 菜单是否展开；纯 UI 状态。
    var fabExpanded by rememberSaveable { mutableStateOf(false) }
    // 他人主页 ⋮ 菜单是否展开；纯 UI 状态。
    // 资料面板（加入时间 / 所在地 / 主页）是否打开；纯 UI 状态。
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    // 待确认绝交的用户昵称；非空即弹确认框。纯 UI 状态。
    var blocking by rememberSaveable { mutableStateOf<String?>(null) }
    // 待解除好友确认；非空即弹确认框。纯 UI 状态。
    var unfriending by rememberSaveable { mutableStateOf(false) }
    // 写操作（加 / 取消好友、绝交、报告）的登录门：未登录点了去登录
    // 可见分段由 LocalAppFeatures 一处决定（专注模式去掉「好友」，保留「动态」）。
    val features = LocalAppFeatures.current
    val tabs = remember(features) { profileTabsFor(features) }
    val avatarUrl = user?.avatarUrl
    // 顶栏标题、绝交确认、报告面板共用的称呼：昵称为空时用用户名。
    val displayName = user?.let(::displayNameOf).orEmpty()
    val accent by rememberImageColor(avatarUrl?.takeIf { it.isNotBlank() })

    // 模式切换可能让当前停着的分段不再可见（如停在「好友」）：回到首个可见分段，
    // 否则 selectedTab 会越界或指向一个不存在的分段。
    LaunchedEffect(tabs) {
        if (selectedTab > tabs.lastIndex) selectedTab = 0
    }

    val activeTab = tabs.getOrNull(selectedTab) ?: tabs.first()
    // 选中项 selectedTab 是唯一真源：点分段、点数字行「好友」都只改它，翻页跟过去；手指左右翻页落定后才回写。
    // 按钮组与翻页的同步在 core:ui 的 TabPager 里
    val pager = rememberTabPagerState(
        selected = tabs.indexOf(activeTab),
        pageCount = tabs.size,
        onSelect = { selectedTab = it },
    )
    val shownTab = tabs[pager.shownPage]
    // 下拉刷新时内容留在原地，只有指示器在转：收藏与好友读列表自己的在途标记
    val isRefreshing = when (activeTab) {
        ProfileTab.TIMELINE -> timeline.isRefreshing
        ProfileTab.SHELF -> shelfContentOf(shelf).list.isRefreshing
        ProfileTab.FRIENDS -> social.current.isRefreshing
        ProfileTab.OVERVIEW -> isProfileRefreshing
    }
    val handleRefresh: () -> Unit = {
        when (activeTab) {
            ProfileTab.TIMELINE -> onRefreshTimeline()
            ProfileTab.SHELF -> onRefreshShelf(shelf.shownCategory)
            ProfileTab.FRIENDS -> onRefreshSocial()
            ProfileTab.OVERVIEW -> onRefreshProfile()
        }
    }

    // 头部收起程度：顶栏据此填色、显示称呼，分段收起后与顶栏同色
    val headerState = rememberCollapsibleHeaderState()
    var boxTopPx by remember { mutableIntStateOf(0) }
    var tabBottomPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val tabOffsetPx = (tabBottomPx - boxTopPx).coerceAtLeast(0)
    val tabBottomDp = with(density) { tabOffsetPx.toDp() }

    // 资料头数字行有哪些项；null = 资料未到（画骨架）。这一行恒占一行，资料到了不改高度（见 [ProfileNumberLine]）。
    val numberLineItems = user?.let { profileNumberLineOf(it, features.community) }
    val othersActionBarVisible = !isSelf && isLoggedIn && features.community

    val shelfEndText = stringResource(R.string.load_more_end_shelf)
    val socialEndText = stringResource(R.string.load_more_end_social)
    val timelineEndText = stringResource(R.string.load_more_end_timeline)

    // 下拉刷新包住整页（头部全开、内容在顶上时才拉得出来），指示器从分段按钮下方开始，不遮挡顶栏身份信息。
    ChogumiPullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = handleRefresh,
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                boxTopPx = coordinates.positionInRoot().y.toInt()
            },
        indicatorPadding = PaddingValues(top = tabBottomDp),
    ) {
        HeaderTabsFrame(
            state = headerState,
            header = {
                // 头部各块之间、头部与分段之间都是列表项间距（原型各块是同一列表里的项）
                Column(Modifier.fillMaxWidth().padding(bottom = GridSpacing)) {
                    ProfileHeader(
                        user = user,
                        accent = accent,
                        joinedDate = user?.joinedAt?.let(::formatIsoDate)?.ifEmpty { null },
                        onOpenInfo = { infoOpen = true }.takeIf { user != null && !profileInfoOf(user).isEmpty },
                    )
                    // 资料头数字行（好友、日志、目录）：0 的项不出现；资料未到是骨架，全为 0 留一行空白——高度恒定，下面不挪位置。
                    ProfileNumberLine(
                        items = numberLineItems,
                        onSelect = { item ->
                            when (item) {
                                is ProfileNumberItem.FRIENDS -> {
                                    // 好友 tab 的下标由 [profileTabsFor] 决定；专注模式下该项本就不出现。
                                    val idx = tabs.indexOf(ProfileTab.FRIENDS)
                                    if (idx >= 0) selectedTab = idx
                                }
                                is ProfileNumberItem.BLOGS -> onOpenBlogs()
                                is ProfileNumberItem.INDEXES -> onOpenIndexes(item.counts)
                            }
                        },
                        // 原型 `.pstats{margin:-6px 0 -4px -10px}`：数字与头像左缘对齐（行自带 10dp 左内边距），
                        // 上下少留可点区域的留白。
                        modifier = Modifier.padding(
                            start = GridPadding - ProfileNumberLineInset,
                            end = GridPadding,
                            top = GridSpacing - 6.dp,
                            bottom = GridSpacing - 4.dp,
                        ),
                    )
                    // 他人主页操作行：只在他人、已登录、非专注时出现（专注隐藏整个好友语义）。
                    if (othersActionBarVisible) {
                        OthersActionBar(
                            user = user,
                            ops = socialOps,
                            onAddFriend = { loginGate.run(onAddFriend) },
                            onUnfriendRequest = { loginGate.run { unfriending = true } },
                            onUnblock = onUnblock,
                            onMessage = { loginGate.run(onMessage) },
                            modifier = Modifier.padding(horizontal = GridPadding),
                        )
                    }
                }
            },
            tabs = {
                ProfileTabGroup(
                    tabs = tabs,
                    selected = shownTab,
                    modifier = Modifier
                        .padding(horizontal = GridPadding)
                        .onGloballyPositioned { coordinates ->
                            // 下拉刷新只在头部全开时拉得出来，指示器的位置按全开时的分段底边算
                            if (headerState.collapsedFraction == 0f) {
                                val bottom = coordinates.positionInRoot().y + coordinates.size.height
                                if (bottom > 0) {
                                    tabBottomPx = bottom.toInt()
                                }
                            }
                        },
                    onSelect = { selectedTab = tabs.indexOf(it) },
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            // 资料头已出、正文确实失败时才整段报错；否则由各 tab 自行表达空态。
            if (user == null && error != null) {
                Box(Modifier.fillMaxSize().padding(horizontal = GridPadding, vertical = GridSpacing)) {
                    // 整页错误块只有重试；退出登录的唯一入口在设置页
                    RetryError(failure = error, onRetry = onRetry, modifier = Modifier.fillMaxWidth())
                }
                return@HeaderTabsFrame
            }
            // 每个分段一页：各自一张网格、各自的滚动位置与分页，翻走再翻回停在原处
            TabPager(pager, Modifier.fillMaxSize(), key = { tabs[it].name }) { page ->
                val tab = tabs[page]
                // 收藏、好友、动态第一次出现在屏幕上（点过去或翻过去）才取数：没理由为没看过的 tab 付流量。
                // 依赖里带 username，登录后资料就绪会自动补一次加载；重复调用不重复取。
                when (tab) {
                    ProfileTab.SHELF -> LaunchedEffect(user?.username) { if (user != null) onOpenShelf() }
                    ProfileTab.FRIENDS -> LaunchedEffect(user?.username) { if (user != null) onOpenSocial() }
                    ProfileTab.TIMELINE -> LaunchedEffect(user?.username) { if (user != null) onTimelineShown() }
                    ProfileTab.OVERVIEW -> Unit
                }
                // 动态的合并与分组按动态列表算一次；只在动态 tab 上才算
                val timelineDays = remember(timeline.items, tab) {
                    if (tab == ProfileTab.TIMELINE) timelineDaysOf(timeline.items) else emptyList()
                }
                val gridState = rememberLazyGridState()
                ContentGrid(
                    // 列数随这一页画的单元（[profileGridCellOf]），由 core:ui 的 GridCell 按屏宽推出。
                    cell = profileGridCellOf(tab, shelf),
                    modifier = Modifier.fillMaxSize(),
                    state = gridState,
                    top = GridSpacing,
                    bottom = LocalGlassContentBottom.current,
                    rowSpacing = profileGridRowSpacingOf(tab),
                ) {
                    when (tab) {
                        ProfileTab.OVERVIEW -> item(span = { GridItemSpan(maxLineSpan) }) {
                            OverviewTab(
                                career = career,
                                publicRatings = features.publicRatings,
                                onOpenCareer = onOpenCareer,
                                onRetryCareer = onRetryCareer,
                            )
                        }
                        ProfileTab.TIMELINE -> timelineItems(
                            user = user,
                            isSelf = isSelf,
                            state = timeline,
                            days = timelineDays,
                            endText = timelineEndText,
                            onLoadMore = onLoadMoreTimeline,
                            onRetry = onRefreshTimeline,
                            onOpen = onOpenTimeline,
                            // 先记下待删 ID 弹确认框，确认后才真发请求——删除不可撤销
                            onDelete = { deleting = it },
                            onSubject = onSubjectClick,
                        )
                        ProfileTab.SHELF -> shelfItems(
                            state = shelf,
                            isSelf = isSelf,
                            onCharacterPersonTabChange = { onSelectShelf(ShelfCategory.CharacterPerson(it)) },
                            onSubjectClick = onSubjectClick,
                            onCharacterClick = onCharacterClick,
                            onPersonClick = onPersonClick,
                            onLoadMore = { onLoadMoreShelf() },
                            onRefresh = { onSelectShelf(shelf.shownCategory) },
                            endText = shelfEndText,
                        )
                        ProfileTab.FRIENDS -> socialItems(
                            state = social,
                            isSelf = isSelf,
                            ownerName = displayName,
                            onSelect = onSelectSocial,
                            onLoadMore = onLoadMoreSocial,
                            onRefresh = onRefreshSocial,
                            onUserClick = onUserClick,
                            endText = socialEndText,
                        )
                    }
                }
            }
        }

        // 顶栏叠在横幅上，头部收起时连状态栏一起填色（画在内容之后，盖住收上去的头部）；头部收满后再往上滚就让位给分段。
        ProfileTopBar(
            title = displayName,
            frame = headerState,
            isSelf = isSelf,
            onSettingsClick = onSettingsClick,
            onBack = onBack,
            link = PageLink(displayName, profilePageUrlOf(user)),
            onBlockRequest = { loginGate.run { blocking = displayName } },
            onOpenReport = { loginGate.run(onOpenReport) },
        )

        // FAB 与提示条停在玻璃底栏上面（玻璃关着、推进来的页面就是整页）
        AboveGlassBar {
            // 收藏 tab 的右下角 FAB 菜单：切换六个分类（原型 .fabwrap 在手机根层，
            // 故放在 PullToRefresh 的 BoxScope 里、与内容同级）。跟着按钮组此刻高亮的那一项出现。
            if (shownTab == ProfileTab.SHELF) {
                ShelfFabMenu(
                    expanded = fabExpanded,
                    onExpandedChange = { fabExpanded = it },
                    selected = shelf.shownCategory.fabSlot,
                    onSelect = {
                        fabExpanded = false
                        onSelectShelf(it)
                    },
                )
            }

            // 社交操作与刷新失败的统一提示；收藏面板开着时画在面板上，这里不画
            if (!snackbarOnSheet) {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    // 删除确认。动作**不可撤销**，所以必须确认后才发请求。
    deleting?.let { id ->
        ConfirmDialog(
            target = id,
            confirmLabel = stringResource(R.string.delete),
            onConfirm = onDeleteTimeline,
            onDismiss = { deleting = null },
            text = stringResource(R.string.delete_activity_confirm),
            destructive = true,
        )
    }

    blocking?.let { nickname -> BlockConfirmDialog(nickname, nickname, onConfirm = { onBlock() }, onDismiss = { blocking = null }) }

    // 解除好友确认：防误触（「已是好友」再点先确认）。
    if (unfriending) UnfriendConfirmDialog(target = Unit, onConfirm = { onRemoveFriend() }, onDismiss = { unfriending = false })

    // 资料面板（简介与资料）：入口是资料头 ID 右侧的 ⓘ。
    if (infoOpen && user != null) {
        ProfileInfoSheet(user = user, onUserClick = onUserClick, onDismiss = { infoOpen = false })
    }
}

/** 收藏 tab 右下角切换分类的 FAB 菜单：六个分类按 [ShelfCategory.entries] 的顺序。 */
@Composable
internal fun BoxScope.ShelfFabMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selected: ShelfCategory,
    onSelect: (ShelfCategory) -> Unit,
) {
    SelectionFabMenu(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        options = ShelfCategory.entries,
        selected = selected,
        iconRes = { it.fabIconRes() },
        label = { stringResource(it.labelRes) },
        onSelect = onSelect,
        collapsedDescription = stringResource(R.string.profile_shelf),
        itemTestTag = { it.fabOptionTestTag },
    )
}
