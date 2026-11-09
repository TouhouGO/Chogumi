
package io.github.touhougo.chogumi.shell


import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import io.github.touhougo.chogumi.core.model.IndexCategory
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SignedInAccount
import io.github.touhougo.chogumi.core.model.TopicType
import io.github.touhougo.chogumi.core.model.indexCategoryOf
import io.github.touhougo.chogumi.core.model.monoTypeSpec
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.LocalIndexPicker
import io.github.touhougo.chogumi.core.ui.common.LocalUserCareerCard
import io.github.touhougo.chogumi.core.ui.common.navigation.AppNavDisplay
import io.github.touhougo.chogumi.core.ui.common.navigation.AppNavigator
import io.github.touhougo.chogumi.core.ui.common.navigation.PageSheetRegistry
import io.github.touhougo.chogumi.core.ui.common.navigation.ProgressEntryRequests
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import io.github.touhougo.chogumi.core.ui.tour.GestureTour
import io.github.touhougo.chogumi.feature.calendar.CalendarScreen
import io.github.touhougo.chogumi.feature.community.AllGroupsScreen
import io.github.touhougo.chogumi.feature.community.BlockedUsersScreen
import io.github.touhougo.chogumi.feature.community.CommunityScreen
import io.github.touhougo.chogumi.feature.community.GroupScreen
import io.github.touhougo.chogumi.feature.community.MutedGroupsScreen
import io.github.touhougo.chogumi.feature.community.MyTopicsScreen
import io.github.touhougo.chogumi.feature.explore.ExploreScreen
import io.github.touhougo.chogumi.feature.explore.SearchScreen
import io.github.touhougo.chogumi.feature.home.HomeScreen
import io.github.touhougo.chogumi.feature.login.LoginScreen
import io.github.touhougo.chogumi.feature.login.SignInPrompt
import io.github.touhougo.chogumi.feature.messages.PmChatKey
import io.github.touhougo.chogumi.feature.messages.PmConversationScreen
import io.github.touhougo.chogumi.feature.messages.PmWriteScreen
import io.github.touhougo.chogumi.feature.monodetails.MonoDetailsScreen
import io.github.touhougo.chogumi.feature.monodetails.MonoRelationsListScreen
import io.github.touhougo.chogumi.feature.monodetails.MonoWorksScreen
import io.github.touhougo.chogumi.feature.monodetails.PersonCharactersScreen
import io.github.touhougo.chogumi.feature.profile.BlogDetailsScreen
import io.github.touhougo.chogumi.feature.profile.BlogListScreen
import io.github.touhougo.chogumi.feature.profile.CareerStatsScreen
import io.github.touhougo.chogumi.feature.profile.IndexDetailsScreen
import io.github.touhougo.chogumi.feature.profile.IndexListCounts
import io.github.touhougo.chogumi.feature.profile.IndexListScreen
import io.github.touhougo.chogumi.feature.profile.IndexPickerSheet
import io.github.touhougo.chogumi.feature.profile.ProfileScreen
import io.github.touhougo.chogumi.feature.profile.UserCareerCard
import io.github.touhougo.chogumi.feature.progress.ProgressScreen
import io.github.touhougo.chogumi.feature.settings.AboutScreen
import io.github.touhougo.chogumi.feature.settings.ProfileSettingsScreen
import io.github.touhougo.chogumi.feature.settings.SettingsPageScreen
import io.github.touhougo.chogumi.feature.settings.SettingsScreen
import io.github.touhougo.chogumi.feature.subjectdetails.EpisodeScreen
import io.github.touhougo.chogumi.feature.subjectdetails.SubjectDetailsScreen
import io.github.touhougo.chogumi.feature.subjectdetails.SubjectIndexesScreen
import io.github.touhougo.chogumi.feature.subjectdetails.SubjectRelationsScreen
import io.github.touhougo.chogumi.feature.subjectdetails.SubjectReviewsScreen
import io.github.touhougo.chogumi.feature.subjectdetails.SubjectTopicsScreen
import io.github.touhougo.chogumi.feature.subjectdetails.TopicScreen
import io.github.touhougo.chogumi.feature.timeline.TimelineScreen

