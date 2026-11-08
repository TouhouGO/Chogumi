package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.data.db.mapper.toGroupBo
import io.github.touhougo.chogumi.core.data.db.mapper.toGroupPageTopicBo
import io.github.touhougo.chogumi.core.data.db.mapper.toGroupSummaryBo
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.authenticatedOutcome
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.data.util.ObservedPreferences
import io.github.touhougo.chogumi.core.data.util.SharedFetch
import io.github.touhougo.chogumi.core.data.util.failuresAsLoad
import io.github.touhougo.chogumi.core.data.util.forbiddenAs
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.CommunityTopicBo
import io.github.touhougo.chogumi.core.model.GroupBo
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.network.api.CommunityApi
import io.github.touhougo.chogumi.core.network.api.groupMembershipLinkOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 小组：小组页（头部、帖子、发帖）、全部小组、我加入的小组，以及本机的置顶名单。
 * 网络部分只走网络、不落库（同社区帖子流，每次进页现拉）；带令牌请求：「我加入的」与 `membership` 要登录，
 * 非公开小组的帖子也只对成员可见。
 */
interface GroupRepository {
    suspend fun getGroup(name: String): GroupBo

    /** 小组页里这个小组的帖子，按最后回复倒序，按 offset 翻页。 */
    suspend fun getTopics(name: String, offset: Int): PagedResult<CommunityTopicBo>

    /** 全部小组，按成员数排（接口没有搜索、分类与活跃度排序）。 */
    suspend fun getAllGroups(offset: Int): PagedResult<GroupSummaryBo>

    /** 我加入的全部小组，按成员数排；未登录为空表。 */
    suspend fun getJoinedGroups(): List<GroupSummaryBo>

    /** 在小组里发帖，成功是新帖的 id。禁言说成 [io.github.touhougo.chogumi.core.foundation.RejectReason.POSTING_NOT_ALLOWED]。 */
    suspend fun createTopic(name: String, title: String, content: String, turnstileToken: String): Outcome<Int>

    /**
     * 加入（[joined] 为 true）或退出小组，成功是回读的小组页头部。开放接口没有这两件事：用网页会话打开小组网页，
     * 点它上面的加入 / 退出链接，再回读接口确认。没有网页会话是 [RejectReason.WEB_LOGIN_REQUIRED]；
     * 网页上没有这个链接（只在新站的小组、已经是这个状态）或回读仍没变是 [RejectReason.GROUP_MEMBERSHIP_UNAVAILABLE]。
     */
    suspend fun setJoined(name: String, joined: Boolean): Outcome<GroupBo>

    /** 当前账号置顶的小组（小组名，最近置顶的在最前）；未登录为空表，换号跟着换。读不出本机存储时是 [Load.Failed]。 */
    val pinned: Flow<Load<List<String>>>

    /** 置顶或取消置顶 [name]（只存在本机、按账号分开）。未登录时什么都不做。 */
    suspend fun setPinned(name: String, pinned: Boolean)
}

/** 小组页帖子、全部小组每页条数（设计决定：与小组新帖同一页长）。 */
internal const val GROUP_PAGE_SIZE = GROUP_TOPICS_PAGE

class GroupRepositoryImpl(
    private val api: CommunityApi,
    private val tokenStore: TokenStore,
    preferences: DataStore<Preferences>,
    private val webSessions: WebSessionRepository,
) : GroupRepository {
    private val prefs = ObservedPreferences(preferences)

    override suspend fun getGroup(name: String): GroupBo =
        tokenStore.withSessionToken { _, token -> api.getGroup(name, token).toGroupBo() }

    override suspend fun getTopics(name: String, offset: Int): PagedResult<CommunityTopicBo> =
        tokenStore.withSessionToken { _, token ->
            val page = api.getTopicsOfGroup(name, GROUP_PAGE_SIZE, offset, token)
            page.toPagedResult(offset, GROUP_PAGE_SIZE, page.data.map { it.toGroupPageTopicBo() })
        }

    override suspend fun getAllGroups(offset: Int): PagedResult<GroupSummaryBo> =
        tokenStore.withSessionToken { _, token ->
            api.getGroups(MODE_ALL, SORT_MEMBERS, GROUP_PAGE_SIZE, offset, token)
                .toPagedResult(offset, GROUP_PAGE_SIZE) { it.toGroupSummaryBo() }
        }

    /** 我加入的小组同时只取一次：社区页顶部那一行和小组新帖的分路同时要它。 */
    private val joinedFetches = SharedFetch<AccountSession, List<GroupSummaryBo>>()

    override suspend fun getJoinedGroups(): List<GroupSummaryBo> =
        tokenStore.withSessionToken { session, token ->
            if (token == null) {
                emptyList()
            } else {
                joinedFetches.fetch(session) {
                    fetchAllPages(CommunityApi.MAX_GROUPS_LIMIT) { offset, limit ->
                        api.getGroups(MODE_JOINED, SORT_MEMBERS, limit, offset, token).toSlice()
                    }.map { it.toGroupSummaryBo() }
                }
            }
        }

    override suspend fun createTopic(name: String, title: String, content: String, turnstileToken: String) =
        tokenStore.authenticatedOutcome { _, token ->
            outcomeOf { api.createGroupTopic(name, title, content, turnstileToken, token) }
        }.forbiddenAs(RejectReason.POSTING_NOT_ALLOWED)

    override suspend fun setJoined(name: String, joined: Boolean): Outcome<GroupBo> = tokenStore.authenticatedOutcome { session, token ->
        outcomeOf {
            val unavailable = Failure.Rejected(RejectReason.GROUP_MEMBERSHIP_UNAVAILABLE)
            webSessions.performOnPage(BgmWeb.group(name), missing = unavailable, knownSession = session) { html -> groupMembershipLinkOf(html, name, joined) }
            val group = api.getGroup(name, token).toGroupBo()
            if (group.joined != joined) throw FailureException(unavailable)
            group
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val pinned: Flow<Load<List<String>>> = tokenStore.sessionFlow
        .map { it.userId }
        .distinctUntilChanged()
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList()) else prefs.data.map { pinnedOf(it[PreferenceKeys.pinnedGroupsKey(userId)]) }
        }
        .distinctUntilChanged()
        .map<List<String>, Load<List<String>>> { Load.Ready(it) }
        .failuresAsLoad()

    override suspend fun setPinned(name: String, pinned: Boolean) {
        val userId = tokenStore.session().userId ?: return
        val key = PreferenceKeys.pinnedGroupsKey(userId)
        prefs.edit { stored ->
            val rest = pinnedOf(stored[key]) - name
            stored[key] = (if (pinned) listOf(name) + rest else rest).joinToString(PINNED_SEPARATOR)
        }
    }

    private fun pinnedOf(stored: String?): List<String> = stored.orEmpty().split(PINNED_SEPARATOR).filter { it.isNotBlank() }

    private companion object {
        const val MODE_ALL = "all"
        const val MODE_JOINED = "joined"
        const val SORT_MEMBERS = "members"

        /** 置顶名单存成一个字符串，按行分开（小组名是网页路径段，不含换行）。 */
        const val PINNED_SEPARATOR = "\n"
    }
}
