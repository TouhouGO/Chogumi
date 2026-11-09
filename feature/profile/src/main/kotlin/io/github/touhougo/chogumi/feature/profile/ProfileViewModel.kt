package io.github.touhougo.chogumi.feature.profile

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.common.paging.Pager
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.BlockedUsersFeed
import io.github.touhougo.chogumi.core.data.repository.CollectionStore
import io.github.touhougo.chogumi.core.data.repository.DraftRepository
import io.github.touhougo.chogumi.core.data.repository.IndexRepository
import io.github.touhougo.chogumi.core.data.repository.OtherUserCollectionsCache
import io.github.touhougo.chogumi.core.data.repository.TimelineRepository
import io.github.touhougo.chogumi.core.data.repository.UserRepository
import io.github.touhougo.chogumi.core.data.repository.postDrafts
import io.github.touhougo.chogumi.core.data.util.loadOutcome
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.AppTime
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CollectionEditing
import io.github.touhougo.chogumi.core.model.CollectionMarker
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.PostDraftKind
import io.github.touhougo.chogumi.core.model.RelatedCharacterBo
import io.github.touhougo.chogumi.core.model.RelatedPersonBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TimelineMode
import io.github.touhougo.chogumi.core.model.TimelineQuery
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.userReportTargetOf
import io.github.touhougo.chogumi.core.reader.TimelineDetailActions
import io.github.touhougo.chogumi.core.reader.TimelineDetailState
import io.github.touhougo.chogumi.core.reader.TimelineReplyTarget
import io.github.touhougo.chogumi.core.reader.draftKey
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkController
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkState
import io.github.touhougo.chogumi.core.ui.composables.labelRes
import io.github.touhougo.chogumi.core.ui.moderation.ModerationAction
import io.github.touhougo.chogumi.core.ui.moderation.ModerationController
import io.github.touhougo.chogumi.core.ui.moderation.ModerationState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 资料页的状态（唯一一份）：资料、收藏 tab、好友 tab、他人主页的社交操作与报告面板。
 * [user] 一旦到手就不再清空（重试、刷新都在它之上进行），资料头与各 tab 直接读它。
 * 动态 tab 的内容在仓库里（同一用户的动态各页共享），见 [ProfileViewModel.timeline]。
 */
data class ProfileUiState(
    /** 资料：取不到且还没有资料时整页报错 + 重试；已有资料时刷新失败走提示条。 */
    val userLoad: Load<UserBo> = Load.Pending,
    val isRefreshing: Boolean = false,
    val shelf: ShelfState = ShelfState(),
    val social: SocialState = SocialState(),
    val socialOps: SocialOpsState = SocialOpsState(),
    /** 报告面板；非 null 表示面板打开。 */
    val moderation: ModerationState = ModerationState(),
    /** 「动态」tab 的动态详情面板。 */
    val timelineDetail: TimelineDetailState = TimelineDetailState(),
    /** 收藏网格海报的长按标记，只由 [ProfileViewModel.posterMarks] 改。 */
    val posterMarks: PosterMarkState = PosterMarkState(),
) {
    val user: UserBo? get() = userLoad.valueOrNull()

    /** 资料取不到且还没有资料（整页报错 + 重试）。 */
    val error: Failure? get() = userLoad.failureOrNull()
}

/**
 * 收藏 tab：当前分类与它的内容（换分类即重取）。条目五个域一次取全（[subjects]），
 * 角色、人物按页取（[characters] / [persons]）。哪个分类画哪份内容只由 [shelfContentOf] 决定。
 */
data class ShelfState(
    /** 当前分类；收藏 tab 还没打开过时为 null。 */
    val category: ShelfCategory? = null,
    val subjects: Load<List<UserCollectionBo>> = Load.Pending,
    /** 条目收藏的下拉刷新在途（条目留在原地，只有指示器在转）；角色、人物的在 [PagedList.isRefreshing]。 */
    val subjectsRefreshing: Boolean = false,
    val characters: PagedList<RelatedCharacterBo> = PagedList(),
    val persons: PagedList<RelatedPersonBo> = PagedList(),
)

