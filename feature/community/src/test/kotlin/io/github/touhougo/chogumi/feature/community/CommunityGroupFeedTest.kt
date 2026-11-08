package io.github.touhougo.chogumi.feature.community

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.db.community.MutedGroupDao
import io.github.touhougo.chogumi.core.data.db.community.MutedGroupEntity
import io.github.touhougo.chogumi.core.data.repository.CommunityRepository
import io.github.touhougo.chogumi.core.data.repository.GroupMuteRepository
import io.github.touhougo.chogumi.core.data.repository.GroupRepository
import io.github.touhougo.chogumi.core.data.repository.NoBlockedUsers
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.fixedViewer
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CommunityTopicBo
import io.github.touhougo.chogumi.core.model.CommunityTopicSource
import io.github.touhougo.chogumi.core.model.GroupBo
import io.github.touhougo.chogumi.core.model.GroupFeedCursor
import io.github.touhougo.chogumi.core.model.GroupFeedPage
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.model.GroupTopicScope
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.RakuenFilter
import io.github.touhougo.chogumi.core.model.groupOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 小组页只显示顶部那一行（实时加入名单）里、没被屏蔽的小组的帖子；已取到的页里可能还带着刚退出的小组。 */
class CommunityGroupFeedTest {

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

    private fun group(name: String) = GroupSummaryBo(name = name, title = name, icon = null, members = 1)

    private fun topic(id: Int, group: String) = CommunityTopicBo(
        key = "group-$id", webUrl = "https://bgm.tv/group/topic/$id", title = "t$id", episode = null,
        source = CommunityTopicSource.Group(group(group)), author = null, replies = 0, updatedAt = 0,
    )

    /** 数据层给的「我加入的」新帖：取页时还加入着 boring，之后退出了。 */
    private val feed = listOf(topic(1, "boring"), topic(2, "a"), topic(3, "boring"), topic(4, "film"))

    private val allStarted = CompletableDeferred<Unit>()
    private val allPage = CompletableDeferred<PagedResult<CommunityTopicBo>>()
    private val joinedFeedStarted = CompletableDeferred<Unit>()
    private val joinedGroupsStarted = CompletableDeferred<Unit>()
    private var joinedGroupsGate: CompletableDeferred<Unit>? = null

    private val community = object : CommunityRepository {
        override suspend fun getGroupFeed(scope: GroupTopicScope?, cursor: GroupFeedCursor): GroupFeedPage {
            // 与真实仓库同一契约：首页没传范围时，游客或名单为空都取全部小组
            if (scope == GroupTopicScope.ALL || (scope == null && (!signedIn || joined.isEmpty()))) {
                allStarted.complete(Unit)
                val page = allPage.await()
                return GroupFeedPage(GroupTopicScope.ALL, page.items, next = null)
            }
            joinedFeedStarted.complete(Unit)
            // 数据层按取页那一刻的实时加入名单分路取：名单里有 new 才有它的帖子
            val fresh = joined.filter { it == "new" }.map { topic(5, it) }
            return GroupFeedPage(GroupTopicScope.JOINED, fresh + feed, next = null)
        }

        override suspend fun getRakuen(filter: RakuenFilter, offset: Int): PagedResult<CommunityTopicBo> = error("超展开不在这条测试里")

        override suspend fun getMyTopics(scope: GroupTopicScope, offset: Int): PagedResult<CommunityTopicBo> = error("我的帖子不在这条测试里")
    }

    private val groups = object : GroupRepository {
        override suspend fun getGroup(name: String): GroupBo = error("小组页不在这条测试里")
        override suspend fun getTopics(name: String, offset: Int): PagedResult<CommunityTopicBo> = error("小组页不在这条测试里")
        override suspend fun getAllGroups(offset: Int): PagedResult<GroupSummaryBo> = error("全部小组不在这条测试里")
        override suspend fun getJoinedGroups(): List<GroupSummaryBo> {
            joinedGroupsStarted.complete(Unit)
            joinedGroupsGate?.await()
            return joined.map(::group)
        }
        override suspend fun createTopic(name: String, title: String, content: String, turnstileToken: String): Outcome<Int> =
            error("发帖不在这条测试里")
        override suspend fun setJoined(name: String, joined: Boolean): Outcome<GroupBo> {
            changeMembership(name, joined)
            return Outcome.Success(GroupBo(group(name), description = "", topics = 0, createdAt = 0, joined = joined))
        }
        override val pinned: Flow<Load<List<String>>> = flowOf(Load.Ready(emptyList()))
        override suspend fun setPinned(name: String, pinned: Boolean) = Unit
    }

