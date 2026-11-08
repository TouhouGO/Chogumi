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
import io.github.touhougo.chogumi.core.model.BlockedUsers
import io.github.touhougo.chogumi.core.model.CommunityTopicBo
import io.github.touhougo.chogumi.core.model.GroupBo
import io.github.touhougo.chogumi.core.model.GroupFeedCursor
import io.github.touhougo.chogumi.core.model.GroupFeedPage
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.model.GroupTopicScope
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.RakuenFilter
import io.github.touhougo.chogumi.core.model.UserBo
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 社区的帖子流跟着绝交名单：绝交用户发的帖不显示；在别处绝交、取消绝交后，已取到的帖子当场按新名单重算，不重取
 * （返回栈里的列表不等下拉刷新）；名单还没到时帖子先到也不画出来。
 */
class CommunityBlockedUsersTest {

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

    private fun topic(key: String, authorId: Int) = CommunityTopicBo(
        key = key, webUrl = "https://bgm.tv/group/topic/$key", title = key, episode = null, source = null,
        author = UserBo(id = authorId, username = "u$authorId", nickname = "u$authorId"), replies = 0, updatedAt = 0,
    )

    private val rakuenRequests = AtomicInteger()

    private val community = object : CommunityRepository {
        override suspend fun getGroupFeed(scope: GroupTopicScope?, cursor: GroupFeedCursor): GroupFeedPage = error("小组页不在这条测试里")

        override suspend fun getRakuen(filter: RakuenFilter, offset: Int): PagedResult<CommunityTopicBo> {
            if (filter == RakuenFilter.ALL) rakuenRequests.incrementAndGet()
            val items = listOf(topic("a", authorId = 9), topic("b", authorId = 2))
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

    private suspend fun openRakuen(blocked: NoBlockedUsers) {
        viewModel = withContext(main.dispatcher) {
            CommunityViewModel(community, groups, GroupMuteRepository(FakeMutedGroupDao(), fixedViewer()), AppClock { 0 }, blocked)
        }
        withContext(main.dispatcher) { viewModel.open(CommunityPage.RAKUEN, isLoggedIn = true) }
    }

    private fun shownKeys() = viewModel.uiState.value.listFor(CommunityPage.RAKUEN).items.map { it.key }

    @Test
    fun `已显示的帖子里绝交一个人_这个人的帖当场不显示且不重取_取消后回来`() = runBlocking {
        val blocked = NoBlockedUsers()
        openRakuen(blocked)
        awaitUntil("帖子到了") { shownKeys() == listOf("a", "b") }

        blocked.list.value = BlockedUsers(setOf(9))
        awaitUntil("绝交后列表跟上") { shownKeys() == listOf("b") }
        assertEquals(1, rakuenRequests.get(), "名单变了只重算已取到的帖子，不重取")

        blocked.list.value = BlockedUsers.NONE
        awaitUntil("取消绝交后帖子回来") { shownKeys() == listOf("a", "b") }
        assertEquals(1, rakuenRequests.get())
    }

    @Test
    fun `名单还没到时帖子先到_不先画出被绝交的人的帖_名单到了按它一次画出`() = runBlocking {
        val blocked = NoBlockedUsers(initial = null)
        openRakuen(blocked)
        awaitUntil("帖子请求发出") { rakuenRequests.get() == 1 }
        val waiting = withContext(main.dispatcher) { viewModel.uiState.value.listFor(CommunityPage.RAKUEN) }
        assertTrue(waiting.items.isEmpty() && waiting.isLoading, "名单没到：仍是首屏加载，一帖也不画：${waiting.items.map { it.key }}")

        blocked.list.value = BlockedUsers(setOf(9))
        awaitUntil("名单到了") { !viewModel.uiState.value.listFor(CommunityPage.RAKUEN).isLoading }
        assertEquals(listOf("b"), shownKeys())
    }
}