/**
 * 他人主页社交操作的**唯一状态源**。
 *
 * 操作行（加为好友 / 已是好友）、⋮ 菜单（绝交 / 取消绝交）、绝交条全部从它派生，
 * 不各自存副本。乐观更新只改这里，失败回滚也只回滚这里。
 *
 * [isFriend] 跟着当前账号的好友名单（`UserRepository.friendChanges`，与名字旁「好友」小牌同一份），[isBlocked] 跟着当前账号的
 * 绝交名单（`UserRepository.blockedChanges`，与内容过滤同一份）。写在途时显示乐观值。
 */
data class SocialOpsState(
    /** null = 好友名单还没读到（资料未到时还不知道对方的 id）：操作行画占位，不先猜一个可点的状态。 */
    val isFriend: Boolean? = null,
    val isBlocked: Boolean = false,
    /** 任一写操作在途；期间按钮不可再点，防双击双发。 */
    val pending: Boolean = false,
) {
    /** 任一写操作开始（乐观更新的同时置在途标记，防双击双发）。 */
    fun started(optimistic: SocialOpsState.() -> SocialOpsState) = copy(pending = true).optimistic()

    /** 操作成功：在途结束，乐观状态即最终状态。 */
    fun settled() = copy(pending = false)

    /** 操作失败：回滚到在途前的快照。 */
    fun restored(snapshot: SocialOpsState) = snapshot.copy(pending = false)
}

/**
 * 好友 tab：当前方向与两个方向各自的列表。
 *
 * [following] 与 [followers] 是**好友表的两个相反方向**，不是包含关系：
 * - `following` = `/p1/users/{username}/friends` → 该用户**关注**的人
 * - `followers` = `/p1/users/{username}/followers` → **关注**该用户的人
 *
 * 端点名 friends 有误导性（服务端只查 `uid = me` 一个方向，不做交集），
 * 详见 [io.github.touhougo.chogumi.core.network.api.UserApi.getFriends]。
 * 两个方向各自分页、各取各的：切过去才取，切回来已取的不重取。
 */
data class SocialState(
    val direction: SocialDirection = SocialDirection.FOLLOWING,
    val following: PagedList<UserBo> = PagedList(),
    val followers: PagedList<UserBo> = PagedList(),
) {
    val current: PagedList<UserBo> get() = if (direction == SocialDirection.FOLLOWING) following else followers
}

/**
 * 收藏的**一键可选分类**，即右下角 FAB 菜单的全部内容，顺序也由此决定：
 * `动画 书籍 游戏 音乐 三次元 角色与人物`（6 项，M3 FAB 菜单上限 6）。
 *
 * **目录不在其中**：目录有独立的「目录」只读页。
 * 「角色与人物」是一档，进页后内部再切「角色 / 人物」两小类；小类就是分类值的一部分
 * （[CharacterPerson.tab]），加载中 / 已加载 / 续取都随分类一起走，不另存一份。
 */
sealed interface ShelfCategory {
    /** FAB 菜单项文案 */
    val labelRes: Int

    /** 条目的五个域之一。 */
    data class SubjectDomain(val type: SubjectType) : ShelfCategory {
        override val labelRes: Int get() = type.labelRes()
    }

    /** 角色与人物：同一档，内部再分 [CharacterPersonTab]。 */
    data class CharacterPerson(val tab: CharacterPersonTab = CharacterPersonTab.CHARACTER) : ShelfCategory {
        override val labelRes: Int get() = R.string.profile_collection_characters_persons
    }

    companion object {
        /**
         * FAB 菜单的**顺序**（唯一的顺序来源）：条目五个域在前，「角色与人物」在后。
         *
         * 用 `ContentDomains` 而不是 `SubjectType.entries`：后者含 `UNKNOWN`，
         * 而"未知类型"不是用户能选的东西。
         */
        val entries: List<ShelfCategory> = ContentDomains.map { SubjectDomain(it) } +
            listOf(CharacterPerson())
    }
}

