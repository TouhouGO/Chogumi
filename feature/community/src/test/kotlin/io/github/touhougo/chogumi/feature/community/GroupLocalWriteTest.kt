package io.github.touhougo.chogumi.feature.community

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.db.community.MutedGroupDao
import io.github.touhougo.chogumi.core.data.db.community.MutedGroupEntity
import io.github.touhougo.chogumi.core.data.repository.GroupMuteRepository
import io.github.touhougo.chogumi.core.data.repository.GroupRepository
import io.github.touhougo.chogumi.core.data.repository.NoBlockedUsers
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.fixedViewer
import io.github.touhougo.chogumi.core.data.repository.testDraftRepository
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CommunityTopicBo
import io.github.touhougo.chogumi.core.model.GroupBo
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.model.PagedResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** 本机置顶或屏蔽失败要提示，已显示的小组头部照常保留。 */
class GroupLocalWriteTest {
    private val main = TestMain()
    private val draftScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var vm: GroupViewModel
    private val group = GroupBo(GroupSummaryBo("a", "A", null, 1), "description", 2, 0, true)
    private var failPinnedRead = false
    private var pinnedNames: List<String> = emptyList()
    private val repository = object : GroupRepository {
        override suspend fun getGroup(name: String): GroupBo = group
        override suspend fun getTopics(name: String, offset: Int): PagedResult<CommunityTopicBo> = PagedResult(emptyList(), 0, null)
        override suspend fun getAllGroups(offset: Int): PagedResult<GroupSummaryBo> = error("unused")
        override suspend fun getJoinedGroups(): List<GroupSummaryBo> = error("unused")
        override suspend fun createTopic(name: String, title: String, content: String, turnstileToken: String): Outcome<Int> = error("unused")
        override suspend fun setJoined(name: String, joined: Boolean): Outcome<GroupBo> = error("unused")
        override val pinned: Flow<Load<List<String>>> = flow {
            emit(if (failPinnedRead) Load.Failed(Failure.Offline) else Load.Ready(pinnedNames))
        }
        override suspend fun setPinned(name: String, pinned: Boolean): Unit = throw FailureException(Failure.Offline)
    }
    private val dao = object : MutedGroupDao {
        override fun observeAll(accountId: Int): Flow<List<MutedGroupEntity>> = flowOf(emptyList())
        override suspend fun upsert(group: MutedGroupEntity): Unit = throw FailureException(Failure.Offline)
        override suspend fun delete(accountId: Int, name: String): Unit = throw FailureException(Failure.Offline)
    }

    @BeforeTest
    fun setUp() = main.install()

    @AfterTest
    fun tearDown() = main.uninstall(listOf(vm.viewModelScope, draftScope))

    private suspend fun open() {
        vm = withContext(main.dispatcher) { GroupViewModel("a", repository, GroupMuteRepository(dao, fixedViewer()), testDraftRepository(draftScope), AppClock { 0 }, NoBlockedUsers()) }
        awaitUntil("小组头部已到") { vm.uiState.value.group is Load.Ready }
    }

    private suspend fun assertFailure() {
        awaitUntil("本机写入失败提示已到") { vm.failures.current.value != null }
        assertEquals(NoticeText.Reason(Failure.Offline), vm.failures.current.value?.text)
        assertEquals(Load.Ready(group), vm.uiState.value.group)
    }

    @Test
    fun `置顶名单读取失败后点重试恢复名单`() = runBlocking {
        failPinnedRead = true
        open()
        awaitUntil("置顶名单失败提示已到") { vm.failures.current.value != null }
        val notice = assertNotNull(vm.failures.current.value)
        assertEquals(NoticeText.Reason(Failure.Offline), notice.text)
        val retry = assertNotNull(notice.retry)
        failPinnedRead = false
        pinnedNames = listOf("a")
        withContext(main.dispatcher) { retry() }
        awaitUntil("重试后的置顶名单已到") { vm.uiState.value.pinned }
        assertEquals(true, vm.uiState.value.pinned)
        assertEquals(Load.Ready(group), vm.uiState.value.group)
    }

    @Test
    fun `置顶保存失败时提示并保留小组内容`() = runBlocking {
        open()
        withContext(main.dispatcher) { vm.setPinned(true) }
        assertFailure()
        assertEquals(false, vm.uiState.value.pinned)
    }

    @Test
    fun `屏蔽保存失败时提示并保留小组内容`() = runBlocking {
        open()
        withContext(main.dispatcher) { vm.setMuted(true) }
        assertFailure()
        assertEquals(false, vm.uiState.value.muted)
    }

    @Test
    fun `取消屏蔽失败时提示并保留小组内容`() = runBlocking {
        open()
        withContext(main.dispatcher) { vm.setMuted(false) }
        assertFailure()
    }
}
