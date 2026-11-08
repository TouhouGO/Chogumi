package io.github.touhougo.chogumi.feature.profile

import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import java.util.concurrent.ConcurrentHashMap
import io.github.touhougo.chogumi.core.data.repository.NoBlockedUsers
import io.github.touhougo.chogumi.core.ui.moderation.ReportReason
import io.github.touhougo.chogumi.core.data.repository.noWebClient
import io.github.touhougo.chogumi.core.data.repository.testWebSessionRepository
import io.github.touhougo.chogumi.core.data.repository.testDraftRepository
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.mark.StillCollectionEditing
import io.github.touhougo.chogumi.core.data.mark.StillCollectionMarker
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.IndexRepository
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TimelineRepository
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testOtherUserCollectionsCache
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.testUserRepository
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.IndexApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.TimelineApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 个人页分页游标。
 *
 * 服务端先切 `chiiFriends` 的结果集，再跳过查不到的用户（账号已注销），
 * 所以**一页可能回不满**。界面若拿"已显示条数"当下一次请求的 `offset`，
 * 就会重复扫描被跳过的那一段——表现为同一个人出现两次、游标越走越慢。
 */
class ProfileViewModelTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()


    // Main 用单线程执行器而非 UnconfinedTestDispatcher：Unconfined 会让 VM 协程
    // 恢复点散落到 IO 线程并发交叠，"读 state→copy→写回" 互相覆盖。
    // 单线程串行与生产 Main 线程 confinement 同语义，竞态在构造上消失。
    private val main = TestMain()
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(bgExecutor.asCoroutineDispatcher() + SupervisorJob())
    private val viewModels = mutableListOf<ProfileViewModel>()
    private val requests = ConcurrentRequestRecorder()
    private val offsets: List<Int> get() = requests.filter { it.url.encodedPath.endsWith("/followers") }
        .map { it.url.parameters["offset"]?.toIntOrNull() ?: 0 }

    /** 第 2、3 位用户查不到：首屏 100 条只回 98 条，但总数仍是 250。 */
    private var missing = setOf(1, 2)

    /**
     * 让某一页**只失败一次**（首次到达该 offset 时 500，之后正常）。
     *
     * 用"永远失败"测不出重试：那样重试也必然失败，测试只能证明它一直在失败。
     */
    private var failOnceAt: Int? = null
    private val failedOffsets = ConcurrentHashMap.newKeySet<Int>()

    /** 社交写操作端点收到的请求序列（"PUT friends"、"DELETE blocklist"、…），按序记录。 */
    private val socialRequests: List<String> get() = requests.mapNotNull {
        val path = it.url.encodedPath
        val method = it.method.value
        when {
            method != "GET" && path.contains("/friends/") -> "$method friends"
            method != "GET" && path.contains("/blocklist/") -> "$method blocklist"
            method == "POST" && path.contains("/report") -> "POST report"
            else -> null
        }
    }

    /** 社交写操作的统一失败开关：非 null 时对应端点回 500。 */
    private var failSocial: String? = null

    /** 加好友做成后自己的好友名单里就有主页上的这个人（id 9），取消做成后又没有：名单与写入一致，跟着名单走的「已是好友」才有真值可跟。 */
    private val friendAdded = AtomicBoolean(false)

    /** 人物收藏端点收到的 offset 序列（「角色与人物」档内切到人物后翻页用）。 */
    private val personOffsets: List<Int> get() = requests.filter { it.url.encodedPath.endsWith("/collections/persons") }
        .map { it.url.parameters["offset"]?.toIntOrNull() ?: 0 }

    /** 非 null 时好友端点挂起到放行为止：让「请求在途」成为确定的状态，不靠轮询碰窗口。 */
    private var socialGate: CompletableDeferred<Unit>? = null

    /** 置 true 后条目收藏端点回 500（先取成功、再让下拉刷新失败）。 */
    private var failCollections = false

    /** 个人动态端点收到的请求数。 */
    private val timelineRequests: Int get() = requests.count { it.url.encodedPath == "/p1/users/fixture_user/timeline" }

    /** 当前账号（fixture_user）收藏的目录数：取消收藏一个就少一个。 */
    private val collectedIndexes = AtomicInteger(2)

    /** 页面与用例共用的目录仓库：用例在别处收藏、取消收藏目录。 */
    private lateinit var indexRepository: IndexRepository

    /** 非 null 时个人动态端点停到放行为止：取数的协程一直在途，数得清。 */
    private var timelineGate: CompletableDeferred<Unit>? = null

    @BeforeTest
    fun setUp() {
        main.install()
        requests.clear()
        failedOffsets.clear()
        failOnceAt = null
        missing = setOf(1, 2)
        failSocial = null
        socialGate = null
        failCollections = false
        timelineGate = null
    }

    @AfterTest
    fun tearDown() {
        main.uninstall(viewModels.map { it.viewModelScope } + scope)
        viewModels.clear()
        bgExecutor.shutdown()
    }

    /** 用户 JSON；字段与 `/p1` 的 SlimUser 一致（缺字段会解析失败，测试就白跑了）。 */
    private fun userJson(id: Int) =
        """{"id":$id,"username":"u$id","nickname":"用户$id","avatar":{"small":"s","medium":"m","large":"l"},
           "group":10,"joinedAt":1600000000,"sign":"","isFriend":false}"""

    /** [idBase]：用户 id 从这里起编（位置仍按 offset 数），两个方向的人不重叠。 */
    private fun pagedUsers(offset: Int, total: Int, pageSize: Int, idBase: Int = 0): String {
        require(offset <= total) { "越界请求 offset=$offset total=$total" }
        val positions = (offset until minOf(offset + pageSize, total)).filter { it !in missing }
        return """{"data":[${positions.joinToString(",") { userJson(idBase + it) }}],"total":$total}"""
    }

    /** 收藏端点收到的 subject_type 序列（按请求顺序），用于验证切类型确实换了请求。 */
    private val collectionTypes: List<String> get() = requests.mapNotNull { it.url.parameters["subject_type"] }

    private fun collectionJson(subjectType: String) = """
        {"data":[{"subject_id":8,"subject_type":$subjectType,"type":3,"rate":0,"comment":"","tags":[],
          "ep_status":0,"vol_status":0,"updated_at":"2026-01-01T00:00:00Z","private":false,
          "subject":{"id":8,"type":$subjectType,"name":"S","name_cn":"条目","short_summary":"",
          "images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"},
          "eps":12,"volumes":0,"collection_total":0,"score":0,"rank":0,"tags":[]}}],
         "total":1,"limit":50,"offset":0}
    """.trimIndent()

    private fun makeViewModel(
        username: String? = "fixture_user",
        total: Int = 250,
        pageSize: Int = 100,
        collectionDao: FakeUserCollectionDao = FakeUserCollectionDao(),
        collectionResultGate: CompletableDeferred<Unit>? = null,
        collectionFailure: Boolean = false,
        collectionFailureResponded: CompletableDeferred<Unit>? = null,
    ): ProfileViewModel {
        val engine = MockEngine { request ->
            requests.record(request)
            val path = request.url.encodedPath
            val offset = request.url.parameters["offset"]?.toIntOrNull() ?: 0
            val subjectType = request.url.parameters["subject_type"]
            val method = request.method.value
            when {
                // 社交写操作：按「METHOD /路径段」记录，供用例断言发了什么。
                method != "GET" && path.contains("/friends/") -> {
                    socialGate?.await()
                    if (failSocial == "friend") respond("{}", HttpStatusCode.InternalServerError,
                        headersOf("Content-Type", "application/json"))
                    else {
                        friendAdded.set(method == "PUT")
                        respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                    }
                }
                method == "PUT" && path.contains("/blocklist/") -> {
                    if (failSocial == "block") respond("{}", HttpStatusCode.InternalServerError,
                        headersOf("Content-Type", "application/json"))
                    else respond("""{"blocklist":[99]}""", HttpStatusCode.OK,
                        headersOf("Content-Type", "application/json"))
                }
                method == "DELETE" && path.contains("/blocklist/") -> {
                    respond("""{"blocklist":[]}""", HttpStatusCode.OK,
                        headersOf("Content-Type", "application/json"))
                }
                method == "POST" && path.contains("/report") -> {
                    if (failSocial == "report") respond("{}", HttpStatusCode.InternalServerError,
                        headersOf("Content-Type", "application/json"))
                    else respond("""{"message":"感谢报告，我们会尽快处理"}""", HttpStatusCode.OK,
                        headersOf("Content-Type", "application/json"))
                }
                path.endsWith("/followers") -> {
                    val failNow = offset == failOnceAt && failedOffsets.add(offset)
                    if (failNow) {
                        respond("{}", HttpStatusCode.InternalServerError,
                            headersOf("Content-Type", "application/json"))
                    } else {
                        respond(pagedUsers(offset, total, pageSize), HttpStatusCode.OK,
                            headersOf("Content-Type", ContentType.Application.Json.toString()))
                    }
                }
                // 「我的关注」方向。offset/close 语义同 followers。也是自己的好友名单（加为好友 / 已是好友的来源）：
                // id 从 1000 起编，名单里没有主页上的这个人（id 9）；加好友做成后名单里只有这个人，取消做成后又没有。
                path.endsWith("/friends") -> respond(
                    if (friendAdded.get()) """{"data":[${userJson(9)}],"total":1}""" else pagedUsers(offset, total = 40, pageSize = pageSize, idBase = 1000),
                    HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                // 角色收藏（切分组用例要用）：SlimCharacter 的必填字段一个都不能少
                path.endsWith("/collections/characters") -> respond(
                    """{"data":[{"id":1,"name":"角色A","nameCN":"角色A","role":1,"info":"",
                       "images":{"large":"l","medium":"m","small":"s","grid":"g"},
                       "comment":0,"lock":false,"nsfw":false}],"total":1}""",
                    HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                // 人物收藏：每页只回 1 条但总数远超一页，确保续取一定有下一页
                path.endsWith("/collections/persons") -> {
                    respond(
                        """{"data":[{"id":${100 + offset},"name":"人物","nameCN":"人物","type":1,"info":"",
                           "career":[],"images":{"large":"l","medium":"m","small":"s","grid":"g"},
                           "comment":0,"lock":false,"nsfw":false}],"total":1000}""",
                        HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                // 资料本身不是本用例的对象，给一个最小可解析的响应即可
                path.startsWith("/v0/users/") || path == "/v0/me" -> {
                    // 收藏端点带 subject_type；资料端点不带。按这个区分。
                    if (subjectType != null) {
                        collectionResultGate?.await()
                        if (collectionFailure || failCollections) {
                            val response = respond("{}", HttpStatusCode.InternalServerError,
                                headersOf("Content-Type", ContentType.Application.Json.toString()))
                            collectionFailureResponded?.complete(Unit)
                            response
                        } else {
                            respond(collectionJson(subjectType), HttpStatusCode.OK,
                                headersOf("Content-Type", ContentType.Application.Json.toString()))
                        }
                    } else {
                        respond(
                            """{"id":9,"username":"fixture_user","nickname":"Fixture User","user_group":10,
                               "avatar":{"large":"l","medium":"m","small":"s"},"sign":"","reg_time":"2020-01-01T00:00:00Z"}""",
                            HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    }
                }
                // 资料头「目录 N」：两个目录列表的 total
                path == "/p1/users/fixture_user/indexes" -> respond(
                    """{"data":[],"total":0}""", HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                path == "/p1/users/fixture_user/collections/indexes" -> respond(
                    """{"data":[],"total":${collectedIndexes.get()}}""", HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                method == "DELETE" && path.startsWith("/p1/collections/indexes/") -> {
                    collectedIndexes.decrementAndGet()
                    respond("{}", HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
                }
                path == "/p1/users/fixture_user/timeline" -> {
                    timelineGate?.await()
                    respond("[]", HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
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
            CollectionApi(client), FakeSubjectDao(), collectionDao, store, FakeEpisodeWatchStateDao(),
        )
        indexRepository = IndexRepository(IndexApi(client), UserApi(client), store)
        return ProfileViewModel(
            username = username,
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
            indexRepository = indexRepository,
        ).also { viewModels += it }
    }

    private suspend fun ProfileViewModel.loadFollowers() {
        selectSocial(SocialDirection.FOLLOWERS)
        awaitUntil("关注者首屏加载") { uiState.value.social.followers.items.isNotEmpty() }
    }

    @Test
    fun `自己的主页_在别处取消收藏目录后资料头的目录数跟着变`() = runBlocking {
        val vm = makeViewModel(username = null)
        awaitUntil("资料头的目录数到位") { vm.uiState.value.user?.indexCollectCount == 2 }

        assertEquals(Outcome.Success(Unit), indexRepository.setCollected(3, collected = false))

        awaitUntil("取消收藏后重读了资料，收藏的目录少一个") { vm.uiState.value.user?.indexCollectCount == 1 }
    }

    @Test
    fun `资料首载不触发下拉指示器而主动下拉触发直到完成`() = runBlocking {
        val viewModel = makeViewModel()
        assertFalse(viewModel.uiState.value.isRefreshing, "首载进行时不得亮下拉指示器")
        val initial = withTimeout(WAIT_BUDGET_MS) { viewModel.uiState.first { it.user != null || it.error != null } }
        assertFalse(initial.isRefreshing, "首载完成后不得亮下拉指示器")

        viewModel.refreshProfile()
        assertTrue(viewModel.uiState.value.isRefreshing, "主动下拉应立即亮指示器")
        val completed = withTimeout(WAIT_BUDGET_MS) { viewModel.uiState.first { !it.isRefreshing } }
        assertFalse(completed.isRefreshing, "内容就绪后应结束刷新")
    }

    @Test
    fun `六个分类的顺序就是 FAB 菜单的顺序`() {
        // FAB 菜单顺序：动画 书籍 游戏 音乐 三次元 角色与人物（目录有独立的目录页，不在这里）。
        // 这是**唯一**的顺序来源，UI 直接遍历它，不另立一份清单。
        assertEquals(6, ShelfCategory.entries.size, "六个分类")
        assertEquals(
            listOf(
                SubjectType.ANIME, SubjectType.BOOK, SubjectType.GAME,
                SubjectType.MUSIC, SubjectType.REAL,
            ),
            ShelfCategory.entries.filterIsInstance<ShelfCategory.SubjectDomain>().map { it.type },
        )
        assertEquals(ShelfCategory.CharacterPerson(), ShelfCategory.entries.last(), "角色与人物在最后")
    }

    @Test
    fun `切到另一个条目域会按新的 subject_type 重新请求`() = runBlocking {
        // 五个条目域是并列的，服务端按 subject_type 分别取。
        // 若在本地筛，切类型时列表会瞬间变空（因为本来就没取过别类型的收藏）。
        val vm = makeViewModel()
        vm.selectShelf(ShelfCategory.SubjectDomain(SubjectType.ANIME))
        awaitUntil("首屏收藏") { vm.uiState.value.shelf.subjects is Load.Ready }
        assertEquals(SubjectType.ANIME.value.toString(), collectionTypes.single(), "默认应取动画")

        vm.selectShelf(ShelfCategory.SubjectDomain(SubjectType.BOOK))
        awaitUntil("按书籍重新请求") { collectionTypes.size >= 2 }

        assertEquals(
            listOf(SubjectType.ANIME.value.toString(), SubjectType.BOOK.value.toString()),
            collectionTypes,
            "切分类必须换一次请求，实际：${collectionTypes}",
        )
    }

    @Test
    fun `他人条目收藏切回已取过的类别读会话缓存，下拉刷新才重拉`() = runBlocking {
        val vm = makeViewModel()
        val anime = ShelfCategory.SubjectDomain(SubjectType.ANIME)
        vm.selectShelf(anime)
        awaitUntil("动画收藏") { vm.uiState.value.shelf.let { it.category == anime && it.subjects is Load.Ready } }
        vm.selectShelf(ShelfCategory.SubjectDomain(SubjectType.BOOK))
        awaitUntil("书籍收藏") { collectionTypes.size >= 2 }
        awaitUntil("书籍落定") { vm.uiState.value.shelf.let { it.category != anime && it.subjects is Load.Ready } }

        vm.selectShelf(anime)
        awaitUntil("切回动画") { vm.uiState.value.shelf.let { it.category == anime && it.subjects is Load.Ready } }
        assertEquals(2, collectionTypes.size, "切回动画命中会话缓存，不得再请求，实际：$collectionTypes")

        vm.selectShelf(anime, refresh = true)
        awaitUntil("下拉刷新重拉") { collectionTypes.size >= 3 }
        assertEquals(SubjectType.ANIME.value.toString(), collectionTypes.last())
    }

    @Test
    fun `本人收藏先显示缓存再由网络结果替换`() = runBlocking {
        val dao = FakeUserCollectionDao().apply {
            upsert(UserCollectionEntity(
                subjectId = 777,
                subjectType = SubjectType.ANIME.value,
                type = CollectionType.DOING.value,
                accountId = 9,
                updatedAt = System.currentTimeMillis(),
            ))
        }
        val networkGate = CompletableDeferred<Unit>()
        val vm = makeViewModel(username = null, collectionDao = dao, collectionResultGate = networkGate)
        awaitUntil("本人资料加载完成") { vm.uiState.value.user?.username == "fixture_user" }

        vm.selectShelf(ShelfCategory.SubjectDomain(SubjectType.ANIME))
        awaitUntil("本人收藏网络请求发出") { collectionTypes.isNotEmpty() }
        awaitUntil("缓存收藏先显示") {
            vm.uiState.value.shelf.subjects.valueOrNull()?.map { it.subjectId } == listOf(777)
        }

        networkGate.complete(Unit)
        awaitUntil("网络收藏替换缓存") {
            vm.uiState.value.shelf.subjects.valueOrNull()?.map { it.subjectId } == listOf(8)
        }
    }

    @Test
    fun `本人收藏网络失败时保留缓存`() = runBlocking {
        val dao = FakeUserCollectionDao().apply {
            upsert(UserCollectionEntity(
                subjectId = 777,
                subjectType = SubjectType.ANIME.value,
                type = CollectionType.DOING.value,
                accountId = 9,
                updatedAt = System.currentTimeMillis(),
            ))
        }
        val networkGate = CompletableDeferred<Unit>()
        val failureResponded = CompletableDeferred<Unit>()
        val vm = makeViewModel(
            username = null,
            collectionDao = dao,
            collectionResultGate = networkGate,
            collectionFailure = true,
            collectionFailureResponded = failureResponded,
        )
        awaitUntil("本人资料加载完成") { vm.uiState.value.user?.username == "fixture_user" }
        val viewModelJob = vm.viewModelScope.coroutineContext[Job]!!
        val jobsBeforeShelf = viewModelJob.children.toSet()

        vm.selectShelf(ShelfCategory.SubjectDomain(SubjectType.ANIME))
        awaitUntil("本人收藏网络请求发出") { collectionTypes.isNotEmpty() }
        awaitUntil("缓存收藏先显示") {
            vm.uiState.value.shelf.subjects.valueOrNull()?.map { it.subjectId } == listOf(777)
        }

        networkGate.complete(Unit)
        withTimeout(WAIT_BUDGET_MS) { failureResponded.await() }
        awaitUntil("本人收藏请求协程结束") {
            viewModelJob.children.none { it !in jobsBeforeShelf }
        }

        assertEquals(listOf(777), vm.uiState.value.shelf.subjects.valueOrNull()?.map { it.subjectId })
    }

    @Test
    fun `切到角色分类走 p1 端点而不是条目端点`() = runBlocking {
        // 角色/人物/目录与条目并列，但走的是各自的分页端点。
        // 这里钉住"切分类不会误用条目的 v0 端点"。
        val vm = makeViewModel()
        vm.selectShelf(ShelfCategory.SubjectDomain(SubjectType.ANIME))
        awaitUntil("首屏收藏") { vm.uiState.value.shelf.subjects is Load.Ready }
        val typesBefore = collectionTypes.size

        vm.selectShelf(ShelfCategory.CharacterPerson())
        awaitUntil("切到角色") {
            vm.uiState.value.shelf.let { it.category == ShelfCategory.CharacterPerson() && !it.characters.isLoading }
        }
        assertEquals(typesBefore, collectionTypes.size, "角色分类不该再打条目端点")
        assertEquals(
            1,
            vm.uiState.value.shelf.characters.items.size,
            "应取到 p1 角色收藏",
        )
    }

    @Test
    fun `角色与人物档切到人物后翻页沿用人物小类并发出第二页请求`() = runBlocking<Unit> {
        // 缺陷现场：续取不带小类，人物档被当成角色档、isSameCategory 判否直接 return，
        // 人物收藏永远翻不到第二页。小类现在是分类值的一部分，续取随分类一起走。
        val vm = makeViewModel()
        val persons = ShelfCategory.CharacterPerson(CharacterPersonTab.PERSON)
        vm.selectShelf(persons)
        awaitUntil("人物首屏") { vm.uiState.value.shelf.let { it.category == persons && !it.persons.isLoading } }

        vm.loadMoreShelf()
        awaitUntil("人物第二页") { vm.uiState.value.shelf.persons.items.size == 2 }
        assertEquals(2, personOffsets.size, "应发出第二页请求")
        assertTrue(personOffsets[1] > personOffsets[0], "第二页从数据层游标继续")
        assertEquals(persons, vm.uiState.value.shelf.category, "仍停在人物小类")
    }

    @Test
    fun `切方向后状态里的方向必须跟着变`() = runBlocking {
        // 缺陷现场：chip 的高亮来自界面自己的 rememberSaveable，
        // 切到「关注我的」后内容确实换了（5 人 → 2 人），但那份本地副本没被更新，
        // 高亮仍停在「我的关注」。方向只能有一个真源——就是这里的 Loaded.direction。
        val vm = makeViewModel()
        vm.loadFollowers()
        assertEquals(SocialDirection.FOLLOWERS, vm.uiState.value.social.direction)

        vm.selectSocial(SocialDirection.FOLLOWING)
        awaitUntil("切回我的关注") {
            vm.uiState.value.social.direction == SocialDirection.FOLLOWING
        }
        assertEquals(
            SocialDirection.FOLLOWING,
            vm.uiState.value.social.direction,
            "切方向必须更新状态里的 direction，界面据它高亮 chip",
        )
    }

    @Test
    fun `两个方向各自的游标互不影响`() = runBlocking {
        // 切回来时已加载过的那一份不该被清空、也不该重新从 0 取
        val vm = makeViewModel()
        vm.loadFollowers()
        assertEquals(listOf(0), offsets)
        val followers = vm.uiState.value.social.followers

        vm.selectSocial(SocialDirection.FOLLOWING)
        awaitUntil("切到我的关注") {
            vm.uiState.value.social.following.items.isNotEmpty()
        }
        val afterSwitch = offsets.size

        // 分页器的每一种重取（首屏、留旧条目换条件、下拉）都在调用当下同步改写列表（加载态、游标清空、刷新态），
        // 所以切回之后列表原样不动，就说明没有发起重取，不必等一段时间看请求来不来。
        vm.selectSocial(SocialDirection.FOLLOWERS)
        assertEquals(followers, vm.uiState.value.social.followers, "切回已加载过的方向，列表与游标原样保留")
        assertEquals(
            afterSwitch,
            offsets.size,
            "切回已加载过的方向不该再打关注者接口，实际：$offsets",
        )
    }

    @Test
    fun `续页按服务端游标推进而不是按已显示条数`() = runBlocking {
        // 首屏 100 条里少了 2 个 → 界面只显示 98 人。
        // 下一页必须从 100 开始；从 98 开始会把第 99、100 位再取一遍。
        val vm = makeViewModel()
        vm.loadFollowers()
        assertEquals(listOf(0), offsets)

        vm.loadMoreSocial()
        awaitUntil("第二页加载") { offsets.size >= 2 }
        assertEquals(listOf(0, 100), offsets, "游标必须按服务端跨度推进，实际：$offsets")

        awaitUntil("列表合并完成") { vm.uiState.value.social.followers.items.size == 198 }
    }

    @Test
    fun `续页失败时保留已加载内容且可以重试`() = runBlocking {
        failOnceAt = 100
        val vm = makeViewModel()
        vm.loadFollowers()
        assertEquals(98, vm.uiState.value.social.followers.items.size)

        vm.loadMoreSocial()
        awaitUntil("失败提示") {
            vm.uiState.value.social.followers.loadMoreError != null
        }
        // 失败不能把已经看到的人清空，也不能丢掉游标——否则重试会从头再来
        val failed = vm.uiState.value.social.followers
        assertEquals(98, failed.items.size, "续页失败不得清空已加载内容")
        assertTrue(failed.hasMore, "失败后仍应能继续翻页")

        vm.loadMoreSocial()
        awaitUntil("重试完成") { vm.uiState.value.social.followers.items.size > 98 }
        assertEquals(listOf(0, 100, 100), offsets, "重试必须复用同一游标，实际：$offsets")
    }

    @Test
    fun `关注者下拉刷新失败_列表原样留着_失败走提示条`() = runBlocking {
        val vm = makeViewModel()
        vm.loadFollowers()
        val shown = vm.uiState.value.social.followers.items

        failOnceAt = 0
        vm.refreshSocial()
        assertEquals(shown, vm.uiState.value.social.followers.items, "下拉期间条目留在原地")
        awaitUntil("失败提示") { vm.failures.current.value != null }

        val followers = vm.uiState.value.social.followers
        assertEquals(shown, followers.items, "下拉失败不清空已显示的列表")
        assertNull(followers.error, "已有内容时不变成整块报错")
        assertFalse(followers.isRefreshing, "失败后下拉指示器收起")
    }

    @Test
    fun `他人条目收藏下拉刷新失败_条目原样留着_失败走提示条`() = runBlocking {
        val vm = makeViewModel()
        val anime = ShelfCategory.SubjectDomain(SubjectType.ANIME)
        vm.selectShelf(anime)
        awaitUntil("动画收藏") { vm.uiState.value.shelf.subjects is Load.Ready }
        val shown = vm.uiState.value.shelf.subjects.valueOrNull()

        failCollections = true
        vm.selectShelf(anime, refresh = true)
        assertEquals(shown, vm.uiState.value.shelf.subjects.valueOrNull(), "下拉期间条目留在原地")
        awaitUntil("失败提示") { vm.failures.current.value != null }
        awaitUntil("下拉指示器收起") { !vm.uiState.value.shelf.subjectsRefreshing }

        assertEquals(shown, vm.uiState.value.shelf.subjects.valueOrNull(), "下拉失败不清空已显示的条目")
    }

    @Test
    fun `打开主页不取动态_动态页出现时取一次_再出现不重取`() = runBlocking {
        val gate = CompletableDeferred<Unit>().also { timelineGate = it }
        val vm = makeViewModel()
        awaitUntil("资料就绪") { vm.uiState.value.user != null }
        val viewModelJob = vm.viewModelScope.coroutineContext[Job]!!
        val before = viewModelJob.children.toSet()

        // 界面订阅动态（主页上 timeline 一直被收集；共享协程在 ViewModel 建好时就在了）。
        // 订阅上的那一刻若顺带取数，取数协程已经起了，且停在闸门上一直在途
        scope.launch { vm.timeline.collect { } }
        awaitUntil("订阅上了这个人的动态") { vm.timeline.value.isLoading }
        assertEquals(0, viewModelJob.children.count { it !in before }, "停在概览时不取动态")
        assertEquals(0, timelineRequests)
        gate.complete(Unit)

        vm.openTimeline()
        awaitUntil("动态取回") { timelineRequests == 1 && !vm.timeline.value.isLoading }

        // 再出现：重取会在 ViewModel 里起一个取数协程，等它（若有）跑完再数请求；下拉要等这之后才发，否则会并进同一次取数
        val beforeReopen = viewModelJob.children.toSet()
        withContext(main.dispatcher) { vm.openTimeline() }
        awaitUntil("再出现引起的协程都结束") { viewModelJob.children.none { it !in beforeReopen } }
        assertEquals(1, timelineRequests, "切走再切回不重取")

        vm.refreshTimeline()
        awaitUntil("下拉取回") { timelineRequests >= 2 && !vm.timeline.value.isRefreshing }
        assertEquals(2, timelineRequests, "只有下拉那一次")
    }

    @Test
    fun `整页被跳过时继续翻页而不是提前结束`() = runBlocking {
        // 第 100~199 位全查不到：这一页为空，但第 200 位之后还有数据，
        // 界面不能因为"这次没拿到人"就认为到底了。
        missing = missing + (100 until 200).toSet()
        val vm = makeViewModel(total = 250, pageSize = 100)
        vm.loadFollowers()

        vm.loadMoreSocial()
        awaitUntil("第二页（空页）完成") {
            offsets.size >= 2 && !vm.uiState.value.social.followers.isLoadingMore
        }
        vm.loadMoreSocial()
        awaitUntil("第三页完成") {
            offsets.contains(200) && !vm.uiState.value.social.followers.isLoadingMore
        }

        // 首屏 98（跳过 2 个）+ 第 200~249 位整批 50 个 = 148
        assertEquals(148, vm.uiState.value.social.followers.items.size, "第 200 位之后的用户必须能取到")
        assertEquals(listOf(0, 100, 200), offsets)
    }

    // ---- 社交操作----

    /** 他人主页 VM 的通用等待：资料就绪 + 好友名单读到（关注状态一旦读到就一直有值）。 */
    private suspend fun ProfileViewModel.awaitOthersReady() {
        withTimeout(WAIT_BUDGET_MS) { uiState.first { it.user != null } }
        awaitUntil("好友名单读到") { uiState.value.socialOps.isFriend != null }
    }

    @Test
    fun `加好友_乐观置位_在途重复点击不发第二个请求_成功不提示`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>().also { socialGate = it }
        val vm = makeViewModel()
        vm.awaitOthersReady()

        vm.addFriend()
        // 请求被闸住：此刻「在途」是确定状态
        awaitUntil("PUT 已发出") { socialRequests.contains("PUT friends") }
        assertEquals(true, vm.uiState.value.socialOps.isFriend, "乐观更新应先置位 isFriend")
        assertTrue(vm.uiState.value.socialOps.pending, "请求在途")

        vm.addFriend() // 在途中的第二次点击应被忽略
        gate.complete(Unit)
        // PUT 发出后 pending 只会由真变假：等它落定是单调条件
        awaitUntil("请求落定") { !vm.uiState.value.socialOps.pending }
        assertNull(vm.failures.current.value, "加好友成功不发提示，只有失败才提示")
        assertEquals(1, socialRequests.count { it == "PUT friends" }, "在途期间的重复点击不得发出第二个请求")
        assertEquals(true, vm.uiState.value.socialOps.isFriend, "成功后保持已关注")
        assertFalse(vm.uiState.value.socialOps.pending, "成功后在途结束")
    }

    @Test
    fun `加好友失败回滚并给失败提示`() = runBlocking<Unit> {
        failSocial = "friend"
        val vm = makeViewModel()
        vm.awaitOthersReady()

        vm.addFriend()
        awaitUntil("失败提示") { vm.failures.current.value?.text is NoticeText.Reason }
        assertEquals(false, vm.uiState.value.socialOps.isFriend, "失败必须回滚 isFriend")
        assertFalse(vm.uiState.value.socialOps.pending, "失败后在途结束")
    }

    @Test
    fun `撤销好友乐观复位`() = runBlocking<Unit> {
        val vm = makeViewModel()
        vm.awaitOthersReady()
        vm.addFriend()
        awaitUntil("先成为好友") { socialRequests.contains("PUT friends") && !vm.uiState.value.socialOps.pending }

        val gate = CompletableDeferred<Unit>().also { socialGate = it }
        vm.removeFriend()
        // 请求被闸住：此刻还没有任何回包，看到的只能是乐观值
        awaitUntil("DELETE 已发出") { socialRequests.contains("DELETE friends") }
        assertEquals(false, vm.uiState.value.socialOps.isFriend, "乐观更新应先复位 isFriend")
        assertTrue(vm.uiState.value.socialOps.pending, "请求在途")
        gate.complete(Unit)
        awaitUntil("DELETE 完成") { !vm.uiState.value.socialOps.pending }
        assertEquals(false, vm.uiState.value.socialOps.isFriend, "成功后保持未关注")
    }

    /**
     * 解除好友的接口在服务端恒 5xx，要改走网页会话；这个账号没有网页会话（不是账号密码登录的）：
     * 回滚成仍是好友，提示要账号密码登录（不是通用的「服务器开小差了」）。
     */
    @Test
    fun `解除好友遇服务端5xx且没有网页会话时回滚并提示账号密码登录`() = runBlocking<Unit> {
        val vm = makeViewModel()
        vm.awaitOthersReady()
        vm.addFriend()
        awaitUntil("先成为好友") { socialRequests.contains("PUT friends") && !vm.uiState.value.socialOps.pending }

        failSocial = "friend"
        vm.removeFriend()
        awaitUntil("失败提示") { vm.failures.current.value?.text is NoticeText.Reason }
        assertEquals(true, vm.uiState.value.socialOps.isFriend, "失败必须回滚成仍是好友")
        assertEquals(
            Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED),
            (vm.failures.current.value?.text as NoticeText.Reason).failure,
        )
    }

    @Test
    fun `绝交成功置位_取消绝交复位`() = runBlocking<Unit> {
        val vm = makeViewModel()
        vm.awaitOthersReady()

        vm.blockUser()
        awaitUntil("PUT blocklist 完成") { socialRequests.contains("PUT blocklist") && !vm.uiState.value.socialOps.pending }
        assertTrue(vm.uiState.value.socialOps.isBlocked)

        vm.unblockUser()
        awaitUntil("DELETE blocklist 完成") { socialRequests.contains("DELETE blocklist") && !vm.uiState.value.socialOps.pending }
        assertFalse(vm.uiState.value.socialOps.isBlocked)
    }

    @Test
    fun `绝交失败回滚并给失败提示`() = runBlocking<Unit> {
        failSocial = "block"
        val vm = makeViewModel()
        vm.awaitOthersReady()

        vm.blockUser()
        awaitUntil("失败提示") { vm.failures.current.value?.text is NoticeText.Reason }
        assertFalse(vm.uiState.value.socialOps.isBlocked, "失败必须回滚")
    }

    @Test
    fun `报告_没选原因不能提交_选原因后提交成功面板原地转确认态`() = runBlocking<Unit> {
        val vm = makeViewModel()
        vm.awaitOthersReady()

        vm.openReport()
        awaitUntil("面板打开") { vm.uiState.value.moderation.report != null }
        assertFalse(vm.uiState.value.moderation.report!!.canSubmit, "没选原因不能提交")

        vm.moderation.selectReportReason(ReportReason.SPAM.value)
        assertTrue(vm.uiState.value.moderation.report!!.canSubmit, "选了原因就能提交")

        vm.moderation.submitReport()
        awaitUntil("面板转确认态") { vm.uiState.value.moderation.report?.submittedText != null }
        assertNotNull(vm.uiState.value.moderation.report, "成功不关面板，原地换确认态")
        assertEquals("感谢报告，我们会尽快处理", vm.uiState.value.moderation.report!!.submittedText, "确认态展示服务端致谢")
        assertFalse(vm.uiState.value.moderation.report!!.canSubmit, "确认态不可再提交")
        assertNull(vm.failures.current.value, "成功不走提示通道")
        assertTrue(socialRequests.contains("POST report"), "应发 POST /p1/report")
    }

    @Test
    fun `报告提交失败面板保留可重试并给失败提示`() = runBlocking<Unit> {
        failSocial = "report"
        val vm = makeViewModel()
        vm.awaitOthersReady()
        vm.openReport()
        awaitUntil("面板打开") { vm.uiState.value.moderation.report != null }
        vm.moderation.selectReportReason(ReportReason.ABUSE.value)
        vm.moderation.submitReport()

        awaitUntil("失败提示") { vm.failures.current.value?.text is NoticeText.Reason }
        assertNotNull(vm.uiState.value.moderation.report, "失败面板保留，可重试")
        assertFalse(vm.uiState.value.moderation.report!!.submitting, "提交结束")
        assertEquals(ReportReason.ABUSE.value, vm.uiState.value.moderation.report?.reason, "已选原因保留")
    }
}