/** 页面区：当前返回栈交给 [NavDisplay]，转场只用 core:ui 的唯一定义（ChogumiTransitions.kt）。 */
@Composable
fun AppEntries(
    navigator: AppNavigator,
    bottomPadding: Dp,
    account: SignedInAccount?,
    sheets: PageSheetRegistry,
    progressEntryRequests: ProgressEntryRequests,
) {
    // 页面在测量时读这份状态而不是建页时的快照：底栏高度随系统导航条变化（切换手势 / 三键导航）时，
    // 已经建好的页面也跟着重排
    val currentBottomPadding = rememberUpdatedState(bottomPadding)
    val entries = remember(navigator, account, progressEntryRequests) {
        appEntries(navigator::navigate, navigator::goBack, account, progressEntryRequests, bottomPadding = { currentBottomPadding.value })
    }
    val userCareerCard = remember(navigator, account) { userCareerCardOf(navigator::navigate, account) }
    CompositionLocalProvider(LocalUserCareerCard provides userCareerCard, LocalIndexPicker provides indexPicker) {
        AppNavDisplay(navigator, entries, sheets)
    }
}

/**
 * 「这个用户键是不是我自己」的唯一判定：指向当前账号时给 null（按自己的页面打开），否则原样。
 * 从楼层头像、动态、链接进自己的主页与进「我的」tab 是同一个页面，不会被当成他人显示加好友 / 绝交；
 * 用户卡片里自己的生涯卡也按自己的数据画。
 */
private fun ownUserKeyOrNull(account: SignedInAccount?, userKey: String?): String? = userKey?.takeUnless { account?.isMe(it) == true }

/** 页面 ⋮ 里「收录」打开的面板（[LocalIndexPicker] 的唯一提供值）。 */
private val indexPicker: @Composable (IndexTarget, () -> Unit) -> Unit = { target, onDismiss -> IndexPickerSheet(target, onDismiss) }

/** 探索页的搜索语义只在接线层变成 Route。 */
internal fun exploreSearchRoute(criteria: SearchCriteria, title: String?, openFilters: Boolean): Route.Search =
    Route.Search(criteria = criteria, title = title, openFilters = openFilters)

/** 探索页的放送表语义只在接线层变成 Route。 */
internal fun exploreCalendarRoute(): Route = Route.Calendar

/** 目录目标只在接线层变成页面 Route；类别反查复用 core:model 的唯一映射。 */
internal fun indexTargetRoute(target: IndexTarget): Route = when (target.category) {
    IndexCategory.SUBJECT -> Route.SubjectDetails(target.id)
    IndexCategory.CHARACTER, IndexCategory.PERSON ->
        Route.MonoDetails(MonoType.entries.first { monoTypeSpec(it).indexCategory == target.category }, target.id)
    IndexCategory.EPISODE -> Route.Episode(target.id)
    IndexCategory.BLOG -> Route.BlogDetails(target.id)
    IndexCategory.GROUP_TOPIC, IndexCategory.SUBJECT_TOPIC ->
        Route.Topic(TopicType.entries.first { indexCategoryOf(it) == target.category }, target.id)
}

/** 用户卡片里的生涯卡（[LocalUserCareerCard] 的唯一提供值）：自己的按自己的数据，点卡片进完整统计。 */
private fun userCareerCardOf(navigate: (Route) -> Unit, account: SignedInAccount?): @Composable (String) -> Unit = { username ->
    UserCareerCard(username = ownUserKeyOrNull(account, username), onOpenCareer = { name, type -> navigate(Route.UserCareer(name, type)) })
}

/**
 * 路由 → 页面，全 App 唯一的接线处。页面只收语义回调（点了哪个条目、哪个用户），去哪一页在这里决定。
 * 没有页面的路由一进就崩，`AppEntriesTest` 守着每种路由都接上了。
 *
 * [bottomPadding] 是底栏的高度：只有顶级页让出它（底栏收起也不让出高度，见 [TopLevelNavigationBar]）；
 * 推进来的页面没有底栏，自己处理系统导航条。
 */