/** 在 FAB 菜单里占的那一格：「角色与人物」两小类同属一格。 */
internal val ShelfCategory.fabSlot: ShelfCategory
    get() = if (this is ShelfCategory.CharacterPerson) ShelfCategory.CharacterPerson() else this

/** 「角色与人物」档内部的两小类。 */
enum class CharacterPersonTab(val labelRes: Int) {
    CHARACTER(R.string.profile_collection_characters),
    PERSON(R.string.profile_collection_persons),
}

/**
 * 社交 tab 的两个方向。命名对照见 [io.github.touhougo.chogumi.core.network.api.UserApi.getFriends]。
 *
 * 文案分本人、他人两套（我关注的 / 关注我的，关注 / 粉丝）；他人的空态文案带主人的名字（`%1$s`），不用代词。
 * 只经 [labelRes] / [emptyRes] 取，看谁的主页由调用方传入。
 */
enum class SocialDirection(
    private val selfLabelRes: Int,
    private val otherLabelRes: Int,
    private val selfEmptyRes: Int,
    private val otherEmptyRes: Int,
) {
    FOLLOWING(
        R.string.profile_friends_count,
        R.string.profile_others_following,
        R.string.empty_following,
        R.string.empty_others_following,
    ),
    FOLLOWERS(
        R.string.profile_followers_count,
        R.string.profile_others_followers,
        R.string.empty_followers,
        R.string.empty_others_followers,
    ),
    ;

    fun labelRes(isSelf: Boolean): Int = if (isSelf) selfLabelRes else otherLabelRes

    fun emptyRes(isSelf: Boolean): Int = if (isSelf) selfEmptyRes else otherEmptyRes
}

