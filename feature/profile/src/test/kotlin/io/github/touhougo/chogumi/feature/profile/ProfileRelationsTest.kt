package io.github.touhougo.chogumi.feature.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.repository.NoBlockedUsers
import io.github.touhougo.chogumi.core.ui.moderation.ReportReason
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.db.user.UserDao
import io.github.touhougo.chogumi.core.data.db.user.UserEntity
import io.github.touhougo.chogumi.core.data.mark.StillCollectionEditing
import io.github.touhougo.chogumi.core.data.mark.StillCollectionMarker
import io.github.touhougo.chogumi.core.data.repository.BlocklistRepository
import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.IndexRepository
import io.github.touhougo.chogumi.core.data.repository.FriendsRepository
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.TimelineRepository
import io.github.touhougo.chogumi.core.data.repository.AccountMe
import io.github.touhougo.chogumi.core.data.repository.UserRepository
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testDraftRepository
import io.github.touhougo.chogumi.core.data.repository.testOtherUserCollectionsCache
import io.github.touhougo.chogumi.core.data.repository.testWebClient
import io.github.touhougo.chogumi.core.data.repository.testWebSessionRepository
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.IndexApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.TimelineApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule

/** 个人页关系读写走真实账号、偏好缓存和接口，只替换传输与可控的存储故障。 */
class ProfileRelationsTest {
    @get:Rule
    val resources = TestResources()

