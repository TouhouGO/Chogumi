package io.github.touhougo.chogumi.feature.community

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.BlockedUsersFeed
import io.github.touhougo.chogumi.core.data.repository.BlockedUsersFilter
import io.github.touhougo.chogumi.core.data.repository.CommunityRepository
import io.github.touhougo.chogumi.core.data.repository.GroupMuteRepository
import io.github.touhougo.chogumi.core.data.repository.GroupRepository
import io.github.touhougo.chogumi.core.data.util.loadOnce
import io.github.touhougo.chogumi.core.data.util.launchWrite
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CommunityTopicBo
import io.github.touhougo.chogumi.core.model.GroupFeedCursor
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.model.GroupTopicScope
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.RakuenFilter
import io.github.touhougo.chogumi.core.model.groupFeedNeedsReload
import io.github.touhougo.chogumi.core.model.onlyJoinedGroups
import io.github.touhougo.chogumi.core.model.pinnedFirst
import io.github.touhougo.chogumi.core.model.withoutMutedGroups
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.common.paging.Pager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 社区 tab 的两页：「小组」在前（默认），「超展开」在后。 */
enum class CommunityPage(val labelRes: Int) {
    GROUPS(R.string.community_page_groups),
    RAKUEN(R.string.community_page_rakuen),
}

/**
 * 社区 tab 的状态：停在哪一页、小组页的帖子流、超展开每个分类各自的帖子流（左右翻页、换分类时都在，切走再切回不重取）、
 * 超展开的分类、小组页顶部那一行（我加入的小组与本机置顶名单）。
 *
 * 小组页是我加入的小组的新帖（未登录或没加入小组时是全部小组，范围由数据层定），按顶部那一行的实时名单再筛一遍（[onlyJoinedGroups]）；
 * 超展开是 [rakuenFilter] 分类下的帖子，换分类就是换一份列表：取过的马上出现，还在路上的画骨架，不留别的分类的帖子。
 * 两页都不出现屏蔽的小组的帖子。
 */
data class CommunityUiState(
    val page: CommunityPage = CommunityPage.GROUPS,
    val groupFeed: PagedList<CommunityTopicBo> = PagedList(),
    val rakuen: Map<RakuenFilter, PagedList<CommunityTopicBo>> = RakuenFilter.entries.associateWith { PagedList() },
    val rakuenFilter: RakuenFilter = RakuenFilter.ALL,
    val joinedGroups: Load<List<GroupSummaryBo>> = Load.Pending,
    val pinnedGroups: List<String> = emptyList(),
) {
    fun listFor(page: CommunityPage): PagedList<CommunityTopicBo> = when (page) {
        CommunityPage.GROUPS -> groupFeed
        CommunityPage.RAKUEN -> rakuen.getValue(rakuenFilter)
    }

    /** 小组页顶部那一行：置顶的在前（[pinnedFirst]）；我加入的还没到时为 null。 */
    val myGroups: List<GroupSummaryBo>? get() = joinedGroups.valueOrNull()?.let { pinnedFirst(it, pinnedGroups) }
}