    /** 数据层的令牌：游客没有，首页永远取全部小组。 */
    private var signedIn = true

    /** 实时加入名单（顶部那一行）。 */
    private var joined = listOf("a", "film")

    private fun changeMembership(name: String, isJoined: Boolean) {
        joined = if (isJoined) joined + name else joined.filterNot { it == name }
    }

    /** 本机屏蔽名单。 */
    private val mutedRows = MutableStateFlow(emptyList<MutedGroupEntity>())

    private val dao = object : MutedGroupDao {
        override fun observeAll(accountId: Int): Flow<List<MutedGroupEntity>> = mutedRows
        override suspend fun upsert(group: MutedGroupEntity): Unit = throw FailureException(Failure.Offline)
        override suspend fun delete(accountId: Int, name: String): Unit = error("取消屏蔽不在这条测试里")
    }

    /** 打开小组页，等加入名单就绪、首轮帖子落定并收敛到 [expected]，之后才允许刷新名单。 */
    private suspend fun assertGroupFeed(expected: List<String>) {
        viewModel = withContext(main.dispatcher) {
            CommunityViewModel(community, groups, GroupMuteRepository(dao, fixedViewer()), AppClock { 0 }, NoBlockedUsers())
        }
        // 屏蔽名单的首个值在订阅的同一步里落进 ViewModel（单线程 Main），之后再打开小组页
        awaitUntil("ViewModel 订阅屏蔽名单") { mutedRows.subscriptionCount.value > 0 }
        withContext(main.dispatcher) { viewModel.open(CommunityPage.GROUPS, isLoggedIn = true) }
        fun shown() = viewModel.uiState.value.listFor(CommunityPage.GROUPS).items.map { groupOf(it)?.name }
        awaitUntil("加入名单就绪且小组页只剩 $expected 的帖子") {
            val state = viewModel.uiState.value
            state.joinedGroups is Load.Ready && !state.groupFeed.isLoading && shown() == expected
        }
        assertEquals(expected, shown())
    }

    @Test
    fun `帖子先返回也要等加入名单就绪才能完成页面初始化`() = runBlocking {
        joined = listOf("boring", "a", "film")
        val releaseGroups = CompletableDeferred<Unit>()
        joinedGroupsGate = releaseGroups
        val opened = async(start = CoroutineStart.UNDISPATCHED) { assertGroupFeed(listOf("boring", "a", "boring", "film")) }
        joinedFeedStarted.await()
        joinedGroupsStarted.await()
        awaitUntil("帖子已上屏而加入名单仍被闸门按住") {
            viewModel.uiState.value.groupFeed.items.map { it.key } == listOf("group-1", "group-2", "group-3", "group-4")
        }
        try {
            assertFalse(opened.isCompleted, "帖子先到不代表加入名单这轮已就绪")
            assertEquals(Load.Pending, viewModel.uiState.value.joinedGroups)
        } finally {
            releaseGroups.complete(Unit)
        }
        opened.await()
        assertTrue(viewModel.uiState.value.joinedGroups is Load.Ready)
    }

    @Test
    fun `退出最后一个小组时清掉加入流并取全部小组的新帖`() = runBlocking {
        assertGroupFeed(listOf("a", "film"))
        assertIs<Outcome.Success<GroupBo>>(groups.setJoined("a", joined = false))
        assertIs<Outcome.Success<GroupBo>>(groups.setJoined("film", joined = false))
        withContext(main.dispatcher) { viewModel.refreshJoinedGroups() }
        withTimeout(io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS) { allStarted.await() }
        withContext(main.dispatcher) { assertEquals(emptyList(), viewModel.uiState.value.groupFeed.items) }
        allPage.complete(PagedResult(listOf(topic(9, "new")), total = 1, nextOffset = null))
        awaitUntil("全部小组新帖已到") { viewModel.uiState.value.groupFeed.items.any { it.key == "group-9" } }
        assertEquals(listOf("group-9"), viewModel.uiState.value.groupFeed.items.map { it.key })
    }

