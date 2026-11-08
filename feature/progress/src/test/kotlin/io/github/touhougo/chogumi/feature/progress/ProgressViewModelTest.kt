package io.github.touhougo.chogumi.feature.progress

import androidx.lifecycle.viewModelScope
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import io.github.touhougo.chogumi.core.data.progress.CommitGate
import io.github.touhougo.chogumi.core.ui.common.navigation.ProgressEntryRequests
import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectSectionDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TestPreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testNsfwFilter
import io.github.touhougo.chogumi.core.data.repository.testSubjectRepository
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.ProgressCategory
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.ProgressOrder
import io.github.touhougo.chogumi.core.model.ProgressSort
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SearchDimensionOption
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.CollectionSheetPhase
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.CoroutineContext

class ProgressViewModelTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()

    /** 进度提交的停手窗口：默认一点就结束；要看「窗口里」的用例先关上，断言完再打开。 */
    private val commitGate = CommitGate(open = true)


    // Main 用单线程执行器而非 UnconfinedTestDispatcher：VM 内 applyFilter（观察 DB）
    // 与 load 收尾都做 "读 state → copy → 写回"，Unconfined 会让这些恢复点散落到
    // IO 线程并发交叠，后写覆盖前写（collections 偶发被回退为空）。单线程串行
    // 与生产 Main 线程 confinement 同语义，竞态在构造上消失。
    private val main = TestMain()
    /** DataStore 专用调度器：隔离全局 IO 池的繁忙程度，避免 tokenFor 读取被饿死。 */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "progress-io").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(ioExecutor.asCoroutineDispatcher() + SupervisorJob())
    private val viewModels = mutableListOf<ProgressViewModel>()
    /** 设置里的偏好（进度页默认分类）：真 DataStore，缺省即生产缺省（全部）。 */
    private val prefs = TestPreferencesRepository()
    private val recorder = ConcurrentRequestRecorder()
    private lateinit var dao: FakeUserCollectionDao
    private lateinit var subjectDao: FakeSubjectDao
    private lateinit var watchStateDao: FakeEpisodeWatchStateDao
    /** 章节表：收藏仓库（逐集记录带回的章节）与条目仓库（章节缓存）共用，与生产里同一个库一致。 */
    private lateinit var episodeDao: FakeEpisodeDao
    private lateinit var engine: MockEngine

    @Test
    fun `全部页首类空结果到达后仍等待其他类型`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val vm = makeViewModel(routed = ContentDomains.toSet()) { request ->
            if (request.url.parameters["subject_type"] != SubjectType.ANIME.value.toString()) gate.await()
            respond(emptyCollectionsJson, HttpStatusCode.OK, jsonHeaders())
        }
        awaitUntil("动画同步完成") { SubjectType.ANIME in vm.uiState.value.syncedTypes }
        assertEquals(ProgressPageShape.LOADING, vm.uiState.value.shapeOf(0))
        gate.complete(Unit)
        awaitUntil("全部类型同步完成") { ContentDomains.all { it in vm.uiState.value.syncedTypes } }
        awaitUntil("本轮结束") { !vm.uiState.value.isLoading }
        assertEquals(ProgressPageShape.EMPTY, vm.uiState.value.shapeOf(0))
    }

    @Test
    fun `全部页首类失败后仍等待其他类型`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val failureGate = CompletableDeferred<Unit>()
        val vm = makeViewModel(routed = ContentDomains.toSet()) { request ->
            when (request.url.parameters["subject_type"]) {
                SubjectType.ANIME.value.toString() -> {
                    failureGate.await()
                    respond("boom", HttpStatusCode.InternalServerError)
                }
                SubjectType.BOOK.value.toString() -> respond(emptyCollectionsJson, HttpStatusCode.OK, jsonHeaders())
                else -> {
                    gate.await()
                    respond(emptyCollectionsJson, HttpStatusCode.OK, jsonHeaders())
                }
            }
        }
        awaitUntil("书籍同步完成") { SubjectType.BOOK in vm.uiState.value.syncedTypes }
        failureGate.complete(Unit)
        awaitUntil("动画同步失败") { vm.uiState.value.syncError == SERVER_500 }
        assertEquals(ProgressPageShape.LOADING, vm.uiState.value.shapeOf(0))
        gate.complete(Unit)
        awaitUntil("本轮结束") { !vm.uiState.value.isLoading }
        assertEquals(ProgressPageShape.ERROR, vm.uiState.value.shapeOf(0))
    }

    @Test
    fun `全部页有条目时其余类型仍在同步也显示列表`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val vm = makeViewModel(routed = ContentDomains.toSet()) { request ->
            if (request.url.encodedPath.endsWith("/episodes")) {
                respond(episodesJson, HttpStatusCode.OK, jsonHeaders())
            } else if (request.url.parameters["subject_type"] == SubjectType.ANIME.value.toString()) {
                respond(collectionsJson(1), HttpStatusCode.OK, jsonHeaders())
            } else {
                gate.await()
                respond(emptyCollectionsJson, HttpStatusCode.OK, jsonHeaders())
            }
        }
        awaitUntil("动画条目显示") { vm.uiState.value.collections.isNotEmpty() }
        assertEquals(ProgressPageShape.LIST, vm.uiState.value.shapeOf(0))
        assertTrue(vm.uiState.value.isLoading)
        gate.complete(Unit)
        awaitUntil("全部类型同步结束") { !vm.uiState.value.isLoading }
    }

    @Test
    fun `切到单类型取消全部页请求后只等待新页`() = runBlocking {
        val allGate = CompletableDeferred<Unit>()
        val bookGate = CompletableDeferred<Unit>()
        val oldBookCancelled = CompletableDeferred<Unit>()
        val bookReads = AtomicInteger()
        val vm = makeViewModel(routed = ContentDomains.toSet()) { request ->
            val isBook = request.url.parameters["subject_type"] == SubjectType.BOOK.value.toString()
            if (isBook && bookReads.incrementAndGet() > 1) {
                bookGate.await()
            } else {
                try {
                    allGate.await()
                } finally {
                    if (isBook) oldBookCancelled.complete(Unit)
                }
            }
            respond(emptyCollectionsJson, HttpStatusCode.OK, jsonHeaders())
        }
        awaitUntil("全部页书籍请求已发出") { bookReads.get() == 1 }
        val book = PROGRESS_TYPES.indexOf(SubjectType.BOOK)
        onMain { vm.onSelectSubjectType(book) }
        awaitUntil("单类型请求已发出") { bookReads.get() == 2 }
        oldBookCancelled.await()
        allGate.complete(Unit)
        onMain { }
        assertEquals(ProgressPageShape.LOADING, vm.uiState.value.shapeOf(book))
        bookGate.complete(Unit)
        awaitUntil("单类型同步结束") { !vm.uiState.value.isLoading }
        assertEquals(ProgressPageShape.EMPTY, vm.uiState.value.shapeOf(book))
    }

    @Test
    fun `类型请求包装超时重试耗尽后显示失败`() = runBlocking {
        val attempts = AtomicInteger()
        val vm = makeViewModel { request ->
            attempts.incrementAndGet()
            throw CancellationException("timeout", HttpRequestTimeoutException(request.url.toString(), 15_000))
        }
        awaitUntil("超时类型重试耗尽") { attempts.get() == 3 }
        awaitUntil("全部类型同步结束") { !vm.uiState.value.isLoading }
        assertEquals(Failure.Timeout, vm.uiState.value.syncError)
        assertEquals(ProgressPageShape.ERROR, vm.uiState.value.shapeOf(0))
    }

    @Test
    fun `切换收藏状态取消旧请求后仍等待新状态`() = runBlocking {
        val oldGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        val oldCancelled = CompletableDeferred<Unit>()
        val oldStarted = AtomicInteger()
        val oldFinished = AtomicInteger()
        val newStarted = AtomicInteger()
        val vm = makeViewModel(routed = ContentDomains.toSet()) { request ->
            if (request.url.parameters["type"] == CollectionType.DOING.value.toString()) {
                oldStarted.incrementAndGet()
                try {
                    oldGate.await()
                } finally {
                    if (oldFinished.incrementAndGet() == ContentDomains.size) oldCancelled.complete(Unit)
                }
            } else {
                newStarted.incrementAndGet()
                newGate.await()
            }
            respond(emptyCollectionsJson, HttpStatusCode.OK, jsonHeaders())
        }
        awaitUntil("旧状态五类请求已发出") { oldStarted.get() == ContentDomains.size }
        onMain { vm.onSelectStatus(CollectionType.DONE) }
        oldCancelled.await()
        oldGate.complete(Unit)
        awaitUntil("新状态五类请求已发出") { newStarted.get() == ContentDomains.size }
        onMain { }
        assertEquals(ProgressPageShape.LOADING, vm.uiState.value.shapeOf(0))
        assertNull(vm.uiState.value.syncError)
        newGate.complete(Unit)
        awaitUntil("新状态同步结束") { !vm.uiState.value.isLoading }
        assertEquals(ProgressPageShape.EMPTY, vm.uiState.value.shapeOf(0))
    }

    @BeforeTest
    fun setUp() {
        main.install()
        dao = FakeUserCollectionDao()
        subjectDao = FakeSubjectDao()
        watchStateDao = FakeEpisodeWatchStateDao()
        episodeDao = FakeEpisodeDao()
    }

    @AfterTest
    fun tearDown() {
        main.uninstall(viewModels.map { it.viewModelScope } + scope)
        viewModels.clear()
        ioExecutor.shutdownNow()
        prefs.close()
    }

    private suspend fun onMain(block: () -> Unit) = withContext(main.dispatcher) { block() }

    private suspend fun reorder(vm: ProgressViewModel, block: () -> Unit) {
        val epoch = vm.uiState.value.selectedEpoch()
        onMain(block)
        awaitUntil("重排完成") { vm.uiState.value.selectedEpoch() > epoch }
    }

    private fun collectionsJson(
        vararg ids: Int,
        type: Int = 3,
        updatedAt: (Int) -> String = { id -> "2026-09-1${id}T10:00:00+08:00" },
    ) = """
        {"data":[${ids.joinToString(",") { id ->
            """{"subject_id":$id,"subject_type":2,"type":$type,"ep_status":1,"rate":0,"comment":"","tags":[],"vol_status":0,"private":false,
               "updated_at":"${updatedAt(id)}",
               "subject":{"id":$id,"type":2,"name":"作品$id","name_cn":"作品$id","short_summary":"",
                 "images":{"large":"l$id.jpg","common":"c$id.jpg","medium":"m$id.jpg","small":"s$id.jpg","grid":"g$id.jpg"},
                 "eps":12,"volumes":0,"collection_total":10,"score":8.0,"rank":0,"tags":[]}}"""
        }}],"total":${ids.size},"limit":50,"offset":0}
    """.trimIndent()

    /** 非动画类型的空列表响应（"全部"页签并行拉 4 个类型，测试数据只属于动画域） */
    private val emptyCollectionsJson = """{"data":[],"total":0,"limit":50,"offset":0}"""

    // Ktor 3.5 的 MockEngine 构造签名为 (suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData)
    // （与 core:data 的测试同构）。按 subject_type 路由——仅动画域返回 handler 数据，
    // 其余类型返回空列表；否则默认态聚合 4 类型时 count 为 4 倍（8 vs 期望 2）
    //
    // [routed]：收藏列表请求里哪些类型交给 handler 处理，其余类型一律返回空列表。
    // 默认只有动画；要让「全部」页签里两个类型各有各的成败，就把两个类型都列进来。
    private fun makeViewModel(
        routed: Set<SubjectType> = setOf(SubjectType.ANIME),
        computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
        now: MutableStateFlow<Long> = MutableStateFlow(System.currentTimeMillis()),
        preferences: PreferencesRepository = prefs.repository,
        waitForEntry: Boolean = true,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ProgressViewModel {
        engine = MockEngine { request ->
            recorder.record(request)
            if (request.url.encodedPath.endsWith("/episodes") ||
                routed.any { request.url.parameters["subject_type"] == it.value.toString() } ||
                request.method != HttpMethod.Get
            ) {
                handler(request)
            } else {
                respond(
                    content = emptyCollectionsJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
        }
        val client = resources.apiClient(engine)
        val tokenStore = testTokenStore(scope = scope).also { store ->
            runBlocking {
                val pending = store.beginSession("token")
                store.completeSession(pending, ACCOUNT_ID, "fixture_user")
            }
        }
        val collectionRepository = testCollections(
            resources,
            CollectionApi(client), subjectDao, dao, tokenStore, watchStateDao, episodeDao,
        )
        val subjectRepo = testSubjectRepository(
            resources,
            SubjectApi(client), io.github.touhougo.chogumi.core.network.api.NextApi(client), subjectDao, episodeDao, FakeSubjectSectionDao(), testNsfwFilter(resources),
            tokenStore = tokenStore,
        )
        val orchestrator = collectionRepository.progressOrchestrator(subjectRepo)
        val coordinator = collectionRepository.epTapCoordinator(orchestrator, scope, { 0L }, commitGate::await)
        return ProgressViewModel(
            collectionRepository.store, collectionRepository.observer, collectionRepository.watchStates, subjectRepo, coordinator, entryRequests,
            preferences, collectionRepository.editing(subjectRepo, orchestrator), testAppTime(now), computationDispatcher,
        ).also { vm ->
            viewModels += vm
            // 默认视图要先读设置：等第一次入口请求落定，用例里随后切 tab 才不会被它盖回去。
            if (waitForEntry) runBlocking { awaitUntil("进度页已按入口请求开好") { vm.uiState.value.opened } }
        }
    }

    @Test
    fun `收藏变化通过计算调度器重筛各页`() = runBlocking {
        val dispatched = AtomicInteger()
        val now = MutableStateFlow(1_700_000_000_000L)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatched.incrementAndGet()
                Dispatchers.Default.dispatch(context, block)
            }
        }
        val vm = makeViewModel(computationDispatcher = dispatcher, now = now) {
            respond(emptyCollectionsJson, HttpStatusCode.OK, jsonHeaders())
        }
        awaitUntil("初始同步完成") { !vm.uiState.value.isLoading }
        val before = dispatched.get()
        seedCollection(1, updatedAt = 300L, subjectType = SubjectType.BOOK)
        awaitUntil("收藏进入页面") { vm.uiState.value.pages[PROGRESS_TYPES.indexOf(SubjectType.BOOK)].any { it.subjectId == 1 } }
        assertTrue(dispatched.get() > before, "收藏变化应交给计算调度器")

        val beforeWatch = dispatched.get()
        watchStateDao.upsertAll(listOf(io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity(
            episodeId = 100, subjectId = 1, accountId = ACCOUNT_ID,
            status = io.github.touhougo.chogumi.core.model.EpisodeStatus.DONE.value, updatedAt = 500L,
        )))
        awaitUntil("逐集快照触发计算") { vm.uiState.value.latestWatchedAt[1] == 500L && dispatched.get() > beforeWatch }

        val beforeDate = dispatched.get()
        val previousDate = vm.uiState.value.today
        now.value += 86_400_000L
        awaitUntil("日期变化触发计算") { vm.uiState.value.today != previousDate && dispatched.get() > beforeDate }
    }

    @Test
    fun `计算在途时新筛选与收藏最终一起生效`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.asCoroutineDispatcher()
        val dispatched = AtomicInteger()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatched.incrementAndGet()
                worker.dispatch(context, block)
            }
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val vm = makeViewModel(computationDispatcher = dispatcher) {
                respond(emptyCollectionsJson, HttpStatusCode.OK, jsonHeaders())
            }
            awaitUntil("初始同步完成") { !vm.uiState.value.isLoading && vm.uiState.value.pageEpochs.all { it > 0 } }
            executor.execute { entered.countDown(); release.await() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val before = dispatched.get()
            seedCollection(1, updatedAt = 300L, title = "猫", subjectType = SubjectType.BOOK)
            seedCollection(2, updatedAt = 200L, title = "狗", subjectType = SubjectType.GAME)
            awaitUntil("收藏观察触发计算") { dispatched.get() > before }
            val epochs = vm.uiState.value.pageEpochs
            onMain {
                vm.onQueryChange("猫")
                vm.onSelectSort(ProgressSort.TITLE)
                vm.onSelectSort(ProgressSort.RATING)
            }
            release.countDown()
            awaitUntil("最新筛选生效") { vm.uiState.value.pageEpochs == epochs.map { it + 2 } }
            assertEquals(listOf(1), vm.uiState.value.pages[0].map { it.subjectId })
            assertEquals(PROGRESS_TYPES.map { if (it == null || it == SubjectType.BOOK) listOf(1) else emptyList() },
                vm.uiState.value.pages.map { page -> page.map { it.subjectId } })
        } finally {
            release.countDown()
            worker.close()
        }
    }

    @Test
    fun `五种类型与全部页按相同条件各自排序`() = runBlocking {
        val vm = makeViewModel { respond(emptyCollectionsJson, HttpStatusCode.OK, jsonHeaders()) }
        awaitUntil("初始同步完成") { !vm.uiState.value.isLoading }
        val types = listOf(SubjectType.ANIME, SubjectType.BOOK, SubjectType.GAME, SubjectType.MUSIC, SubjectType.REAL)
        types.forEachIndexed { index, type ->
            seedCollection(index * 2 + 1, updatedAt = 10L + index, subjectType = type)
            seedCollection(index * 2 + 2, updatedAt = 20L + index, subjectType = type)
        }
        awaitUntil("五类收藏到达") { vm.uiState.value.pages[0].size == 10 }
        reorder(vm) { vm.onSelectSort(ProgressSort.DATE) }
        val expected = PROGRESS_TYPES.map { type ->
            if (type == null) (1..10).toList() else {
                val first = types.indexOf(type) * 2 + 1
                listOf(first, first + 1)
            }
        }
        assertEquals(expected, vm.uiState.value.pages.map { page -> page.map { it.subjectId } })
    }

    @Test
    fun `业务日变化后最近更新排序使用新的播出日`() = runBlocking {
        val now = MutableStateFlow(java.time.Instant.parse("2026-01-01T04:00:00Z").toEpochMilli())
        for (id in 1..2) {
            episodeDao.replaceAll(id, listOf(io.github.touhougo.chogumi.core.data.db.subject.EpisodeEntity(
                id = 100 + id, subjectId = id, type = 0, name = "EP", nameCn = "", sort = 1.0,
                airdate = "2026-01-0$id", updatedAt = System.currentTimeMillis(),
            )), fetchedAt = System.currentTimeMillis())
        }
        val vm = makeViewModel(now = now) { request ->
            if (request.url.encodedPath == collectionsPath) respond(collectionsJson(1, 2), HttpStatusCode.OK, jsonHeaders())
            else respond("{}", HttpStatusCode.NotFound)
        }
        awaitUntil("两部章节到达") { vm.uiState.value.episodesMap.keys.containsAll(listOf(1, 2)) }
        reorder(vm) { vm.onSelectSort(ProgressSort.LATEST_AIRED) }
        assertEquals(listOf(1, 2), vm.uiState.value.collections.map { it.subjectId })
        now.value += 86_400_000L
        awaitUntil("业务日更新") { vm.uiState.value.today == java.time.LocalDate.parse("2026-01-02") }
        reorder(vm) { vm.onSelectSort(ProgressSort.DATE) }
        reorder(vm) { vm.onSelectSort(ProgressSort.LATEST_AIRED) }
        assertEquals(listOf(2, 1), vm.uiState.value.collections.map { it.subjectId })
    }

    @Test
    fun `切收藏状态筛选在途时加载失败 按新页面是否有内容呈现`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.asCoroutineDispatcher()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fail = AtomicBoolean(false)
        try {
            val vm = makeViewModel(computationDispatcher = worker) {
                if (fail.get()) respond("boom", HttpStatusCode.InternalServerError)
                else respond(collectionsJson(1), HttpStatusCode.OK, jsonHeaders())
            }
            awaitUntil("旧状态列表加载完") { !vm.uiState.value.isLoading && vm.uiState.value.collections.size == 1 }
            executor.execute { entered.countDown(); release.await() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            fail.set(true)
            onMain { vm.onSelectStatus(CollectionType.WISH) }
            awaitUntil("新状态加载失败") { SubjectType.ANIME in vm.uiState.value.syncErrors }
            assertTrue(vm.uiState.value.collections.isNotEmpty(), "筛选仍被阻塞")
            release.countDown()
            awaitUntil("新状态筛选与加载结束") {
                !vm.uiState.value.isLoading && vm.uiState.value.collections.isEmpty()
            }
            assertEquals(ProgressPageShape.ERROR, vm.uiState.value.shapeOf(vm.uiState.value.selectedSubjectType))
            assertNull(vm.failures.current.value, "空页面显示重试块，不按旧列表另发提示")
        } finally {
            release.countDown()
            worker.close()
        }
    }

    @Test
    fun `新状态有缓存但筛选在途时加载失败 仍提示失败`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.asCoroutineDispatcher()
        val dispatched = AtomicInteger()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatched.incrementAndGet()
                worker.dispatch(context, block)
            }
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fail = AtomicBoolean(false)
        try {
            val vm = makeViewModel(computationDispatcher = dispatcher) {
                if (fail.get()) respond("boom", HttpStatusCode.InternalServerError)
                else respond(emptyCollectionsJson, HttpStatusCode.OK, jsonHeaders())
            }
            awaitUntil("初始同步完成") { !vm.uiState.value.isLoading }
            val before = dispatched.get()
            seedCollection(1, updatedAt = 300L, status = CollectionType.WISH, subjectType = SubjectType.ANIME)
            awaitUntil("收藏已进入筛选") { dispatched.get() > before }
            executor.execute { entered.countDown(); release.await() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            fail.set(true)
            onMain { vm.onSelectStatus(CollectionType.WISH) }
            awaitUntil("新状态加载失败") { SubjectType.ANIME in vm.uiState.value.syncErrors }
            assertTrue(vm.uiState.value.collections.isEmpty(), "筛选仍被阻塞")
            release.countDown()
            awaitUntil("有缓存时提示失败") { vm.failures.current.value != null }
            assertEquals(listOf(1), vm.uiState.value.collections.map { it.subjectId })
            assertEquals(ProgressPageShape.LIST, vm.uiState.value.shapeOf(vm.uiState.value.selectedSubjectType))
        } finally {
            release.countDown()
            worker.close()
        }
    }

    @Test
    fun `默认分类读取失败可重试且后续入口仍可用`() = runBlocking {
        val failRead = AtomicBoolean(true)
        val file = File(resources.directory("progress-fault"), "preferences.preferences_pb")
        val preferences = PreferencesRepository(PreferenceDataStoreFactory.create(scope = resources.scope) {
            if (failRead.get()) throw IOException("injected disk failure")
            file
        })
        val vm = makeViewModel(preferences = preferences, waitForEntry = false) {
            respond(emptyCollectionsJson, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        awaitUntil("显示存储失败") { vm.failures.current.value != null }
        val notice = assertNotNull(vm.failures.current.value)
        assertEquals(NoticeText.Reason(Failure.Storage), notice.text)
        assertTrue(!vm.uiState.value.opened)
        failRead.set(false)
        onMain { assertNotNull(notice.retry).invoke() }
        awaitUntil("重试后打开") { vm.uiState.value.opened }
        onMain { entryRequests.open(SubjectType.BOOK) }
        awaitUntil("后续入口仍被处理") { vm.uiState.value.selectedSubjectType == PROGRESS_TYPES.indexOf(SubjectType.BOOK) }
    }

    @Test
    fun `默认入口失败后新入口可打开且旧重试不覆盖`() = runBlocking {
        val failRead = AtomicBoolean(true)
        val file = File(resources.directory("progress-stale"), "preferences.preferences_pb")
        val preferences = PreferencesRepository(PreferenceDataStoreFactory.create(scope = resources.scope) {
            if (failRead.get()) throw IOException("injected disk failure")
            file
        })
        val vm = makeViewModel(preferences = preferences, waitForEntry = false) {
            respond(emptyCollectionsJson, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        awaitUntil("显示存储失败") { vm.failures.current.value != null }
        val retry = assertNotNull(assertNotNull(vm.failures.current.value).retry)
        onMain { entryRequests.open(SubjectType.BOOK) }
        awaitUntil("新入口打开") { vm.uiState.value.opened && vm.uiState.value.selectedSubjectType == PROGRESS_TYPES.indexOf(SubjectType.BOOK) }
        failRead.set(false)
        onMain { retry() }
        onMain { }
        assertEquals(PROGRESS_TYPES.indexOf(SubjectType.BOOK), vm.uiState.value.selectedSubjectType)
    }

    @Test
    fun `重试中的默认分类不能覆盖页内选择与搜索`() = runBlocking {
        val real = PreferenceDataStoreFactory.create(scope = resources.scope) {
            File(resources.directory("progress-delayed"), "preferences.preferences_pb")
        }
        PreferencesRepository(real).setProgressDefaultType(SubjectType.ANIME)
        val reads = AtomicInteger()
        val retryJob = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        val dataStore = object : DataStore<Preferences> by real {
            override val data: Flow<Preferences> = flow {
                if (reads.incrementAndGet() == 1) throw IOException("injected disk failure")
                retryJob.complete(currentCoroutineContext()[Job]!!)
                release.await()
                emitAll(real.data)
            }
        }
        val vm = makeViewModel(preferences = PreferencesRepository(dataStore), waitForEntry = false) {
            respond(emptyCollectionsJson, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        awaitUntil("显示存储失败") { vm.failures.current.value != null }
        onMain { assertNotNull(assertNotNull(vm.failures.current.value).retry).invoke() }
        val job = retryJob.await()
        onMain {
            vm.onSelectSubjectType(PROGRESS_TYPES.indexOf(SubjectType.BOOK))
            vm.onQueryChange("needle")
        }
        awaitUntil("书籍请求发出") { recorder.any { it.url.parameters["subject_type"] == SubjectType.BOOK.value.toString() } }
        release.complete(Unit)
        job.join()
        assertTrue(vm.uiState.value.opened)
        assertEquals(PROGRESS_TYPES.indexOf(SubjectType.BOOK), vm.uiState.value.selectedSubjectType)
        assertEquals("needle", vm.uiState.value.query)
        assertTrue(recorder.none { it.url.parameters["subject_type"] == SubjectType.ANIME.value.toString() })
    }

    @Test
    fun `初始默认读取等待时搜索启动当前页面`() = runBlocking {
        val real = PreferenceDataStoreFactory.create(scope = resources.scope) {
            File(resources.directory("progress-initial-delay"), "preferences.preferences_pb")
        }
        PreferencesRepository(real).setProgressDefaultType(SubjectType.ANIME)
        val started = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        val dataStore = object : DataStore<Preferences> by real {
            override val data: Flow<Preferences> = flow {
                started.complete(currentCoroutineContext()[Job]!!)
                release.await()
                emitAll(real.data)
            }
        }
        val vm = makeViewModel(preferences = PreferencesRepository(dataStore), waitForEntry = false) {
            respond(emptyCollectionsJson, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val job = started.await()
        onMain { vm.onQueryChange("needle") }
        awaitUntil("当前页面开始加载") { recorder.isNotEmpty() }
        release.complete(Unit)
        job.join()
        assertTrue(vm.uiState.value.opened)
        assertEquals("needle", vm.uiState.value.query)
        assertEquals(0, vm.uiState.value.selectedSubjectType)
    }

    @Test
    fun `外部新入口取消等待中的默认读取`() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val real = PreferenceDataStoreFactory.create(scope = resources.scope) {
            File(resources.directory("progress-cancel"), "preferences.preferences_pb")
        }
        val dataStore = object : DataStore<Preferences> by real {
            override val data: Flow<Preferences> = flow {
                started.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                    emitAll(real.data)
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        val vm = makeViewModel(preferences = PreferencesRepository(dataStore), waitForEntry = false) {
            respond(emptyCollectionsJson, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        started.await()
        onMain { entryRequests.open(SubjectType.BOOK) }
        cancelled.await()
        awaitUntil("新入口打开") { vm.uiState.value.opened && vm.uiState.value.selectedSubjectType == PROGRESS_TYPES.indexOf(SubjectType.BOOK) }
        assertNull(vm.failures.current.value)
    }

    @Test
    fun `默认读取等待时选择当前全部页仍以用户选择为准`() = runBlocking {
        val real = PreferenceDataStoreFactory.create(scope = resources.scope) {
            File(resources.directory("progress-select-all"), "preferences.preferences_pb")
        }
        PreferencesRepository(real).setProgressDefaultType(SubjectType.ANIME)
        val started = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        val dataStore = object : DataStore<Preferences> by real {
            override val data: Flow<Preferences> = flow {
                started.complete(currentCoroutineContext()[Job]!!)
                release.await()
                emitAll(real.data)
            }
        }
        val vm = makeViewModel(preferences = PreferencesRepository(dataStore), waitForEntry = false) {
            respond(emptyCollectionsJson, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val job = started.await()
        onMain { vm.onSelectSubjectType(0) }
        release.complete(Unit)
        job.join()
        assertTrue(vm.uiState.value.opened)
        assertEquals(0, vm.uiState.value.selectedSubjectType)
    }

    @Test
    fun `重复重试只打开一次默认入口`() = runBlocking {
        val real = PreferenceDataStoreFactory.create(scope = resources.scope) {
            File(resources.directory("progress-retry-twice"), "preferences.preferences_pb")
        }
        PreferencesRepository(real).setProgressDefaultType(SubjectType.ANIME)
        val reads = AtomicInteger()
        val jobs = java.util.concurrent.ConcurrentLinkedQueue<Job>()
        val release = CompletableDeferred<Unit>()
        val dataStore = object : DataStore<Preferences> by real {
            override val data: Flow<Preferences> = flow {
                if (reads.incrementAndGet() == 1) throw IOException("injected disk failure")
                jobs.add(currentCoroutineContext()[Job]!!)
                release.await()
                emitAll(real.data)
            }
        }
        val vm = makeViewModel(preferences = PreferencesRepository(dataStore), waitForEntry = false) {
            respond(emptyCollectionsJson, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        awaitUntil("显示存储失败") { vm.failures.current.value != null }
        val retry = assertNotNull(assertNotNull(vm.failures.current.value).retry)
        onMain { retry(); retry() }
        awaitUntil("两次重试都开始") { jobs.size == 2 }
        release.complete(Unit)
        jobs.toList().forEach { it.join() }
        awaitUntil("动画请求发出") { recorder.any { it.url.parameters["subject_type"] == SubjectType.ANIME.value.toString() } }
        assertTrue(vm.uiState.value.opened)
        assertEquals(PROGRESS_TYPES.indexOf(SubjectType.ANIME), vm.uiState.value.selectedSubjectType)
        assertEquals(1, recorder.count { it.url.parameters["subject_type"] == SubjectType.ANIME.value.toString() })
    }

    /** 直接种一条带 metaTags 的条目（分类筛选的数据来源是 subjects 表，不在收藏响应里）。suspend：调用方已在 runBlocking 内。 */
    private suspend fun seedCollection(
        id: Int,
        updatedAt: Long,
        title: String = "作品$id",
        metaTags: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        status: CollectionType = CollectionType.DOING,
        subjectType: SubjectType = SubjectType.ANIME,
    ) {
        subjectDao.upsert(
            SubjectEntity(
                id = id, type = subjectType.value, name = title, nameCn = title,
                ratingScore = 8.0, metaTagsJson = metaTags.joinToString(",", "[", "]") { "\"$it\"" },
                updatedAt = System.currentTimeMillis(),
            ),
        )
        dao.upsert(
            UserCollectionEntity(
                subjectId = id, subjectType = subjectType.value,
                type = status.value, epStatus = 1, updatedAt = updatedAt,
                tagsJson = tags.joinToString(",", "[", "]") { "\"$it\"" }, accountId = ACCOUNT_ID,
            ),
        )
    }

    @Test
    fun `默认排序为最近操作 方向为顺序`() = runBlocking {
        val vm = makeViewModel { respond(collectionsJson(1, 2), HttpStatusCode.OK, jsonHeaders()) }
        awaitUntil("在看列表加载完成") { vm.uiState.value.collections.size == 2 }
        assertEquals(ProgressOrder(ProgressSort.RECENT, reversed = false), vm.uiState.value.order)
    }

    @Test
    fun `本地收藏观察失败发一次 Storage 提示且 ViewModel 继续运行`() = runBlocking {
        dao = object : FakeUserCollectionDao() {
            override fun observeCurrent(accountId: Int): Flow<List<UserCollectionEntity>> =
                flow { throw IOException("collection database unavailable") }
        }
        val vm = makeViewModel { respond(collectionsJson(1), HttpStatusCode.OK, jsonHeaders()) }

        awaitUntil("收藏观察失败提示到达") { vm.failures.current.value != null }

        val notice = vm.failures.current.value!!
        assertEquals(Failure.Storage, (notice.text as NoticeText.Reason).failure)
        assertTrue(vm.uiState.value.opened, "观察失败不应结束 ViewModel")
    }

    @Test
    fun `组合观察失败的重试重新订阅收藏与逐集`() = runBlocking {
        val broken = AtomicBoolean(true)
        val collectionReads = AtomicInteger()
        val episodeReads = AtomicInteger()
        dao = object : FakeUserCollectionDao() {
            override fun observeCurrent(accountId: Int): Flow<List<UserCollectionEntity>> {
                collectionReads.incrementAndGet()
                return if (broken.get()) flow { throw IOException("unavailable") } else super.observeCurrent(accountId)
            }
        }
        watchStateDao = object : FakeEpisodeWatchStateDao() {
            override fun observeAll(accountId: Int): Flow<List<EpisodeWatchStateEntity>> {
                episodeReads.incrementAndGet()
                return flowOf(emptyList())
            }
        }
        val vm = makeViewModel { respond(collectionsJson(1), HttpStatusCode.OK, jsonHeaders()) }
        awaitUntil("失败提示到达") { vm.failures.current.value != null && episodeReads.get() > 0 }
        val notice = vm.failures.current.value!!
        assertEquals(NoticeText.Reason(Failure.Storage), notice.text)
        val beforeCollection = collectionReads.get()
        val beforeEpisode = episodeReads.get()

        broken.set(false)
        vm.failures.consume(notice)
        onMain { notice.retry!!.invoke() }
        awaitUntil("两路重新订阅") { collectionReads.get() > beforeCollection && episodeReads.get() > beforeEpisode }
        assertNull(vm.failures.current.value)
    }

    /** 排序菜单：上一组换档并回到这一档的默认方向，点正在用的档不做事；方向在下一组里直接选。 */
    @Test
    fun `排序换档回到默认方向 方向在第二组里直接选`() = runBlocking {
        val vm = makeViewModel { respond(collectionsJson(1, 2), HttpStatusCode.OK, jsonHeaders()) }
        awaitUntil("在看列表加载完成") { vm.uiState.value.collections.size == 2 }

        onMain { vm.onSelectSort(ProgressSort.TITLE) }
        assertEquals(ProgressOrder(ProgressSort.TITLE, reversed = false), vm.uiState.value.order, "换到新档位用它的默认方向")

        onMain { vm.onSelectDirection(reversed = true) }
        assertEquals(ProgressOrder(ProgressSort.TITLE, reversed = true), vm.uiState.value.order)

        onMain { vm.onSelectSort(ProgressSort.TITLE) }
        assertEquals(ProgressOrder(ProgressSort.TITLE, reversed = true), vm.uiState.value.order, "点正在用的档不反转方向")

        onMain { vm.onSelectSort(ProgressSort.RATING) }
        assertEquals(ProgressOrder(ProgressSort.RATING, reversed = false), vm.uiState.value.order, "换档回到默认方向")
    }

    /** 书籍没有「最近更新」：那一页按最近操作排、菜单也显示最近操作；用户的选择留着，翻回动画页照旧。 */
    @Test
    fun `没有所选排序档的页按最近操作排 翻回去照旧`() = runBlocking {
        val vm = makeViewModel { respond(collectionsJson(1, 2), HttpStatusCode.OK, jsonHeaders()) }
        vm.awaitAnimeTab()
        onMain { vm.onSelectSort(ProgressSort.LATEST_AIRED) }
        assertEquals(ProgressOrder(ProgressSort.LATEST_AIRED), vm.uiState.value.pageOrder)

        onMain { vm.onSelectSubjectType(PROGRESS_TYPES.indexOf(SubjectType.BOOK)) }
        assertEquals(ProgressOrder(), vm.uiState.value.pageOrder)
        assertEquals(ProgressSort.LATEST_AIRED, vm.uiState.value.order.sort, "用户的选择不因翻页丢掉")

        onMain { vm.onSelectSubjectType(PROGRESS_TYPES.indexOf(SubjectType.ANIME)) }
        assertEquals(ProgressOrder(ProgressSort.LATEST_AIRED), vm.uiState.value.pageOrder)
    }

    @Test
    fun `刚标记一集的作品在最近操作下排到最前 即使收藏时间较旧`() = runBlocking {
        // 作品 1 收藏时间最新、没有任何逐集记录；作品 2 收藏时间旧，但第 100 集刚看过。
        // 收藏时间只来自服务端这一份：若再往本地预种一份不同的时间，列表首拍拿到的是预种还是同步结果
        // 取决于调度先后，首个断言就成了掷硬币。
        val collectedAt = mapOf(1 to "2026-09-20T10:00:00+08:00", 2 to "2026-09-01T10:00:00+08:00")
        val watchedAt = java.time.OffsetDateTime.parse("2026-09-25T10:00:00+08:00").toInstant().toEpochMilli()
        val vm = makeViewModel { request ->
            when (request.url.encodedPath) {
                "/v0/users/fixture_user/collections" -> respond(
                    if (request.url.parameters["subject_type"] == SubjectType.ANIME.value.toString()) {
                        collectionsJson(1, 2) { id -> collectedAt.getValue(id) }
                    } else {
                        emptyCollectionsJson
                    },
                    HttpStatusCode.OK, jsonHeaders(),
                )
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        awaitUntil("列表加载") { vm.uiState.value.collections.size == 2 }
        // 直接向逐集表写入「作品 2 刚看过第 100 集」，快照的 latestWatchedAt 随之更新。
        watchStateDao.upsertAll(
            listOf(
                io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity(
                    episodeId = 100, subjectId = 2, accountId = ACCOUNT_ID,
                    status = io.github.touhougo.chogumi.core.model.EpisodeStatus.DONE.value, updatedAt = watchedAt,
                ),
            ),
        )
        // 先等快照到达（否则 refresh 用的是尚未更新的 latestWatchedAt）——
        // 快照到达本身不重排（stableOrder 保持原位），故此时列表仍是原顺序。
        awaitUntil("逐集快照到达") { vm.uiState.value.latestWatchedAt[2] == watchedAt }
        assertEquals(listOf(1, 2), vm.uiState.value.collections.map { it.subjectId }, "快照到达不重排")
        // 强制重排后，「最近操作」按最后看过时刻把作品 2 排到最前。
        reorder(vm) { vm.refresh() }
        assertEquals(listOf(2, 1), vm.uiState.value.collections.map { it.subjectId })
    }





    private fun jsonHeaders() = headersOf("Content-Type", ContentType.Application.Json.toString())

    /** 单个 ANIME/DOING 条目：+1 回滚测试的最小数据集。 */
    private val singleDoingJson = """
        {"data":[
            {"subject_id":1,"subject_type":2,"type":3,"ep_status":2,"rate":0,"comment":"","tags":[],"vol_status":0,"private":false,
             "updated_at":"2026-09-15T10:00:00+08:00",
             "subject":{"id":1,"type":2,"name":"作品1","name_cn":"作品1","short_summary":"","eps":12,"volumes":0,
               "images":{"large":"l1.jpg","common":"c1.jpg","medium":"m1.jpg","small":"s1.jpg","grid":"g1.jpg"},
               "collection_total":10,"score":8.0,"rank":0,"tags":[]}}
        ],"total":1,"limit":1,"offset":0}
    """.trimIndent()

    private val episodesJson = """{"data":[{"id":101,"subject_id":1,"type":0,"sort":1.0,"name":"ep1","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"airdate":""},{"id":102,"subject_id":1,"type":0,"sort":2.0,"name":"ep2","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"airdate":""},{"id":103,"subject_id":1,"type":0,"sort":3.0,"name":"ep3","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"airdate":""}],"total":3,"limit":100,"offset":0}"""

    /**
     * 用户逐集端点的响应：与 [episodesBody]（`/v0/episodes`）是同一份章节——服务端两条路读的是同一张章节表——
     * 逐集带上状态。v0 对未标过的集也显式返回 type = 0：第 1 集（101）看过，其余未看。
     */
    private fun userEpisodesJson(episodesBody: String, watchedIds: Set<Int> = setOf(101)): String {
        val rows = Json.parseToJsonElement(episodesBody).jsonObject.getValue("data").jsonArray.map { episode ->
            val watched = episode.jsonObject.getValue("id").jsonPrimitive.int in watchedIds
            """{"episode":$episode,"type":${if (watched) 2 else 0},"updated_at":1000}"""
        }
        return """{"total":${rows.size},"limit":100,"offset":0,"data":[${rows.joinToString(",")}]}"""
    }

    private val statesJson = userEpisodesJson(episodesJson)

    private fun singleAnimeViewModel(failWrites: Boolean, episodesBody: String = episodesJson, episodesStatus: HttpStatusCode = HttpStatusCode.OK): ProgressViewModel {
        // 只保留一个内容域有数据，避免 5 个域并发 replaceSnapshot 互相清分区
        runBlocking {
            dao.upsert(
                UserCollectionEntity(
                    subjectId = 1, subjectType = SubjectType.ANIME.value,
                    type = CollectionType.DOING.value, epStatus = 1, accountId = ACCOUNT_ID,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        return makeViewModel { request ->
            when (request.url.encodedPath) {
                "/v0/users/fixture_user/collections" -> respond(
                    if (request.url.parameters["subject_type"] == SubjectType.ANIME.value.toString()) singleDoingJson else emptyCollectionsJson,
                    HttpStatusCode.OK, jsonHeaders(),
                )
                "/v0/episodes" -> respond(episodesBody, episodesStatus, jsonHeaders())
                "/v0/users/-/collections/1/episodes" -> when {
                    // 章节取不到时这条路也取不到：两条路读的是同一张章节表
                    request.method == HttpMethod.Get -> if (episodesStatus == HttpStatusCode.OK) {
                        respond(userEpisodesJson(episodesBody), HttpStatusCode.OK, jsonHeaders())
                    } else {
                        respond(episodesBody, episodesStatus, jsonHeaders())
                    }
                    failWrites -> respond("boom", HttpStatusCode.InternalServerError)
                    else -> respond("", HttpStatusCode.NoContent)
                }
                else -> respond(content = "{}", status = HttpStatusCode.NotFound)
            }
        }
    }

    private suspend fun ProgressViewModel.awaitItem(id: Int) = run {
        awaitUntil("条目 $id 出现在列表") { uiState.value.collections.any { it.subjectId == id } }
        uiState.value.collections.first { it.subjectId == id }
    }

    @Test
    fun `列表出来即预取章节与逐集状态 不等条目滑进屏幕 下一集正确`() = runBlocking {
        val vm = singleAnimeViewModel(failWrites = false)
        val item = vm.awaitItem(1)
        // 没有任何「可见」回调：只靠列表加载触发的预取
        awaitUntil("预取章节与逐集状态") { vm.epButtonState(item, vm.uiState.value) is EpButtonState.Next }
        // 第 1 集已看：若逐集状态没到就算，下一集会错成第 1 集（101）
        assertEquals(102, (vm.epButtonState(item, vm.uiState.value) as EpButtonState.Next).episode.id)
        assertEquals(ProgressCount.Known(null, 1, 3), vm.progressCount(item, vm.uiState.value))
    }

    @Test
    fun `下拉刷新重取已成功加载的逐集状态 更新计数与下一集锚点`() = runBlocking {
        val episodeGets = AtomicInteger()
        val vm = makeViewModel { request ->
            when (request.url.encodedPath) {
                collectionsPath -> respond(singleDoingJson, HttpStatusCode.OK, jsonHeaders())
                "/v0/users/-/collections/1/episodes" -> {
                    val watched = if (episodeGets.getAndIncrement() == 0) setOf(101) else setOf(101, 102, 103)
                    respond(userEpisodesJson(episodesJson, watched), HttpStatusCode.OK, jsonHeaders())
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val item = vm.awaitItem(1)
        awaitUntil("首轮逐集状态到位") {
            vm.progressCount(item, vm.uiState.value) == ProgressCount.Known(null, 1, 3)
        }
        assertEquals(1, episodeGets.get())

        onMain { vm.refresh() }

        awaitUntil("刷新后服务端第三集也标为看过") {
            vm.progressCount(item, vm.uiState.value) == ProgressCount.Known(null, 3, 3) &&
                vm.epButtonState(item, vm.uiState.value) == EpButtonState.Finished
        }
        assertEquals(2, episodeGets.get(), "显式刷新必须重新请求已成功加载过的逐集状态")
    }

    @Test
    fun `逐集请求在途时下拉刷新 本轮结束后仍重取一次`() = runBlocking {
        val episodeGets = AtomicInteger()
        val firstRequest = CompletableDeferred<Unit>()
        val vm = makeViewModel { request ->
            when (request.url.encodedPath) {
                collectionsPath -> respond(singleDoingJson, HttpStatusCode.OK, jsonHeaders())
                "/v0/users/-/collections/1/episodes" -> {
                    val call = episodeGets.getAndIncrement()
                    if (call == 0) firstRequest.await()
                    val watched = if (call == 0) setOf(101) else setOf(101, 102, 103)
                    respond(userEpisodesJson(episodesJson, watched), HttpStatusCode.OK, jsonHeaders())
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val item = vm.awaitItem(1)
        awaitUntil("首轮逐集请求已在途") { episodeGets.get() == 1 }

        onMain { vm.refresh() }
        firstRequest.complete(Unit)

        awaitUntil("在途首轮结束后，刷新仍请求最新逐集状态") {
            vm.progressCount(item, vm.uiState.value) == ProgressCount.Known(null, 3, 3) &&
                vm.epButtonState(item, vm.uiState.value) == EpButtonState.Finished
        }
        assertEquals(2, episodeGets.get(), "在途去重不能吞掉随后的一次显式刷新")
    }

    @Test
    fun `下拉刷新逐集失败 保留旧内容并提示 重试后更新`() = runBlocking {
        val episodeGets = AtomicInteger()
        val vm = makeViewModel { request ->
            when (request.url.encodedPath) {
                collectionsPath -> respond(singleDoingJson, HttpStatusCode.OK, jsonHeaders())
                "/v0/users/-/collections/1/episodes" -> when (episodeGets.getAndIncrement()) {
                    0 -> respond(userEpisodesJson(episodesJson, setOf(101)), HttpStatusCode.OK, jsonHeaders())
                    1 -> respond("boom", HttpStatusCode.InternalServerError)
                    else -> respond(userEpisodesJson(episodesJson, setOf(101, 102, 103)), HttpStatusCode.OK, jsonHeaders())
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val item = vm.awaitItem(1)
        awaitUntil("首轮逐集状态到位") {
            vm.progressCount(item, vm.uiState.value) == ProgressCount.Known(null, 1, 3)
        }

        onMain { vm.refresh() }

        awaitUntil("刷新失败给出提示") { vm.failures.current.value != null }
        assertEquals(2, episodeGets.get(), "失败也必须先真正重取逐集状态")
        assertTrue(vm.uiState.value.collections.any { it.subjectId == 1 }, "列表保留")
        assertEquals(listOf(101, 102, 103), vm.uiState.value.episodesMap.getValue(1).map { it.id }, "章节保留")
        assertEquals(ProgressCount.Known(null, 1, 3), vm.progressCount(item, vm.uiState.value), "旧逐集内容保留")
        val notice = assertNotNull(vm.failures.current.value)
        assertEquals(NoticeText.Reason(SERVER_500), notice.text)
        val retry = assertNotNull(notice.retry)

        onMain { retry() }

        awaitUntil("重试成功后刷新到最新状态") {
            vm.progressCount(item, vm.uiState.value) == ProgressCount.Known(null, 3, 3) &&
                vm.epButtonState(item, vm.uiState.value) == EpButtonState.Finished &&
                item.subjectId !in vm.uiState.value.episodesFailed
        }
        assertEquals(3, episodeGets.get())
    }

    @Test
    fun `零集刷新失败保留已知空状态 重试后更新`() = runBlocking {
        val episodeGets = AtomicInteger()
        val emptyEpisodes = """{"data":[],"total":0,"limit":100,"offset":0}"""
        val vm = makeViewModel { request ->
            when (request.url.encodedPath) {
                collectionsPath -> respond(singleDoingJson, HttpStatusCode.OK, jsonHeaders())
                "/v0/users/-/collections/1/episodes" -> when (episodeGets.getAndIncrement()) {
                    0 -> respond(userEpisodesJson(emptyEpisodes), HttpStatusCode.OK, jsonHeaders())
                    1 -> respond("boom", HttpStatusCode.InternalServerError)
                    else -> respond(userEpisodesJson(episodesJson), HttpStatusCode.OK, jsonHeaders())
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val item = vm.awaitItem(1)
        awaitUntil("零集已知") { vm.progressCount(item, vm.uiState.value) == ProgressCount.Known(null, 0, 0) }

        onMain { vm.refresh() }
        awaitUntil("刷新失败给出提示") { vm.failures.current.value != null }
        assertEquals(2, episodeGets.get())
        assertTrue(1 in vm.uiState.value.emptyWatchStates)
        assertEquals(ProgressCount.Known(null, 0, 0), vm.progressCount(item, vm.uiState.value))
        val retry = assertNotNull(assertNotNull(vm.failures.current.value).retry)
        onMain { retry() }
        awaitUntil("重试取回章节和状态") {
            val state = vm.uiState.value
            vm.progressCount(item, state) == ProgressCount.Known(null, 1, 3) &&
                1 !in state.emptyWatchStates && 1 !in state.episodesFailed
        }
        assertEquals(3, episodeGets.get())
        assertTrue(1 !in vm.uiState.value.emptyWatchStates)
    }

    @Test
    fun `筛选变化不重取已成功加载的逐集状态`() = runBlocking {
        val episodeGets = AtomicInteger()
        val emptyEpisodes = """{"data":[],"total":0,"limit":100,"offset":0}"""
        val vm = makeViewModel { request ->
            when (request.url.encodedPath) {
                collectionsPath -> respond(singleDoingJson, HttpStatusCode.OK, jsonHeaders())
                "/v0/users/-/collections/1/episodes" -> {
                    if (episodeGets.incrementAndGet() == 2) {
                        respond("boom", HttpStatusCode.InternalServerError)
                    } else {
                        respond(userEpisodesJson(emptyEpisodes), HttpStatusCode.OK, jsonHeaders())
                    }
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val item = vm.awaitItem(1)
        awaitUntil("首轮逐集状态到位") {
            vm.progressCount(item, vm.uiState.value) == ProgressCount.Known(null, 0, 0)
        }
        assertEquals(1, episodeGets.get())

        // 显式刷新失败的提示在逐集请求完成后发出；同一 Main 协程随即清除在途标记。
        onMain { vm.refresh() }
        awaitUntil("刷新失败提示到位") { vm.failures.current.value != null }
        onMain { }
        assertEquals(2, episodeGets.get())
        var filterJobs = emptyList<Job>()
        onMain {
            val previous = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
            vm.onQueryChange("不存在的关键词")
            vm.onQueryChange("作品1")
            filterJobs = vm.viewModelScope.coroutineContext[Job]!!.children.filterNot { it in previous }.toList()
        }
        filterJobs.joinAll()

        assertEquals(2, episodeGets.get(), "普通筛选只复用已加载状态")
    }

    @Test
    fun `显式刷新覆盖被筛选隐藏的在看条目`() = runBlocking {
        val episodeGets = AtomicInteger()
        val vm = makeViewModel { request ->
            when (request.url.encodedPath) {
                collectionsPath -> respond(singleDoingJson, HttpStatusCode.OK, jsonHeaders())
                "/v0/users/-/collections/1/episodes" -> {
                    val watched = if (episodeGets.getAndIncrement() == 0) setOf(101) else setOf(101, 102, 103)
                    respond(userEpisodesJson(episodesJson, watched), HttpStatusCode.OK, jsonHeaders())
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val item = vm.awaitItem(1)
        awaitUntil("首轮逐集状态到位") {
            vm.progressCount(item, vm.uiState.value) == ProgressCount.Known(null, 1, 3)
        }
        onMain { vm.onQueryChange("不存在的关键词") }
        awaitUntil("筛选把条目隐藏") { vm.uiState.value.collections.isEmpty() }

        onMain { vm.refresh() }

        awaitUntil("被筛选隐藏的条目也刷新到最新状态") {
            vm.progressCount(item, vm.uiState.value) == ProgressCount.Known(null, 3, 3)
        }
        assertEquals(2, episodeGets.get())
    }

    @Test
    fun `没有在看条目时不请求逐集状态`() = runBlocking {
        val vm = makeViewModel { request ->
            when (request.url.encodedPath) {
                collectionsPath -> respond(
                    typedTaggedCollectionsJson(1 to (CollectionType.WISH.value to emptyList())),
                    HttpStatusCode.OK,
                    jsonHeaders(),
                )
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        onMain { vm.onSelectStatus(CollectionType.WISH) }
        awaitUntil("想看列表到位") { vm.uiState.value.collections.map { it.subjectId } == listOf(1) }
        onMain { vm.refresh() }
        awaitUntil("刷新结束") { !vm.uiState.value.isRefreshing }

        assertTrue(
            recorder.none { it.url.encodedPath == "/v0/users/-/collections/1/episodes" },
            "只有在看的条目需要逐集状态",
        )
    }

    @Test
    fun `逐集预取同时最多四个请求`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val episodeGets = AtomicInteger()
        val emptyEpisodes = """{"data":[],"total":0,"limit":100,"offset":0}"""
        val vm = makeViewModel { request ->
            when (request.url.encodedPath) {
                collectionsPath -> respond(collectionsJson(1, 2, 3, 4, 5), HttpStatusCode.OK, jsonHeaders())
                else -> {
                    if (request.url.encodedPath.endsWith("/episodes")) {
                        episodeGets.incrementAndGet()
                        val current = active.incrementAndGet()
                        maxActive.updateAndGet { maxOf(it, current) }
                        release.await()
                        active.decrementAndGet()
                        respond(userEpisodesJson(emptyEpisodes, emptySet()), HttpStatusCode.OK, jsonHeaders())
                    } else {
                        respond("{}", HttpStatusCode.NotFound)
                    }
                }
            }
        }
        awaitUntil("四个逐集请求同时到达") { maxActive.get() >= 4 }
        assertEquals(4, maxActive.get())

        release.complete(Unit)

        awaitUntil("五个逐集请求全部完成") { episodeGets.get() == 5 }
        assertTrue(maxActive.get() <= 4, "并发上限保持为 4")
    }

    @Test
    fun `显式刷新逐集状态同时最多四个请求`() = runBlocking {
        val refreshPhase = AtomicBoolean(false)
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val refreshGets = AtomicInteger()
        val emptyEpisodes = """{"data":[],"total":0,"limit":100,"offset":0}"""
        val vm = makeViewModel { request ->
            when {
                request.url.encodedPath == collectionsPath ->
                    respond(collectionsJson(1, 2, 3, 4, 5), HttpStatusCode.OK, jsonHeaders())
                request.url.encodedPath.endsWith("/episodes") -> {
                    if (refreshPhase.get()) {
                        refreshGets.incrementAndGet()
                        val current = active.incrementAndGet()
                        maxActive.updateAndGet { maxOf(it, current) }
                        release.await()
                        active.decrementAndGet()
                    }
                    respond(userEpisodesJson(emptyEpisodes), HttpStatusCode.OK, jsonHeaders())
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        awaitUntil("五部首轮均已知空") { vm.uiState.value.emptyWatchStates.containsAll((1..5).toSet()) }
        onMain { }
        refreshPhase.set(true)

        onMain { vm.refresh() }
        awaitUntil("刷新中至少四个逐集请求同时到达") { maxActive.get() >= 4 }
        assertEquals(4, refreshGets.get())
        assertEquals(4, maxActive.get())
        release.complete(Unit)
        awaitUntil("五部均发出刷新请求") { refreshGets.get() == 5 }
        assertEquals(4, maxActive.get())
    }

    @Test
    fun `章节与逐集状态本地都没有时 一路分页取回两样 不再请求 v0 章节`() = runBlocking {
        val vm = singleAnimeViewModel(failWrites = false)
        val item = vm.awaitItem(1)
        awaitUntil("预取到位") { vm.epButtonState(item, vm.uiState.value) is EpButtonState.Next }
        assertEquals(102, (vm.epButtonState(item, vm.uiState.value) as EpButtonState.Next).episode.id)
        assertEquals(listOf(101, 102, 103), vm.uiState.value.episodesMap.getValue(1).map { it.id })
        assertTrue(recorder.none { it.url.encodedPath == "/v0/episodes" }, "逐集记录已带回章节，不该再拉 /v0/episodes")
        assertEquals(1, recorder.count { it.method == HttpMethod.Get && it.url.encodedPath == "/v0/users/-/collections/1/episodes" })
    }

    @Test
    fun `本地已有章节时 逐集状态还在路上就先出章节`() = runBlocking {
        episodeDao.upsertAll(listOf(101, 102, 103).map { id ->
            EpisodeEntity(id, 1, 0, "ep$id", "", (id - 100).toDouble(), updatedAt = System.currentTimeMillis())
        })
        val statesGate = CompletableDeferred<Unit>()
        val vm = makeViewModel { request ->
            when (request.url.encodedPath) {
                collectionsPath -> respond(singleDoingJson, HttpStatusCode.OK, jsonHeaders())
                "/v0/users/-/collections/1/episodes" -> {
                    statesGate.await()
                    respond(statesJson, HttpStatusCode.OK, jsonHeaders())
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        vm.awaitItem(1)
        // 进度条的波浪只看章节：本地有就当场给出，不等逐集状态那一路
        awaitUntil("章节先到") { 1 in vm.uiState.value.episodesMap }
        assertEquals(listOf(101, 102, 103), vm.uiState.value.episodesMap.getValue(1).map { it.id })
        assertTrue(recorder.none { it.url.encodedPath == "/v0/episodes" })
        statesGate.complete(Unit)
        val item = vm.awaitItem(1)
        awaitUntil("逐集状态到了，按钮可点") { vm.epButtonState(item, vm.uiState.value) is EpButtonState.Next }
    }

    @Test
    fun `章节请求未返回 或逐集状态未到 是加载态`() = runBlocking {
        val vm = singleAnimeViewModel(failWrites = false)
        val item = vm.awaitItem(1)
        val base = vm.uiState.value
        val episodes = listOf(EpisodeBo(id = 101, subjectId = 1, type = 0, name = "", nameCn = "", sort = 1.0))
        val noEpisodesState = base.copy(episodesMap = emptyMap(), watchStates = emptyMap())
        assertEquals(
            EpButtonState.Loading,
            vm.epButtonState(item, noEpisodesState),
        )
        assertEquals(
            ProgressCount.Loading,
            vm.progressCount(item, noEpisodesState),
        )
        // 章节已返回、逐集状态没到：同样算不准下一集，仍是加载态
        val noWatchState = base.copy(episodesMap = mapOf(1 to episodes), watchStates = emptyMap())
        assertEquals(
            EpButtonState.Loading,
            vm.epButtonState(item, noWatchState),
        )
        assertEquals(
            ProgressCount.Loading,
            vm.progressCount(item, noWatchState),
        )
    }

    @Test
    fun `章节请求已返回但零正片 是留空而不是永久加载`() = runBlocking {
        // 只有一集 SP、没有正片
        val noMainEpisodes = """{"data":[{"id":201,"subject_id":1,"type":1,"sort":1.0,"name":"sp1","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"airdate":""}],"total":1,"limit":100,"offset":0}"""
        val vm = singleAnimeViewModel(failWrites = false, episodesBody = noMainEpisodes)
        val item = vm.awaitItem(1)
        awaitUntil("零集章节也算返回") { vm.epButtonState(item, vm.uiState.value) !is EpButtonState.Loading }
        assertEquals(EpButtonState.Empty, vm.epButtonState(item, vm.uiState.value))
    }

    @Test
    fun `章节请求失败 槽位留空而不是永久加载`() = runBlocking {
        val vm = singleAnimeViewModel(failWrites = false, episodesBody = "boom", episodesStatus = HttpStatusCode.InternalServerError)
        val item = vm.awaitItem(1)
        awaitUntil("请求失败后不再是加载态") { vm.epButtonState(item, vm.uiState.value) !is EpButtonState.Loading }
        assertEquals(EpButtonState.Empty, vm.epButtonState(item, vm.uiState.value))
        assertTrue(item.subjectId in vm.uiState.value.episodesFailed)
        // 计数不停在骨架上：回落到收藏记录的已看话数与总话数
        assertEquals(ProgressCount.Known(null, item.epStatus, item.subject?.eps ?: 0), vm.progressCount(item, vm.uiState.value))
    }

    @Test
    fun `还没录入章节的新番 两个端点都返回零行 是留空而不是永久加载`() = runBlocking {
        // 用户逐集响应由夹具从章节派生，同样零行：本地两样都没有，走一次请求那一路。
        // 零行时逐集表里没有这部的行，快照里也就永远没有它：不能凭「有没有行」判逐集状态已知
        val vm = singleAnimeViewModel(failWrites = false, episodesBody = """{"data":[],"total":0,"limit":100,"offset":0}""")
        val item = vm.awaitItem(1)
        awaitUntil("逐集请求返回零行也算已知") { vm.epButtonState(item, vm.uiState.value) !is EpButtonState.Loading }
        assertEquals(EpButtonState.Empty, vm.epButtonState(item, vm.uiState.value))
        assertEquals(ProgressCount.Known(null, 0, 0), vm.progressCount(item, vm.uiState.value))
    }

    @Test
    fun `逐集状态请求失败 槽位留空而不是永久加载 重试成功后变回按钮`() = runBlocking {
        // 本地已有章节：走两步那一路（章节读缓存，逐集状态单独请求）
        episodeDao.upsertAll(listOf(101, 102, 103).map { id ->
            EpisodeEntity(id, 1, 0, "ep$id", "", (id - 100).toDouble(), updatedAt = System.currentTimeMillis())
        })
        // 第一次逐集请求失败；之后的请求挂到服务端恢复（serverBack）才成功：列表更新随时会触发重试，
        // 恢复之前的重试不会碰巧成功，恢复时正在途中的重试也不会再失败一次
        val statesCalls = AtomicInteger(0)
        val serverBack = CompletableDeferred<Unit>()
        val vm = makeViewModel { request ->
            when (request.url.encodedPath) {
                collectionsPath -> respond(singleDoingJson, HttpStatusCode.OK, jsonHeaders())
                "/v0/users/-/collections/1/episodes" -> if (statesCalls.getAndIncrement() == 0) {
                    respond("boom", HttpStatusCode.InternalServerError)
                } else {
                    serverBack.await()
                    respond(statesJson, HttpStatusCode.OK, jsonHeaders())
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val item = vm.awaitItem(1)
        awaitUntil("逐集请求失败后不再是加载态") { vm.epButtonState(item, vm.uiState.value) !is EpButtonState.Loading }
        // 章节已到、逐集状态没有：不能按「一集都没看」算出第 1 集
        assertEquals(EpButtonState.Empty, vm.epButtonState(item, vm.uiState.value))
        assertTrue(item.subjectId in vm.uiState.value.episodesFailed)
        // 计数回落到收藏记录的已看话数与总话数，不停在骨架上
        assertEquals(ProgressCount.Known(null, item.epStatus, item.subject?.eps ?: 0), vm.progressCount(item, vm.uiState.value))

        serverBack.complete(Unit)
        onMain { vm.refresh() }
        // 两个条件一旦成立都不再变：行经快照到达与失败标记摘掉谁先谁后不定，所以一起等
        awaitUntil("重试成功后变回按钮、失败标记摘掉") {
            val state = vm.uiState.value
            vm.epButtonState(item, state) is EpButtonState.Next && item.subjectId !in state.episodesFailed
        }
        // 第 1 集已看：下一集是 102，说明按钮按重试拿回的逐集状态算
        assertEquals(102, (vm.epButtonState(item, vm.uiState.value) as EpButtonState.Next).episode.id)
        // 计数换回按逐集数：看过 1 集，共 3 集正片
        assertEquals(ProgressCount.Known(null, 1, 3), vm.progressCount(item, vm.uiState.value))
    }

    @Test
    fun `章节缓存成功而逐集持续失败 不自行重试 显式刷新才重试`() = runBlocking {
        episodeDao.upsertAll(listOf(101, 102, 103).map { id ->
            EpisodeEntity(id, 1, 0, "ep$id", "", (id - 100).toDouble(), updatedAt = System.currentTimeMillis())
        })
        val statesCalls = AtomicInteger()
        val vm = makeViewModel { request ->
            when (request.url.encodedPath) {
                collectionsPath -> respond(singleDoingJson, HttpStatusCode.OK, jsonHeaders())
                "/v0/users/-/collections/1/episodes" -> {
                    statesCalls.incrementAndGet()
                    respond("boom", HttpStatusCode.InternalServerError)
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val item = vm.awaitItem(1)
        awaitUntil("首轮失败落定") { item.subjectId in vm.uiState.value.episodesFailed && statesCalls.get() >= 1 }
        val settledCalls = statesCalls.get()
        onMain { vm.onQueryChange("没有这个标题") }
        awaitUntil("普通重筛完成") { vm.uiState.value.collections.isEmpty() }
        assertEquals(1, settledCalls, "首轮只请求一次")
        assertEquals(settledCalls, statesCalls.get(), "普通重筛不得再次触发失败项预取")

        val epoch = vm.uiState.value.selectedEpoch()
        onMain {
            vm.onQueryChange("")
            vm.refresh()
        }
        awaitUntil("显式刷新触发下一轮") {
            vm.uiState.value.selectedEpoch() > epoch && !vm.uiState.value.isRefreshing && statesCalls.get() > settledCalls
        }
        assertEquals(settledCalls + 1, statesCalls.get())
    }

    @Test
    fun `游戏与音乐从第一帧起就不是加载态`() = runBlocking {
        val vm = singleAnimeViewModel(failWrites = false)
        val item = vm.awaitItem(1)
        val firstFrame = vm.uiState.value.copy(episodesMap = emptyMap(), watchStates = emptyMap())
        for (type in listOf(SubjectType.GAME, SubjectType.MUSIC)) {
            assertEquals(EpButtonState.Empty, vm.epButtonState(item.copy(subjectType = type), firstFrame), "$type")
        }
    }

    @Test
    fun `不是在看的条目没有按钮 也不等章节`() = runBlocking {
        val vm = singleAnimeViewModel(failWrites = false)
        val item = vm.awaitItem(1)
        val firstFrame = vm.uiState.value.copy(episodesMap = emptyMap(), watchStates = emptyMap())
        assertEquals(EpButtonState.Empty, vm.epButtonState(item.copy(type = CollectionType.WISH), firstFrame))
    }

    @Test
    fun `EP按钮提交失败 本地回滚且只在本页提示错误`() = runBlocking {
        val vm = singleAnimeViewModel(failWrites = true)
        val item = vm.awaitItem(1)
        awaitUntil("按钮可点") { vm.epButtonState(item, vm.uiState.value) is EpButtonState.Next }

        commitGate.close()
        onMain { vm.onEpButtonTap(item) }
        awaitUntil("乐观更新：下一集变成第 3 集") {
            (vm.epButtonState(item, vm.uiState.value) as? EpButtonState.Next)?.episode?.id == 103
        }
        commitGate.open()
        awaitUntil("提交失败后给出本页提示") { vm.failures.current.value != null }
        awaitUntil("本地回滚：下一集回到第 2 集") {
            (vm.epButtonState(item, vm.uiState.value) as? EpButtonState.Next)?.episode?.id == 102
        }
    }

    @Test
    fun `标记进度后条目不跳位置 刷新后才重排`() = runBlocking<Unit> {
        val held = CompletableDeferred<Unit>()
        val vm = heldRefreshViewModel(held)
        awaitUntil("列表加载") { vm.uiState.value.collections.size == 2 }
        val before = vm.uiState.value.collections.map { it.subjectId }
        val last = before.last()
        // 模拟本地进度写入：最后一个条目的 updatedAt 变成最新
        dao.upsert(dao.store.getValue(ACCOUNT_ID to last).copy(updatedAt = Long.MAX_VALUE / 2))
        awaitUntil("本地数据变化已到达") {
            vm.uiState.value.collections.first { it.subjectId == last }.updatedAt == Long.MAX_VALUE / 2
        }
        assertEquals(before, vm.uiState.value.collections.map { it.subjectId }, "标记后位置不变")

        // 强制重排：refresh() 先把 order 置 null 再按现有数据排序（不等网络回来的那次发射）。
        reorder(vm) { vm.refresh() }
        assertEquals(last, vm.uiState.value.collections.first().subjectId, "重新排序后按最新时间排")
        held.complete(Unit)
    }

    @Test
    fun `下拉刷新立刻重排并递增重排序号 原位更新不递增`() = runBlocking<Unit> {
        val held = CompletableDeferred<Unit>()
        val vm = heldRefreshViewModel(held)
        awaitUntil("列表加载") { vm.uiState.value.collections.size == 2 }
        val last = vm.uiState.value.collections.last().subjectId
        val epochBefore = vm.uiState.value.selectedEpoch()
        dao.upsert(dao.store.getValue(ACCOUNT_ID to last).copy(updatedAt = Long.MAX_VALUE / 2))
        awaitUntil("本地数据变化已到达") {
            vm.uiState.value.collections.first { it.subjectId == last }.updatedAt == Long.MAX_VALUE / 2
        }
        assertEquals(epochBefore, vm.uiState.value.selectedEpoch(), "原位更新不递增")

        reorder(vm) { vm.refresh() }
        assertEquals(last, vm.uiState.value.collections.first().subjectId, "刷新按最新时间重排")
        assertEquals(epochBefore + 1, vm.uiState.value.selectedEpoch(), "重排才递增")
        held.complete(Unit)
    }

    /**
     * 首轮收藏同步照常回包；之后的收藏请求（下拉刷新）停在 [held] 上。筛选在后台算，
     * 测「刷新先按本地现有数据重排」时，带旧时间的网络回包不能抢在那一次重排之前落进列表。
     */
    private fun heldRefreshViewModel(held: CompletableDeferred<Unit>): ProgressViewModel {
        val collectionCalls = AtomicInteger()
        return makeViewModel { request ->
            if (request.url.encodedPath == collectionsPath && collectionCalls.getAndIncrement() > 0) held.await()
            respond(collectionsJson(1, 2), HttpStatusCode.OK, jsonHeaders())
        }
    }

    @Test
    fun `进度页内切类型不经入口请求 也不动搜索`() = runBlocking {
        val vm = makeViewModel { respond(collectionsJson(1, 2), HttpStatusCode.OK, jsonHeaders()) }
        val before = entryRequests.latest.value
        onMain { vm.onQueryChange("关键词") }
        onMain { vm.onSelectSubjectType(PROGRESS_TYPES.indexOf(SubjectType.BOOK)) }
        assertEquals(SubjectType.BOOK, PROGRESS_TYPES[vm.uiState.value.selectedSubjectType])
        assertEquals("关键词", vm.uiState.value.query, "用户自己切类型保持原行为：不清搜索")
        assertEquals(before, entryRequests.latest.value, "用户自己切类型不是入口请求")
    }

    /**
     * 首页入口请求的场景底稿：动画域里一部在看（1）、一部看过（2），两部都带同一个标签与分类，
     * 这样切到「看过」后搜索、标签、分类仍然生效，入口请求清掉的是**真的存在**的旧条件。
     */
    private suspend fun enterableViewModel(): ProgressViewModel {
        seedCollection(1, updatedAt = 300L, metaTags = listOf("TV"), tags = listOf("补番"))
        seedCollection(2, updatedAt = 200L, metaTags = listOf("TV"), tags = listOf("补番"), status = CollectionType.DONE)
        val vm = makeViewModel {
            respond(
                typedTaggedCollectionsJson(1 to (CollectionType.DOING.value to listOf("补番")), 2 to (CollectionType.DONE.value to listOf("补番"))),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        vm.awaitAnimeTab()
        awaitUntil("在看栏与可选项到位") {
            vm.uiState.value.collections.map { it.subjectId } == listOf(1) &&
                vm.uiState.value.tagOptions.map { it.key } == listOf("补番") &&
                vm.uiState.value.categoryGroups.isNotEmpty()
        }
        return vm
    }

    /** 搜关键词（命中两部的标题）、选标签与分类、切到「看过」——并确认这些旧条件都真的生效了。 */
    private suspend fun ProgressViewModel.dirtyFilters() {
        onMain { onQueryChange("作品") }
        onMain { onToggleTag("补番") }
        onMain { onToggleCategory(category(SearchDimension.CATEGORY, "TV", "TV")) }
        onMain { onSelectStatus(CollectionType.DONE) }
        awaitUntil("看过栏就位") { uiState.value.collections.map { it.subjectId } == listOf(2) }
        assertEquals("作品", uiState.value.query)
        assertEquals(setOf("补番"), uiState.value.selectedTags)
        assertEquals(1, uiState.value.selectedCategories.size)
        assertEquals(CollectionType.DONE, uiState.value.collectionStatus)
    }

    private fun ProgressViewModel.assertBackToDefaultAnime() {
        val state = uiState.value
        assertEquals(SubjectType.ANIME, PROGRESS_TYPES[state.selectedSubjectType])
        assertEquals(CollectionType.DOING, state.collectionStatus, "回到在看")
        assertEquals("", state.query, "清掉搜索")
        assertTrue(state.selectedTags.isEmpty(), "清掉标签")
        assertTrue(state.selectedCategories.isEmpty(), "清掉分类")
        assertEquals(listOf(1), state.collections.map { it.subjectId })
    }

    @Test
    fun `首页入口请求 回到在看 并清掉搜索与标签分类`() = runBlocking {
        val vm = enterableViewModel()
        vm.dirtyFilters()

        entryRequests.open(SubjectType.ANIME)

        awaitUntil("入口请求已处理：回到在看") { vm.uiState.value.collectionStatus == CollectionType.DOING }
        awaitUntil("在看栏列表就位") { vm.uiState.value.collections.map { it.subjectId } == listOf(1) }
        vm.assertBackToDefaultAnime()
    }

    @Test
    fun `同类型重复的入口请求也生效`() = runBlocking {
        val vm = enterableViewModel()
        vm.dirtyFilters()
        entryRequests.open(SubjectType.ANIME)
        awaitUntil("第一次请求已处理") { vm.uiState.value.collectionStatus == CollectionType.DOING }
        awaitUntil("第一次请求后在看栏就位") { vm.uiState.value.collections.map { it.subjectId } == listOf(1) }

        vm.dirtyFilters()
        entryRequests.open(SubjectType.ANIME) // 同一个类型再来一次

        awaitUntil("第二次同类型请求也被处理") { vm.uiState.value.collectionStatus == CollectionType.DOING }
        awaitUntil("第二次请求后在看栏就位") { vm.uiState.value.collections.map { it.subjectId } == listOf(1) }
        vm.assertBackToDefaultAnime()
    }

    @Test
    fun `入口请求类型为 null 时进度页回到全部类型`() = runBlocking {
        val vm = enterableViewModel()
        vm.dirtyFilters()

        entryRequests.open(null)

        awaitUntil("入口请求已处理：回到在看") { vm.uiState.value.collectionStatus == CollectionType.DOING }
        val state = vm.uiState.value
        assertNull(PROGRESS_TYPES[state.selectedSubjectType], "null = 全部类型")
        assertEquals("", state.query)
        assertTrue(state.selectedTags.isEmpty())
        assertTrue(state.selectedCategories.isEmpty())
    }

    @Test
    fun `进度页创建之前发出的入口请求 创建后生效`() = runBlocking {
        entryRequests.open(SubjectType.BOOK)
        val vm = makeViewModel { respond(collectionsJson(1, 2), HttpStatusCode.OK, jsonHeaders()) }
        awaitUntil("进度页按已发出的请求落在书籍") {
            PROGRESS_TYPES[vm.uiState.value.selectedSubjectType] == SubjectType.BOOK
        }
        assertEquals(CollectionType.DOING, vm.uiState.value.collectionStatus)
    }

    @Test
    fun `没有入口请求时 进度页开在设置里选的默认分类`() = runBlocking {
        prefs.repository.setProgressDefaultType(SubjectType.BOOK)
        val vm = makeViewModel { respond(collectionsJson(1, 2), HttpStatusCode.OK, jsonHeaders()) }
        assertEquals(SubjectType.BOOK, PROGRESS_TYPES[vm.uiState.value.selectedSubjectType])
        awaitUntil("按书籍拉取") { recorder.any { it.url.parameters["subject_type"] == SubjectType.BOOK.value.toString() } }
        assertTrue(
            recorder.none { it.url.parameters["subject_type"].let { type -> type != null && type != SubjectType.BOOK.value.toString() } },
            "只拉默认分类那一类，不先按全部拉一轮",
        )
    }

    @Test
    fun `首页的入口请求不看默认分类`() = runBlocking {
        prefs.repository.setProgressDefaultType(SubjectType.BOOK)
        entryRequests.open(SubjectType.ANIME)
        val vm = makeViewModel { respond(collectionsJson(1, 2), HttpStatusCode.OK, jsonHeaders()) }
        assertEquals(SubjectType.ANIME, PROGRESS_TYPES[vm.uiState.value.selectedSubjectType])
    }

    /** 门禁：onSelectStatus 只是服务端筛选参数 + 本地过滤，严禁被当成条目收藏的写入入口。 */
    @Test
    fun `onSelectStatus 只做筛选不发任何网络写请求`() = runBlocking {
        val vm = makeViewModel { respond(collectionsJson(1, 2), HttpStatusCode.OK, jsonHeaders()) }
        awaitUntil("初始加载请求已发出") { recorder.isNotEmpty() }
        val readsBefore = recorder.count { it.method == HttpMethod.Get }
        onMain { vm.onSelectStatus(CollectionType.WISH) }
        awaitUntil("筛选后重新拉取已发出") {
            recorder.any { it.url.parameters["type"] == "1" }
        }
        // 除 GET 列表刷新外不得有任何写请求（POST/PATCH/PUT/DELETE 均禁止）
        assertTrue(
            recorder.none { it.method != HttpMethod.Get },
            "onSelectStatus 严禁发出网络写请求：${recorder.filter { it.method != HttpMethod.Get }}",
        )
        assertTrue(recorder.count { it.method == HttpMethod.Get } > readsBefore)
    }

    // ---------- 左右翻页：每页自己的列表、顺序与形态 ----------

    /** 动画、书籍的收藏请求一直不回：两页只按本地已有的收藏画（网络不会把本地种的条目覆盖掉）。 */
    private fun pagedViewModel(): ProgressViewModel {
        val never = CompletableDeferred<Unit>()
        return makeViewModel(routed = setOf(SubjectType.ANIME, SubjectType.BOOK)) {
            never.await()
            respond(emptyCollectionsJson, HttpStatusCode.OK, jsonHeaders())
        }
    }

    /**
     * 翻页途中看到的邻页就是落定后的样子，单纯翻页不重排：翻到书籍页之前它在 pages 里的顺序，
     * 就是翻过去之后的列表；各页的重排次数都不变（翻回去的页还在原位）。
     */
    @Test
    fun `翻页不重排_邻页就是落定后的样子`() = runBlocking {
        seedCollection(1, updatedAt = 100L)
        seedCollection(2, updatedAt = 200L)
        seedCollection(3, updatedAt = 300L, subjectType = SubjectType.BOOK)
        seedCollection(4, updatedAt = 400L, subjectType = SubjectType.BOOK)
        val vm = pagedViewModel()
        val book = PROGRESS_TYPES.indexOf(SubjectType.BOOK)
        // 初始化走完的信号：进入请求先重排各页、再发收藏请求（带 subject_type）。收藏流可能先到，
        // 只等「书籍页排好」会在进入请求重排之前取快照
        awaitUntil("进入请求已发出收藏请求") { recorder.any { it.url.parameters["subject_type"] != null } }
        awaitUntil("书籍页按本地收藏排好") { vm.uiState.value.pages[book].size == 2 }
        val preview = vm.uiState.value.pages[book].map { it.subjectId }
        val epochs = vm.uiState.value.pageEpochs

        onMain { vm.onSelectSubjectType(book) }
        assertEquals(preview, vm.uiState.value.collections.map { it.subjectId }, "落定后就是翻页途中看到的那一页")
        assertEquals(epochs, vm.uiState.value.pageEpochs, "单纯翻页不重排")
    }

    /**
     * 一页画成什么只看 shapeOf：有条目画列表；没条目又没同步过（不知道是不是真的空）画骨架；
     * 同步过还是空的才写「没有条目」。
     */
    @Test
    fun `没同步过的空页画骨架_同步过的空页写没有条目`() = runBlocking {
        seedCollection(1, updatedAt = 100L)
        val vm = pagedViewModel()
        val anime = PROGRESS_TYPES.indexOf(SubjectType.ANIME)
        val book = PROGRESS_TYPES.indexOf(SubjectType.BOOK)
        val game = PROGRESS_TYPES.indexOf(SubjectType.GAME)
        // 默认的「全部」页一并同步五类：游戏的请求当场回空，书籍的一直不回
        awaitUntil("游戏已同步") { SubjectType.GAME in vm.uiState.value.syncedTypes }
        val state = vm.uiState.value
        assertEquals(ProgressPageShape.LIST, state.shapeOf(anime))
        assertEquals(ProgressPageShape.LOADING, state.shapeOf(book), "书籍还不知道有没有条目")
        assertEquals(ProgressPageShape.EMPTY, state.shapeOf(game), "游戏同步过，确实没有")
    }

    // ---------- 筛选面板：标签 / 分类 ----------

    /**
     * 切到动画 tab 并等到可选项到位。分类与标签的可选项都只在**具体类型 tab**下才有
     * （默认的「全部」页签没有跨类型维度，词典返回空）。
     */
    private suspend fun ProgressViewModel.awaitAnimeTab() {
        onMain { onSelectSubjectType(PROGRESS_TYPES.indexOf(SubjectType.ANIME)) }
        awaitUntil("动画 tab 就位") { uiState.value.selectedSubjectType == PROGRESS_TYPES.indexOf(SubjectType.ANIME) }
    }

    @Test
    fun `标签取并集 命中任一即显示`() = runBlocking {
        val vm = makeViewModel {
            respond(taggedCollectionsJson(1 to listOf("补番"), 2 to listOf("神作"), 3 to listOf("下饭")), HttpStatusCode.OK, jsonHeaders())
        }
        vm.awaitAnimeTab()
        // 等到**最终态**（三个标签全部到位）再断言：切 tab 会重新拉取，回包前 tagOptions 只有一部分。
        awaitUntil("标签可选项全部到位") {
            vm.uiState.value.tagOptions.map { it.key } == listOf("补番", "神作", "下饭") && vm.uiState.value.collections.size == 3
        }

        reorder(vm) { vm.onToggleTag("神作") }
        assertEquals(listOf(2), vm.uiState.value.collections.map { it.subjectId })

        // 再选一个 = 并集，不是交集
        reorder(vm) { vm.onToggleTag("下饭") }
        assertEquals(listOf(2, 3), vm.uiState.value.collections.map { it.subjectId }.sorted())
    }

    @Test
    fun `选分类后列表按同维并集跨维交集收敛`() = runBlocking {
        // 1：TV + 日本；2：剧场版 + 日本；3：TV + 中国
        seedCollection(1, updatedAt = 300L, metaTags = listOf("TV", "日本"))
        seedCollection(2, updatedAt = 200L, metaTags = listOf("剧场版", "日本"))
        seedCollection(3, updatedAt = 100L, metaTags = listOf("TV", "中国"))
        val vm = makeViewModel { respond(collectionsJson(1, 2, 3), HttpStatusCode.OK, jsonHeaders()) }
        vm.awaitAnimeTab()
        awaitUntil("分类可选项到位") {
            vm.uiState.value.collections.size == 3 && vm.uiState.value.categoryGroups.isNotEmpty()
        }

        // CATEGORY 维度出现过的取值只有 TV、剧场版；REGION 只有 日本、中国
        val categoryOptions = vm.uiState.value.categoryGroups
            .first { it.dimension == SearchDimension.CATEGORY }.options.map { it.key.option.label }
        assertEquals(listOf("TV", "剧场版"), categoryOptions)

        // 选 CATEGORY=TV → 只剩 1、3
        reorder(vm) { vm.onToggleCategory(category(SearchDimension.CATEGORY, "TV", "TV")) }
        assertEquals(listOf(1, 3), vm.uiState.value.collections.map { it.subjectId }.sorted())

        // 再选同维度 剧场版 → 并集，1、2、3 都回来
        reorder(vm) { vm.onToggleCategory(category(SearchDimension.CATEGORY, "剧场版", "剧场版")) }
        assertEquals(listOf(1, 2, 3), vm.uiState.value.collections.map { it.subjectId }.sorted())

        // 去掉剧场版、改选跨维度 REGION=日本 → 交集只剩 1
        reorder(vm) {
            vm.onToggleCategory(category(SearchDimension.CATEGORY, "剧场版", "剧场版"))
            vm.onToggleCategory(category(SearchDimension.REGION, "日本", "日本"))
        }
        assertEquals(listOf(1), vm.uiState.value.collections.map { it.subjectId })
        assertEquals(2, vm.uiState.value.activeFilterCount)
    }

    @Test
    fun `分类与标签之间取交集`() = runBlocking {
        // metaTags 只能来自 subjects 表（网络收藏响应里没有），故先种进 DB；
        // 标签则必须走响应体——getUserCollections 会用响应整行覆盖收藏表。
        seedCollection(1, updatedAt = 300L, metaTags = listOf("TV", "日本"))
        seedCollection(2, updatedAt = 200L, metaTags = listOf("TV", "日本"))
        val vm = makeViewModel {
            respond(taggedCollectionsJson(1 to listOf("补番"), 2 to listOf("神作")), HttpStatusCode.OK, jsonHeaders())
        }
        vm.awaitAnimeTab()
        // 等到最终态（分类可选项与标签选项都已完全到位）再动作：
        // 若在 tagOptions 还是空/旧值时点标签，收敛会把刚选的标签丢掉。
        awaitUntil("分类与标签可选项全部到位") {
            vm.uiState.value.collections.size == 2 &&
                vm.uiState.value.categoryGroups.isNotEmpty() &&
                vm.uiState.value.tagOptions.map { it.key } == listOf("补番", "神作")
        }

        reorder(vm) { vm.onToggleCategory(category(SearchDimension.CATEGORY, "TV", "TV")) }
        assertEquals(listOf(1, 2), vm.uiState.value.collections.map { it.subjectId }.sorted())
        reorder(vm) { vm.onToggleTag("神作") }
        assertEquals(listOf(2), vm.uiState.value.collections.map { it.subjectId })
    }

    @Test
    fun `面板的清除只清标签与分类 不动搜索 收藏状态与排序`() = runBlocking {
        seedCollection(1, updatedAt = 300L, metaTags = listOf("TV", "日本"))
        val vm = makeViewModel {
            respond(taggedCollectionsJson(1 to listOf("补番")), HttpStatusCode.OK, jsonHeaders())
        }
        vm.awaitAnimeTab()
        awaitUntil("可选项全部到位") {
            vm.uiState.value.tagOptions.map { it.key } == listOf("补番") &&
                vm.uiState.value.collections.size == 1 &&
                vm.uiState.value.categoryGroups.isNotEmpty()
        }
        onMain { vm.onToggleTag("补番") }
        onMain { vm.onToggleCategory(category(SearchDimension.CATEGORY, "TV", "TV")) }
        onMain { vm.onSelectSort(ProgressSort.TITLE) }
        onMain { vm.onSelectDirection(reversed = true) }
        onMain { vm.onQueryChange("作品") }
        assertEquals(2, vm.uiState.value.activeFilterCount)

        onMain { vm.clearPanelFilters() }
        assertTrue(vm.uiState.value.selectedTags.isEmpty(), "清除必须清掉标签")
        assertTrue(vm.uiState.value.selectedCategories.isEmpty(), "清除必须清掉分类")
        assertEquals(0, vm.uiState.value.activeFilterCount)
        assertEquals(ProgressOrder(ProgressSort.TITLE, reversed = true), vm.uiState.value.order, "排序不是筛选，不得被清除")
        assertEquals("作品", vm.uiState.value.query, "搜索词不在面板里，不得被清除")
        assertEquals(CollectionType.DOING, vm.uiState.value.collectionStatus, "收藏状态不在面板里，不得被清除")
    }

    /**
     * 已选项在新的一批里不存在时被**收敛**掉：`selectedTags` / `selectedCategories` 清空、
     * 已选数（数字徽标）归零、列表恢复。
     */
    @Test
    fun `已选标签与分类不在新一批里时被收敛 面板不留隐形筛选`() = runBlocking {
        seedCollection(1, updatedAt = 300L, metaTags = listOf("TV"))
        seedCollection(2, updatedAt = 200L, metaTags = listOf("剧场版"))
        val vm = makeViewModel {
            respond(
                typedTaggedCollectionsJson(
                    1 to (3 to listOf("补番")),
                    2 to (1 to listOf("神作")),
                ),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        vm.awaitAnimeTab()
        awaitUntil("可选项全部到位") {
            vm.uiState.value.collections.map { it.subjectId } == listOf(1) &&
                vm.uiState.value.tagOptions.map { it.key } == listOf("补番") &&
                vm.uiState.value.categoryGroups.isNotEmpty()
        }

        onMain { vm.onToggleTag("补番") }
        onMain { vm.onToggleCategory(category(SearchDimension.CATEGORY, "TV", "TV")) }
        assertEquals(2, vm.uiState.value.activeFilterCount)

        onMain { vm.onSelectStatus(CollectionType.WISH) }
        awaitUntil("想看栏就位且已选项被收敛") {
            vm.uiState.value.collections.map { it.subjectId } == listOf(2)
        }
        assertTrue(vm.uiState.value.selectedTags.isEmpty(), "新一批里没有的标签必须被丢弃")
        assertTrue(vm.uiState.value.selectedCategories.isEmpty(), "新一批里没有的分类必须被丢弃")
        assertEquals(0, vm.uiState.value.activeFilterCount, "只剩空集时不得再算作有筛选")
    }

    /** 切到书籍 tab 时上一个类型的已选分类必须被清掉（各类型词典互不通用）。 */
    @Test
    fun `切到书籍 tab 清空分类筛选`() = runBlocking {
        seedCollection(1, updatedAt = 300L, metaTags = listOf("TV"))
        val vm = makeViewModel { respond(collectionsJson(1), HttpStatusCode.OK, jsonHeaders()) }
        vm.awaitAnimeTab()
        awaitUntil("分类可选项到位") {
            vm.uiState.value.collections.size == 1 && vm.uiState.value.categoryGroups.isNotEmpty()
        }
        onMain { vm.onToggleCategory(category(SearchDimension.CATEGORY, "TV", "TV")) }
        assertTrue(vm.uiState.value.selectedCategories.isNotEmpty())

        onMain { vm.onSelectSubjectType(PROGRESS_TYPES.indexOf(SubjectType.BOOK)) }
        // 等到 applyFilter 真的按书籍跑过一轮（书词典收不到 TV，可选项转空），
        // 而不是只等类型真源写入（那只发生在 applyFilter 之前的收集器里）——
        // 否则断言会读到上一轮的 selectedCategories。
        awaitUntil("书籍 tab 已应用筛选") {
            PROGRESS_TYPES[vm.uiState.value.selectedSubjectType] == SubjectType.BOOK &&
                vm.uiState.value.categoryGroups.isEmpty()
        }
        assertTrue(vm.uiState.value.selectedCategories.isEmpty(), "切到书籍 tab 必须清空分类")
        assertEquals(0, vm.uiState.value.activeFilterCount)
    }

    @Test
    fun `全部 tab 没有分类可选项`() = runBlocking {
        seedCollection(1, updatedAt = 300L, metaTags = listOf("TV"))
        val vm = makeViewModel { respond(collectionsJson(1), HttpStatusCode.OK, jsonHeaders()) }
        vm.awaitAnimeTab()
        awaitUntil("动画 tab 有分类") {
            vm.uiState.value.collections.size == 1 && vm.uiState.value.categoryGroups.isNotEmpty()
        }

        onMain { vm.onSelectSubjectType(PROGRESS_TYPES.indexOf(null)) }
        // selectedSubjectType 由类型真源的收集器先写、applyFilter 后写——等到分类真的空了再断言，
        // 否则会读到上一轮的 categoryGroups（等待中间态）。
        awaitUntil("切回全部且分类可选清空") { vm.uiState.value.categoryGroups.isEmpty() }
        assertTrue(vm.uiState.value.categoryGroups.isEmpty(), "全部页签没有跨类型维度")
    }

    /** 面板打开那一刻已选的标签排在最前；开着时点选不让格子挪位置，关了再开才按新的已选重排。 */
    @Test
    fun `筛选面板里的标签次序在打开时定下 开着时点选不挪位置`() = runBlocking {
        val vm = makeViewModel {
            respond(taggedCollectionsJson(1 to listOf("补番"), 2 to listOf("补番", "神作"), 3 to listOf("下饭")), HttpStatusCode.OK, jsonHeaders())
        }
        vm.awaitAnimeTab()
        awaitUntil("标签可选项全部到位") {
            vm.uiState.value.tagOptions.map { it.key } == listOf("补番", "神作", "下饭") && vm.uiState.value.collections.size == 3
        }
        onMain { vm.onToggleTag("下饭") }

        onMain { vm.openFilters() }
        assertTrue(vm.uiState.value.filtersOpen)
        assertEquals(listOf("下饭", "补番", "神作"), vm.uiState.value.panelTags.map { it.key }, "打开时已选的在前，其余按部数")

        onMain { vm.onToggleTag("神作") }
        onMain { vm.onToggleTag("下饭") }
        assertEquals(listOf("下饭", "补番", "神作"), vm.uiState.value.panelTags.map { it.key }, "开着时点选不挪位置")

        onMain { vm.closeFilters(vm.uiState.value.filterPanelSession) }
        onMain { vm.openFilters() }
        assertEquals(listOf("神作", "补番", "下饭"), vm.uiState.value.panelTags.map { it.key }, "重新打开按此刻的已选排")
    }

    /** 上一个面板还在滑下去时又点了筛选入口：新面板要打开，旧面板滑完后迟到的关闭不能把新面板关掉。 */
    @Test
    fun `面板收起途中再点筛选入口 新面板不被旧面板的关闭关掉`() = runBlocking {
        val vm = makeViewModel { respond(collectionsJson(1), HttpStatusCode.OK, jsonHeaders()) }
        awaitUntil("列表加载完成") { vm.uiState.value.collections.size == 1 }
        onMain { vm.openFilters() }
        val first = vm.uiState.value.filterPanelSession

        onMain { vm.openFilters() } // 旧面板还没滑完（状态仍算开着）时又点了入口
        val second = vm.uiState.value.filterPanelSession
        assertTrue(second != first, "每次点入口都是一个新面板")

        onMain { vm.closeFilters(first) }
        assertTrue(vm.uiState.value.filtersOpen, "旧面板迟到的关闭不能关掉新面板")
        onMain { vm.closeFilters(second) }
        assertTrue(!vm.uiState.value.filtersOpen)
    }

    /** 改排序、改分类都视为重新排序（界面据 pageEpochs 回到顶部）。 */
    @Test
    fun `改排序与分类递增重排序号`() = runBlocking {
        seedCollection(1, updatedAt = 300L, metaTags = listOf("TV"))
        seedCollection(2, updatedAt = 200L, metaTags = listOf("TV"))
        val vm = makeViewModel { respond(collectionsJson(1, 2), HttpStatusCode.OK, jsonHeaders()) }
        vm.awaitAnimeTab()
        awaitUntil("分类可选项到位") {
            vm.uiState.value.collections.size == 2 && vm.uiState.value.categoryGroups.isNotEmpty()
        }
        val epochBefore = vm.uiState.value.selectedEpoch()

        reorder(vm) { vm.onSelectSort(ProgressSort.TITLE) }
        assertEquals(epochBefore + 1, vm.uiState.value.selectedEpoch(), "改排序要重排")

        val epochAfterSort = vm.uiState.value.selectedEpoch()
        reorder(vm) { vm.onToggleCategory(category(SearchDimension.CATEGORY, "TV", "TV")) }
        assertEquals(epochAfterSort + 1, vm.uiState.value.selectedEpoch(), "改分类要重排")
        // 「标记进度不递增重排序号」的守卫在 `下拉刷新立刻重排并递增重排序号 原位更新不递增`
        // 与 `标记进度后条目不跳位置 刷新后才重排` 两条用例里——那两条不切 tab，
        // 没有「切 tab 后的网络回包覆盖本地写入」的竞态，断言才是稳定的。
    }

    // ---- 进度页同步失败：按类型记录，该类型自己同步成功后才清除 ----

    private val collectionsPath = "/v0/users/fixture_user/collections"

    @Test
    fun `全部页签 一个类型失败 另一个类型成功时失败仍在 失败的类型自己成功后才清掉`() = runBlocking {
        val animeFails = AtomicBoolean(true)
        val retryGate = CompletableDeferred<Unit>()
        val vm = makeViewModel {
            if (animeFails.get()) {
                respond("boom", HttpStatusCode.InternalServerError)
            } else {
                retryGate.await()
                respond(emptyCollectionsJson, HttpStatusCode.OK, jsonHeaders())
            }
        }
        // 动画失败、其余四个类型成功
        awaitUntil("动画同步失败") { vm.uiState.value.syncError == SERVER_500 }
        assertEquals(setOf(SubjectType.ANIME), vm.uiState.value.syncErrors.keys)

        // 重试：动画这一轮被闸门卡住，其余类型再次请求。
        val bookReads = recorder.count { it.url.parameters["subject_type"] == SubjectType.BOOK.value.toString() }
        animeFails.set(false)
        onMain { vm.refresh() }
        awaitUntil("书籍本轮请求已发出") {
            recorder.count { it.url.parameters["subject_type"] == SubjectType.BOOK.value.toString() } > bookReads
        }
        assertTrue(vm.uiState.value.isRefreshing, "动画还没回包，这一轮没结束")
        assertEquals(ProgressPageShape.LOADING, vm.uiState.value.shapeOf(0))
        assertEquals(SERVER_500, vm.uiState.value.syncError, "别的类型成功不得掩盖动画的失败")

        // 动画自己成功：这一轮结束，失败清掉
        retryGate.complete(Unit)
        awaitUntil("本轮结束") { !vm.uiState.value.isRefreshing }
        assertNull(vm.uiState.value.syncError)
        assertTrue(vm.uiState.value.syncErrors.isEmpty())
    }

    @Test
    fun `两个类型都失败 只有一个恢复时换成另一个的失败 全部恢复才清掉`() = runBlocking {
        val animeFails = AtomicBoolean(true)
        val bookFails = AtomicBoolean(true)
        val vm = makeViewModel(routed = setOf(SubjectType.ANIME, SubjectType.BOOK)) { request ->
            val fails = if (request.url.parameters["subject_type"] == SubjectType.ANIME.value.toString()) animeFails else bookFails
            val status = if (request.url.parameters["subject_type"] == SubjectType.ANIME.value.toString()) {
                HttpStatusCode.InternalServerError
            } else {
                HttpStatusCode.BadGateway
            }
            if (fails.get()) respond("boom", status) else respond(emptyCollectionsJson, HttpStatusCode.OK, jsonHeaders())
        }
        awaitUntil("两个类型都记下了失败") {
            vm.uiState.value.syncErrors.keys == setOf(SubjectType.ANIME, SubjectType.BOOK)
        }
        assertEquals(SERVER_500, vm.uiState.value.syncError, "多个失败时按类型固定顺序取第一个")

        animeFails.set(false)
        onMain { vm.refresh() }
        awaitUntil("本轮结束") { !vm.uiState.value.isRefreshing }
        assertEquals(setOf(SubjectType.BOOK), vm.uiState.value.syncErrors.keys, "动画恢复后只清动画自己的")
        assertEquals(SERVER_502, vm.uiState.value.syncError)

        bookFails.set(false)
        onMain { vm.refresh() }
        awaitUntil("下一轮结束") { !vm.uiState.value.isRefreshing }
        assertNull(vm.uiState.value.syncError)
    }

    @Test
    fun `切到单个类型的页签 其余类型的旧错误随之丢弃`() = runBlocking {
        val vm = makeViewModel { respond("boom", HttpStatusCode.InternalServerError) }
        awaitUntil("动画同步失败") { vm.uiState.value.syncError == SERVER_500 }

        onMain { vm.onSelectSubjectType(PROGRESS_TYPES.indexOf(SubjectType.BOOK)) }

        assertNull(vm.uiState.value.syncError, "书籍页签上不该挂着动画的同步失败")
        assertTrue(vm.uiState.value.syncErrors.isEmpty())
    }

    @Test
    fun `有条目时一轮同步失败 合成一条带重试的提示 按类型固定顺序说第一个`() = runBlocking {
        seedCollection(1, updatedAt = 300L)
        val vm = makeViewModel(routed = setOf(SubjectType.ANIME, SubjectType.BOOK)) { request ->
            val anime = request.url.parameters["subject_type"] == SubjectType.ANIME.value.toString()
            respond("boom", if (anime) HttpStatusCode.InternalServerError else HttpStatusCode.BadGateway)
        }
        // 进页那一轮动画有本地条目、缓存未过期不请求，只有书籍失败；下拉刷新两类都去网络
        awaitUntil("进页那一轮给出提示") { vm.failures.current.value != null }
        vm.failures.consume(vm.failures.current.value!!)
        onMain { vm.refresh() }
        awaitUntil("刷新这一轮给出提示") { vm.failures.current.value != null }

        val notice = vm.failures.current.value!!
        assertEquals(NoticeText.Reason(SERVER_500), notice.text, "动画排在书籍前面")
        assertNotNull(notice.retry, "提示带重试")
        assertEquals(ProgressPageShape.LIST, vm.uiState.value.shapeOf(vm.uiState.value.selectedSubjectType), "条目留在屏上")
    }

    @Test
    fun `没有条目时同步失败 画重试块 不另发提示`() = runBlocking {
        val vm = makeViewModel { respond("boom", HttpStatusCode.InternalServerError) }
        awaitUntil("动画同步失败") { vm.uiState.value.syncError == SERVER_500 }
        onMain { vm.refresh() }
        awaitUntil("这一轮结束") { !vm.uiState.value.isRefreshing }

        assertEquals(ProgressPageShape.ERROR, vm.uiState.value.shapeOf(vm.uiState.value.selectedSubjectType))
        assertNull(vm.failures.current.value, "没有内容时失败画在页面上，不另提示")
    }

    @Test
    fun `标记看完失败走提示条 不记成同步失败`() = runBlocking {
        val vm = makeViewModel { request ->
            when {
                request.url.encodedPath == collectionsPath ->
                    respond(singleDoingJson, HttpStatusCode.OK, jsonHeaders())
                request.url.encodedPath == "/v0/episodes" -> respond(episodesJson, HttpStatusCode.OK, jsonHeaders())
                request.method == HttpMethod.Patch -> respond("boom", HttpStatusCode.InternalServerError)
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val item = vm.awaitItem(1)

        onMain { vm.markDone(item) }
        awaitUntil("写失败给出提示") { vm.failures.current.value != null }
        assertEquals(NoticeText.Resource(R.string.collection_save_failed, listOf(SERVER_500)), vm.failures.current.value?.text)
        assertNull(vm.uiState.value.syncError, "写失败不是同步失败")
    }

    /**
     * 长按一行：面板改的就是这一部。作品与收藏只回了列表同步落下的本地一份（单条的网络请求在这里拿不到可用的响应），
     * 照样就绪——长按的那一刻它就在列表里。存好后发出改了的那一个字段，面板退场。
     */
    @Test
    fun `长按一行打开收藏面板 作品取自本地 存好后面板退场`() = runBlocking {
        val vm = makeViewModel { request ->
            when (request.url.encodedPath) {
                collectionsPath -> respond(singleDoingJson, HttpStatusCode.OK, jsonHeaders())
                "/v0/episodes" -> respond(episodesJson, HttpStatusCode.OK, jsonHeaders())
                "/v0/users/-/collections/1/episodes" -> respond(statesJson, HttpStatusCode.OK, jsonHeaders())
                "/v0/users/-/collections/1" -> respond("", HttpStatusCode.NoContent)
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        vm.awaitItem(1)

        onMain { vm.openCollectionSheet(1) }
        assertEquals(CollectionSheetPhase.OPEN, vm.uiState.value.collectionSheet.phase)
        awaitUntil("这一部到了") { vm.uiState.value.collectionSheet.fetched is Load.Ready }
        val work = assertNotNull(vm.uiState.value.collectionSheet.fetched.valueOrNull())
        assertEquals(1, work.subject.id)
        assertEquals(CollectionType.DOING, work.collection?.type)

        onMain { vm.saveCollection(CollectionEdits(rate = 8)) }
        awaitUntil("保存成功，面板退场") { vm.uiState.value.collectionSheet.phase == CollectionSheetPhase.SAVED }
        val patch = recorder.single { it.method == HttpMethod.Patch && it.url.encodedPath == "/v0/users/-/collections/1" }
        assertEquals("""{"rate":8}""", (patch.body as io.ktor.http.content.TextContent).text)
        assertTrue(vm.failures.current.value != null, "缓存后的详情刷新失败应通知，不影响已成功保存")
    }

    private fun category(dimension: SearchDimension, label: String, metaTag: String) =
        ProgressCategory(dimension, SearchDimensionOption(label, metaTag))

    private fun taggedCollectionsJson(vararg entries: Pair<Int, List<String>>) = """
        {"data":[${entries.joinToString(",") { (id, tags) ->
            """{"subject_id":$id,"subject_type":2,"type":3,"ep_status":1,"rate":0,"comment":"",
                "tags":[${tags.joinToString(",") { "\"$it\"" }}],"vol_status":0,"private":false,
               "updated_at":"2026-09-1${id}T10:00:00+08:00",
               "subject":{"id":$id,"type":2,"name":"作品$id","name_cn":"作品$id","short_summary":"",
                 "images":{"large":"l$id.jpg","common":"c$id.jpg","medium":"m$id.jpg","small":"s$id.jpg","grid":"g$id.jpg"},
                 "eps":12,"volumes":0,"collection_total":10,"score":8.0,"rank":0,"tags":[]}}"""
        }}],"total":${entries.size},"limit":50,"offset":0}
    """.trimIndent()

    /** 与 [taggedCollectionsJson] 同构，但每条自带收藏状态（`type`），用于「切收藏状态」相关用例。 */
    private fun typedTaggedCollectionsJson(vararg entries: Pair<Int, Pair<Int, List<String>>>) = """
        {"data":[${entries.joinToString(",") { (id, typed) ->
            val (type, tags) = typed
            """{"subject_id":$id,"subject_type":2,"type":$type,"ep_status":1,"rate":0,"comment":"",
                "tags":[${tags.joinToString(",") { "\"$it\"" }}],"vol_status":0,"private":false,
               "updated_at":"2026-09-1${id}T10:00:00+08:00",
               "subject":{"id":$id,"type":2,"name":"作品$id","name_cn":"作品$id","short_summary":"",
                 "images":{"large":"l$id.jpg","common":"c$id.jpg","medium":"m$id.jpg","small":"s$id.jpg","grid":"g$id.jpg"},
                 "eps":12,"volumes":0,"collection_total":10,"score":8.0,"rank":0,"tags":[]}}"""
        }}],"total":${entries.size},"limit":50,"offset":0}
    """.trimIndent()

    private val entryRequests = ProgressEntryRequests()

    private companion object {
        const val ACCOUNT_ID = 7
        val SERVER_500 = Failure.Http(500)
        val SERVER_502 = Failure.Http(502)
    }
}

/** 选中页的重排次数：界面读的是 `pageEpochs`，这里取选中页那一项。 */
private fun ProgressUiState.selectedEpoch(): Int = pageEpochs[selectedSubjectType]