internal fun appEntries(
    navigate: (Route) -> Unit,
    goBack: () -> Unit,
    account: SignedInAccount?,
    progressEntryRequests: ProgressEntryRequests,
    bottomPadding: () -> Dp,
): (NavKey) -> NavEntry<NavKey> {
    val isLoggedIn = account != null
    val toLogin = { navigate(Route.Login) }
    val toSettings = { navigate(Route.Settings) }
    val toSubject = { id: Int -> navigate(Route.SubjectDetails(id)) }
    val toUser = { username: String -> navigate(Route.UserProfile(username)) }
    val toMono = { type: MonoType, id: Int -> navigate(Route.MonoDetails(type, id)) }
    val toCharacter = { id: Int -> toMono(MonoType.CHARACTER, id) }
    val toPerson = { id: Int -> toMono(MonoType.PERSON, id) }
    val toIndex = { id: Int -> navigate(Route.IndexDetails(id)) }
    val toBlog = { id: Int -> navigate(Route.BlogDetails(id)) }
    val toGroup = { name: String -> navigate(Route.Group(name)) }
    // 让出底栏的同时把这段记为已消费：底栏已经含系统导航条，页里的 Scaffold、imePadding 不再把它算第二遍
    // （否则底部多出一段导航条高度，FAB 离底栏不是 16dp，键盘弹出时上方空出一截）。
    val aboveBottomBar = BottomBarPadding(bottomPadding).let { Modifier.padding(it).consumeWindowInsets(it) }
    // 未登录时首页、进度、「我的」三个 tab 没有游客可看的内容：整页换成同一个登录提示，「登录」按钮打开登录页（可以返回）。
    // 未登录的界面全 App 只此一种，页面自己不画未登录分支。
    val signIn: @Composable (title: Int, message: Int) -> Unit = { title, message ->
        SignInPrompt(
            title = title,
            message = message,
            onLogin = toLogin,
            onSettingsClick = toSettings,
            onBack = null,
            modifier = aboveBottomBar,
        )
    }

    // 自己的空间（顶级目的地）与别人的空间是同一个页面；推进来的才有返回，也不让出底栏（推进来的页面没有底栏）
    val profile: @Composable (username: String?, onBack: (() -> Unit)?, modifier: Modifier) -> Unit = { username, onBack, modifier ->
        ProfileScreen(
            username = username,
            isLoggedIn = isLoggedIn,
            onLogin = toLogin,
            onSettingsClick = toSettings,
            onBack = onBack,
            onSubjectClick = toSubject,
            onUserClick = toUser,
            onCharacterClick = toCharacter,
            onPersonClick = toPerson,
            onOpenBlogs = { navigate(Route.UserBlogs(it)) },
            onOpenIndexes = { name, counts -> navigate(Route.UserIndexes(name, counts.created, counts.collected)) },
            onOpenCareer = { name, type -> navigate(Route.UserCareer(name, type)) },
            onMessage = { navigate(Route.PmWrite(it)) },
            modifier = modifier,
        )
    }

    return entryProvider {
        // ---- 顶级目的地 ----
        entry<Route.Home> {
            if (isLoggedIn) {
                HomeScreen(
                    onSubjectClick = toSubject,
                    // 入口请求（类型，null 为全部）与切到进度 tab 在这一处一起做。
                    onProgress = { type ->
                        progressEntryRequests.open(type)
                        navigate(Route.Progress)
                    },
                    onFeedClick = { navigate(Route.Feed) },
                    modifier = aboveBottomBar,
                )
            } else {
                signIn(R.string.home, R.string.sign_in_prompt_home)
            }
        }
        entry<Route.Explore> {
            ExploreScreen(
                isLoggedIn = isLoggedIn,
                onLogin = toLogin,
                onSubjectClick = toSubject,
                onOpenSearch = { criteria, title, openFilters ->
                    navigate(exploreSearchRoute(criteria, title, openFilters))
                },
                onOpenCalendar = { navigate(exploreCalendarRoute()) },
                modifier = aboveBottomBar,
            )
        }
        entry<Route.Progress> {
            if (isLoggedIn) {
                ProgressScreen(
                    onSubjectClick = toSubject,
                    modifier = aboveBottomBar,
                )
            } else {
                signIn(R.string.progress, R.string.sign_in_prompt_progress)
            }
        }
        entry<Route.Community> {
            CommunityScreen(
                isLoggedIn = isLoggedIn,
                onLogin = toLogin,
                onUserClick = toUser,
                onGroupClick = toGroup,
                onAllGroups = { navigate(Route.AllGroups) },
                onMyTopics = { navigate(Route.MyTopics) },
                onConversation = { navigate(Route.PmConversation(it)) },
                onWrite = { navigate(Route.PmWrite()) },
                modifier = aboveBottomBar,
            )
        }
        entry<Route.Profile> {
            if (isLoggedIn) profile(null, null, aboveBottomBar) else signIn(R.string.profile, R.string.sign_in_prompt_profile)
        }

        // ---- 独立页 ----
        entry<Route.Login> {
            LoginScreen(onBack = goBack, onSettingsClick = toSettings)
        }
        entry<Route.Feed> {
            TimelineScreen(
                isLoggedIn = isLoggedIn,
                onLogin = toLogin,
                onSubjectClick = toSubject,
                onUserClick = toUser,
                onBack = goBack,
                // 列表铺满整页、内容不叠系统导航条
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
            )
        }
        entry<Route.Calendar> {
            CalendarScreen(isLoggedIn = isLoggedIn, onLogin = toLogin, onBack = goBack, onSubjectClick = toSubject)
        }
        entry<Route.Settings> {
            SettingsScreen(
                onBack = goBack,
                isLoggedIn = isLoggedIn,
                onProfile = { navigate(Route.ProfileSettings) },
                onSection = { navigate(Route.SettingsPage(it)) },
                onGestureTour = { navigate(Route.GestureTour) },
                onAbout = { navigate(Route.About) },
            )
        }
        entry<Route.SettingsPage> {
            SettingsPageScreen(
                section = it.section,
                isLoggedIn = isLoggedIn,
                onBack = goBack,
                onMutedGroups = { navigate(Route.MutedGroups) },
                onBlockedUsers = { navigate(Route.BlockedUsers) },
            )
        }
        entry<Route.ProfileSettings> { ProfileSettingsScreen(onBack = goBack) }
        entry<Route.About> { AboutScreen(onBack = goBack) }
        entry<Route.GestureTour> { GestureTour(onDone = goBack) }
        entry<Route.Search> {
            SearchScreen(
                criteria = it.criteria,
                title = it.title,
                openFilters = it.openFilters,
                isLoggedIn = isLoggedIn,
                onLogin = toLogin,
                onSubjectClick = toSubject,
                onBack = goBack,
            )
        }
        entry<Route.UserProfile> { profile(ownUserKeyOrNull(account, it.username), goBack, Modifier) }

        // ---- 条目 ----
        entry<Route.SubjectDetails> { route ->
            SubjectDetailsScreen(
                subjectId = route.subjectId,
                isLoggedIn = isLoggedIn,
                onLogin = toLogin,
                onBack = goBack,
                onSubjectClick = toSubject,
                onCharacterClick = toCharacter,
                onPersonClick = toPerson,
                onTagClick = { navigate(Route.Search(it)) },
                onRelationsSeeAll = { navigate(Route.SubjectRelations(route.subjectId)) },
                onUserClick = toUser,
                onOpenTopics = { navigate(Route.SubjectTopics(route.subjectId)) },
                onOpenReviews = { navigate(Route.SubjectReviews(route.subjectId)) },
                onOpenIndexes = { navigate(Route.SubjectIndexes(route.subjectId)) },
            )
        }
        entry<Route.SubjectRelations> {
            SubjectRelationsScreen(
                subjectId = it.subjectId,
                isLoggedIn = isLoggedIn,
                onLogin = toLogin,
                onBack = goBack,
                onSubjectClick = toSubject,
            )
        }
        entry<Route.SubjectTopics> {
            SubjectTopicsScreen(
                subjectId = it.subjectId,
                onBack = goBack,
                onTopicClick = { id -> navigate(Route.Topic(TopicType.SUBJECT, id)) },
            )
        }
        entry<Route.Topic> {
            TopicScreen(
                type = it.type,
                topicId = it.topicId,
                draftPostId = it.draftPostId,
                focusPostId = it.focusPostId,
                isLoggedIn = isLoggedIn,
                onLogin = toLogin,
                onBack = goBack,
                onSubjectClick = toSubject,
                onGroupClick = toGroup,
                onUserClick = toUser,
            )
        }
        entry<Route.Episode> {
            EpisodeScreen(
                episodeId = it.episodeId,
                focusPostId = it.focusPostId,
                isLoggedIn = isLoggedIn,
                onLogin = toLogin,
                onBack = goBack,
                onSubjectClick = toSubject,
                onUserClick = toUser,
            )
        }
        entry<Route.Group> {
            GroupScreen(name = it.name, isLoggedIn = isLoggedIn, onLogin = toLogin, onBack = goBack)
        }
        entry<Route.AllGroups> {
            AllGroupsScreen(onBack = goBack, onGroupClick = toGroup)
        }
        entry<Route.MyTopics> {
            if (isLoggedIn) {
                MyTopicsScreen(
                    onBack = goBack,
                    onOpenDraft = { draft -> navigate(Route.Topic(draft.topicType, draft.topicId, draftPostId = draft.postId)) },
                )
            } else {
                SignInPrompt(
                    title = R.string.my_topics,
                    message = R.string.sign_in_prompt_my_topics,
                    onLogin = toLogin,
                    onSettingsClick = toSettings,
                    onBack = goBack,
                )
            }
        }
        entry<Route.MutedGroups> {
            MutedGroupsScreen(onBack = goBack, onGroupClick = toGroup)
        }
        entry<Route.BlockedUsers> {
            BlockedUsersScreen(onBack = goBack, onUserClick = toUser)
        }
        entry<Route.SubjectReviews> {
            SubjectReviewsScreen(subjectId = it.subjectId, onBack = goBack, onBlogClick = toBlog)
        }
        entry<Route.SubjectIndexes> {
            SubjectIndexesScreen(subjectId = it.subjectId, onBack = goBack, onIndexClick = toIndex)
        }

        // ---- 角色与人物 ----
        entry<Route.MonoDetails> { route ->
            MonoDetailsScreen(
                monoType = route.monoType,
                id = route.id,
                isLoggedIn = isLoggedIn,
                onLogin = toLogin,
                onBack = goBack,
                onSubjectClick = toSubject,
                onMonoClick = toMono,
                onIndexClick = toIndex,
                onUserClick = toUser,
                onWorksSeeAll = { navigate(Route.MonoWorks(route.monoType, route.id)) },
                onRelationsSeeAll = { navigate(Route.MonoRelations(route.monoType, route.id)) },
                onCharactersSeeAll = { navigate(Route.PersonCharacters(route.id)) },
            )
        }
        entry<Route.MonoWorks> {
            MonoWorksScreen(monoType = it.monoType, id = it.id, onBack = goBack, onSubjectClick = toSubject)
        }
        // 角色的关联是角色，人物的关联是人物
        entry<Route.MonoRelations> { route ->
            MonoRelationsListScreen(
                monoType = route.monoType,
                id = route.id,
                onBack = goBack,
                onRelationClick = { toMono(route.monoType, it) },
            )
        }
        entry<Route.PersonCharacters> {
            PersonCharactersScreen(personId = it.personId, onBack = goBack, onCharacterClick = toCharacter)
        }

        // ---- 用户的日志、目录、生涯 ----
        entry<Route.UserBlogs> {
            BlogListScreen(username = it.username, onBack = goBack, onBlogClick = toBlog)
        }
        entry<Route.BlogDetails> {
            BlogDetailsScreen(
                blogId = it.blogId,
                focusPostId = it.focusPostId,
                isLoggedIn = isLoggedIn,
                onLogin = toLogin,
                onBack = goBack,
                onSubjectClick = toSubject,
                onUserClick = toUser,
            )
        }
        entry<Route.UserIndexes> {
            IndexListScreen(
                username = it.username,
                counts = IndexListCounts(created = it.createdCount, collected = it.collectedCount),
                isOwn = account?.isMe(it.username) == true,
                onBack = goBack,
                onIndexClick = toIndex,
            )
        }
        entry<Route.IndexDetails> {
            IndexDetailsScreen(
                indexId = it.indexId,
                isLoggedIn = isLoggedIn,
                onLogin = toLogin,
                onBack = goBack,
                onEntryClick = { target -> navigate(indexTargetRoute(target)) },
                onUserClick = toUser,
            )
        }
        // ---- 站内短信 ----
        entry<Route.PmConversation> {
            PmConversationScreen(
                key = PmChatKey.Conversation(it.conversationId),
                isLoggedIn = isLoggedIn,
                onLogin = toLogin,
                onBack = goBack,
                onUserClick = toUser,
            )
        }
        entry<Route.PmWrite> {
            PmWriteScreen(
                username = it.username,
                isLoggedIn = isLoggedIn,
                onLogin = toLogin,
                onBack = goBack,
                onUserClick = toUser,
            )
        }
        entry<Route.UserCareer> {
            CareerStatsScreen(
                username = ownUserKeyOrNull(account, it.username),
                initialType = it.subjectType,
                onBack = goBack,
                onSubjectClick = toSubject,
                onPersonClick = toPerson,
            )
        }
    }
}

/** 顶级页让出底栏的留白：测量时才读 [height]（见 [AppEntries]）。 */
private class BottomBarPadding(private val height: () -> Dp) : PaddingValues {
    override fun calculateTopPadding(): Dp = 0.dp
    override fun calculateBottomPadding(): Dp = height()
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp
    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
}