class CommunityViewModel(
    private val repository: CommunityRepository,
    private val groups: GroupRepository,
    private val mutes: GroupMuteRepository,
    private val clock: AppClock,
    blockedUsers: BlockedUsersFeed,
) : StateViewModel<CommunityUiState>(CommunityUiState()) {

    /** 本页的失败提示：下拉刷新失败（帖子还在屏幕上）。 */
    val failures = FailureNotices()

    /** 小组新帖首页定下的范围，后面的页沿用（见 [CommunityRepository.getGroupFeed]）；首页之前为 null。 */
    private var groupScope: GroupTopicScope? = null

    /** 小组新帖第 n 页从哪取（取页器的位置是页码）：第一页是起点，其后每页是上一页给的游标。 */
    private val groupCursors = mutableListOf(GroupFeedCursor.START)

    /** 屏蔽的小组的英文键（本机名单的镜像，名单一变两页都按新名单重算）。 */
    private var muted: Set<String> = emptySet()

    /** 小组页的帖子流。 */
    private val groupPager = Pager(
        scope = viewModelScope,
        read = { currentState.groupFeed },
        write = { f -> updateState { it.copy(groupFeed = f(it.groupFeed)) } },
        // 按最后回复排的流在翻页途中会变：刚有人回复的帖子顶到前面，下一页里又出现一次。只留先到的那一份
        present = { topics -> shown(onlyJoinedGroups(topics.distinctBy { it.key }, if (groupScope == GroupTopicScope.JOINED) joinedNames() else null)) },
    ) { position ->
        outcomeOf {
            // 续页一律沿用第一页的流；首页由数据层按实时加入名单定范围（名单变空由名单观察者重取）
            val scope = if (position > 0) groupScope else null
            val cursor = if (position == 0) GroupFeedCursor.START else groupCursors[position]
            val feed = blocked.alongside { repository.getGroupFeed(scope, cursor) }
            currentCoroutineContext().ensureActive()
            groupScope = feed.scope
            while (groupCursors.size > position + 1) groupCursors.removeAt(groupCursors.lastIndex)
            feed.next?.let(groupCursors::add)
            if (feed.failedRuns > 0) failures.post(R.string.community_group_feed_partial)
            PagedResult(feed.items, total = 0, nextOffset = feed.next?.let { position + 1 })
        }
    }

    /** 超展开每个分类一份帖子流：换分类不重取，各自的请求互不作废。 */
    private val rakuenPagers = RakuenFilter.entries.associateWith { filter ->
        Pager(
            scope = viewModelScope,
            read = { currentState.rakuen.getValue(filter) },
            write = { f -> updateState { it.copy(rakuen = it.rakuen + (filter to f(it.rakuen.getValue(filter)))) } },
            present = { topics -> shown(topics.distinctBy { it.key }) },
        ) { offset -> outcomeOf { blocked.alongside { repository.getRakuen(filter, offset) } } }
    }

    /** 绝交名单（[BlockedUsersFilter]）：在别处绝交、取消后，两页已取到的帖子当场按新名单重算。 */
    private val blocked = BlockedUsersFilter(viewModelScope, blockedUsers) { representAll() }

    /** 两页共同的显示规则：屏蔽的小组的帖子、绝交用户发的帖子不显示。 */
    private fun shown(topics: List<CommunityTopicBo>) = blocked.visible(withoutMutedGroups(topics, muted)) { it.author?.id }

    private fun representAll() {
        groupPager.represent()
        rakuenPagers.values.forEach { it.represent() }
    }

    /** 这一页现在显示的那份列表的取页器：超展开按当前分类。 */
    private fun pagerOf(page: CommunityPage): Pager<CommunityTopicBo> = when (page) {
        CommunityPage.GROUPS -> groupPager
        CommunityPage.RAKUEN -> rakuenPagers.getValue(currentState.rakuenFilter)
    }

    /** 登录状态（页面从 `appEntries` 拿到的那份）：变了，两页都要按新身份重取。 */
    private var loggedIn: Boolean? = null

    /** 按当前登录状态取过第一页的页：一页出现在屏幕上（打开时、点过去或翻过去）才取。 */
    private val opened = mutableSetOf<CommunityPage>()

    init {
        follow(failures, { groups.pinned }) { names -> updateState { it.copy(pinnedGroups = names) } }
        viewModelScope.launch {
            // 加入名单到了或变了（回到社区页、下拉时重取）：小组页按新名单重筛；多了小组（刚加入）就重取，把它的帖子合进来
            var previous: Set<String>? = null
            uiState.map { it.joinedGroups.valueOrNull()?.mapTo(HashSet()) { group -> group.name } }.distinctUntilChanged().collect { names ->
                if (groupFeedNeedsReload(groupScope, previous, names)) groupPager.refresh() else groupPager.represent()
                previous = names
            }
        }
        follow(failures, { mutes.observeAll() }) { list ->
            muted = list.mapTo(HashSet()) { it.name }
            representAll()
        }
    }

    /**
     * 这一页出现在屏幕上时调：这一页在当前登录状态下还没取过就取第一页。登录、退出后旧帖子先留着，新的到手后整体换掉
     * （小组页从全部小组换成我加入的小组）。超展开一次把每个分类都取上（当前分类先发）：超展开的接口一次要一两秒，
     * 点分类时等它就是点了没反应，先取好了换分类就是马上换。
     */
    fun open(page: CommunityPage, isLoggedIn: Boolean) {
        if (loggedIn != isLoggedIn) {
            loggedIn = isLoggedIn
            opened.clear()
            cancelSection(JOINED_SECTION)
            groupScope = null
            updateState { it.copy(joinedGroups = Load.Pending) }
        }
        if (opened.add(page)) {
            when (page) {
                CommunityPage.GROUPS -> {
                    groupPager.refresh(keepItems = true)
                    loadJoinedGroups()
                }
                CommunityPage.RAKUEN -> {
                    val current = currentState.rakuenFilter
                    rakuenPagers.getValue(current).refresh(keepItems = true)
                    rakuenPagers.forEach { (filter, pager) -> if (filter != current) pager.refresh(keepItems = true) }
                }
            }
        }
    }

    /** 回到社区页时重取我加入的小组（在网页上加入、退出之后跟着变）；还没取到时什么都不做，取不到也不报错。 */
    fun refreshJoinedGroups() {
        if (currentState.joinedGroups is Load.Ready) loadJoinedGroups(quiet = true)
    }

    fun selectPage(page: CommunityPage) {
        if (currentState.page != page) updateState { it.copy(page = page) }
    }

    /**
     * 换分类：换成这个分类自己的那份列表，取过的马上出现、在路上的画骨架，不再重取；
     * 它先前没取到（整块报错）时重取一次。
     */
    fun selectRakuenFilter(filter: RakuenFilter) {
        if (filter == currentState.rakuenFilter) return
        updateState { it.copy(rakuenFilter = filter) }
        if (currentState.rakuen.getValue(filter).error != null) rakuenPagers.getValue(filter).refresh()
    }

    /** 下拉刷新：失败时帖子留着，提示条说原因、带重试。小组页顶部那一行一起重取。 */
    fun refresh(page: CommunityPage) {
        if (page == CommunityPage.GROUPS) loadJoinedGroups()
        pagerOf(page).reload { failures.post(it, retry = { refresh(page) }) }
    }

    /** 整块报错后的重试。 */
    fun retry(page: CommunityPage) = pagerOf(page).refresh()

    fun loadMore(page: CommunityPage) = pagerOf(page).loadMore()

    /** 屏蔽 [group]：两页马上不再显示它的帖子（名单变了 → [Pager.represent]）；在设置的「屏蔽的小组」里取消。 */
    fun mute(group: GroupSummaryBo) {
        viewModelScope.launchWrite(failures, retry = { mute(group) }) { mutes.mute(group, clock.nowMillis()) }
    }

    /** 实时加入名单；名单还没到时为 null，已知没有加入的小组时为空集。 */
    private fun joinedNames(): Set<String>? =
        currentState.joinedGroups.valueOrNull()?.mapTo(HashSet()) { it.name }

    /**
     * 我加入的小组（未登录直接是空表，不发请求）。已显示的那一行留到新的到手。
     * [quiet]：用户没动手的后台重取（回到页面），已有内容时取不到不弹提示。
     */
    private fun loadJoinedGroups(quiet: Boolean = false) {
        if (loggedIn != true) {
            cancelSection(JOINED_SECTION)
            updateState { it.copy(joinedGroups = Load.Ready(emptyList())) }
            return
        }
        launchSection(JOINED_SECTION, failures.takeUnless { quiet }, { loadOnce { groups.getJoinedGroups() } }, { it.joinedGroups }) {
            copy(joinedGroups = it)
        }
    }
}

private const val JOINED_SECTION = "joinedGroups"
