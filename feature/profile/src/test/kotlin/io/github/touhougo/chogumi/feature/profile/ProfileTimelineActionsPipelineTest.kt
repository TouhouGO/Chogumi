package io.github.touhougo.chogumi.feature.profile

import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.NoBlockedUsers
import io.github.touhougo.chogumi.core.data.repository.noWebClient
import io.github.touhougo.chogumi.core.data.repository.testWebSessionRepository
import io.github.touhougo.chogumi.core.data.repository.testDraftRepository
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.mark.StillCollectionEditing
import io.github.touhougo.chogumi.core.data.mark.StillCollectionMarker
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.IndexRepository
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TimelineRepository
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testOtherUserCollectionsCache
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.testUserRepository
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.IndexApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.TimelineApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 「我的主页 → 动态」的写操作与回复，走**真实调用链**（真仓库 + MockEngine）。
 *
 * ## 为什么必须用真仓库
 *
 * docs/dev/guide.md §3「与生产同构」：裸 `HttpClient` 缺 `ContentNegotiation`，`detail` 会恒为 null，
 * 测试**因错误的原因通过**（假绿）。所以这里复用与 `ProfileViewModelTest` 相同的生产同构装配。
 *
 * ## 覆盖的行为
 *
 * 1. 展开详情 → 调回复端点、回复从未取回到落定（楼主是发动态的人）
 * 2. 回复失败 → 保留目标 ID（界面才能重试），且错误可见
 * 3. 贴贴 → 把点的那个表情 PUT 到该动态、缓存回写、在途态收起；失败 → 错误可见、缓存不变
 * 4. 删除**自己的**动态 → 调 DELETE 且从列表移除
 * 5. 删除**别人的**动态 → 被拒绝，且不发请求
 */
class ProfileTimelineActionsPipelineTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()


    // Main 用单线程执行器而非 UnconfinedTestDispatcher：Unconfined 会让 VM 协程
    // 恢复点散落到 IO 线程并发交叠，"读 state→copy→写回" 互相覆盖。
    // 单线程串行与生产 Main 线程 confinement 同语义，竞态在构造上消失。
    private val main = TestMain()
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(bgExecutor.asCoroutineDispatcher() + SupervisorJob())
    private val viewModels = mutableListOf<ProfileViewModel>()

    /** 按顺序记录收到的请求，用于断言"打了哪个端点"。 */
    private val requestRecorder = ConcurrentRequestRecorder()
    private val requests: List<String> get() = requestRecorder.map { "${it.method.value} ${it.url.encodedPath}" }

    /** 置非空的路径前缀 → 该请求返回 500。 */
    private var failPaths = setOf<String>()

    /**
     * 时间线列表返回的动态。id=500 属当前用户(9)，别人(77)贴过一个 79；id=501 属别人(77)。
     * 删除权限按 `uid == session.userId` 判定，两个都得有才能覆盖正反两面。
     */
    private val feedJson = """
        [
          {"id":500,"uid":9,"cat":5,"type":1,"memo":{"status":{"tsukkomi":"我的动态"}},
           "batch":false,"source":{"name":"web"},"replies":2,"createdAt":1700000000,
           "reactions":[{"users":[{"id":77,"username":"other","nickname":"Other"}],"value":79}],
           "user":{"id":9,"username":"fixture_user","nickname":"Fixture User","avatar":{"small":"s","medium":"m","large":"l"},
                   "group":10,"joinedAt":1600000000,"sign":"","isFriend":false}},
          {"id":501,"uid":77,"cat":5,"type":1,"memo":{"status":{"tsukkomi":"别人的动态"}},
           "batch":false,"source":{"name":"web"},"replies":0,"createdAt":1699990000,
           "user":{"id":77,"username":"other","nickname":"Other","avatar":{"small":"s","medium":"m","large":"l"},
                   "group":10,"joinedAt":1600000000,"sign":"","isFriend":false}}
        ]
    """.trimIndent()

    /**
     * 回复按**动态 ID** 给出不同内容，用于识别"打开 A 却显示 B 的回复"这类串台。
     * CommentDto 的必填字段一个都不能少（mainID/creatorID/relatedID/state/replies）。
     */
    private fun repliesJsonFor(timelineId: Long, text: String) = """
        [{"id":${timelineId * 10},"mainID":$timelineId,"creatorID":77,"relatedID":0,
          "createdAt":1700000100,"content":"$text","state":0,"replies":[],
          "user":{"id":77,"username":"other","nickname":"Other","avatar":{"small":"s","medium":"m","large":"l"},
                  "group":10,"joinedAt":1600000000,"sign":"","isFriend":false}}]
    """.trimIndent()

    /** 让指定动态的回复请求一直停在服务端，直到被取消，用于制造"先发的后到"。 */
    private var slowReplyFor: Long? = null

    /** 慢请求已进入服务端（之后再发的请求一定晚于它）。 */
    private val slowStarted = CompletableDeferred<Unit>()

    /** 慢请求在服务端等待时被取消了。 */
    private val slowCancelled = CompletableDeferred<Boolean>()

    @BeforeTest
    fun setUp() {
        main.install()
        requestRecorder.clear()
        failPaths = emptySet()
        slowReplyFor = null
    }

    @AfterTest
    fun tearDown() {
        main.uninstall(viewModels.map { it.viewModelScope } + scope)
        viewModels.clear()
        bgExecutor.shutdown()
    }

    private fun makeViewModel(): ProfileViewModel {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requestRecorder.record(request)
            // 慢请求一直等到被取消；取消记进 slowCancelled
            val timelineId = Regex("""/timeline/(\d+)/replies""").find(path)?.groupValues?.get(1)?.toLongOrNull()
            if (timelineId != null && timelineId == slowReplyFor) {
                slowStarted.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    slowCancelled.complete(true)
                    throw e
                }
            }
            val status = if (failPaths.any { path.startsWith(it) }) HttpStatusCode.InternalServerError else HttpStatusCode.OK
            val body = when {
                timelineId != null -> repliesJsonFor(timelineId, "动态$timelineId 的回复")
                path == "/p1/users/fixture_user/timeline" -> feedJson
                path.startsWith("/v0/users/") -> """{"id":9,"username":"fixture_user","nickname":"Fixture User","user_group":10,
                    "avatar":{"large":"l","medium":"m","small":"s"},"sign":"","reg_time":"2020-01-01T00:00:00Z"}"""
                else -> "{}"
            }
            respond(body, status, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val client = resources.apiClient(engine)
        val store = testTokenStore(scope = scope).also { s ->
            runBlocking {
                val pending = s.beginSession("token")
                s.completeSession(pending, 9, "fixture_user")
            }
        }
        val userRepository = testUserRepository(resources, UserApi(client), store, object : io.github.touhougo.chogumi.core.data.db.user.UserDao {
            override suspend fun get(id: Int) = null
            override suspend fun getByUsername(username: String) = null
            override suspend fun upsert(user: io.github.touhougo.chogumi.core.data.db.user.UserEntity) = Unit
        })
        val collectionRepository = testCollections(
            resources,
            CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), store, FakeEpisodeWatchStateDao(),
        )
        return ProfileViewModel(
            username = "fixture_user",
            userRepository = userRepository,
            collectionStore = collectionRepository.store,
            otherCollections = testOtherUserCollectionsCache(CollectionApi(client), FakeSubjectDao(), store),
            timelineRepository = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, resources.own(noWebClient())), scope, bgExecutor.asCoroutineDispatcher()),
            collectionMarker = StillCollectionMarker(),
            collectionEditing = StillCollectionEditing(),
            appTime = testAppTime(System.currentTimeMillis()),
            drafts = testDraftRepository(scope, tokens = store),
            clock = AppClock { 0L },
            blockedUsers = NoBlockedUsers(),
            indexRepository = IndexRepository(IndexApi(client), UserApi(client), store),
        ).also { viewModels += it }
    }

    /**
     * 加载时间线首屏，让两条动态进入 VM 已知集合（删除需要先有缓存）。
     *
     * 与界面一致的两步：`timeline` 是 `stateIn(SharingStarted.Lazily)`，要有一个**收集者**才读得到本地
     * （界面里是 `collectAsStateWithLifecycle`）；「动态」tab 出现在屏幕上时调 [ProfileViewModel.openTimeline] 才取网络。
     */
    private suspend fun ProfileViewModel.loadFeed() {
        scope.launch { timeline.collect { } }
        awaitUntil("资料就绪") { uiState.value.user != null }
        openTimeline()
        awaitUntil("时间线首屏") { timeline.value.items.size == 2 }
    }

    /** 等慢请求被取消。它不会自己返回：放行后正常返回与被取消分不开，所以只等取消这一个信号。 */
    private suspend fun slowRequestWasCancelled(): Boolean = withTimeout(10.seconds) { slowCancelled.await() }

    // ---- 展开回复 ----

    private fun ProfileViewModel.item(id: Long) = timeline.value.items.first { it.id == id }

    private val ProfileViewModel.threads get() = uiState.value.timelineDetail.comments.reading.threads

    @Test
    fun `展开回复打到该动态的replies端点并填充列表`() = runBlocking {
        val vm = makeViewModel()
        vm.loadFeed()
        assertNull(vm.uiState.value.timelineDetail.id, "初始不该有打开的回复")

        vm.timelineDetail.open(vm.item(500))
        assertEquals(500L, vm.uiState.value.timelineDetail.id, "展开时立即带上目标 ID")
        awaitUntil("回复落定") { vm.uiState.value.timelineDetail.comments.floors != Load.Pending }

        assertTrue(
            requests.any { it == "GET /p1/timeline/500/replies" },
            "必须打该动态自己的回复端点，实际：$requests",
        )
        assertEquals(1, vm.threads.size)
        assertNull(vm.uiState.value.timelineDetail.comments.floors.failureOrNull())
        assertEquals(9, vm.uiState.value.timelineDetail.comments.reading.opCreatorId, "楼主是发这条动态的人")
    }

    @Test
    fun `回复加载失败保留目标ID以便重试`() = runBlocking<Unit> {
        failPaths = setOf("/p1/timeline/500/replies")
        val vm = makeViewModel()
        vm.loadFeed()

        vm.timelineDetail.open(vm.item(500))
        awaitUntil("失败落定") { vm.uiState.value.timelineDetail.comments.floors != Load.Pending }

        assertEquals(500L, vm.uiState.value.timelineDetail.id, "失败必须保留 ID，否则界面无法重试")
        assertNotNull(vm.uiState.value.timelineDetail.comments.floors.failureOrNull())
    }

    @Test
    fun `关闭回复清空状态`() = runBlocking {
        val vm = makeViewModel()
        vm.loadFeed()
        vm.timelineDetail.open(vm.item(500))
        awaitUntil("回复落定") { vm.uiState.value.timelineDetail.comments.floors != Load.Pending }

        vm.timelineDetail.close()
        assertNull(vm.uiState.value.timelineDetail.id)
        assertTrue(vm.threads.isEmpty())
    }

    /**
     * 连续打开两条动态的回复：先发的那条**后到**，不得覆盖后发的那条。
     * 钉住 `TimelineDetailActions.loadReplies` 取消在途请求：去掉取消，别的用例都不会红。
     */
    @Test
    fun `连续打开两条动态时后发的结果不被先发的覆盖`() = runBlocking {
        val vm = makeViewModel()
        vm.loadFeed()

        // 500 的请求停在服务端，501 的照常返回
        slowReplyFor = 500L
        vm.timelineDetail.open(vm.item(500))
        withTimeout(10.seconds) { slowStarted.await() }
        vm.timelineDetail.open(vm.item(501))

        awaitUntil("501 的回复落定") { vm.uiState.value.timelineDetail.comments.floors != Load.Pending }
        assertEquals(501L, vm.uiState.value.timelineDetail.id, "当前展开的应是后点的 501")

        // 被取消的请求不会再回来，此刻的状态就是最终状态
        assertTrue(slowRequestWasCancelled(), "打开 501 时 500 的在途请求必须被取消")
        assertEquals(501L, vm.uiState.value.timelineDetail.id, "先发的 500 回来时不得抢走弹层")
        assertEquals(
            "动态501 的回复",
            vm.threads.single().anchor.content,
            "显示的内容必须是 501 的，不能串台成 500 的",
        )
    }

    /**
     * 关掉弹层后，在途请求回来**不得把弹层重新填上内容**。
     * 钉住 `TimelineDetailActions.close` 取消在途请求。
     */
    @Test
    fun `关闭弹层后在途回复回来不得重新填上内容`() = runBlocking {
        val vm = makeViewModel()
        vm.loadFeed()

        slowReplyFor = 500L
        vm.timelineDetail.open(vm.item(500))
        withTimeout(10.seconds) { slowStarted.await() }
        vm.timelineDetail.close()
        assertNull(vm.uiState.value.timelineDetail.id, "关闭后应立即清空")

        assertTrue(slowRequestWasCancelled(), "关闭弹层时在途请求必须被取消")
        assertNull(vm.uiState.value.timelineDetail.id, "在途请求回来时弹层必须仍然是关着的")
        assertTrue(vm.threads.isEmpty(), "不得凭空出现回复内容")
    }

    // ---- 贴贴 ----

    private fun ProfileViewModel.reactions(id: Long) = timeline.value.items.first { it.id == id }.reactions

    /** 点的是别人贴过的 79：PUT 的就是 79（不是固定的某一个），缓存里 79 加一并成了我的。 */
    @Test
    fun `贴贴把点的表情PUT到该动态并回写缓存且在途态收起`() = runBlocking {
        val vm = makeViewModel()
        vm.loadFeed()
        vm.timelineDetail.open(vm.item(500))

        vm.timelineDetail.react(vm.item(500), 79)
        val mine = listOf(CommentReactionBo(value = 79, count = 2, selected = true))
        // 缓存已贴且在途态已收起：两者都成立后不会再变（没有后续操作），是单调条件。
        awaitUntil("贴贴落定") { vm.reactions(500) == mine && vm.uiState.value.timelineDetail.pendingReaction == null }

        assertTrue(requests.any { it == "PUT /p1/timeline/500/like" }, "必须发 PUT 到该动态，实际：$requests")
        assertEquals(listOf("""{"value":79}"""), requestRecorder.filter { it.url.encodedPath.endsWith("/like") }.map { it.body.toByteArray().decodeToString() })
    }

    @Test
    fun `贴贴失败报错且缓存不变`() = runBlocking {
        failPaths = setOf("/p1/timeline/500/like")
        val vm = makeViewModel()
        vm.loadFeed()
        vm.timelineDetail.open(vm.item(500))
        val before = vm.reactions(500)

        vm.timelineDetail.react(vm.item(500), 79)
        awaitUntil("错误上报") { vm.failures.current.value != null }

        // 在途态在报错之前收起（TimelineDetailActions.react 先 reactionSettled 再提示）
        assertNull(vm.uiState.value.timelineDetail.pendingReaction)
        assertEquals(before, vm.reactions(500), "失败不得改动缓存")
    }

    // ---- 删除 ----

    @Test
    fun `删除自己的动态会发DELETE并从列表移除`() = runBlocking {
        val vm = makeViewModel()
        vm.loadFeed()

        vm.deleteTimeline(500)
        awaitUntil("删除生效") { vm.timeline.value.items.size == 1 }

        assertTrue(
            requests.any { it == "DELETE /p1/timeline/500" },
            "必须发 DELETE 到该动态，实际：$requests",
        )
        assertTrue(vm.timeline.value.items.none { it.id == 500L }, "删除后不得留在列表里")
    }

    /**
     * 删别人的动态必须被**拒绝且不发请求**。
     *
     * 这条不只是"界面不显示删除按钮"的补充——真源在仓库层：
     * `TimelineRepository.delete` 会校验 `item.uid == session.userId`。
     */
    @Test
    fun `删除别人的动态被拒绝且不发请求`() = runBlocking {
        val vm = makeViewModel()
        vm.loadFeed()
        val before = requests.size

        vm.deleteTimeline(501)
        awaitUntil("错误上报") { vm.failures.current.value != null }

        assertTrue(
            requests.drop(before).none { it.startsWith("DELETE") },
            "越权删除不得发出请求，实际：${requests.drop(before)}",
        )
        assertEquals(2, vm.timeline.value.items.size, "被拒绝的删除不得改动列表")
    }

    @Test
    fun `动态按当前账号标出本人的（只有这些能删）`() = runBlocking {
        val vm = makeViewModel()
        vm.loadFeed()
        assertTrue(vm.timeline.value.items.first { it.id == 500L }.isOwn, "本人动态（uid 9）应标为本人")
        assertFalse(vm.timeline.value.items.first { it.id == 501L }.isOwn, "他人动态不得标为本人")
    }
}
