package io.github.touhougo.chogumi.feature.community

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.db.community.MutedGroupDao
import io.github.touhougo.chogumi.core.data.db.community.MutedGroupEntity
import io.github.touhougo.chogumi.core.data.repository.GroupMuteRepository
import io.github.touhougo.chogumi.core.data.repository.GroupRepository
import io.github.touhougo.chogumi.core.data.repository.NoBlockedUsers
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.fixedViewer
import io.github.touhougo.chogumi.core.data.repository.testDraftRepository
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.CommunityTopicBo
import io.github.touhougo.chogumi.core.model.GroupBo
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.model.PagedResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 加入、退出小组没做成：只能在网页上做的，提示带「去网页」；别的失败提示带重试。 */
class GroupMembershipTest {
    private val main = TestMain()
    private val draftScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var vm: GroupViewModel
    private val group = GroupBo(GroupSummaryBo("a", "A", null, 1), "description", 2, 0, false)
    private var joinFailure: Failure = Failure.Offline
    private val repository = object : GroupRepository {
        override suspend fun getGroup(name: String): GroupBo = group
        override suspend fun getTopics(name: String, offset: Int): PagedResult<CommunityTopicBo> = PagedResult(emptyList(), 0, null)
        override suspend fun getAllGroups(offset: Int): PagedResult<GroupSummaryBo> = error("unused")
        override suspend fun getJoinedGroups(): List<GroupSummaryBo> = error("unused")
        override suspend fun createTopic(name: String, title: String, content: String, turnstileToken: String): Outcome<Int> = error("unused")
        override suspend fun setJoined(name: String, joined: Boolean): Outcome<GroupBo> = Outcome.Failed(joinFailure)
        override val pinned: Flow<Load<List<String>>> = flowOf(Load.Ready(emptyList()))
        override suspend fun setPinned(name: String, pinned: Boolean) = Unit
    }
    private val dao = object : MutedGroupDao {
        override fun observeAll(accountId: Int): Flow<List<MutedGroupEntity>> = flowOf(emptyList())
        override suspend fun upsert(group: MutedGroupEntity) = Unit
        override suspend fun delete(accountId: Int, name: String) = Unit
    }

    @BeforeTest
    fun setUp() = main.install()

    @AfterTest
    fun tearDown() = main.uninstall(listOf(vm.viewModelScope, draftScope))

    /**
     * 等 Main 上已经排队的那一轮跑完。只对整轮不离开 Main 的一轮成立：同步抛的假仓库（`refreshGroup` 的重取），或放行一个
     * 挂在 Main 协程里的读（续体在放行那一刻就排进 Main）——这一拍排在它后面，它处理完才轮到这里。会在别的线程上挂起的一轮
     * （DataStore、计算调度器）排空 Main 证明不了什么，要等它自己的信号。
     */
    private suspend fun settleMain() = withContext(main.dispatcher) { }

    private suspend fun joinAndAwaitNotice() {
        vm = withContext(main.dispatcher) { GroupViewModel("a", repository, GroupMuteRepository(dao, fixedViewer()), testDraftRepository(draftScope), AppClock { 0 }, NoBlockedUsers()) }
        awaitUntil("小组头部已到") { vm.uiState.value.group is Load.Ready }
        withContext(main.dispatcher) { vm.setJoined(true) }
        awaitUntil("加入失败的提示已到") { vm.failures.current.value != null }
    }

    @Test
    fun `只能在网页上加入时 提示的动作是去小组网页`() = runBlocking {
        joinFailure = Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED)
        joinAndAwaitNotice()