    private val main = TestMain()
    private val escaped = MutableStateFlow<List<Throwable>>(emptyList())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, error -> escaped.update { it + error } })
    private val viewModels = mutableListOf<ProfileViewModel>()
    private val clients = mutableListOf<HttpClient>()
    private val engines = mutableListOf<HttpClientEngine>()
    private val directories = mutableListOf<File>()
    private var priorHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeTest
    fun setUp() {
        main.install()
        runBlocking(main.dispatcher) {
            priorHandler = Thread.currentThread().uncaughtExceptionHandler
            Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> escaped.update { it + error } }
        }
    }

    @AfterTest
    fun tearDown() {
        val scopes = viewModels.map { it.viewModelScope } + scope
        cancelAndAwait(scopes)
        clients.forEach { it.close() }
        engines.forEach { it.close() }
        cancelAndAwait(clients.map { CoroutineScope(it.coroutineContext) } + engines.map { CoroutineScope(it.coroutineContext) })
        runBlocking(main.dispatcher) { Thread.currentThread().uncaughtExceptionHandler = priorHandler }
        main.uninstall(scopes)
        directories.forEach { it.deleteRecursively() }
        assertEquals(emptyList(), escaped.value, "读取失败不得逃出生产作用域")
    }

    private class FaultDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        val failed = AtomicBoolean(false)
        var readFinished = CompletableDeferred<Unit>()
        /** 拦在这之后开始的读取：每一拍读到值之后等它放行；之前已经在读的（主页跟着名单走的那条）不受影响。 */
        var readGate: CompletableDeferred<Unit>? = null
        /** 拦住已经在读的那些读取的后续拍（名单写入带来的那一拍）：放行之前它们停在旧值上；新开始的读取的第一拍不受影响。 */
        var updateGate: CompletableDeferred<Unit>? = null
        /** 放过 [readGate] 的拍数。 */
        val delivered = AtomicInteger(0)
        override val data: Flow<Preferences> = flow {
            val gate = readGate
            var first = true
            emitAll(
                delegate.data.map {
                    readFinished.complete(Unit)
                    gate?.await()
                    if (!first) updateGate?.await()
                    first = false
                    if (failed.get()) throw IOException("绝交名单缓存不可读")
                    delivered.incrementAndGet()
                    it
                },
            )
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = delegate.updateData(transform)
    }

    private class Harness(
        val viewModel: ProfileViewModel,
        val faultStore: FaultDataStore,
        val requests: ConcurrentRequestRecorder,
        val blocklist: BlocklistRepository,
        val friends: FriendsRepository,
        val session: io.github.touhougo.chogumi.core.data.token.AccountSession,
        val friend: AtomicBoolean,
        val reportGate: CompletableDeferred<Unit>,
        val reportEntered: CompletableDeferred<Unit>,
        val reportFailed: AtomicBoolean,
    )

    /** [staleProfileFriend]：对方 p1 资料上的 `isFriend` 恒为 true（服务端一小时的好友缓存，取消关注后不清），与好友名单无关。 */
    private suspend fun harness(
        failRead: Boolean = false,
        staleProfileFriend: Boolean = false,
        friendWriteGate: CompletableDeferred<Unit>? = null,
        username: String? = "bob",
        blockWriteGate: CompletableDeferred<Unit>? = null,
        relationReadGate: CompletableDeferred<Unit>? = null,
    ): Harness {
        val dir = File(System.getProperty("java.io.tmpdir"), "profile-relations-${System.nanoTime()}").apply { mkdirs(); directories += this }
        fun dataStore(name: String) = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "$name.preferences_pb") }
        val store = TokenStore(dataStore("tokens"))
        val session = store.completeSession(store.beginSession("profile-test-token"), 42, "viewer")
        val requests = ConcurrentRequestRecorder()
        val friend = AtomicBoolean(false)
        val reportGate = CompletableDeferred<Unit>()
        val reportEntered = CompletableDeferred<Unit>()
        val reportFailed = AtomicBoolean(false)
        val engine = MockEngine { request ->
            requests.record(request)
            if (request.url.encodedPath == "/p1/report" && request.method == HttpMethod.Post) {
                reportEntered.complete(Unit)
                reportGate.await()
                return@MockEngine respond(
                    if (reportFailed.get()) """{"code":"ERROR","error":"Bad Request","message":"invalid report","statusCode":400}""" else """{"message":"感谢报告，我们会尽快处理"}""",
                    if (reportFailed.get()) HttpStatusCode.BadRequest else HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            }
            val body = when {
                request.url.encodedPath == "/v0/users/bob" -> """{"id":99,"username":"bob","nickname":"Bob","user_group":10,"avatar":{"large":"l","medium":"m","small":"s"},"sign":"","email":"bob@example.invalid","reg_time":"2020-01-01T00:00:00Z","time_offset":0}"""
                request.url.encodedPath == "/p1/users/bob" -> p1User(friend.get() || staleProfileFriend)
                request.url.encodedPath == "/v0/me" -> """{"id":42,"username":"viewer","nickname":"Viewer","user_group":10,"avatar":{"large":"l","medium":"m","small":"s"},"sign":"","email":"viewer@example.invalid","reg_time":"2020-01-01T00:00:00Z","time_offset":8}"""
                // 资料上的好友数是服务端缓存，加、取消好友后不变；好友数要跟着列表的 total
                request.url.encodedPath == "/p1/users/viewer" -> p1Viewer(staleFriendCount = 7)
                request.url.encodedPath == "/p1/users/bob/friends" -> """{"data":[],"total":0}"""
                request.url.encodedPath == "/p1/friends/bob" && request.method == HttpMethod.Put -> {
                    friendWriteGate?.await()
                    "{}".also { friend.set(true) }
                }
                request.url.encodedPath == "/p1/users/viewer/friends" -> if (friend.get()) {
                    """{"data":[{"id":99,"username":"bob","nickname":"Bob","avatar":{"large":"","medium":"","small":""},"group":10,"joinedAt":0,"sign":"","isFriend":true}],"total":1}"""
                } else {
                    """{"data":[],"total":0}"""
                }
                request.url.encodedPath == "/p1/blocklist/bob" && request.method == HttpMethod.Put -> {
                    blockWriteGate?.await()
                    """{"blocklist":[99]}"""
                }
                request.url.encodedPath == "/p1/blocklist/bob" && request.method == HttpMethod.Delete -> """{"blocklist":[]}"""
                else -> error("未预期的请求 ${request.method} ${request.url.encodedPath}")
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }.also { engines += it }
        val client = testApiClient(engine).also { clients += it }
        val web = testWebClient { error("本用例不打开网页") }.also { clients += it; engines += it.engine }
        val webSessions = testWebSessionRepository(store, web)
        val fault = FaultDataStore(dataStore("blocklist"))
        val clock = AppClock { 1_000L }
        val blocked = BlocklistRepository(fault, UserApi(client), store, webSessions, clock, scope)
        blocked.record(session, emptyList())
        fault.failed.set(failRead)
        fault.readGate = relationReadGate
        val friends = FriendsRepository(dataStore("friends"), UserApi(client), store, clock, scope)
        val users = UserRepository(UserApi(client), store, object : UserDao {
            override suspend fun get(id: Int): UserEntity? = null
            override suspend fun getByUsername(username: String): UserEntity? = null
            override suspend fun upsert(user: UserEntity) = Unit
        }, clock, webSessions, friends, blocked, AccountMe(UserApi(client), store))
        val collections = testCollections(resources, CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), store, FakeEpisodeWatchStateDao(), webClient = web)
        val vm = withContext(Dispatchers.Main) {
            ProfileViewModel(
                username, users, collections.store, testOtherUserCollectionsCache(CollectionApi(client), FakeSubjectDao(), store),
                TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, webSessions, scope, Dispatchers.Default),
                StillCollectionMarker(), StillCollectionEditing(), testAppTime(1_000L),
                testDraftRepository(scope, tokens = store), clock, NoBlockedUsers(), IndexRepository(IndexApi(client), UserApi(client), store),
            ).also { viewModels += it }
        }
        return Harness(vm, fault, requests, blocked, friends, session, friend, reportGate, reportEntered, reportFailed)
    }

    private fun p1User(friend: Boolean) = """{"id":99,"username":"bob","nickname":"Bob","avatar":{"large":"l","medium":"m","small":"s"},"group":10,"joinedAt":0,"sign":"","site":"","location":"","bio":"","networkServices":[],"homepage":{"left":[],"right":[]},"stats":{"subject":{},"mono":{"character":0,"person":0},"blog":0,"friend":0,"group":0,"index":{"create":0,"collect":0}},"isFriend":$friend}"""

    private fun p1Viewer(staleFriendCount: Int) = """{"id":42,"username":"viewer","nickname":"Viewer","avatar":{"large":"l","medium":"m","small":"s"},"group":10,"joinedAt":0,"sign":"","site":"","location":"","bio":"","networkServices":[],"homepage":{"left":[],"right":[]},"stats":{"subject":{},"mono":{"character":0,"person":0},"blog":0,"friend":$staleFriendCount,"group":0,"index":{"create":0,"collect":0}},"isFriend":false}"""

    private suspend fun ProfileViewModel.awaitProfile() {
        withTimeout(WAIT_BUDGET_MS) { uiState.first { it.userLoad is Load.Ready } }
        withContext(Dispatchers.Main) { }
    }

    private suspend fun ProfileViewModel.actionJob(action: () -> Unit): Job = withContext(Dispatchers.Main) {
        val owner = viewModelScope.coroutineContext[Job]!!
        val before = owner.children.toSet()
        action()
        owner.children.single { it !in before }
    }

    /**
     * 刷新这一轮发起的那次只读一次的名单读取。「读到值了」不能当它已经开始的信号：主页跟着名单走的那条订阅也读同一份存储，
     * 它的一拍可以先把 [FaultDataStore.readFinished] 置上；所以等这个协程真的出现再取。
     */
    private suspend fun ProfileViewModel.refreshRead(before: Set<Job>, refresh: Job): Job {
        val owner = viewModelScope.coroutineContext[Job]!!
        awaitUntil("刷新发起的名单读取已经开始") { owner.children.any { it !in before && it !== refresh } }
        return owner.children.single { it !in before && it !== refresh }
    }

    private suspend fun ProfileViewModel.awaitFailure() = withTimeout(WAIT_BUDGET_MS) {
        merge(failures.current.filterNotNull(), escaped.map { errors ->
            assertEquals(emptyList(), errors, "绝交名单IO失败必须进入FailureNotices")
            null
        }.filterNotNull()).first()
    }

    private suspend fun Harness.beginReport(): Job {
        viewModel.awaitProfile()
        withContext(Dispatchers.Main) {
            viewModel.openReport()
            viewModel.moderation.selectReportReason(ReportReason.ABUSE.value)
            viewModel.moderation.setReportComment("原始说明")
        }
        return viewModel.actionJob { viewModel.moderation.submitReport() }.also { withTimeout(WAIT_BUDGET_MS) { reportEntered.await() } }
    }

    @Test
    fun `关闭举报后旧成功不复活面板`() = runBlocking {
        val h = harness()
        val submit = h.beginReport()
        withContext(Dispatchers.Main) { h.viewModel.moderation.closeReport() }
        h.reportGate.complete(Unit)
        withTimeout(WAIT_BUDGET_MS) { submit.join() }
        assertNull(h.viewModel.uiState.value.moderation.report, "旧成功不得重新打开已关闭面板")
    }

    @Test
    fun `重开举报后旧失败不覆盖新草稿也不提示`() = runBlocking {
        val h = harness()
        h.reportFailed.set(true)
        val submit = h.beginReport()
        withContext(Dispatchers.Main) {
            h.viewModel.moderation.closeReport()
            h.viewModel.openReport()
            h.viewModel.moderation.selectReportReason(ReportReason.SPAM.value)
            h.viewModel.moderation.setReportComment("新的说明")
        }
        h.reportGate.complete(Unit)
        withTimeout(WAIT_BUDGET_MS) { submit.join() }
        assertEquals(ReportReason.SPAM.value to "新的说明", h.viewModel.uiState.value.moderation.report?.let { it.reason to it.comment })
        assertNull(h.viewModel.uiState.value.moderation.report?.submittedText)
        assertNull(h.viewModel.failures.current.value, "过期失败不提示当前面板")
    }

    @Test
    fun `举报在途冻结输入并拒绝重复提交`() = runBlocking {
        val h = harness()
        val submit = h.beginReport()
        withContext(Dispatchers.Main) {
            h.viewModel.moderation.setReportComment("不应接受的说明")
            h.viewModel.moderation.selectReportReason(ReportReason.SPAM.value)
            h.viewModel.moderation.submitReport()
        }
        assertEquals("原始说明", h.viewModel.uiState.value.moderation.report?.comment)
        assertEquals(ReportReason.ABUSE.value, h.viewModel.uiState.value.moderation.report?.reason)
        h.reportGate.complete(Unit)
        withTimeout(WAIT_BUDGET_MS) { submit.join() }
        assertEquals(1, h.requests.count { it.url.encodedPath == "/p1/report" })
        assertEquals("感谢报告，我们会尽快处理", h.viewModel.uiState.value.moderation.report?.submittedText)
        assertFalse(h.viewModel.uiState.value.moderation.report!!.canSubmit)
    }

    @Test
    fun `别处改了好友名单 停在栈里的主页不刷新也跟上`() = runBlocking {
        val h = harness()
        h.viewModel.awaitProfile()
        // 另一页加好友、取消好友做成后的回读把新名单记下；这一页没有任何刷新
        h.friend.set(true)
        h.friends.confirmWrite(h.session)
        awaitUntil("名单带上对方后主页显示已是好友") { h.viewModel.uiState.value.socialOps.isFriend == true }
        h.friend.set(false)
        h.friends.confirmWrite(h.session)
        awaitUntil("名单去掉对方后主页显示未关注") { h.viewModel.uiState.value.socialOps.isFriend == false }
    }

    @Test
    fun `别处改了好友名单 自己主页的好友数与我关注的不刷新也跟上`() = runBlocking {
        val h = harness(username = null)
        h.viewModel.awaitProfile()
        withContext(Dispatchers.Main) { h.viewModel.openSocial() }
        awaitUntil("我关注的第一次取到（空）") { h.viewModel.uiState.value.social.following.let { !it.isLoading && it.error == null && it.items.isEmpty() } }
        // 在 bob 的主页加好友做成后的回读把新名单记下；自己的主页没有任何刷新
        h.friend.set(true)
        h.friends.confirmWrite(h.session)
        awaitUntil("我关注的带上 bob") { h.viewModel.uiState.value.social.following.items.map { it.id } == listOf(99) }
        awaitUntil("好友数跟上") { (h.viewModel.uiState.value.userLoad as? Load.Ready)?.value?.friendCount == 1 }
        h.friend.set(false)
        h.friends.confirmWrite(h.session)
        awaitUntil("取消好友后我关注的去掉 bob") { h.viewModel.uiState.value.social.following.items.isEmpty() }
        awaitUntil("取消好友后好友数跟上") { (h.viewModel.uiState.value.userLoad as? Load.Ready)?.value?.friendCount == 0 }
    }

    /**
     * 写在途时主页跟随读到的是写入之前的名单；写记下的新名单那一拍晚于写的结束才到。写完的那一刻主页仍是写成的关系，
     * 不被在途时读到的旧名单盖回去。
     */
    @Test
    fun `写在途时读到写入之前的名单_写完不盖掉写成的关系`() = runBlocking {
        val reads = CompletableDeferred<Unit>()
        val put = CompletableDeferred<Unit>()
        val h = harness(blockWriteGate = put, relationReadGate = reads)
        h.viewModel.awaitProfile()
        withTimeout(WAIT_BUDGET_MS) { h.faultStore.readFinished.await() }
        h.faultStore.readGate = null
        val updates = CompletableDeferred<Unit>()
        h.faultStore.updateGate = updates
        val write = h.viewModel.actionJob { h.viewModel.blockUser() }
        reads.complete(Unit)
        awaitUntil("写入之前的名单交到主页跟随") { h.faultStore.delivered.get() > 0 }
        repeat(2) { withContext(Dispatchers.Main) { } }
        put.complete(Unit)
        withTimeout(WAIT_BUDGET_MS) { write.join() }
        assertTrue(h.viewModel.uiState.value.socialOps.isBlocked, "写完时主页是写成的已绝交，不是在途时读到的旧名单")
        updates.complete(Unit)
        awaitUntil("名单带来写成的那一拍之后仍是已绝交") { h.faultStore.delivered.get() > 1 }
        withContext(Dispatchers.Main) { }
        assertTrue(h.viewModel.uiState.value.socialOps.isBlocked)
    }

    @Test
    fun `写关系在途时别处改了绝交名单_写完之后主页跟上`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val h = harness(friendWriteGate = gate)
        h.viewModel.awaitProfile()
        withTimeout(WAIT_BUDGET_MS) { h.faultStore.readFinished.await() }
        val write = h.viewModel.actionJob { h.viewModel.addFriend() }
        awaitUntil("加好友在途") { h.viewModel.uiState.value.socialOps.pending }
        // 加好友还没回来时，设置页或另一页绝交了对方
        h.blocklist.record(h.session, listOf(99))
        gate.complete(Unit)
        withTimeout(WAIT_BUDGET_MS) { write.join() }
        awaitUntil("写完之后主页显示已绝交") { h.viewModel.uiState.value.socialOps.isBlocked }
        assertEquals(true, h.viewModel.uiState.value.socialOps.isFriend)
    }

    @Test
    fun `资料上的isFriend是服务端旧缓存_主页按好友名单显示未关注`() = runBlocking {
        val h = harness(staleProfileFriend = true)
        h.viewModel.awaitProfile()
        awaitUntil("好友名单读到") { h.viewModel.uiState.value.socialOps.isFriend != null }
        assertEquals(false, h.viewModel.uiState.value.socialOps.isFriend, "取消关注后刷新资料，按钮不得变回「已是好友」")
    }

    @Test
    fun `别处改了绝交名单 停在栈里的主页不刷新也跟上`() = runBlocking {
        val h = harness()
        h.viewModel.awaitProfile()
        withTimeout(WAIT_BUDGET_MS) { h.faultStore.readFinished.await() }
        assertEquals(false, h.viewModel.uiState.value.socialOps.isBlocked)
        // 设置页的屏蔽列表或另一页绝交成功后，名单经 record 记下；这一页没有任何刷新
        h.blocklist.record(h.session, listOf(99))
        awaitUntil("名单记下对方后主页显示已绝交") { h.viewModel.uiState.value.socialOps.isBlocked }
        h.blocklist.record(h.session, emptyList())
        awaitUntil("名单移除对方后主页解除绝交") { !h.viewModel.uiState.value.socialOps.isBlocked }
    }

    @Test
    fun `刷新时发出的绝交名单读取晚到_不盖掉期间名单带来的新关系`() = runBlocking {
        val h = harness()
        h.viewModel.awaitProfile()
        // 一拍绝交、一拍取消都接住：主页跟着名单走的那条已经订阅（只读一次的读取只落一拍）
        h.blocklist.record(h.session, listOf(99))
        awaitUntil("主页接住名单里的对方") { h.viewModel.uiState.value.socialOps.isBlocked }
        h.blocklist.record(h.session, emptyList())
        awaitUntil("主页接住名单里去掉对方") { !h.viewModel.uiState.value.socialOps.isBlocked }
        val gate = CompletableDeferred<Unit>()
        h.faultStore.readGate = gate
        h.faultStore.readFinished = CompletableDeferred()
        val before = withContext(Dispatchers.Main) { h.viewModel.viewModelScope.coroutineContext[Job]!!.children.toSet() }
        val refresh = h.viewModel.actionJob { h.viewModel.refreshProfile() }
        // 刷新这一轮读到了还没有对方的名单，停在闸门前
        withTimeout(WAIT_BUDGET_MS) { h.faultStore.readFinished.await() }
        val oldRead = h.viewModel.refreshRead(before, refresh)
        h.faultStore.readGate = null
        // 设置页或另一页在这时绝交了对方，名单记下
        h.blocklist.record(h.session, listOf(99))
        awaitUntil("主页跟上别处的绝交") { h.viewModel.uiState.value.socialOps.isBlocked }
        gate.complete(Unit)
        withTimeout(WAIT_BUDGET_MS) { oldRead.join(); refresh.join() }
        assertTrue(h.viewModel.uiState.value.socialOps.isBlocked, "刷新时读到的旧名单不得把别处刚绝交的关系盖回去")
    }

    @Test
    fun `旧名单读在绝交再取消后不能恢复过期值`() = runBlocking {
        val h = harness()
        h.viewModel.awaitProfile()
        withTimeout(WAIT_BUDGET_MS) { h.faultStore.readFinished.await() }
        h.blocklist.record(h.session, listOf(99))
        // 主页跟着名单走：等它接住这一拍再设闸门，闸门只拦本轮刷新的那次读取
        awaitUntil("主页接住名单里的对方") { h.viewModel.uiState.value.socialOps.isBlocked }
        val gate = CompletableDeferred<Unit>()
        h.faultStore.readGate = gate
        h.faultStore.readFinished = CompletableDeferred()
        val before = withContext(Dispatchers.Main) { h.viewModel.viewModelScope.coroutineContext[Job]!!.children.toSet() }
        val refresh = h.viewModel.actionJob { h.viewModel.refreshProfile() }
        withTimeout(WAIT_BUDGET_MS) { h.faultStore.readFinished.await() }
        val oldRead = h.viewModel.refreshRead(before, refresh)
        h.viewModel.actionJob { h.viewModel.blockUser() }.let { withTimeout(WAIT_BUDGET_MS) { it.join() } }
        h.viewModel.actionJob { h.viewModel.unblockUser() }.let { withTimeout(WAIT_BUDGET_MS) { it.join() } }
        assertEquals(false, h.viewModel.uiState.value.socialOps.isBlocked)
        h.faultStore.readGate = null
        gate.complete(Unit)
        withTimeout(WAIT_BUDGET_MS) { oldRead.join(); refresh.join() }
        assertEquals(false, h.viewModel.uiState.value.socialOps.isBlocked, "刷新时那次只读一次的名单读取晚到，也不把它读到的值写回关系")
    }

    /** 只读一次的名单读取只报失败：读取开始之后用户写过关系，这次读取已经过期，它的失败不再提示。 */
    @Test
    fun `写关系期间晚到的绝交名单读取失败_不提示`() = runBlocking {
        val h = harness()
        h.viewModel.awaitProfile()
        withTimeout(WAIT_BUDGET_MS) { h.faultStore.readFinished.await() }
        val gate = CompletableDeferred<Unit>()
        h.faultStore.readGate = gate
        h.faultStore.readFinished = CompletableDeferred()
        val before = withContext(Dispatchers.Main) { h.viewModel.viewModelScope.coroutineContext[Job]!!.children.toSet() }
        val refresh = h.viewModel.actionJob { h.viewModel.refreshProfile() }
        withTimeout(WAIT_BUDGET_MS) { h.faultStore.readFinished.await() }
        val oldRead = h.viewModel.refreshRead(before, refresh)
        h.faultStore.readGate = null
        h.viewModel.actionJob { h.viewModel.blockUser() }.let { withTimeout(WAIT_BUDGET_MS) { it.join() } }
        awaitUntil("绝交写完") { !h.viewModel.uiState.value.socialOps.pending }
        h.faultStore.failed.set(true)
        gate.complete(Unit)
        withTimeout(WAIT_BUDGET_MS) { oldRead.join(); refresh.join() }
        assertNull(h.viewModel.failures.current.value, "过期的名单读取失败不提示")
    }

    @Test
    fun `绝交名单首读IO失败进入提示并可重试`() = runBlocking {
        val h = harness(failRead = true)
        val notice = h.viewModel.awaitFailure()
        assertEquals(Failure.Storage, assertIs<NoticeText.Reason>(notice.text).failure)
        h.faultStore.failed.set(false)
        h.faultStore.readFinished = CompletableDeferred()
        val retry = assertNotNull(notice.retry)
        h.viewModel.actionJob(retry).let { withTimeout(WAIT_BUDGET_MS) { it.join() } }
        assertTrue(h.faultStore.readFinished.isCompleted, "重试实际重读名单缓存")
        assertEquals(false, h.viewModel.uiState.value.socialOps.isBlocked)
    }

    @Test
    fun `绝交名单首读失败重试成功后_主页重新跟着名单走`() = runBlocking {
        val h = harness(failRead = true)
        val notice = h.viewModel.awaitFailure()
        h.faultStore.failed.set(false)
        h.viewModel.actionJob(assertNotNull(notice.retry)).let { withTimeout(WAIT_BUDGET_MS) { it.join() } }
        // 别处绝交了对方：主页不刷新也要跟上，跟随不能停在首读失败的那一轮
        h.blocklist.record(h.session, listOf(99))
        awaitUntil("重试之后名单记下对方，主页显示已绝交") { h.viewModel.uiState.value.socialOps.isBlocked }
    }

    @Test
    fun `绝交名单重读IO失败保留已绝交状态`() = runBlocking {
        val h = harness()
        h.viewModel.awaitProfile()
        withTimeout(WAIT_BUDGET_MS) { h.faultStore.readFinished.await() }
        val write = h.viewModel.actionJob { h.viewModel.blockUser() }
        withTimeout(WAIT_BUDGET_MS) { write.join() }
        assertEquals(1, h.requests.count { it.method == HttpMethod.Put && it.url.encodedPath == "/p1/blocklist/bob" })
        assertTrue(h.viewModel.uiState.value.socialOps.isBlocked)
        h.faultStore.failed.set(true)
        h.faultStore.readFinished = CompletableDeferred()
        val refresh = h.viewModel.actionJob { h.viewModel.refreshProfile() }
        withTimeout(WAIT_BUDGET_MS) { refresh.join() }
        val notice = h.viewModel.awaitFailure()
        assertTrue(h.faultStore.readFinished.isCompleted, "失败提示来自本轮实际名单读取")
        assertEquals(Failure.Storage, assertIs<NoticeText.Reason>(notice.text).failure)
        assertTrue(h.viewModel.uiState.value.socialOps.isBlocked, "读失败不得伪装成已解除绝交")
    }
}