class ProfileViewModel(
    /** 目标用户名；null = 当前登录用户自己（导航参数，见 ProfileModule）。 */
    private val username: String?,
    private val userRepository: UserRepository,
    private val collectionStore: CollectionStore,
    /** 他人收藏的唯一入口（与生涯页同一份会话内缓存）。 */
    private val otherCollections: OtherUserCollectionsCache,
    private val timelineRepository: TimelineRepository,
    collectionMarker: CollectionMarker,
    collectionEditing: CollectionEditing,
    appTime: AppTime,
    drafts: DraftRepository,
    clock: AppClock,
    blockedUsers: BlockedUsersFeed,
    indexRepository: IndexRepository,
) : StateViewModel<ProfileUiState>(ProfileUiState()) {

    /** 本页的失败提示：写操作失败、有资料时刷新失败、动态操作失败、收藏网格海报上长按标记的提交失败。 */
    val failures = FailureNotices()

    /** 收藏网格海报的长按标记：标的是「我」的收藏状态，看别人的资料页也一样。 */
    val posterMarks = PosterMarkController(
        scope = viewModelScope,
        read = { currentState.posterMarks },
        write = { change -> updateState { it.copy(posterMarks = change(it.posterMarks)) } },
        failures = failures,
        marker = collectionMarker,
        editing = collectionEditing,
        today = appTime.today,
    )

    /** 看的是不是自己（自己的用户名已在 appEntries 归为 null，见 SignedInAccount.isMe）。决定收藏、动态与社交内容的语义和权限。 */
    val isSelf: Boolean = username.isNullOrBlank()

    /** 目标用户名：看别人时用传入值，看自己时等资料就绪后取资料里的。 */
    private val targetUsername: String? get() = username?.takeIf { it.isNotBlank() } ?: currentState.user?.username

    /**
     * 这个人的动态（仓库里同一用户的动态各页共享）。这里只读本地，不发请求：
     * 「动态」tab 第一次出现在屏幕上时由 [openTimeline] 取一次（主页大多停在「概览」，不为没看的 tab 付流量）。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val timeline: StateFlow<PagedList<TimelineItemBo>> = uiState
        .map { targetUsername }
        .distinctUntilChanged()
        .flatMapLatest { name ->
            if (name.isNullOrBlank()) {
                flowOf(PagedList<TimelineItemBo>(isLoading = false))
            } else {
                timelineRepository.observe(TimelineQuery(TimelineMode.USER, name))
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, PagedList(isLoading = false))

    /** 动态详情面板的操作（楼层、贴贴、写回复），与动态页共用同一份逻辑；状态是 [ProfileUiState.timelineDetail]。 */
    val timelineDetail = TimelineDetailActions(
        scope = viewModelScope,
        state = { currentState.timelineDetail },
        update = { change -> updateState { it.copy(timelineDetail = change(it.timelineDetail)) } },
        replies = timelineRepository::replies,
        react = timelineRepository::react,
        reply = timelineRepository::reply,
        drafts = drafts.postDrafts(PostDraftKind.TIMELINE_REPLY, clock, TimelineReplyTarget::draftKey),
        failures = failures,
        blocked = blockedUsers.current(),
    )

    init {
        load()
        // 自己的资料头「目录 N」是两个目录列表的 total：在别处建、删、收藏、取消收藏目录之后重读，不等下拉刷新
        if (isSelf) viewModelScope.launch { indexRepository.writes.drop(1).collect { load() } }
        // 自己的头像、昵称、签名、自我介绍：在设置里改成之后重读
        if (isSelf) viewModelScope.launch { userRepository.profileEdits.drop(1).collect { load() } }
        // 自己的「好友 N」与「我关注的」：在别人的主页、楼层菜单里加或取消好友之后跟上，不等下拉刷新
        if (isSelf) viewModelScope.launch { userRepository.friendsChanges().drop(1).collect { followFriends() } }
    }

    fun retry() = load()

    fun refreshProfile() = load(refresh = true)

    /** 看别人走 getUser（公开端点，匿名可读）；看自己走 getMe（需要凭据）。每一拍就绪的资料先给社交操作区播种。 */
    private fun load(refresh: Boolean = false) {
        val version = socialWriteVersion
        updateState { it.copy(isRefreshing = refresh) }
        launchSection(
            "profile",
            failures,
            {
                val source = username?.takeIf { it.isNotBlank() }
                    ?.let { userRepository.getUser(it, refresh = true) }
                    ?: userRepository.getMe(refresh = true)
                source.onEach { load -> if (load is Load.Ready) seedSocialOps(load.value, version) }
            },
            { it.userLoad },
            alongside = { load -> if (load is Load.Pending) this else copy(isRefreshing = false) },
        ) { copy(userLoad = it) }
    }

    // ---- 动态 tab ----

    /** 已经为谁取过动态：同一个人只在第一次打开「动态」时取。 */
    private var timelineOpenedFor: String? = null

    /** 「动态」tab 第一次出现在屏幕上：取一次。之后再调不重复取（刷新走 [refreshTimeline]）。 */
    fun openTimeline() {
        val name = targetUsername ?: return
        if (timelineOpenedFor == name) return
        timelineOpenedFor = name
        timelineAction { timelineRepository.refresh(it) }
    }

    fun refreshTimeline() = timelineAction { timelineRepository.refresh(it, userInitiated = true) }

    /** 续取失败记在动态列表的页脚（见 [PagedList.loadMoreError]），不另行提示。 */
    fun loadMoreTimeline() = timelineAction {
        timelineRepository.loadMore(it)
        null
    }

    /** 删除一条动态。能不能删由仓库判定（[TimelineRepository.delete]），这里不重复判断。 */
    fun deleteTimeline(id: Long) = timelineAction { timelineRepository.delete(id).failureOrNull() }

    /** 动态 tab 的操作。[block] 返回界面上看不到、要用提示条说的失败原因；null = 成功或失败已画在列表里。 */
    private fun timelineAction(block: suspend (TimelineQuery) -> Failure?) {
        val name = targetUsername ?: return
        viewModelScope.launch {
            val unshown = when (val result = outcomeOf { block(TimelineQuery(TimelineMode.USER, name)) }) {
                is Outcome.Success -> result.value
                is Outcome.Failed -> result.failure
            }
            unshown?.let { failures.post(it) }
        }
    }

    // ---- 收藏 tab ----
    // 首次打开 tab 才取（[openShelf]）：没看过的 tab 不付流量。

    private val characters = Pager(
        scope = viewModelScope,
        read = { currentState.shelf.characters },
        write = { f -> updateState { it.copy(shelf = it.shelf.copy(characters = f(it.shelf.characters))) } },
    ) { offset -> targetUsername?.let { userRepository.getCharacterCollectionsPage(it, offset = offset) } ?: noTarget() }

    private val persons = Pager(
        scope = viewModelScope,
        read = { currentState.shelf.persons },
        write = { f -> updateState { it.copy(shelf = it.shelf.copy(persons = f(it.shelf.persons))) } },
    ) { offset -> targetUsername?.let { userRepository.getPersonCollectionsPage(it, offset = offset) } ?: noTarget() }

    /** 第一次打开收藏 tab：取第一个分类。之后再调不重复取。 */
    fun openShelf() {
        if (currentState.shelf.category == null) selectShelf(ShelfCategory.entries.first())
    }

    /**
     * 选分类（含「角色与人物」的小类）并取它的内容；[refresh] 只由下拉刷新给 true：条目收藏据此跳过缓存。
     */
    fun selectShelf(category: ShelfCategory, refresh: Boolean = false) {
        if (targetUsername == null) return
        updateState { it.copy(shelf = it.shelf.copy(category = category)) }
        when (category) {
            is ShelfCategory.SubjectDomain -> loadSubjects(category.type, refresh)
            is ShelfCategory.CharacterPerson ->
                if (refresh) category.tab.pager().reload { failures.post(it, retry = { retryShelf(category) }) }
                else category.tab.pager().refresh()
        }
    }

    /** 提示条上的「重试」：还停在那个分类才重新下拉，已经换走的分类不替用户切回去。 */
    private fun retryShelf(category: ShelfCategory) {
        if (currentState.shelf.category == category) selectShelf(category, refresh = true)
    }

    /** 续取当前分类（只有角色、人物分页；条目一次取全）。 */
    fun loadMoreShelf() {
        (currentState.shelf.category as? ShelfCategory.CharacterPerson)?.tab?.pager()?.loadMore()
    }

    private fun CharacterPersonTab.pager(): Pager<*> = when (this) {
        CharacterPersonTab.CHARACTER -> characters
        CharacterPersonTab.PERSON -> persons
    }

    /**
     * 条目收藏：自己的走本地收藏流（先缓存、后网络），别人的与生涯页共用会话内缓存、一次取完。
     * 换分类先清成「等待」：上一个分类的条目不能留在新分类下面。同一分类的下拉刷新不清：条目留在原地，
     * 失败走提示条（[collectSection] 留住已显示的内容）。
     */
    private fun loadSubjects(type: SubjectType, refresh: Boolean) {
        val name = targetUsername ?: return
        val keep = refresh && currentState.shelf.subjects is Load.Ready
        updateState { it.copy(shelf = it.shelf.copy(subjects = if (keep) it.shelf.subjects else Load.Pending, subjectsRefreshing = keep)) }
        // 同一个键：换分类时上一个分类的取数被作废
        launchSection(
            "shelfSubjects",
            failures,
            {
                if (isSelf) {
                    collectionStore.getUserCollections(type, type = null, refresh = refresh)
                } else {
                    loadOutcome { otherCollections.collectionsFor(name, type, refresh = refresh) }
                }
            },
            { it.shelf.subjects },
            alongside = { load -> if (load is Load.Pending) this else copy(shelf = shelf.copy(subjectsRefreshing = false)) },
            retry = { retryShelf(ShelfCategory.SubjectDomain(type)) },
        ) { copy(shelf = shelf.copy(subjects = it)) }
    }

    // ---- 好友 tab ----

    private val following = Pager(
        scope = viewModelScope,
        read = { currentState.social.following },
        write = { f -> updateState { it.copy(social = it.social.copy(following = f(it.social.following))) } },
        present = { fetched -> fetched.distinctBy(UserBo::id) },
    ) { offset -> targetUsername?.let { userRepository.getFriendsPage(it, offset = offset) } ?: noTarget() }

    private val followers = Pager(
        scope = viewModelScope,
        read = { currentState.social.followers },
        write = { f -> updateState { it.copy(social = it.social.copy(followers = f(it.social.followers))) } },
        present = { fetched -> fetched.distinctBy(UserBo::id) },
    ) { offset -> targetUsername?.let { userRepository.getFollowersPage(it, offset = offset) } ?: noTarget() }

    /** 取过的方向：切回来不重取。 */
    private val requestedDirections = mutableSetOf<SocialDirection>()

    /** 第一次打开好友 tab：取当前方向。 */
    fun openSocial() = selectSocial(currentState.social.direction)

    /** 切方向：没取过的方向才取。 */
    fun selectSocial(direction: SocialDirection) {
        if (targetUsername == null) return
        updateState { it.copy(social = it.social.copy(direction = direction)) }
        if (requestedDirections.add(direction)) direction.pager().refresh()
    }

    /** 自己的好友名单变了：重读资料头的好友数；「我关注的」取过就在原地重拉（没取过的第一次打开时本来就取新的）。 */
    private fun followFriends() {
        load()
        if (SocialDirection.FOLLOWING in requestedDirections) following.reload(showIndicator = false) { failures.post(it) }
    }

    /** 下拉刷新当前方向：条目留在原地，失败走提示条。 */
    fun refreshSocial() = currentState.social.direction.pager().reload { failures.post(it, retry = ::refreshSocial) }

    fun loadMoreSocial() = currentState.social.direction.pager().loadMore()

    private fun SocialDirection.pager(): Pager<UserBo> = when (this) {
        SocialDirection.FOLLOWING -> following
        SocialDirection.FOLLOWERS -> followers
    }

    // ---- 他人主页社交操作 ----
    //
    // 全部乐观更新：先改 [ProfileUiState.socialOps]（界面立刻变），失败回滚并经 [failures] 给提示。
    // 操作行、⋮ 菜单、绝交条都从它派生。未登录时界面把这些入口带去登录，走不到这里。

    /** 关系读取和乐观写共用的版本：写操作让已发出的旧读取失效，即使两次写后值又回到原值。 */
    private var socialWriteVersion = 0L

    private fun acceptsSocialRead(version: Long): Boolean = socialWriteVersion == version && !currentState.socialOps.pending

    /** 每轮资料就绪都接上关系的跟随，并查一次本机绝交名单读不读得出；读取开始以后已经写过关系的旧一轮不再播种。 */
    private fun seedSocialOps(user: UserBo, version: Long) {
        if (isSelf || !acceptsSocialRead(version)) return
        followRelations(user.id)
        checkBlocklist(user.id, version)
    }

    /** 跟着本机绝交名单、好友名单走的那一轮（[followRelation]）；读不出本机名单而结束后，下一轮资料就绪或重试时再接上。 */
    private var blockedFollow: Job? = null
    private var friendFollow: Job? = null

    /** 关系只由这两条跟随写进 [SocialOpsState]（写操作的乐观值除外）：同一个值没有第二个写入方，晚到的一次性读取盖不掉它。 */
    private fun followRelations(userId: Int) {
        blockedFollow = followRelation(blockedFollow, userRepository.blockedChanges(userId)) { copy(isBlocked = it) }
        friendFollow = followRelation(friendFollow, userRepository.friendChanges(userId)) { copy(isFriend = it) }
    }

    /**
     * 写结束时重新接上两条跟随：新的一轮先读一次写之后的名单，写成的结果、写的同时别处改的都由它带回。
     * 写在途时旧的一轮读到的那一拍不能留到写完再落：它可能是写入之前的名单，而写记下的新名单那一拍不保证在写结束之前到。
     */
    private fun refollowRelations() {
        val userId = currentState.user?.id ?: return
        blockedFollow?.cancel()
        friendFollow?.cancel()
        blockedFollow = null
        friendFollow = null
        followRelations(userId)
    }

    /**
     * 关系跟着本机名单走：名单在别处改了（设置页的屏蔽列表、另一页的绝交与加好友、网页端）也跟上。写在途时不覆盖乐观值，
     * 这期间的拍不记（写结束时 [refollowRelations] 重读）。[current] 还在跟就不另起一轮。
     */
    private fun followRelation(
        current: Job?,
        changes: Flow<Boolean>,
        apply: SocialOpsState.(Boolean) -> SocialOpsState,
    ): Job = current?.takeIf { it.isActive } ?: viewModelScope.launch {
        changes.collect { value ->
            if (!currentState.socialOps.pending) updateState { it.copy(socialOps = it.socialOps.apply(value)) }
        }
    }

    /**
     * 本机绝交名单读不读得出：跟随的那条读不出时只是不再出拍，这里把失败报给用户，重试重读一次并接上跟随。只报失败，
     * 读到的值不写进状态（值由跟随带来）；过期一轮的失败不报。
     */
    private fun checkBlocklist(userId: Int, version: Long = socialWriteVersion) {
        viewModelScope.launch {
            val result = userRepository.isBlocked(userId)
            if (!acceptsSocialRead(version)) return@launch
            when (result) {
                is Outcome.Failed -> failures.post(result.failure, retry = { checkBlocklist(userId) })
                is Outcome.Success -> followRelations(userId)
            }
        }
    }

    /** 写操作的统一流程：在途时忽略（防双击双发）→ 乐观更新 → 请求；失败回滚到操作前的快照并提示原因。 */
    private fun socialAction(
        optimistic: SocialOpsState.() -> SocialOpsState,
        request: suspend () -> Outcome<*>,
    ) {
        val snapshot = currentState.socialOps
        if (snapshot.pending) return
        ++socialWriteVersion
        updateState { it.copy(socialOps = snapshot.started(optimistic)) }
        viewModelScope.launch {
            val result = request()
            updateState { state ->
                state.copy(
                    socialOps = when (result) {
                        is Outcome.Failed -> state.socialOps.restored(snapshot)
                        is Outcome.Success -> state.socialOps.settled()
                    },
                )
            }
            refollowRelations()
            if (result is Outcome.Failed) failures.post(result.failure)
        }
    }

    /** 加为好友。成功不提示（按钮已变成「已是好友」，那就是结果）；只有失败才提示。 */
    fun addFriend() {
        val name = targetUsername ?: return
        socialAction(optimistic = { copy(isFriend = true) }, request = { userRepository.addFriend(name) })
    }

    /** 撤销好友（先在界面弹确认框，确认后才到这里）。 */
    fun removeFriend() {
        val name = targetUsername ?: return
        socialAction(optimistic = { copy(isFriend = false) }, request = { userRepository.removeFriend(name) })
    }

    /** 绝交（确认框之后调用）。 */
    fun blockUser() {
        val name = targetUsername ?: return
        socialAction(optimistic = { copy(isBlocked = true) }, request = { userRepository.addUserToBlocklist(name) })
    }

    /** 取消绝交。 */
    fun unblockUser() {
        val name = targetUsername ?: return
        socialAction(optimistic = { copy(isBlocked = false) }, request = { userRepository.removeUserFromBlocklist(name) })
    }

    // ---- 报告面板 ----

    /** 报告这个用户：⋮「报告」与动态回复楼层菜单里的报告、加好友、绝交同经它（[ModerationController]）。 */
    val moderation = ModerationController(
        scope = viewModelScope,
        read = { currentState.moderation },
        write = { change -> updateState { it.copy(moderation = change(it.moderation)) } },
        failures = failures,
        moderation = userRepository,
    )

    fun openReport() {
        if (isSelf) return
        val user = currentState.user ?: return
        moderation.perform(ModerationAction.Report(userReportTargetOf(user.id), displayNameOf(user)))
    }
}

/** 还没有目标用户名（看自己而资料未到）时的取页结果；界面只在资料到了之后才打开收藏、好友 tab。 */
private fun noTarget(): Outcome.Failed = Outcome.Failed(Failure.NotLoggedIn)