        val notice = assertNotNull(vm.failures.current.value)
        assertEquals(NoticeText.Reason(joinFailure), notice.text)
        assertEquals(BgmWeb.group("a"), notice.webUrl)
        assertNull(notice.retry, "去网页与重试只有一个动作")
        assertEquals(false, vm.uiState.value.membershipPending)
    }

    @Test
    fun `别的失败 提示带重试 不去网页`() = runBlocking {
        joinFailure = Failure.Offline
        joinAndAwaitNotice()

        val notice = assertNotNull(vm.failures.current.value)
        assertEquals(NoticeText.Reason(Failure.Offline), notice.text)
        assertNull(notice.webUrl)
        assertNotNull(notice.retry)
        Unit
    }

    /** 回到页面时的后台重取：用户没动手，取不到不弹提示，已显示的头部留着。 */
    @Test
    fun `回到页面重取头部失败 不弹提示 头部留着`() = runBlocking {
        var failNext = false
        val flaky = object : GroupRepository by repository {
            override suspend fun getGroup(name: String): GroupBo = if (failNext) throw java.io.IOException("offline") else group
        }
        vm = withContext(main.dispatcher) { GroupViewModel("a", flaky, GroupMuteRepository(dao, fixedViewer()), testDraftRepository(draftScope), AppClock { 0 }, NoBlockedUsers()) }
        awaitUntil("小组头部已到") { vm.uiState.value.group is Load.Ready }

        failNext = true
        withContext(main.dispatcher) { vm.refreshGroup() }
        settleMain()

        assertNull(vm.failures.current.value)
        assertEquals(Load.Ready(group), vm.uiState.value.group)
    }

    @Test
    fun `写前发出的旧头部在加入成功后返回 不覆盖新成员状态`() = runBlocking {
        val oldReadStarted = CompletableDeferred<Unit>()
        val releaseOldRead = CompletableDeferred<Unit>()
        val joined = group.withMembership(joined = true, members = 2)
        var reads = 0
        val gated = object : GroupRepository by repository {
            override suspend fun getGroup(name: String): GroupBo = when (++reads) {
                1 -> group
                else -> {
                    oldReadStarted.complete(Unit)
                    releaseOldRead.await()
                    group
                }
            }

            override suspend fun setJoined(name: String, joined: Boolean): Outcome<GroupBo> = Outcome.Success(
                group.withMembership(joined = true, members = 2),
            )
        }
        vm = open(gated)

        withContext(main.dispatcher) { vm.refresh() }
        withTimeout(WAIT_BUDGET_MS) { oldReadStarted.await() }
        withContext(main.dispatcher) { vm.setJoined(true) }
        awaitUntil("加入成功头部已到") { vm.uiState.value.group == Load.Ready(joined) }
        releaseOldRead.complete(Unit)
        settleMain()

        assertEquals(Load.Ready(joined), vm.uiState.value.group)
    }

    @Test
    fun `加入写入中发出的旧头部在成功后返回 不覆盖新成员状态`() = runBlocking {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val oldReadReturned = CompletableDeferred<Unit>()
        val releaseOldRead = CompletableDeferred<Unit>()
        val joined = group.withMembership(joined = true, members = 2)
        var reads = 0
        val gated = object : GroupRepository by repository {
            override suspend fun getGroup(name: String): GroupBo = when (++reads) {
                1 -> group
                else -> {
                    oldReadReturned.complete(Unit)
                    releaseOldRead.await()
                    group
                }
            }

            override suspend fun setJoined(name: String, joined: Boolean): Outcome<GroupBo> {
                writeStarted.complete(Unit)
                releaseWrite.await()
                return Outcome.Success(group.withMembership(joined = true, members = 2))
            }
        }
        vm = open(gated)

        withContext(main.dispatcher) { vm.setJoined(true) }
        withTimeout(WAIT_BUDGET_MS) { writeStarted.await() }
        withContext(main.dispatcher) { vm.refresh() }
        withTimeout(WAIT_BUDGET_MS) { oldReadReturned.await() }
        releaseWrite.complete(Unit)
        awaitUntil("加入成功头部已到") { vm.uiState.value.group == Load.Ready(joined) }
        releaseOldRead.complete(Unit)
        settleMain()

        assertEquals(Load.Ready(joined), vm.uiState.value.group)
    }

    @Test
    fun `退出写入中发出的旧头部在成功后返回 不覆盖新成员状态`() = runBlocking {
        val joinedBefore = group.withMembership(joined = true, members = 2)
        val left = group.withMembership(joined = false, members = 1)
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val oldReadReturned = CompletableDeferred<Unit>()
        val releaseOldRead = CompletableDeferred<Unit>()
        var reads = 0
        val gated = object : GroupRepository by repository {
            override suspend fun getGroup(name: String): GroupBo = when (++reads) {
                1 -> joinedBefore
                else -> {
                    oldReadReturned.complete(Unit)
                    releaseOldRead.await()
                    joinedBefore
                }
            }

            override suspend fun setJoined(name: String, joined: Boolean): Outcome<GroupBo> {
                writeStarted.complete(Unit)
                releaseWrite.await()
                return Outcome.Success(left)
            }
        }
        vm = open(gated)

        withContext(main.dispatcher) { vm.setJoined(false) }
        withTimeout(WAIT_BUDGET_MS) { writeStarted.await() }
        withContext(main.dispatcher) { vm.refreshGroup() }
        withTimeout(WAIT_BUDGET_MS) { oldReadReturned.await() }
        releaseWrite.complete(Unit)
        awaitUntil("退出成功头部已到") { vm.uiState.value.group == Load.Ready(left) }
        releaseOldRead.complete(Unit)
        settleMain()

        assertEquals(Load.Ready(left), vm.uiState.value.group)
    }

    @Test
    fun `成员写入失败后在途与新头部读取都可更新且旧失败提示保留`() = runBlocking {
        val afterFailure = group.withMembership(joined = true, members = 2)
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val oldReadReturned = CompletableDeferred<Unit>()
        val releaseOldRead = CompletableDeferred<Unit>()
        var current = group
        var reads = 0
        val failing = object : GroupRepository by repository {
            override suspend fun getGroup(name: String): GroupBo = when (++reads) {
                1 -> current
                2 -> {
                    oldReadReturned.complete(Unit)
                    releaseOldRead.await()
                    group.withMembership(joined = true, members = 9)
                }
                else -> current
            }

            override suspend fun setJoined(name: String, joined: Boolean): Outcome<GroupBo> {
                writeStarted.complete(Unit)
                releaseWrite.await()
                return Outcome.Failed(Failure.Offline)
            }
        }
        vm = open(failing)

        withContext(main.dispatcher) { vm.setJoined(true) }
        withTimeout(WAIT_BUDGET_MS) { writeStarted.await() }
        withContext(main.dispatcher) { vm.refreshGroup() }
        withTimeout(WAIT_BUDGET_MS) { oldReadReturned.await() }
        releaseWrite.complete(Unit)
        awaitUntil("加入失败提示已到") { vm.failures.current.value != null }
        releaseOldRead.complete(Unit)
        val inFlightAfterFailure = group.withMembership(joined = true, members = 9)
        awaitUntil("失败时在途的头部已到") { vm.uiState.value.group == Load.Ready(inFlightAfterFailure) }

        current = afterFailure
        withContext(main.dispatcher) { vm.refreshGroup() }
        awaitUntil("失败后的新头部已到") { vm.uiState.value.group == Load.Ready(afterFailure) }

        assertEquals(NoticeText.Reason(Failure.Offline), assertNotNull(vm.failures.current.value).text)
        assertEquals(Load.Ready(afterFailure), vm.uiState.value.group)
        assertEquals(false, vm.uiState.value.membershipPending)
    }

    @Test
    fun `没有成员写入时新头部读取正常更新`() = runBlocking {
        val refreshed = group.copy(description = "new description", topics = 3)
        val pinnedNames = MutableStateFlow<Load<List<String>>>(Load.Ready(emptyList()))
        val mutedGroups = MutableStateFlow<List<MutedGroupEntity>>(emptyList())
        val independentDao = object : MutedGroupDao by dao {
            override fun observeAll(accountId: Int): Flow<List<MutedGroupEntity>> = mutedGroups
        }
        var current = group
        val changing = object : GroupRepository by repository {
            override suspend fun getGroup(name: String): GroupBo = current
            override val pinned: Flow<Load<List<String>>> = pinnedNames
        }
        vm = open(changing, independentDao)

        pinnedNames.value = Load.Ready(listOf("a"))
        mutedGroups.value = listOf(MutedGroupEntity(0, "a", "A", null, 1, 0))
        awaitUntil("置顶与屏蔽名单已到") { vm.uiState.value.pinned && vm.uiState.value.muted }

        current = refreshed
        withContext(main.dispatcher) { vm.refreshGroup() }
        awaitUntil("普通新头部已到") { vm.uiState.value.group == Load.Ready(refreshed) }

        assertEquals(Load.Ready(refreshed), vm.uiState.value.group)
        assertEquals(true, vm.uiState.value.pinned)
        assertEquals(true, vm.uiState.value.muted)
    }

    private suspend fun open(source: GroupRepository, mutedDao: MutedGroupDao = dao): GroupViewModel {
        val opened = withContext(main.dispatcher) {
            GroupViewModel("a", source, GroupMuteRepository(mutedDao, fixedViewer()), testDraftRepository(draftScope), AppClock { 0 }, NoBlockedUsers())
        }
        awaitUntil("小组头部已到") { opened.uiState.value.group is Load.Ready }
        return opened
    }

    private fun GroupBo.withMembership(joined: Boolean, members: Int) = copy(summary = summary.copy(members = members), joined = joined)
}
