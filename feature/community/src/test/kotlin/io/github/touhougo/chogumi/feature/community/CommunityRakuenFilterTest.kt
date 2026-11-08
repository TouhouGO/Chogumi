package io.github.touhougo.chogumi.feature.community

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.repository.CommunityRepository
import io.github.touhougo.chogumi.core.data.repository.FakeMutedGroupDao
import io.github.touhougo.chogumi.core.data.repository.GroupMuteRepository
import io.github.touhougo.chogumi.core.data.repository.GroupRepository
import io.github.touhougo.chogumi.core.data.repository.NoBlockedUsers
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.fixedViewer
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CommunityTopicBo
import io.github.touhougo.chogumi.core.model.GroupBo
import io.github.touhougo.chogumi.core.model.GroupFeedCursor
import io.github.touhougo.chogumi.core.model.GroupFeedPage
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.model.GroupTopicScope
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.RakuenFilter
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 超展开换分类：每个分类一份列表。进超展开就把每个分类都取上；换到取过的分类马上是它的帖子、不再发请求；
 * 换到还在路上的分类画骨架，不留上一个分类的帖子。超展开的接口一次要一两秒，换分类等它就是「点了半天才换」。
 */
class CommunityRakuenFilterTest {

    private val main = TestMain()
    private lateinit var viewModel: CommunityViewModel

    @BeforeTest
    fun setUp() {
        main.install()
    }

    @AfterTest
    fun tearDown() {
        main.uninstall(listOf(viewModel.viewModelScope))
    }

    private fun topics(filter: RakuenFilter) = List(3) { i ->
        CommunityTopicBo(
            key = "${filter.name}-$i", webUrl = "https://bgm.tv/ep/$i", title = "${filter.name} $i", episode = null,
            source = null, author = null, replies = 0, updatedAt = 0,
        )
    }

    /** 每个分类的请求在测试放行前一直挂着：放行的先后由测试定。 */
    private val gates = RakuenFilter.entries.associateWith { CompletableDeferred<Unit>() }

    /** 发出过的超展开请求（按分类）。 */
    private val requests = ConcurrentLinkedQueue<RakuenFilter>()

    private val community = object : CommunityRepository {
        override suspend fun getGroupFeed(scope: GroupTopicScope?, cursor: GroupFeedCursor): GroupFeedPage = error("小组页不在这条测试里")

        override suspend fun getRakuen(filter: RakuenFilter, offset: Int): PagedResult<CommunityTopicBo> {
            requests += filter
            gates.getValue(filter).await()
            val items = topics(filter)
            return PagedResult(items, total = items.size, nextOffset = null)
        }

        override suspend fun getMyTopics(scope: GroupTopicScope, offset: Int): PagedResult<CommunityTopicBo> = error("我的帖子不在这条测试里")
    }

    private val groups = object : GroupRepository {
        override suspend fun getGroup(name: String): GroupBo = error("小组页不在这条测试里")
        override suspend fun getTopics(name: String, offset: Int): PagedResult<CommunityTopicBo> = error("小组页不在这条测试里")
        override suspend fun getAllGroups(offset: Int): PagedResult<GroupSummaryBo> = error("全部小组不在这条测试里")
        override suspend fun getJoinedGroups(): List<GroupSummaryBo> = error("小组页不在这条测试里")
        override suspend fun createTopic(name: String, title: String, content: String, turnstileToken: String): Outcome<Int> =
            error("发帖不在这条测试里")
        override suspend fun setJoined(name: String, joined: Boolean): Outcome<GroupBo> = error("加入退出不在这条测试里")
        override val pinned: Flow<Load<List<String>>> = flowOf(Load.Ready(emptyList()))
        override suspend fun setPinned(name: String, pinned: Boolean) = Unit
    }

    private suspend fun openRakuen() {
        viewModel = withContext(main.dispatcher) {
            CommunityViewModel(community, groups, GroupMuteRepository(FakeMutedGroupDao(), fixedViewer()), AppClock { 0 }, NoBlockedUsers())
        }
        withContext(main.dispatcher) { viewModel.open(CommunityPage.RAKUEN, isLoggedIn = false) }
    }

    private fun shownKeys() = viewModel.uiState.value.listFor(CommunityPage.RAKUEN).items.map { it.key }

    @Test
    fun `进超展开时每个分类都已在取`() = runBlocking {
        openRakuen()
        // 请求只会越发越多：等到六个分类都发过
        awaitUntil("六个分类都发出请求") { requests.toSet() == RakuenFilter.entries.toSet() }
        assertEquals(RakuenFilter.ALL, requests.first(), "当前分类先发")
    }

    @Test
    fun `换到在路上的分类画骨架_换回取过的分类马上出现且不重取`() = runBlocking {
        openRakuen()
        gates.getValue(RakuenFilter.ALL).complete(Unit)
        awaitUntil("「全部」的帖子到了") { shownKeys() == topics(RakuenFilter.ALL).map { it.key } }

        val episodeList = withContext(main.dispatcher) {
            viewModel.selectRakuenFilter(RakuenFilter.EPISODE)
            viewModel.uiState.value.listFor(CommunityPage.RAKUEN)
        }
        assertTrue(episodeList.items.isEmpty(), "换分类后不留「全部」的帖子：${episodeList.items.map { it.key }}")
        assertTrue(episodeList.isLoading, "单集还在路上：画骨架")

        gates.getValue(RakuenFilter.EPISODE).complete(Unit)
        awaitUntil("「单集」的帖子到了") { shownKeys() == topics(RakuenFilter.EPISODE).map { it.key } }

        val backToAll = withContext(main.dispatcher) {
            viewModel.selectRakuenFilter(RakuenFilter.ALL)
            viewModel.uiState.value.listFor(CommunityPage.RAKUEN)
        }
        assertEquals(topics(RakuenFilter.ALL).map { it.key }, backToAll.items.map { it.key }, "换回「全部」马上是它的帖子")
        assertEquals(1, requests.count { it == RakuenFilter.ALL }, "换回取过的分类不重取")
    }
}
