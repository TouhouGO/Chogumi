package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import io.github.touhougo.chogumi.core.data.mark.StillCollectionMarker
import io.github.touhougo.chogumi.core.data.progress.CommitGate
import io.github.touhougo.chogumi.core.data.progress.EpTapCoordinator
import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeStatsModuleDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectSectionDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.ProgressOrchestrator
import io.github.touhougo.chogumi.core.data.repository.StatsLayoutRepository
import io.github.touhougo.chogumi.core.data.repository.SubjectRepository
import io.github.touhougo.chogumi.core.data.repository.TestCollections
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TestPreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testNsfwFilter
import io.github.touhougo.chogumi.core.data.repository.testSubjectRepository
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.orEmpty
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionProgress
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EP_COMMIT_DELAY_NANOS
import io.github.touhougo.chogumi.core.model.EpisodeProgressTarget
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.github.touhougo.chogumi.core.network.model.v0.BatchEpisodeStatusDto
import io.github.touhougo.chogumi.core.network.model.v0.EpisodeDto
import io.github.touhougo.chogumi.core.network.model.v0.PagedDto
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.CollectionSheetPhase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SubjectDetailsCollectionTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()

    /** 进度提交的停手窗口：默认一点就结束；要看「窗口里」的用例先关上，断言完再打开。 */
    private val commitGate = CommitGate(open = true)

    private val main = TestMain()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dao = FakeUserCollectionDao()
    private val recorder = ConcurrentRequestRecorder()
    private val patchResponse = CompletableDeferred<HttpStatusCode>()
    private val postSaveReadStarted = CompletableDeferred<Unit>()
    private val postSaveReadResponse = CompletableDeferred<Unit>()
    /** 批量章节端点的闸门：非 null 时该端点在应答前先等它（用于制造「提交在途」窗口）。 */
    private var batchGate: CompletableDeferred<Unit>? = null
    /** 同时在途的批量请求数与峰值——串行锁的**真判据**（并发就会 >1）。 */
    private val batchInFlight = java.util.concurrent.atomic.AtomicInteger(0)
    private val batchPeak = java.util.concurrent.atomic.AtomicInteger(0)
    /** 第 2 个批量请求到达时完成：用于「给第二个写入者一次真正发出的机会」。 */
    private val secondBatchArrived = CompletableDeferred<Unit>()
    private val progressReadStarted = CompletableDeferred<Unit>()
    /** 服务端的逐集状态是不是 8 集正片全看过（默认一集都没有记录）。 */
    private var serverWatchedAll = false
    private val progressReadResponse = CompletableDeferred<Unit>()
    private lateinit var client: HttpClient
    private lateinit var engine: MockEngine
    private lateinit var vm: SubjectDetailsViewModel
    private val viewModels = mutableListOf<SubjectDetailsViewModel>()

    /**
     * 体验模式夹具：本文件测的是收藏读写，默认取完整模式（与生产缺省一致），
     * 故断言看到的是完整模式的选项卡。
     */
    private val prefs = TestPreferencesRepository()

    private fun createSubjectDetailsViewModel(
        subjectId: Int,
        subRepo: SubjectRepository,
        collRepo: TestCollections,
        orchestrator: ProgressOrchestrator = collRepo.progressOrchestrator(subRepo),
        coordinator: EpTapCoordinator = collRepo.epTapCoordinator(orchestrator, scope, { 0L }, commitGate::await),
        prefsRepo: PreferencesRepository = prefs.repository,
    ): SubjectDetailsViewModel = SubjectDetailsViewModel(
        subjectId, subRepo, collRepo.store, collRepo.observer, collRepo.watchStates, collRepo.editing(subRepo, orchestrator), coordinator, prefsRepo, testAppTime(System.currentTimeMillis()),
        StillCollectionMarker(),
        SavedStateHandle(),
            statsLayouts = StatsLayoutRepository(FakeStatsModuleDao()),
        ).also { viewModels += it }

    @BeforeTest
    fun setUp() { main.install() }

    @AfterTest
    fun tearDown() {
        if (progressReadResponse.isActive) progressReadResponse.complete(Unit)
        if (postSaveReadResponse.isActive) postSaveReadResponse.complete(Unit)
        main.uninstall(viewModels.map { it.viewModelScope } + scope)
        prefs.close()
    }

    private suspend fun createViewModel() {
        val store = testTokenStore(scope = scope)
        store.completeSession(store.beginSession("test-token"), 7, "test-user")
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 8, type = 2, name = "作品", nameCn = "作品", updatedAt = System.currentTimeMillis()))
        dao.upsert(UserCollectionEntity(
            subjectId = 8, subjectType = 2, type = 3, rate = 7, epStatus = 5, volStatus = 2,
            comment = "原吐槽", tagsJson = "[\"原标签\"]", isPrivate = true, accountId = 7,
            updatedAt = System.currentTimeMillis(),
        ))
        var saved = false
        val episodes = (1..8).map { sort ->
            EpisodeDto(id = 800 + sort, subjectId = 8, name = "ep$sort", sort = sort.toDouble())
        } + listOf(
            EpisodeDto(id = 813, subjectId = 8, name = "ep12.5", sort = 12.5),
            // SP：非正片，不得进网格/进度，但必须留在状态里（网格下方只读列出）
            EpisodeDto(id = 901, subjectId = 8, type = 1, name = "sp1", sort = 4.5),
        )
        val episodeResponse = Json { encodeDefaults = true }.encodeToString(
            PagedDto(data = episodes, total = episodes.size, limit = 100, offset = 0),
        )
        /**
         * 批量章节端点写入的进度，GET 回读据此回显（模拟服务端状态）。null=未同步过。
         * 新语义下请求体恒为「sort <= N 全部正片」（增）或「sort > N 全部正片」（减），
         * 故正片 sort 连续时 max(增) / min-1(减) 仍恰等于客户端意图的 N，口径不变。
         */
        var syncedEpStatus: Int? = null
        engine = MockEngine { request ->
            recorder.record(request)
            when {
                request.method == HttpMethod.Patch && request.url.encodedPath == "/v0/users/-/collections/8/episodes" -> {
                    // 批量章节端点（与收藏 PATCH 区分）。记录区间目标进度，回读 GET 据此回显
                    val body = request.body.toByteArray().decodeToString()
                    val batch = Json.decodeFromString<BatchEpisodeStatusDto>(body)
                    val sorts = batch.episodeIds.map { id -> episodes.single { it.id == id }.sort.toInt() }
                    require(sorts.isNotEmpty() && batch.type in setOf(0, 2))
                    // API 接收章节 ID，但收藏进度是集数；两者只能从同一份章节数据映射。
                    syncedEpStatus = if (batch.type == 2) sorts.max() else sorts.min() - 1
                    // 在途计数：进入时 +1 并刷新峰值，应答后 -1
                    val now = batchInFlight.incrementAndGet()
                    if (now >= 2) secondBatchArrived.complete(Unit)
                    batchPeak.updateAndGet { maxOf(it, now) }
                    try {
                        // 测试可要求该请求挂起（制造「提交在途」窗口）；默认不阻塞
                        batchGate?.await()
                        respond("", HttpStatusCode.NoContent)
                    } finally {
                        batchInFlight.decrementAndGet()
                    }
                }
                request.method == HttpMethod.Patch -> {
                    val status = patchResponse.await()
                    saved = status == HttpStatusCode.NoContent
                    respond("", status)
                }
                request.url.encodedPath == "/v0/users/test-user/collections/8" -> {
                    if (syncedEpStatus != null) {
                        progressReadStarted.complete(Unit)
                        progressReadResponse.await()
                    }
                    if (saved) {
                        postSaveReadStarted.complete(Unit)
                        postSaveReadResponse.await()
                    }
                    respond(
                        """{"subject_id":8,"subject_type":2,"type":${if (saved) 1 else 3},"rate":7,"ep_status":${syncedEpStatus ?: 5},"vol_status":2,"comment":"原吐槽","tags":["原标签"],"private":true,"updated_at":"2026-09-22T00:00:00Z"}""",
                        HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                    )
                }
                request.url.encodedPath == "/v0/episodes" -> respond(
                    episodeResponse,
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                )
                request.url.encodedPath.endsWith("/episodes") && request.method == HttpMethod.Get -> respond(
                    if (serverWatchedAll) {
                        val done = (1..8).joinToString(",") { sort ->
                            """{"episode":{"id":${800 + sort},"subject_id":8,"type":0,"name":"ep$sort","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":$sort.0,"airdate":""},"type":2,"updated_at":1000}"""
                        } + "," + """{"episode":{"id":813,"subject_id":8,"type":0,"name":"ep12.5","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":12.5,"airdate":""},"type":2,"updated_at":1000}"""
                        """{"total":9,"limit":100,"offset":0,"data":[$done]}"""
                    } else {
                        """{"total":0,"limit":100,"offset":0,"data":[]}"""
                    },
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                )
                else -> respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        client = resources.apiClient(engine)
        val subRepo = testSubjectRepository(resources, SubjectApi(client), io.github.touhougo.chogumi.core.network.api.NextApi(client), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val collRepo = testCollections(resources, CollectionApi(client), subjectDao, dao, store, FakeEpisodeWatchStateDao())
        withContext(main.dispatcher) {
            vm = createSubjectDetailsViewModel(8, subRepo, collRepo)
        }
        withTimeout(WAIT_BUDGET_MS) {
            // 等待首次网络拍落地，避免把初始加载与保存后的回读混为同一个请求。
            // 章节列表与收藏各自独立加载：点集用例要从 episodes 里取集，必须等它到位（慢机上章节可能后到）。
            vm.uiState.first {
                it.collection.valueOrNull()?.updatedAt == java.time.Instant.parse("2026-09-22T00:00:00Z").toEpochMilli() &&
                it.relations !is Load.Pending &&
                    it.episodes.orEmpty().isNotEmpty() &&
                    (!serverWatchedAll || it.episodeWatchStates.isNotEmpty())
            }
        }
        withContext(main.dispatcher) { vm.openEditSheet() }
    }

    private suspend fun awaitProgressReadback(expectedEpStatus: Int? = null): SubjectDetailsUiState {
        withTimeout(WAIT_BUDGET_MS) { progressReadStarted.await() }
        progressReadResponse.complete(Unit)
        return withTimeout(WAIT_BUDGET_MS) {
            // 写穿透缓存使用当前时间；固定服务端时间标识回读终态，不能只断言中间缓存拍。
            // 同时核对期望进度：初次加载的拍也带同一服务端时间，只凭时间分不清是不是回读。
            vm.uiState.first {
                val collection = it.collection.valueOrNull()
                collection?.updatedAt == java.time.Instant.parse("2026-09-22T00:00:00Z").toEpochMilli() &&
                    (expectedEpStatus == null || collection.epStatus == expectedEpStatus)
            }
        }
    }

    @Test
    fun `保存成功后无需等待回读即可显示新收藏且重开弹层保持新值`() = runBlocking {
        createViewModel()
        withContext(main.dispatcher) { vm.onSaveCollection(CollectionEdits(type = CollectionType.WISH)) }
        assertEquals(CollectionType.DOING, vm.uiState.value.collection.valueOrNull()?.type)
        patchResponse.complete(HttpStatusCode.NoContent)

        val state = withTimeout(WAIT_BUDGET_MS) {
            vm.uiState.first { it.collection.valueOrNull()?.type == CollectionType.WISH }
        }
        withTimeout(WAIT_BUDGET_MS) { postSaveReadStarted.await() }
        assertFalse(postSaveReadResponse.isCompleted)
        assertEquals(1, dao.getBySubjectId(8, 7)?.type)
        assertEquals(5, state.collection.valueOrNull()?.epStatus)
        assertEquals(2, state.collection.valueOrNull()?.volStatus)
        assertEquals(true, state.collection.valueOrNull()?.isPrivate)
        assertEquals("原吐槽", state.collection.valueOrNull()?.comment)
        assertEquals(listOf("原标签"), state.collection.valueOrNull()?.tags)
        assertEquals(CollectionType.WISH, state.collectionWork!!.editor.saved.type, "重开面板看到的是新值")
    }

    @Test
    fun `正片没看完不建议标为看过`() = runBlocking {
        createViewModel()
        val state = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.episodes.orEmpty().isNotEmpty() && it.collection is Load.Ready } }
        assertFalse(state.offersMarkDone, "在看但一集都没看：章节方格下不出「全部看完了」卡片")
    }

    @Test
    fun `看完后的标为看过走收藏保存 只发状态 标完不再建议`() = runBlocking {
        serverWatchedAll = true
        createViewModel()
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.offersMarkDone } }

        withContext(main.dispatcher) { vm.markDone() }
        assertEquals(CollectionFabState.SAVING, vm.uiState.value.fabState, "与收藏面板同一条保存路径：FAB 显示保存中")
        patchResponse.complete(HttpStatusCode.NoContent)

        val state = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.collection.valueOrNull()?.type == CollectionType.DONE } }
        assertFalse(state.offersMarkDone, "已经是看过：不再建议")
        val body = recorder.single { it.method == HttpMethod.Patch && !it.url.encodedPath.endsWith("/episodes") }
            .body.toByteArray().decodeToString()
        assertTrue(body.contains("\"type\":2"), body)
        assertFalse(body.contains("comment") || body.contains("rate"), "没动的吐槽、评分不跟着发（会重进时间线）：$body")
    }

    @Test
    fun `保存失败时面板留着并提示 关掉后修改暂存 再打开接着改`() = runBlocking {
        createViewModel()
        val before = dao.getBySubjectId(8, 7)
        val edits = CollectionEdits(type = CollectionType.WISH)
        withContext(main.dispatcher) { vm.onSaveCollection(edits) }
        patchResponse.complete(HttpStatusCode.InternalServerError)
        assertEquals(R.string.collection_save_failed, vm.awaitFailureResource())
        val state = vm.uiState.value
        assertEquals(CollectionSheetPhase.OPEN, state.collectionSheet.phase, "失败时面板不退场：修改还在面板里，改一改再存")
        assertEquals(CollectionType.DOING, state.collection.valueOrNull()?.type)
        assertEquals(before, dao.getBySubjectId(8, 7))
        assertFalse(postSaveReadStarted.isCompleted)

        withContext(main.dispatcher) { vm.closeEditSheet(edits) }
        assertEquals(edits, vm.uiState.value.collectionSheet.draft, "没保存就关掉：修改暂存，下次打开带回来")
    }

    @Test
    fun `保存成功后面板退场 退场完回到关着 不留修改`() = runBlocking {
        createViewModel()
        val edits = CollectionEdits(type = CollectionType.WISH)
        withContext(main.dispatcher) { vm.onSaveCollection(edits) }
        assertEquals(CollectionSheetPhase.OPEN, vm.uiState.value.collectionSheet.phase, "保存途中面板开着，按钮里转圈")
        patchResponse.complete(HttpStatusCode.NoContent)
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.collectionSheet.phase == CollectionSheetPhase.SAVED } }

        withContext(main.dispatcher) { vm.closeEditSheet(edits) }
        val closed = vm.uiState.value
        assertEquals(CollectionSheetPhase.CLOSED, closed.collectionSheet.phase)
        assertNull(closed.collectionSheet.draft, "存过的修改不再暂存")
    }

    @Test
    fun `没改什么就关掉不留修改`() = runBlocking {
        createViewModel()
        withContext(main.dispatcher) { vm.closeEditSheet(CollectionEdits(rate = 7, comment = "原吐槽")) }
        assertEquals(CollectionSheetPhase.CLOSED, vm.uiState.value.collectionSheet.phase)
        assertNull(vm.uiState.value.collectionSheet.draft, "和已保存的一样：没有要暂存的")
    }

    @Test
    fun `内部占位状态被本地拒绝且不会发送保存请求`() = runBlocking {
        createViewModel()
        withContext(main.dispatcher) { vm.onSaveCollection(CollectionEdits(type = CollectionType.UNKNOWN)) }
        assertEquals(R.string.collection_save_failed, vm.awaitFailureResource())
        val state = vm.uiState.value
        assertEquals(CollectionType.DOING, state.collection.valueOrNull()?.type)
        assertEquals(3, dao.getBySubjectId(8, 7)?.type)
        assertTrue(recorder.none { it.method == HttpMethod.Patch })
    }

    @Test
    fun `detailsLoaded为false且网络在途中时禁止打开弹层且不被默认值覆盖`() = runBlocking {
        val store = testTokenStore(scope = scope)
        store.completeSession(store.beginSession("test-token"), 7, "test-user")
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 9, type = 2, name = "测试条目", nameCn = "测试条目", updatedAt = System.currentTimeMillis()))
        dao.upsert(
            UserCollectionEntity(
                subjectId = 9, subjectType = 2, type = 3, rate = 7, epStatus = 5, volStatus = 2,
                comment = "真实原吐槽", tagsJson = "[\"真实原标签\"]", isPrivate = true, accountId = 7,
                detailsLoaded = false, // 模拟来自列表/首页的浅层同步
                updatedAt = System.currentTimeMillis(),
            )
        )
        val networkReadGate = CompletableDeferred<Unit>()
        val testEngine = MockEngine { request ->
            when {
                request.url.encodedPath == "/v0/users/test-user/collections/9" -> {
                    networkReadGate.await()
                    respond(
                        """{"subject_id":9,"subject_type":2,"type":3,"rate":7,"ep_status":5,"vol_status":2,"comment":"真实原吐槽","tags":["真实原标签"],"private":true,"updated_at":"2026-09-22T00:00:00Z"}""",
                        HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                    )
                }
                request.url.encodedPath == "/v0/episodes" -> respond(
                    """{"data":[],"total":0,"limit":100,"offset":0}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                )
                else -> respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val testClient = resources.apiClient(testEngine)
        val testSubRepo = testSubjectRepository(resources, SubjectApi(testClient), io.github.touhougo.chogumi.core.network.api.NextApi(testClient), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val testCollRepo = testCollections(resources, CollectionApi(testClient), subjectDao, dao, store, FakeEpisodeWatchStateDao())
        val testVm = withContext(main.dispatcher) {
            createSubjectDetailsViewModel(9, testSubRepo, testCollRepo)
        }

        // 1. 网络在途中（首拍 Loading 阶段）
        assertFalse(testVm.uiState.value.collection is Load.Ready, "首拍 Loading 期间收藏不得算就绪")
        assertEquals(Load.Pending, testVm.uiState.value.collection, "首拍 Loading 期间 collection 尚未加载完成")

        // 2. 工具栏由 subject != null 即出（进页立刻有控件），收藏未到位时投影为 LOADING；
        //    弹层门禁见下方 openEditSheet 断言。
        assertEquals(
            CollectionFabState.LOADING,
            testVm.uiState.value.fabState,
            "收藏未终结前 工具栏必须可见但呈 loading 态（LOADING 投影），不得缺席也不得可开弹层",
        )

        // 3. 尝试在此期间打开弹层：门禁必须拦截，不得允许以 null 初始化打开
        withContext(main.dispatcher) { testVm.openEditSheet() }
        assertEquals(CollectionSheetPhase.CLOSED, testVm.uiState.value.collectionSheet.phase, "collection 未就绪前门禁必须拦截，严禁打开面板")

        // 4. 释放网络响应
        networkReadGate.complete(Unit)
        val readyState = withTimeout(WAIT_BUDGET_MS) {
            testVm.uiState.first { it.collection.valueOrNull() != null }
        }
        assertEquals(CollectionType.DOING, readyState.collection.valueOrNull()?.type)
        assertEquals(5, readyState.collection.valueOrNull()?.epStatus)
        assertEquals(7, readyState.collection.valueOrNull()?.rate)
        assertEquals(CollectionFabState.READY, readyState.fabState, "就绪后必须投影为 READY")

        // 5. 加载完成后方可打开弹层，且展示真实数据而非默认值
        withContext(main.dispatcher) { testVm.openEditSheet() }
        assertEquals(CollectionSheetPhase.OPEN, testVm.uiState.value.collectionSheet.phase)
        assertEquals(CollectionType.DOING, testVm.uiState.value.collection.valueOrNull()?.type)
        assertEquals(5, testVm.uiState.value.collection.valueOrNull()?.epStatus)

    }

    @Test
    fun `detailsLoaded为false且网络失败时收藏是取不到而不是就绪 不拿默认值开面板`() = runBlocking {
        val store = testTokenStore(scope = scope)
        store.completeSession(store.beginSession("test-token"), 7, "test-user")
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 10, type = 2, name = "测试条目10", nameCn = "测试条目10", updatedAt = System.currentTimeMillis()))
        dao.upsert(
            UserCollectionEntity(
                subjectId = 10, subjectType = 2, type = 3, rate = 8, epStatus = 6, volStatus = 0,
                comment = "原吐槽", tagsJson = "[]", isPrivate = false, accountId = 7,
                detailsLoaded = false,
                updatedAt = System.currentTimeMillis(),
            )
        )
        val testEngine = MockEngine { request ->
            when {
                request.url.encodedPath == "/v0/users/test-user/collections/10" -> {
                    respond("Server Error", HttpStatusCode.InternalServerError)
                }
                request.url.encodedPath == "/v0/episodes" -> respond(
                    """{"data":[],"total":0,"limit":100,"offset":0}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                )
                else -> respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val testClient = resources.apiClient(testEngine)
        val testSubRepo = testSubjectRepository(resources, SubjectApi(testClient), io.github.touhougo.chogumi.core.network.api.NextApi(testClient), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val testCollRepo = testCollections(resources, CollectionApi(testClient), subjectDao, dao, store, FakeEpisodeWatchStateDao())
        val testVm = withContext(main.dispatcher) {
            createSubjectDetailsViewModel(10, testSubRepo, testCollRepo)
        }

        // 网络失败后，门禁绝不可置位（因为存在尚未加载完成的本地真实记录，绝不能伪造成未收藏而默认覆盖）
        assertFalse(testVm.uiState.value.collection is Load.Ready)
        withContext(main.dispatcher) { testVm.openEditSheet() }
        assertEquals(CollectionSheetPhase.CLOSED, testVm.uiState.value.collectionSheet.phase)

    }

    @Test
    fun `本地无收藏缓存且网络失败时工具栏可见但点击给出提示且不开启空壳弹层`() = runBlocking {
        val store = testTokenStore(scope = scope)
        store.completeSession(store.beginSession("test-token"), 7, "test-user")
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 11, type = 2, name = "测试条目11", nameCn = "测试条目11", updatedAt = System.currentTimeMillis()))
        // dao 中无任何缓存记录（从未打开过的条目）

        val testEngine = MockEngine { request ->
            when {
                request.url.encodedPath == "/v0/users/test-user/collections/11" -> {
                    respond("Gateway Timeout", HttpStatusCode.GatewayTimeout)
                }
                // 逐集状态的后台刷新照常成功：它失败会另发一条提示，与本用例要看的点击提示抢同一个槽位
                request.url.encodedPath == "/v0/users/-/collections/11/episodes" -> respond(
                    """{"data":[],"total":0,"limit":100,"offset":0}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                )
                request.url.encodedPath == "/v0/episodes" -> respond(
                    """{"data":[],"total":0,"limit":100,"offset":0}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                )
                else -> respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val testClient = resources.apiClient(testEngine)
        val testSubRepo = testSubjectRepository(resources, SubjectApi(testClient), io.github.touhougo.chogumi.core.network.api.NextApi(testClient), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val testCollRepo = testCollections(resources, CollectionApi(testClient), subjectDao, dao, store, FakeEpisodeWatchStateDao())
        val testVm = withContext(main.dispatcher) {
            createSubjectDetailsViewModel(11, testSubRepo, testCollRepo)
        }

        // 等待加载终结
        val state = withTimeout(WAIT_BUDGET_MS) {
            testVm.uiState.first { it.collection != Load.Pending }
        }

        // 1. 新机制下 工具栏由 subject != null 即出：终结且未就绪投影为 SETTLED_NO_DATA，
        //    控件必须可见（不得消失），只是点击会被门禁拦下并提示重试。
        assertEquals(
            CollectionFabState.SETTLED_NO_DATA,
            state.fabState,
            "网络失败终结后 工具栏必须可见（进页即有控件），投影为 SETTLED_NO_DATA",
        )
        // 2. 弹层数据门禁保持未就绪
        assertFalse(state.collection is Load.Ready, "网络失败未确认服务端无收藏，严禁置位弹层门禁")
        assertTrue(state.collection is Load.Failed, "无缓存且未就绪：收藏是「取不到」，不是「没收藏」")

        // 3. 点击 工具栏尝试打开弹层：不可开空壳弹层，且必须给出重试提示
        withContext(main.dispatcher) { testVm.openEditSheet() }
        assertEquals(CollectionSheetPhase.CLOSED, testVm.uiState.value.collectionSheet.phase, "严禁打开空壳面板")
        assertEquals(R.string.collection_load_failed, (testVm.failures.current.value?.text as NoticeText.Resource).id)

    }

    @Test
    fun `已就绪收藏在遭遇非404失败时门禁保持true且保存可正常发起`() = runBlocking {
        val store = testTokenStore(scope = scope)
        store.completeSession(store.beginSession("test-token"), 7, "test-user")
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 12, type = 2, name = "测试条目12", nameCn = "测试条目12", updatedAt = System.currentTimeMillis()))
        dao.upsert(
            UserCollectionEntity(
                subjectId = 12, subjectType = 2, type = 3, rate = 7, epStatus = 5, volStatus = 2,
                comment = "真实原吐槽", tagsJson = "[]", isPrivate = false, accountId = 7,
                detailsLoaded = true, // 本地有完整缓存
                updatedAt = System.currentTimeMillis(),
            )
        )

        val getCount = AtomicInteger()
        val patchStarted = CompletableDeferred<Unit>()
        val testEngine = MockEngine { request ->
            when {
                request.method == HttpMethod.Patch -> {
                    patchStarted.complete(Unit)
                    respond("", HttpStatusCode.NoContent)
                }
                request.url.encodedPath == "/v0/users/test-user/collections/12" -> {
                    if (getCount.incrementAndGet() == 1) {
                        // 首次网络刷新：模拟网络 500 失败（SWR 后续拍失败）
                        respond("Server Error", HttpStatusCode.InternalServerError)
                    } else {
                        respond(
                            """{"subject_id":12,"subject_type":2,"type":1,"rate":7,"ep_status":5,"vol_status":2,"comment":"真实原吐槽","tags":[],"private":false,"updated_at":"2026-09-22T00:00:00Z"}""",
                            HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                        )
                    }
                }
                request.url.encodedPath == "/v0/episodes" -> respond(
                    """{"data":[],"total":0,"limit":100,"offset":0}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                )
                else -> respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val testClient = resources.apiClient(testEngine)
        val testSubRepo = testSubjectRepository(resources, SubjectApi(testClient), io.github.touhougo.chogumi.core.network.api.NextApi(testClient), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val testCollRepo = testCollections(resources, CollectionApi(testClient), subjectDao, dao, store, FakeEpisodeWatchStateDao())
        val testVm = withContext(main.dispatcher) {
            createSubjectDetailsViewModel(12, testSubRepo, testCollRepo)
        }

        // 等待加载终结（已走过缓存 Success 拍和后续的 500 Error 拍）
        withTimeout(WAIT_BUDGET_MS) {
            testVm.uiState.first { it.collection != Load.Pending }
        }

        // 1. 断言：经历非 404 失败后，此前缓存给出的就绪收藏必须留着
        assertTrue(testVm.uiState.value.collection is Load.Ready, "已就绪的收藏门禁严禁被非 404 错误打回 false")
        assertEquals(CollectionType.DOING, testVm.uiState.value.collection.valueOrNull()?.type)

        // 2. 断言：此时打开弹层必须成功
        withContext(main.dispatcher) { testVm.openEditSheet() }
        assertEquals(CollectionSheetPhase.OPEN, testVm.uiState.value.collectionSheet.phase, "已就绪状态下点击工具栏必须能正常打开面板")

        // 3. 断言：onSaveCollection 不得被静默拦截，必须真正发起保存
        withContext(main.dispatcher) { testVm.onSaveCollection(CollectionEdits(type = CollectionType.WISH)) }
        withTimeout(WAIT_BUDGET_MS) { patchStarted.await() }
        assertTrue(patchStarted.isCompleted, "onSaveCollection 必须真正触发 PATCH 保存，不得因门禁丢失被静默丢弃")

    }

    @Test
    fun `确认服务端404确无收藏时门禁仍正确置true允许开启新建`() = runBlocking {
        val store = testTokenStore(scope = scope)
        store.completeSession(store.beginSession("test-token"), 7, "test-user")
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 13, type = 2, name = "测试条目13", nameCn = "测试条目13", updatedAt = System.currentTimeMillis()))

        val testEngine = MockEngine { request ->
            when {
                request.url.encodedPath == "/v0/users/test-user/collections/13" -> {
                    respond("Not Found", HttpStatusCode.NotFound)
                }
                request.url.encodedPath == "/v0/episodes" -> respond(
                    """{"data":[],"total":0,"limit":100,"offset":0}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                )
                else -> respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val testClient = resources.apiClient(testEngine)
        val testSubRepo = testSubjectRepository(resources, SubjectApi(testClient), io.github.touhougo.chogumi.core.network.api.NextApi(testClient), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val testCollRepo = testCollections(resources, CollectionApi(testClient), subjectDao, dao, store, FakeEpisodeWatchStateDao())
        val testVm = withContext(main.dispatcher) {
            createSubjectDetailsViewModel(13, testSubRepo, testCollRepo)
        }

        withTimeout(WAIT_BUDGET_MS) {
            testVm.uiState.first { it.collection != Load.Pending }
        }

        // 404 场景下确无收藏：就绪且为 null（允许新建）
        assertTrue(testVm.uiState.value.collection is Load.Ready, "404 场景下收藏必须就绪，允许新建收藏")
        assertEquals(Load.Ready(null), testVm.uiState.value.collection)

        withContext(main.dispatcher) { testVm.openEditSheet() }
        assertEquals(CollectionSheetPhase.OPEN, testVm.uiState.value.collectionSheet.phase)

    }

    /**
     * 没收藏的作品点一集「看到这里」：替用户建的收藏是「在看」；建收藏在途时 FAB 是保存中（转圈、不开面板），
     * 这一轮结束、收藏回读上屏时才落到「在看」。
     */
    @Test
    fun `没收藏时点一集 建在看收藏 在途时FAB保存中 回读后落到在看`() = runBlocking {
        val store = testTokenStore(scope = scope)
        store.completeSession(store.beginSession("test-token"), 7, "test-user")
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 14, type = 2, name = "测试条目14", nameCn = "测试条目14", updatedAt = System.currentTimeMillis()))
        val episodes = (1..3).map { sort -> EpisodeDto(id = 1400 + sort, subjectId = 14, name = "ep$sort", sort = sort.toDouble()) }
        val episodeResponse = Json { encodeDefaults = true }.encodeToString(PagedDto(data = episodes, total = episodes.size, limit = 100, offset = 0))
        val createArrived = CompletableDeferred<Unit>()
        val createRelease = CompletableDeferred<Unit>()
        /** 建收藏请求体里的收藏状态值；null = 还没建。 */
        var createdType: Int? = null
        val jsonHeaders = headersOf("Content-Type", "application/json")
        val testEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path == "/v0/users/-/collections/14" -> {
                    createArrived.complete(Unit)
                    createRelease.await()
                    createdType = Json.decodeFromString<Map<String, Int>>(request.body.toByteArray().decodeToString()).getValue("type")
                    respond("", HttpStatusCode.NoContent)
                }
                path == "/v0/users/test-user/collections/14" -> createdType?.let { type ->
                    respond(
                        """{"subject_id":14,"subject_type":2,"type":$type,"rate":0,"ep_status":0,"vol_status":0,"comment":"","tags":[],"private":false,"updated_at":"2026-10-12T00:00:00Z"}""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                } ?: respond("Not Found", HttpStatusCode.NotFound)
                path == "/v0/episodes" -> respond(episodeResponse, HttpStatusCode.OK, jsonHeaders)
                path == "/v0/users/-/collections/14/episodes" && request.method == HttpMethod.Get ->
                    respond("""{"total":0,"limit":100,"offset":0,"data":[]}""", HttpStatusCode.OK, jsonHeaders)
                path == "/v0/users/-/collections/14/episodes" -> respond("", HttpStatusCode.NoContent)
                else -> respond("[]", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val testClient = resources.apiClient(testEngine)
        val testSubRepo = testSubjectRepository(resources, SubjectApi(testClient), io.github.touhougo.chogumi.core.network.api.NextApi(testClient), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val testCollRepo = testCollections(resources, CollectionApi(testClient), subjectDao, dao, store, FakeEpisodeWatchStateDao())
        val testVm = withContext(main.dispatcher) { createSubjectDetailsViewModel(14, testSubRepo, testCollRepo) }
        withTimeout(WAIT_BUDGET_MS) {
            testVm.uiState.first { it.collection == Load.Ready(null) && it.episodes.orEmpty().isNotEmpty() }
        }

        withContext(main.dispatcher) { testVm.onWatchUpTo(EpisodeProgressTarget.Episode(1401, 1.0)) }
        withTimeout(WAIT_BUDGET_MS) { createArrived.await() }
        awaitUntil("建收藏在途：FAB 显示保存中") { testVm.uiState.value.fabState == CollectionFabState.SAVING }
        withContext(main.dispatcher) { testVm.openEditSheet() }
        assertEquals(CollectionSheetPhase.CLOSED, testVm.uiState.value.collectionSheet.phase, "保存中点 FAB 不开面板")

        createRelease.complete(Unit)
        val settled = withTimeout(WAIT_BUDGET_MS) {
            testVm.uiState.first { it.collection.valueOrNull()?.type == CollectionType.DOING }
        }
        assertEquals(CollectionType.DOING.value, createdType, "开始看了就是在看")
        assertEquals(CollectionFabState.READY, settled.fabState, "回读上屏的同一拍落下保存中")

    }

    /**
     * 非书籍条目在详情页保存进度：集合保存走收藏 PATCH（不含 ep_status），
     * 进度变更走状态式批量端点——1 -> 4 发 sort <= 4 的全部正片（101..104）。
     */
    @Test
    fun `非书籍条目修改进度保存时换算为章节批量变更并提交`() = runBlocking {
        val store = testTokenStore(scope = scope)
        store.completeSession(store.beginSession("test-token"), 7, "test-user")
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 14, type = 2, name = "测试动画14", nameCn = "测试动画14", updatedAt = System.currentTimeMillis()))

        dao.upsert(UserCollectionEntity(
            subjectId = 14, subjectType = 2, type = 3, epStatus = 1, accountId = 7, detailsLoaded = true,
            updatedAt = System.currentTimeMillis(),
        ))

        val writes = ConcurrentRequestRecorder()
        val testEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v0/users/test-user/collections/14" -> {
                    respond(
                        """{"subject_id":14,"subject_type":2,"type":3,"rate":0,"ep_status":1,"vol_status":0,"comment":"","private":false,"tags":[],"updated_at":1000}""",
                        HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                    )
                }
                "/v0/users/-/collections/14" -> {
                    writes.record(request)
                    respond("", HttpStatusCode.NoContent)
                }
                "/v0/users/-/collections/14/episodes" -> {
                    if (request.method == HttpMethod.Patch) {
                        writes.record(request)
                        respond("", HttpStatusCode.NoContent)
                    } else {
                        // 预置 105 为 DONE（状态式写入回退断言：sort > 4 且已为 DONE 的 105 会被清空为 NONE）
                        respond(
                            """{"total":1,"limit":100,"offset":0,"data":[{"episode":{"id":105,"subject_id":14,"type":0,"name":"ep5","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":5.0,"airdate":""},"type":2,"updated_at":1000}]}""",
                            HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                        )
                    }
                }
                "/v0/episodes" -> respond(
                    content = """
                        {"data":[
                          {"id":101,"subject_id":14,"type":0,"name":"ep1","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":1.0,"airdate":""},
                          {"id":102,"subject_id":14,"type":0,"name":"ep2","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":2.0,"airdate":""},
                          {"id":103,"subject_id":14,"type":0,"name":"ep3","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":3.0,"airdate":""},
                          {"id":104,"subject_id":14,"type":0,"name":"ep4","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":4.0,"airdate":""},
                          {"id":105,"subject_id":14,"type":0,"name":"ep5","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":5.0,"airdate":""}],
                         "total":5,"limit":100,"offset":0}
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                else -> respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val testClient = resources.apiClient(testEngine)
        val testSubRepo = testSubjectRepository(resources, SubjectApi(testClient), io.github.touhougo.chogumi.core.network.api.NextApi(testClient), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val testCollRepo = testCollections(resources, CollectionApi(testClient), subjectDao, dao, store, FakeEpisodeWatchStateDao())
        val testVm = withContext(main.dispatcher) {
            createSubjectDetailsViewModel(14, testSubRepo, testCollRepo)
        }

        // 面板保存要作品、收藏、章节都到了（collectionWork）：三样都落定才开面板，三样各自只会从等待变成落定
        withTimeout(WAIT_BUDGET_MS) {
            testVm.uiState.first { it.collection.valueOrNull()?.epStatus == 1 && it.subject != null && it.episodes is Load.Ready }
        }

        // 面板上把进度拖到第 4 集
        withContext(main.dispatcher) {
            testVm.openEditSheet()
            testVm.onSaveCollection(
                CollectionEdits(progress = CollectionProgress.Episodes(EpisodeProgressTarget.Episode(104, 4.0))),
            )
        }

        withTimeout(WAIT_BUDGET_MS) {
            testVm.uiState.first { it.collectionSheet.phase == CollectionSheetPhase.SAVED }
        }

        // 验证 1：状态没变、非书籍条目的进度又不走收藏 PATCH（严禁携带 ep_status），剥离后无字段可改——不发空 PATCH
        assertTrue(writes.none { it.url.encodedPath == "/v0/users/-/collections/14" })

        // 验证 2：非书籍条目的进度变更触发章节批量端点，
        // 状态式语义：sort <= 4 的全部正片 = 101, 102, 103, 104 标 DONE，sort > 4 的 105 清 NONE
        val batchEpisodePatchBodies = writes.filter { it.url.encodedPath == "/v0/users/-/collections/14/episodes" }
            .map { it.body.toByteArray().decodeToString() }
        assertTrue(batchEpisodePatchBodies.isNotEmpty())
        val doneBatch = batchEpisodePatchBodies.first { it.contains("\"type\":2") }
        assertTrue(doneBatch.contains("\"episode_id\":[101,102,103,104]"))
        // 超出 N 的 105 严禁包含在 DONE 请求中
        assertFalse(doneBatch.contains("105"))
        val noneBatch = batchEpisodePatchBodies.first { it.contains("\"type\":0") }
        assertTrue(noneBatch.contains("\"episode_id\":[105]"))

    }

    /**
     * 缓存命中时零 loading 帧：`getCollection` 是 cache-first，`detailsLoaded=true` 的
     * 完整缓存会**先**发 Success 拍。因此 ViewModel 侧观察不到任何 LOADING 投影 ——
     * 工具栏直接以 READY 形态出现，不会闪一下 loading。
     */
    @Test
    fun `本地缓存命中时工具栏不出现loading帧`() = runBlocking<Unit> {
        val store = testTokenStore(scope = scope)
        store.completeSession(store.beginSession("test-token"), 7, "test-user")
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 15, type = 2, name = "测试条目15", nameCn = "测试条目15", updatedAt = System.currentTimeMillis()))
        dao.upsert(
            UserCollectionEntity(
                subjectId = 15, subjectType = 2, type = 3, rate = 7, epStatus = 5, volStatus = 2,
                comment = "缓存吐槽", tagsJson = "[\"缓存标签\"]", isPrivate = false, accountId = 7,
                detailsLoaded = true, // 完整缓存 → getCollection 首拍即 Success(cached = true)
                updatedAt = System.currentTimeMillis(),
            )
        )
        // 网络拍挂起，确保观察窗口内只有缓存拍
        val networkGate = CompletableDeferred<Unit>()
        val testEngine = MockEngine { request ->
            when {
                request.url.encodedPath == "/v0/users/test-user/collections/15" -> {
                    networkGate.await()
                    respond(
                        """{"subject_id":15,"subject_type":2,"type":3,"rate":7,"ep_status":5,"vol_status":2,"comment":"缓存吐槽","tags":["缓存标签"],"private":false,"updated_at":"2026-09-22T00:00:00Z"}""",
                        HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                    )
                }
                request.url.encodedPath == "/v0/episodes" -> respond(
                    """{"data":[],"total":0,"limit":100,"offset":0}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                )
                else -> respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val testClient = resources.apiClient(testEngine)
        val testSubRepo = testSubjectRepository(resources, SubjectApi(testClient), io.github.touhougo.chogumi.core.network.api.NextApi(testClient), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val testCollRepo = testCollections(resources, CollectionApi(testClient), subjectDao, dao, store, FakeEpisodeWatchStateDao())

        // VM 由构造触发 load()：缓存拍会先于网络拍发 Success
        val testVm = withContext(main.dispatcher) {
            createSubjectDetailsViewModel(15, testSubRepo, testCollRepo)
        }

        // 等待缓存拍落地
        val cachedState = withTimeout(WAIT_BUDGET_MS) {
            testVm.uiState.first { it.collection.valueOrNull() != null }
        }

        // 关键断言：缓存命中后从未经过 LOADING；且网络拍仍挂起，说明这是纯缓存拍的结果
        assertEquals(CollectionFabState.READY, cachedState.fabState, "缓存命中后 FAB 必须直接 READY，不得出现 loading 帧")
        assertFalse(networkGate.isCompleted, "网络拍仍挂起，证明上述 READY 来自缓存拍")

        networkGate.complete(Unit)
    }

    /**
     * 无缓存时 loading → 终态：先经 LOADING（工具栏可见并内嵌 loading），
     * 网络回来后落到 READY。
     */
    @Test
    fun `无缓存时工具栏从loading过渡到终态`() = runBlocking {
        val testVm = createIsolatedViewModel(subjectId = 16)

        // 首拍：无缓存 → Loading，投影为 LOADING
        assertEquals(CollectionFabState.LOADING, testVm.uiState.value.fabState, "无缓存首拍必须为 LOADING")

        val terminal = withTimeout(WAIT_BUDGET_MS) { testVm.uiState.first { it.collection != Load.Pending } }
        assertEquals(CollectionFabState.READY, terminal.fabState, "网络成功后必须落到 READY")
        assertTrue(terminal.collection is Load.Ready)

    }

    /**
     * loading 期点击 工具栏不开弹层：门禁（收藏就绪才开面板）仍在 ViewModel 里原样生效，
     * 与 工具栏是否可见无关 —— 这就是「工具栏可见 ≠ 弹层可开」。
     */
    @Test
    fun `loading期点击工具栏不开弹层`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val store = testTokenStore(scope = scope)
        store.completeSession(store.beginSession("test-token"), 7, "test-user")
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 17, type = 2, name = "测试条目17", nameCn = "测试条目17", updatedAt = System.currentTimeMillis()))
        // 无缓存
        val testEngine = MockEngine { request ->
            when {
                request.url.encodedPath == "/v0/users/test-user/collections/17" -> {
                    gate.await()
                    respond(
                        """{"subject_id":17,"subject_type":2,"type":3,"rate":0,"ep_status":0,"vol_status":0,"comment":"","tags":[],"private":false,"updated_at":"2026-09-22T00:00:00Z"}""",
                        HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                    )
                }
                // 逐集状态的后台刷新照常成功：它失败会另发一条提示，与本用例要看的点击提示抢同一个槽位
                request.url.encodedPath == "/v0/users/-/collections/17/episodes" -> respond(
                    """{"data":[],"total":0,"limit":100,"offset":0}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                )
                request.url.encodedPath == "/v0/episodes" -> respond(
                    """{"data":[],"total":0,"limit":100,"offset":0}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                )
                else -> respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val testClient = resources.apiClient(testEngine)
        val testSubRepo = testSubjectRepository(resources, SubjectApi(testClient), io.github.touhougo.chogumi.core.network.api.NextApi(testClient), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val testCollRepo = testCollections(resources, CollectionApi(testClient), subjectDao, dao, store, FakeEpisodeWatchStateDao())
        val testVm = withContext(main.dispatcher) {
            createSubjectDetailsViewModel(17, testSubRepo, testCollRepo)
        }

        // 处于 LOADING 且 工具栏可见
        assertEquals(CollectionFabState.LOADING, testVm.uiState.value.fabState)

        // 点击 工具栏→ 门禁拦截，不开弹层；且因为尚未 settled，不弹「加载失败」提示（那是 SETTLED_NO_DATA 才给）
        withContext(main.dispatcher) { testVm.openEditSheet() }
        assertEquals(CollectionSheetPhase.CLOSED, testVm.uiState.value.collectionSheet.phase, "loading 期严禁打开面板")
        assertNull(testVm.failures.current.value, "loading 期尚未终结，不应提前给出失败提示")

        // 释放网络后仍可正常打开
        gate.complete(Unit)
        withTimeout(WAIT_BUDGET_MS) { testVm.uiState.first { it.collection is Load.Ready } }
        withContext(main.dispatcher) { testVm.openEditSheet() }
        assertEquals(CollectionSheetPhase.OPEN, testVm.uiState.value.collectionSheet.phase)

    }

    /** 构造一个指向独立 subjectId 的 VM（网络恒定成功、无缓存）。 */
    private suspend fun createIsolatedViewModel(subjectId: Int): SubjectDetailsViewModel {
        val store = testTokenStore(scope = scope)
        store.completeSession(store.beginSession("test-token"), 7, "test-user")
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = subjectId, type = 2, name = "条目$subjectId", nameCn = "条目$subjectId", updatedAt = System.currentTimeMillis()))
        val testEngine = MockEngine { request ->
            when {
                request.url.encodedPath == "/v0/users/test-user/collections/$subjectId" -> respond(
                    """{"subject_id":$subjectId,"subject_type":2,"type":3,"rate":0,"ep_status":0,"vol_status":0,"comment":"","tags":[],"private":false,"updated_at":"2026-09-22T00:00:00Z"}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                )
                request.url.encodedPath == "/v0/episodes" -> respond(
                    """{"data":[],"total":0,"limit":100,"offset":0}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                )
                else -> respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val testClient = resources.apiClient(testEngine)
        val testSubRepo = testSubjectRepository(resources, SubjectApi(testClient), io.github.touhougo.chogumi.core.network.api.NextApi(testClient), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val testCollRepo = testCollections(resources, CollectionApi(testClient), subjectDao, dao, store, FakeEpisodeWatchStateDao())
        return withContext(main.dispatcher) {
            createSubjectDetailsViewModel(subjectId, testSubRepo, testCollRepo)
        }
    }

    /**
     * 保存成功后请求落定：解除在途、不走 error 通道（成功不弹提示条，由界面自身变化表达）。
     */
    @Test
    fun `保存成功落定且不走error通道`() = runBlocking {
        createViewModel()
        assertFalse(vm.uiState.value.collectionSheet.saving, "初始不在保存中")

        withContext(main.dispatcher) { vm.onSaveCollection(CollectionEdits(type = CollectionType.WISH)) }
        assertTrue(vm.uiState.value.collectionSheet.saving, "提交后进入在途")
        patchResponse.complete(HttpStatusCode.NoContent)

        withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { !it.collectionSheet.saving } }
        assertNull(vm.failures.current.value, "成功路径不得给失败提示")
    }

    /**
     * 保存失败 → 给出「收藏保存失败」提示，不得误报成功，也不是整页失败。
     */
    @Test
    fun `保存失败给出失败提示`() = runBlocking {
        createViewModel()
        withContext(main.dispatcher) { vm.onSaveCollection(CollectionEdits(type = CollectionType.WISH)) }
        patchResponse.complete(HttpStatusCode.InternalServerError)

        assertEquals(R.string.collection_save_failed, vm.awaitFailureResource())
        assertNull(vm.uiState.value.error, "保存失败不是整页失败")
    }

    /**
     * 改**标签**保存成功同样落定（由界面自身变化表达，不弹提示条）。
     * 实测依据（2026-09-25 模拟器抓包）：tag-only PATCH 成功、无 error。
     */
    @Test
    fun `改标签保存成功落定且不报错`() = runBlocking {
        createViewModel()

        // tag-only：只动了标签
        withContext(main.dispatcher) {
            vm.onSaveCollection(CollectionEdits(tags = listOf("新标签")))
        }
        assertTrue(vm.uiState.value.collectionSheet.saving, "提交后进入在途")
        patchResponse.complete(HttpStatusCode.NoContent)

        val settled = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { !it.collectionSheet.saving } }
        assertNull(settled.error, "tag-only 成功路径不得走 error 通道")
    }

    /**
     * 保存请求在途投影为 SAVING；失败解除 SAVING 回落 READY。
     * SAVING 的意义：填补「点保存 → 网络返回」约 2 秒的反馈空窗（实测），
     * 工具栏显示 loading 圈 + 原状态文案。
     */
    @Test
    fun `保存在途投影为SAVING且失败后解除`() = runBlocking {
        createViewModel()
        assertFalse(vm.uiState.value.collectionSheet.saving, "初始不在保存中")
        assertEquals(CollectionFabState.READY, vm.uiState.value.fabState, "就绪态基线")

        // PATCH 挂起（patchResponse 未 complete）→ 在途
        withContext(main.dispatcher) { vm.onSaveCollection(CollectionEdits(type = CollectionType.WISH)) }
        assertEquals(CollectionFabState.SAVING, vm.uiState.value.fabState, "保存请求在途必须投影为 SAVING")
        assertTrue(vm.uiState.value.collectionSheet.saving)

        // 失败 → 解除 SAVING（回落 READY，已就绪的收藏未被打回）
        patchResponse.complete(HttpStatusCode.InternalServerError)
        assertEquals(R.string.collection_save_failed, vm.awaitFailureResource())
        val settled = vm.uiState.value
        assertEquals(CollectionFabState.READY, settled.fabState, "失败后必须解除 SAVING 回落 READY")
        assertFalse(settled.collectionSheet.saving, "保存在途标记必须归位")
    }

    /**
     * 保存在途时 FAB 忙：面板里再点保存不发第二次请求；途中把面板关了、修改暂存着，再点 FAB 也不开面板。
     * 保存成功后暂存的正是存了的这一份，一并清掉。
     */
    @Test
    fun `保存在途时再保存与再开面板都不响应 存成功后暂存的同一份修改清掉`() = runBlocking {
        createViewModel()
        val edits = CollectionEdits(type = CollectionType.WISH)
        withContext(main.dispatcher) {
            vm.onSaveCollection(edits)
            vm.onSaveCollection(CollectionEdits(type = CollectionType.DONE))
            vm.closeEditSheet(edits)
            vm.openEditSheet()
        }
        val saving = vm.uiState.value
        assertEquals(CollectionSheetPhase.CLOSED, saving.collectionSheet.phase, "保存在途不开面板")
        assertEquals(edits, saving.collectionSheet.draft, "途中关掉面板：修改暂存")

        patchResponse.complete(HttpStatusCode.NoContent)
        val settled = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { !it.collectionSheet.saving } }
        assertEquals(1, recorder.count { it.method == HttpMethod.Patch }, "只发一次保存请求")
        assertNull(settled.collectionSheet.draft, "存了的那一份不再暂存")
        assertEquals(CollectionSheetPhase.CLOSED, settled.collectionSheet.phase, "面板已经关了：成功后不再「退场」")
    }

    /**
     * 保存成功同样解除 SAVING（成功路径在途标记归位）。
     */
    @Test
    fun `保存成功后SAVING留到回读上屏 与新状态同一拍解除`() = runBlocking {
        createViewModel()
        withContext(main.dispatcher) { vm.onSaveCollection(CollectionEdits(type = CollectionType.WISH)) }
        assertEquals(CollectionFabState.SAVING, vm.uiState.value.fabState)

        patchResponse.complete(HttpStatusCode.NoContent)
        val settled = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.fabState != CollectionFabState.SAVING } }
        assertEquals(CollectionFabState.READY, settled.fabState)
        assertEquals(CollectionType.WISH, settled.collection.valueOrNull()?.type, "解除保存中的那一拍，FAB 下面的收藏已是新状态")
    }

    /**
     * 点第 N 集 = 看到第 N 集——委托 changeProgress 的状态式语义，
     * 不发单集 PUT。当前进度 5，点 sort=8 的集 → 批量标 sort <= 8 的全部正片为 DONE。
     */
    @Test
    fun `点击未看集触发批量标记而非单集PUT`() = runBlocking {
        createViewModel()

        withContext(main.dispatcher) { vm.onWatchUpTo(EpisodeProgressTarget.Episode(808, 8.0)) }
        val batched = awaitProgressReadback(expectedEpStatus = 8)
        assertEquals(8, batched.collection.valueOrNull()?.epStatus, "点第 8 集后进度必须是 8（看到第 8 集）")
        // 状态式请求：批量端点必须被调用，单集 PUT 严禁出现
        val paths = recorder.map { it.url.encodedPath }
        assertTrue(
            paths.any { it == "/v0/users/-/collections/8/episodes" },
            "必须走批量章节端点（状态式语义）: $paths",
        )
        assertTrue(
            paths.none { it.startsWith("/v0/users/-/collections/-/episodes/") },
            "严禁再走单集 PUT: $paths",
        )
    }

    /**
     * 点击已看区间内的集（sort=3 < 当前进度 5）→ 清除 sort > 3 的全部正片，进度回退到 3。
     */
    @Test
    fun `点击已看集回退进度到该集`() = runBlocking {
        createViewModel()

        withContext(main.dispatcher) { vm.onWatchUpTo(EpisodeProgressTarget.Episode(803, 3.0)) }
        val rolled = awaitProgressReadback(expectedEpStatus = 3)
        assertEquals(3, rolled.collection.valueOrNull()?.epStatus, "点已看区间第 3 集 → 进度回退到 3")
    }

    // -----------------------------------------------------------------------
    // 章节网格：乐观更新 + 停手合并提交（写入核心在 EpTapCoordinator，这里测详情页接线）
    // -----------------------------------------------------------------------

    /** 数出「批量章节端点」的写请求次数（GET 回读不算）。 */
    private fun batchWriteCount(): Int = (recorder as Iterable<io.ktor.client.request.HttpRequestData>)
        .count { it.method == HttpMethod.Patch && it.url.encodedPath == "/v0/users/-/collections/8/episodes" }

    @Test
    fun `网格点选立刻反映在逐集状态 连点只发一次写入`() = runBlocking {
        createViewModel()
        commitGate.close()
        withContext(main.dispatcher) {
            vm.onWatchUpTo(EpisodeProgressTarget.Episode(806, 6.0))
            vm.onWatchUpTo(EpisodeProgressTarget.Episode(803, 3.0))
        }
        // 乐观：不等服务器，逐集状态立刻是「看到第 3 集」
        awaitUntil("乐观更新") {
            vm.uiState.value.episodeWatchStates.filterValues { it == EpisodeStatus.DONE }.keys == setOf(801, 802, 803)
        }
        assertEquals(0, batchWriteCount(), "停手窗口内不得发出写入")
        commitGate.open()
        awaitProgressReadback(expectedEpStatus = 3)
        assertEquals(1, batchWriteCount(), "连点只以最后一次为准写一次")
    }

    @Test
    fun `停手待提交时方格拿得到这一轮的提交时刻 提交后清掉`() = runBlocking {
        createViewModel()
        commitGate.close()
        withContext(main.dispatcher) { vm.onWatchUpTo(EpisodeProgressTarget.Episode(806, 6.0)) }
        awaitUntil("待提交") { vm.uiState.value.pendingCommit != null }
        // 协调器的时钟在这里恒为 0：提交时刻 = 0 + 停手时长
        assertEquals(EP_COMMIT_DELAY_NANOS, vm.uiState.value.pendingCommit?.commitAtNanos)
        commitGate.open()
        awaitProgressReadback(expectedEpStatus = 6)
        awaitUntil("提交后清掉") { vm.uiState.value.pendingCommit == null }
    }

    /**
     * 状态里存的是**完整**章节列表（含 SP）：特别篇只读列出，且不进任何写入。
     *
     * 这条守的是「正片切分只有一份」：章节存完整列表，正片 / 非正片只经 `EpisodeTracking` 切分；
     * 状态里一旦只留正片，网格照样正确，特别篇列表却永远为空。
     */
    @Test
    fun `状态保留非正片供只读列出且不参与进度写入`() = runBlocking {
        createViewModel()
        val all = vm.uiState.value.episodes.orEmpty()
        assertTrue(all.any { it.id == 901 }, "SP 必须留在状态里（否则特别篇列表永远为空）")

        // 标到第 8 集：写入只含正片，SP 不得出现在任何批量请求体里
        val before = batchWriteCount()
        withContext(main.dispatcher) { vm.onWatchUpTo(EpisodeProgressTarget.Episode(808, 8.0)) }
        awaitUntil("写入发出") { batchWriteCount() > before }
        assertTrue(
            recorder.none { it.method == HttpMethod.Patch && it.url.encodedPath.contains("/episodes") &&
                it.body.toByteArray().decodeToString().contains("901") },
            "SP 严禁进批量写入",
        )
    }

    /** 小数集号（12.5 话）是一集正片：看到它，它和它之前的正片都标看过，不按整数集号截掉。 */
    @Test
    fun `看到小数集号那一集_它也在看过的批量里`() = runBlocking {
        createViewModel()
        // 看到 12.5 话（含分集的作品很常见）；正片是 1 到 8 话（801 到 808）与 12.5 话（813）
        withContext(main.dispatcher) { vm.onWatchUpTo(EpisodeProgressTarget.Episode(813, 12.5)) }
        awaitUntil("写入发出") { batchWriteCount() > 0 }

        val bodies = recorder.filter {
            it.method == HttpMethod.Patch && it.url.encodedPath == "/v0/users/-/collections/8/episodes"
        }.map { it.body.toByteArray().decodeToString() }
        val doneBatch = bodies.first { it.contains("\"type\":2") }
        assertTrue(doneBatch.contains("\"episode_id\":[801,802,803,804,805,806,807,808,813]"), "看过的批量：$doneBatch")
    }

    /**
     * 提交途中再点网格：第二轮写入必须等第一轮结束（按条目串行），不得同时有两个批量请求在途。
     *
     * 确定性做法：批量端点被闸门卡住，第一轮停在请求里；此时再点一集，它的提交在串行锁上排队。
     * 判据是**在途峰值**——并发就会 >1。
     */
    @Test
    fun `提交途中点网格不会发出第二个并发批量请求`() = runBlocking {
        createViewModel()
        val gate = CompletableDeferred<Unit>()
        batchGate = gate

        withContext(main.dispatcher) { vm.onWatchUpTo(EpisodeProgressTarget.Episode(803, 3.0)) }
        awaitUntil("第一轮写入卡在请求里") { batchInFlight.get() == 1 }
        withContext(main.dispatcher) { vm.onWatchUpTo(EpisodeProgressTarget.Episode(806, 6.0)) }
        // 给第二轮一次**真正发出**的机会：有锁时它一直排队，这里会超时返回。
        // 这段等待不承载断言，只为让「没有锁」的实现露出并发的马脚。
        withTimeoutOrNull(1_000) { secondBatchArrived.await() }

        gate.complete(Unit)
        awaitUntil("两轮写入都完成") { batchWriteCount() == 2 && batchInFlight.get() == 0 }
        assertEquals(1, batchPeak.get(), "两轮写入必须先后串行：在途峰值必须是 1")
    }

    /**
     * ① 音乐/游戏条目没有进度区，保存时收藏记录不带进度：
     * MockEngine 抓 PATCH body 断言不含 ep_status 与 vol_status 键（杜绝 400）。
     */
    @Test
    fun `音乐与游戏条目保存时epStatus与volStatus为null且PATCH请求体不含进度键`() = runBlocking {
        val store = testTokenStore(scope = scope)
        store.completeSession(store.beginSession("test-token"), 7, "test-user")
        val subjectDao = FakeSubjectDao()
        // type = 3 为 MUSIC
        subjectDao.upsert(SubjectEntity(id = 301, type = 3, name = "音乐作品", nameCn = "音乐作品", updatedAt = System.currentTimeMillis()))
        val collDao = FakeUserCollectionDao()
        collDao.upsert(UserCollectionEntity(
            subjectId = 301, subjectType = 3, type = 3, rate = 8, epStatus = 0, volStatus = 0,
            comment = "好听", tagsJson = "[]", isPrivate = false, accountId = 7,
            updatedAt = System.currentTimeMillis(),
        ))

        val writes = ConcurrentRequestRecorder()
        val testEngine = MockEngine { request ->
            when {
                request.method == HttpMethod.Patch && request.url.encodedPath == "/v0/users/-/collections/301" -> {
                    writes.record(request)
                    respond("", HttpStatusCode.NoContent)
                }
                // 与本地缓存同值：保存前的「原值」不随缓存拍 / 网络拍谁先到而变（网络拍若回 type 1，正是要保存的值，
                // 网络拍先到时没有改动、根本不发 PATCH）
                request.url.encodedPath == "/v0/users/test-user/collections/301" -> {
                    respond(
                        """{"subject_id":301,"subject_type":3,"type":3,"rate":8,"ep_status":0,"vol_status":0,"comment":"好听","tags":[],"private":false,"updated_at":"2026-09-22T00:00:00Z"}""",
                        HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                    )
                }
                else -> respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val testClient = resources.apiClient(testEngine)
        val subRepo = testSubjectRepository(resources, SubjectApi(testClient), io.github.touhougo.chogumi.core.network.api.NextApi(testClient), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val collRepo = testCollections(resources, CollectionApi(testClient), subjectDao, collDao, store, FakeEpisodeWatchStateDao())
        val testVm = withContext(main.dispatcher) {
            createSubjectDetailsViewModel(301, subRepo, collRepo)
        }
        withTimeout(WAIT_BUDGET_MS) { testVm.uiState.first { it.collection is Load.Ready && it.subject != null } }

        // 面板上只改了状态
        withContext(main.dispatcher) { testVm.onSaveCollection(CollectionEdits(type = CollectionType.WISH)) }

        withTimeout(WAIT_BUDGET_MS) { testVm.uiState.first { !it.collectionSheet.saving } }
        val patchBody = assertNotNull(writes.singleOrNull(), "必须发起 PATCH 请求").body.toByteArray().decodeToString()
        assertFalse(patchBody.contains("ep_status"), "音乐条目 PATCH body 严禁携带 ep_status: $patchBody")
        assertFalse(patchBody.contains("vol_status"), "音乐条目 PATCH body 严禁携带 vol_status: $patchBody")
    }

    /**
     * ② 书籍保存时 body 同时含 ep_status 与 vol_status。
     */
    @Test
    fun `书籍保存时PATCH请求体同时包含ep_status与vol_status`() = runBlocking {
        val store = testTokenStore(scope = scope)
        store.completeSession(store.beginSession("test-token"), 7, "test-user")
        val subjectDao = FakeSubjectDao()
        // type = 1 为 BOOK
        subjectDao.upsert(SubjectEntity(id = 101, type = 1, name = "书籍作品", nameCn = "书籍作品", updatedAt = System.currentTimeMillis()))
        val collDao = FakeUserCollectionDao()
        collDao.upsert(UserCollectionEntity(
            subjectId = 101, subjectType = 1, type = 3, rate = 9, epStatus = 10, volStatus = 2,
            comment = "神作", tagsJson = "[]", isPrivate = false, accountId = 7,
            updatedAt = System.currentTimeMillis(),
        ))

        val writes = ConcurrentRequestRecorder()
        val testEngine = MockEngine { request ->
            when {
                request.method == HttpMethod.Patch && request.url.encodedPath == "/v0/users/-/collections/101" -> {
                    writes.record(request)
                    respond("", HttpStatusCode.NoContent)
                }
                // 与本地缓存同值：保存前的「原值」不随缓存拍 / 网络拍谁先到而变，改动一定含 ep_status 与 vol_status
                // （网络拍若回要保存的 15 / 3，网络拍先到时没有改动、根本不发 PATCH）
                request.url.encodedPath == "/v0/users/test-user/collections/101" -> {
                    respond(
                        """{"subject_id":101,"subject_type":1,"type":3,"rate":9,"ep_status":10,"vol_status":2,"comment":"神作","tags":[],"private":false,"updated_at":"2026-09-22T00:00:00Z"}""",
                        HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                    )
                }
                else -> respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val testClient = resources.apiClient(testEngine)
        val subRepo = testSubjectRepository(resources, SubjectApi(testClient), io.github.touhougo.chogumi.core.network.api.NextApi(testClient), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val collRepo = testCollections(resources, CollectionApi(testClient), subjectDao, collDao, store, FakeEpisodeWatchStateDao())
        val testVm = withContext(main.dispatcher) {
            createSubjectDetailsViewModel(101, subRepo, collRepo)
        }
        withTimeout(WAIT_BUDGET_MS) { testVm.uiState.first { it.collection is Load.Ready && it.subject != null } }

        // 面板上把两把尺子拖到第 15 话、第 3 卷
        withContext(main.dispatcher) { testVm.onSaveCollection(CollectionEdits(progress = CollectionProgress.Book(15, 3))) }

        withTimeout(WAIT_BUDGET_MS) { testVm.uiState.first { !it.collectionSheet.saving } }
        val patchBody = assertNotNull(writes.singleOrNull(), "必须发起 PATCH 请求").body.toByteArray().decodeToString()
        assertTrue(patchBody.contains("\"ep_status\":15"), "书籍 PATCH body 必须携带 ep_status: $patchBody")
        assertTrue(patchBody.contains("\"vol_status\":3"), "书籍 PATCH body 必须携带 vol_status: $patchBody")
    }

    /**
     * 详情页 Tab 分化与回落：非剧集条目（游戏/音乐/书籍）选择 EPISODES 时自动回退至概览。
     */
    @Test
    fun `非剧集条目选择EPISODES时自动回退至概览`() = runBlocking {
        val store = testTokenStore(scope = scope)
        store.completeSession(store.beginSession("test-token"), 7, "test-user")
        val subjectDao = FakeSubjectDao()
        // type = 4 为 GAME
        // 评分人数给足，统计 tab 才可选（不足 10 人时统计 tab 不出现）
        subjectDao.upsert(SubjectEntity(id = 201, type = 4, name = "游戏作品", nameCn = "游戏作品", ratingTotal = 100, updatedAt = System.currentTimeMillis()))
        val collDao = FakeUserCollectionDao()
        val testEngine = MockEngine { _ ->
            respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val testClient = resources.apiClient(testEngine)
        val subRepo = testSubjectRepository(resources, SubjectApi(testClient), io.github.touhougo.chogumi.core.network.api.NextApi(testClient), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val collRepo = testCollections(resources, CollectionApi(testClient), subjectDao, collDao, store, FakeEpisodeWatchStateDao())
        val testVm = withContext(main.dispatcher) {
            createSubjectDetailsViewModel(201, subRepo, collRepo)
        }
        withTimeout(WAIT_BUDGET_MS) { testVm.uiState.first { it.subject != null } }

        // 选择合法的 STATS
        withContext(main.dispatcher) { testVm.selectSection(SubjectDetailsSection.STATS) }
        assertEquals(SubjectDetailsSection.STATS, testVm.uiState.value.selectedSection)

        // 尝试选择非法（对游戏隐藏）的 EPISODES，必须自动回退至概览
        withContext(main.dispatcher) { testVm.selectSection(SubjectDetailsSection.EPISODES) }
        assertEquals(SubjectDetailsSection.OVERVIEW, testVm.uiState.value.selectedSection)

    }

    /**
     * 详情页 Tab 分化与回落：加载非剧集条目（如音乐）时，若当前选中态为 EPISODES，自动纠正至概览。
     */
    @Test
    fun `加载非剧集条目时自动修正非法选中态至概览`() = runBlocking {
        val store = testTokenStore(scope = scope)
        store.completeSession(store.beginSession("test-token"), 7, "test-user")
        val subjectDao = FakeSubjectDao()
        // type = 3 为 MUSIC
        subjectDao.upsert(SubjectEntity(id = 301, type = 3, name = "音乐作品", nameCn = "音乐作品", updatedAt = System.currentTimeMillis()))
        val collDao = FakeUserCollectionDao()
        val testEngine = MockEngine { _ ->
            respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val testClient = resources.apiClient(testEngine)
        val subRepo = testSubjectRepository(resources, SubjectApi(testClient), io.github.touhougo.chogumi.core.network.api.NextApi(testClient), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val collRepo = testCollections(resources, CollectionApi(testClient), subjectDao, collDao, store, FakeEpisodeWatchStateDao())
        val testVm = withContext(main.dispatcher) {
            createSubjectDetailsViewModel(301, subRepo, collRepo).apply {
                selectSection(SubjectDetailsSection.EPISODES)
            }
        }
        withTimeout(WAIT_BUDGET_MS) { testVm.uiState.first { it.subject != null } }

        // 条目加载成功（类型为 MUSIC）后，自动纠正为概览
        assertEquals(SubjectDetailsSection.OVERVIEW, testVm.uiState.value.selectedSection)

    }


    /** 等到失败提示发出，返回它的字符串资源。 */
    private suspend fun SubjectDetailsViewModel.awaitFailureResource(): Int =
        (withTimeout(WAIT_BUDGET_MS) { failures.current.filterNotNull().first() }.text as NoticeText.Resource).id
}