    @Test
    fun `游客登录后按新账号加入名单取帖`() = runBlocking {
        allPage.complete(PagedResult(listOf(topic(9, "guest")), total = 1, nextOffset = null))
        signedIn = false
        viewModel = withContext(main.dispatcher) { CommunityViewModel(community, groups, GroupMuteRepository(dao, fixedViewer()), AppClock { 0 }, NoBlockedUsers()) }
        withContext(main.dispatcher) { viewModel.open(CommunityPage.GROUPS, isLoggedIn = false) }
        awaitUntil("游客帖子已到") { viewModel.uiState.value.groupFeed.items.any { it.key == "group-9" } }
        signedIn = true
        withContext(main.dispatcher) { viewModel.open(CommunityPage.GROUPS, isLoggedIn = true) }
        awaitUntil("账号加入流已到") { viewModel.uiState.value.myGroups?.map { it.name } == listOf("a", "film") && viewModel.uiState.value.groupFeed.items.map { it.key } == listOf("group-2", "group-4") }
        assertEquals(listOf("group-2", "group-4"), viewModel.uiState.value.groupFeed.items.map { it.key })
    }

    @Test
    fun `无加入小组后加入新小组会重取加入流`() = runBlocking {
        allPage.complete(PagedResult(listOf(topic(9, "public")), total = 1, nextOffset = null))
        joined = emptyList()
        assertGroupFeed(listOf("public"))
        joined = listOf("a", "film")
        withContext(main.dispatcher) { viewModel.refreshJoinedGroups() }
        awaitUntil("加入后的帖子已到") { viewModel.uiState.value.groupFeed.items.map { it.key } == listOf("group-2", "group-4") }
        assertEquals(listOf("group-2", "group-4"), viewModel.uiState.value.groupFeed.items.map { it.key })
    }

    @Test
    fun `屏蔽写入失败会提示且保留现有帖子`() = runBlocking {
        assertGroupFeed(listOf("a", "film"))
        withContext(main.dispatcher) { viewModel.mute(group("a")) }
        awaitUntil("屏蔽失败提示已到") { viewModel.failures.current.value != null }
        assertEquals(NoticeText.Reason(Failure.Offline), viewModel.failures.current.value?.text)
        assertEquals(listOf("group-2", "group-4"), viewModel.uiState.value.groupFeed.items.map { it.key })
    }

    @Test
    fun `名单里多了刚加入的小组就重取，它的帖子合进来`() = runBlocking {
        assertGroupFeed(listOf("a", "film"))
        joined = listOf("a", "film", "new")
        withContext(main.dispatcher) { viewModel.refreshJoinedGroups() }
        awaitUntil("刚加入的小组的帖子已到") { viewModel.uiState.value.groupFeed.items.any { it.key == "group-5" } }
        assertEquals(listOf("group-5", "group-2", "group-4"), viewModel.uiState.value.groupFeed.items.map { it.key })
    }

    @Test
    fun `刚退出的小组的帖子不出现`() = runBlocking {
        joined = listOf("boring", "a", "film")
        assertGroupFeed(listOf("boring", "a", "boring", "film"))

        val left = assertIs<Outcome.Success<GroupBo>>(groups.setJoined("boring", joined = false))
        assertFalse(left.value.joined)
        withContext(main.dispatcher) { viewModel.refreshJoinedGroups() }

        awaitUntil("退出后名单与旧帖子都已更新") {
            viewModel.uiState.value.myGroups?.map { it.name } == listOf("a", "film") &&
                viewModel.uiState.value.groupFeed.items.map { it.key } == listOf("group-2", "group-4")
        }
        assertEquals(listOf("group-2", "group-4"), viewModel.uiState.value.groupFeed.items.map { it.key })
    }

    @Test
    fun `屏蔽的小组即使还加入着也不出现`() = runBlocking {
        joined = listOf("boring", "a", "film")
        mutedRows.value = listOf(MutedGroupEntity(0, "boring", "boring", null, 1, mutedAt = 0))
        assertGroupFeed(listOf("a", "film"))
    }
}
