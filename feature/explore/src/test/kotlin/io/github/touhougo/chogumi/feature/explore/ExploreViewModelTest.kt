package io.github.touhougo.chogumi.feature.explore

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.db.calendar.CalendarEntity
import io.github.touhougo.chogumi.core.data.db.explore.ExploreShelfDao
import io.github.touhougo.chogumi.core.data.db.mapper.SubjectSnapshot
import io.github.touhougo.chogumi.core.data.db.subject.SubjectDao
import io.github.touhougo.chogumi.core.data.mark.StillCollectionEditing
import io.github.touhougo.chogumi.core.data.mark.StillCollectionMarker
import io.github.touhougo.chogumi.core.data.repository.ExploreLayoutRepository
import io.github.touhougo.chogumi.core.data.repository.MonoRepository
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.model.MonoCharacterRefBo
import io.github.touhougo.chogumi.core.model.MonoDetailsBo
import io.github.touhougo.chogumi.core.model.MonoRelationBo
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.MonoWorkBo
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.PersonCastFilter
import io.github.touhougo.chogumi.core.model.TileDrawing
import io.github.touhougo.chogumi.core.model.TileLogo
import io.github.touhougo.chogumi.core.data.repository.FakeCalendarDao
import io.github.touhougo.chogumi.core.data.repository.FakeRecentSearchDao
import io.github.touhougo.chogumi.core.data.repository.FakeSearchFirstPageDao
import io.github.touhougo.chogumi.core.data.repository.FakeExploreShelfDao
import io.github.touhougo.chogumi.core.data.repository.FaultableExploreShelfDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TestPreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.data.repository.testCalendarRepository
import io.github.touhougo.chogumi.core.data.repository.FakeTagCatalogDao
import io.github.touhougo.chogumi.core.data.repository.testNsfwFilter
import io.github.touhougo.chogumi.core.data.repository.testTagCatalogRepository
import io.github.touhougo.chogumi.core.data.repository.testSearchRepository
import io.github.touhougo.chogumi.core.foundation.BANGUMI_ZONE
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.ExploreShelf
import io.github.touhougo.chogumi.core.model.ExploreTitle
import io.github.touhougo.chogumi.core.model.FixedWorkShelf
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.SearchFilters
import io.github.touhougo.chogumi.core.model.TagFilter
import io.github.touhougo.chogumi.core.model.withWorkShelfSaved
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TRENDING_SHELF_ID
import io.github.touhougo.chogumi.core.model.WorkShelfContent
import io.github.touhougo.chogumi.core.model.WorkShelfStyle
import io.github.touhougo.chogumi.core.model.defaultShelfLayout
import io.github.touhougo.chogumi.core.model.exploreEntriesFor
import io.github.touhougo.chogumi.core.model.exploreShelvesFor
import io.github.touhougo.chogumi.core.model.fixedWorkShelfEntry
import io.github.touhougo.chogumi.core.model.pinned
import io.github.touhougo.chogumi.core.model.pinnedShelfOf
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ExploreViewModel] 的确定性回归。
 *
 * 同步纪律：所有等待都落在**单调**信号上——请求计数只增不减、加载 Job 句柄一经捕获不撤销、
 * 货架内容一经加载不再变回 loading；不使用 `delay` 去碰窗口。
 *
 * 假接口把「条目属于哪个分类」编码进条目 id（[subjectId]）：断言数据归属就是比 id。
 * 搜索请求由测试手里的闸门（[CompletableDeferred]）放行：闸门不放行，对应请求一律挂起。
 *
 * 收尾走 [cancelAndAwait]（装了 `Dispatchers.setMain`），不裸 `cancel()`。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()


    private val main = TestMain()
    private val viewModels = mutableListOf<ExploreViewModel>()


    /** 「长按标题可以整理」提示的记录：真 DataStore，收尾删掉。 */
    private val preferences = TestPreferencesRepository()

    /** 每个分类收到过多少个搜索请求；只增不减，测试据此等「补加载真的又发了一次」。 */
    private val requestCounts = ConcurrentHashMap<SubjectType, AtomicInteger>()

    /** 每日放送收到过多少个网络请求（只有动画会发）。 */
    private val calendarRequests = AtomicInteger()

    /** 被单独挂起的「固定组合」请求数（请求体带 [PIN_MARKER]）。 */
    private val pinRequests = AtomicInteger()
    private val pinCancelled = AtomicInteger()

    /** 被单独挂起的「排行榜」请求数（体为只带 rank、无任何标签/维度/时间的搜索）。 */
    private val rankedRequests = AtomicInteger()

    /** 排行榜请求在闸门里被取消的次数（切分类打断的确定性证据）。 */
    private val rankCancelled = AtomicInteger()

    private val today = LocalDate.of(2026, 10, 1)

    /** 公司标志图的假仓库：每个人物条目回 `logo:<id>`，记下被问过几次。 */
    private val logos = LogoMonoRepository()

    /** 非空时「固定组合」那一类请求都以这个状态码失败。 */
    @Volatile
    private var pinFailure: HttpStatusCode? = null

    @Before
    fun setUp() {
        main.install()
    }

    @After
    fun tearDown() {
        main.uninstall(viewModels.map { it.viewModelScope })

        preferences.close()
        viewModels.clear()

        requestCounts.clear()
        calendarRequests.set(0)
        pinRequests.set(0)
        pinCancelled.set(0)
        rankedRequests.set(0)
        rankCancelled.set(0)
        pinFailure = null
    }

    /** 测试持有的句柄：VM 与它用到的 Fake DAO（断言「迟到结果写到了哪里」要等待生产加载 Job）。 */
    private class Harness(
        val vm: ExploreViewModel,
        val layoutRepo: ExploreLayoutRepository,
        /** 每个条目落库时所在协程的祖先链（id → 由内到外的 Job），只用来找它所属的那一轮加载（[roundOf]）。 */
        val writes: Map<Int, List<Job>>,
        /** 动画首轮里放送表条目落库时所在协程的祖先链。 */
        val initialLoad: CompletableDeferred<List<Job>>,
    ) {
        /**
         * 祖先链 [lineage] 所属的那一轮加载：ViewModel 作用域正下方的那个 Job（一轮货架加载，或一个组合的加载）。
         * 它结束时这一轮的消费与界面写回都已完成；落库所在的协程往上隔几层（`withContext`、`flowOn`）不影响结果。
         * 祖先链在落库当时记下：Job 结束后 `parent` 读出来是 null，事后再往上找会断。
         */
        fun roundOf(lineage: List<Job>): Job = lineage[lineage.indexOf(vm.viewModelScope.coroutineContext.job) - 1]
    }

    /**
     * 组装一套走**生产接线**的 VM。
     *
     * @param gateFor 按分类挂起搜索请求；不放行则该分类的请求一律停在闸门。
     * @param pinGate 命中 [PIN_MARKER] 的请求（「固定组合」那一类）单独走它。
     * @param calendarGate 每日放送请求单独走它。
     * @param rankedGate 指定分类的**排行榜**请求（体为只带 rank 的搜索）单独走它，并回一条
     *   唯一 id（[LATE_RANKING_ID]）的响应，好让测试等到「这个响应已被消费」。
     */
    private fun createViewModel(
        subjectDao: FakeSubjectDao = FakeSubjectDao(),
        calendarDao: FakeCalendarDao = FakeCalendarDao(),
        firstPageDao: FakeSearchFirstPageDao = FakeSearchFirstPageDao(),
        gateFor: (SubjectType) -> CompletableDeferred<Unit>? = { null },
        pinGate: CompletableDeferred<Unit>? = null,
        calendarGate: CompletableDeferred<Unit>? = null,
        rankedGate: Pair<SubjectType, CompletableDeferred<Unit>>? = null,
        searchId: (SubjectType) -> Int = ::subjectId,
        searchOverride: suspend (String, SubjectType) -> Int? = { _, _ -> null },
        now: MutableStateFlow<Long> = MutableStateFlow(today.atStartOfDay(BANGUMI_ZONE).toInstant().toEpochMilli()),
        layoutDao: ExploreShelfDao = FakeExploreShelfDao(),
    ): Harness {
        // 在真实仓库的落库边界捕获所属加载 Job；join 等待包含过滤、发射与 VM 写回的整轮工作。
        val writes = ConcurrentHashMap<Int, List<Job>>()
        val initialLoad = CompletableDeferred<List<Job>>()
        val trackingDao = object : SubjectDao by subjectDao {
            override suspend fun merge(snapshot: SubjectSnapshot) = subjectDao.merge(snapshot).also {
                val lineage = generateSequence(requireNotNull(currentCoroutineContext()[Job])) { job -> job.parent }.toList()
                writes[it.id] = lineage
                if (it.id == FRESH_CALENDAR_ID) initialLoad.complete(lineage)
            }
            override suspend fun mergeAll(snapshots: List<SubjectSnapshot>) = snapshots.map { merge(it) }
        }
        val rankedType = rankedGate?.first
        val rankedDeferred = rankedGate?.second

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            if (path == "/p1/calendar") {
                calendarRequests.incrementAndGet()
                calendarGate?.await()
                respond(content = calendarBody(), status = HttpStatusCode.OK, headers = JSON_HEADERS)
            } else {
                val body = requestBody(request)
                val type = requestType(request)
                    ?: error("假接口只认带类型的搜索请求，收到：$path")
                recordRequest(type)
                when {
                    body.contains(PIN_MARKER) -> {
                        pinRequests.incrementAndGet()
                        try {
                            pinGate?.await()
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            pinCancelled.incrementAndGet()
                            throw cancel
                        }
                        val failing = pinFailure
                        if (failing != null) {
                            respond(content = "{}", status = failing, headers = JSON_HEADERS)
                        } else {
                            respond(content = searchBody(path, type, PIN_SUBJECT_ID), status = HttpStatusCode.OK, headers = JSON_HEADERS)
                        }
                    }
                    rankedType != null && isRankedSearch(body, rankedType.value) -> {
                        rankedRequests.incrementAndGet()
                        try {
                            rankedDeferred!!.await()
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            // 请求在闸门里被取消：记为已取消，测试据此区分「这一轮被中断」。
                            rankCancelled.incrementAndGet()
                            throw cancel
                        }
                        respond(content = searchBody(path, type, LATE_RANKING_ID), status = HttpStatusCode.OK, headers = JSON_HEADERS)
                    }
                    else -> {
                        gateFor(type)?.await()
                        val id = searchOverride(body, type) ?: searchId(type)
                        respond(content = searchBody(path, type, id), status = HttpStatusCode.OK, headers = JSON_HEADERS)
                    }
                }
            }
        }

        val client = resources.apiClient(engine)

        val searchRepo = testSearchRepository(
            resources,
            SubjectApi(client),
            NextApi(client),
            trackingDao,
            FakeRecentSearchDao(),
            testNsfwFilter(resources),
            firstPageDao = firstPageDao,
        )
        val calendarRepo = testCalendarRepository(
            resources,
            NextApi(client),
            calendarDao,
            trackingDao,
            testNsfwFilter(resources),
        )
        val layoutRepo = ExploreLayoutRepository(layoutDao)

        val vm = ExploreViewModel(
            searchRepository = searchRepo,
            calendarRepository = calendarRepo,
            monoRepository = logos,
            layoutRepository = layoutRepo,
            tagCatalogRepository = testTagCatalogRepository(NextApi(client), FakeTagCatalogDao(), testNsfwFilter(resources)),
            preferencesRepository = preferences.repository,
            appTime = testAppTime(now),
            collectionMarker = StillCollectionMarker(),
            collectionEditing = StillCollectionEditing(),
        ).also { viewModels += it }

        return Harness(vm, layoutRepo, writes, initialLoad)
    }

    /** 图钉放上来的那条货架的 id（判等只认组合键，见 [pinnedShelfOf]）。 */
    private suspend fun Harness.pinnedId(criteria: SearchCriteria): String =
        checkNotNull(pinnedShelfOf(SubjectType.ANIME, layoutRepo.observe(SubjectType.ANIME).first(), criteria)) { "$criteria 不在布局里" }.id

    @Test
    fun `布局表读不出时探索页提示存储失败 默认布局照常加载 重试读到后自建货架上屏`() = runBlocking(main.dispatcher) {
        val dao = FaultableExploreShelfDao().apply { failReads.set(true) }
        val harness = createViewModel(layoutDao = dao)
        awaitUntil("存储失败提示") { harness.vm.failures.current.value?.text == NoticeText.Reason(Failure.Storage) }
        val retry = checkNotNull(harness.vm.failures.current.value?.retry) { "提示带重试" }
        awaitInitialLoad(harness)

        // 表能读了、里面多了一条自建货架：页面的订阅已经结束，要用户点重试才重订
        dao.failReads.set(false)
        val type = SubjectType.ANIME
        val criteria = SearchCriteria(subjectType = type, sort = SearchSort.COLLECTS,
            filters = SearchFilters(tags = listOf(TagFilter.Public("ORIGINAL"))))
        harness.layoutRepo.pin(criteria, "自建货架")
        val id = harness.pinnedId(criteria)
        retry()
        awaitUntil("重试后自建货架已加载") { harness.vm.uiState.value.current.shelf(id) is Load.Ready }
    }

    @Test
    fun `自建货架保存新条件后加载新结果`() = runBlocking(main.dispatcher) {
        val harness = createViewModel()
        awaitInitialLoad(harness)
        val type = SubjectType.ANIME
        val original = SearchCriteria(subjectType = type, sort = SearchSort.COLLECTS,
            filters = SearchFilters(tags = listOf(TagFilter.Public("ORIGINAL"))))
        harness.layoutRepo.pin(original, "自建货架")
        val id = harness.pinnedId(original)
        awaitUntil("自建货架已加载") { harness.vm.uiState.value.current.shelf(id) is Load.Ready }
        harness.roundOf(harness.writes.getValue(subjectId(type))).join()

        val edited = original.copy(filters = SearchFilters(tags = listOf(TagFilter.Public(PIN_MARKER))))
        harness.layoutRepo.edit(type) { it.withWorkShelfSaved(type, id, edited, "新条件", WorkShelfStyle.POSTER_ROW) }

        awaitUntil("新条件的作品已显示") {
            harness.vm.uiState.value.current.shelf(id).valueOrNull().orEmpty().any { it.id == PIN_SUBJECT_ID }
        }
        assertEquals(listOf(PIN_SUBJECT_ID), harness.vm.uiState.value.current.shelf(id).valueOrNull()?.map { it.id })
    }

    @Test
    fun `货架请求未完成时保存新条件会取消旧请求`() = runBlocking(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val harness = createViewModel(pinGate = gate)
        awaitInitialLoad(harness)
        val type = SubjectType.ANIME
        val original = SearchCriteria(subjectType = type, sort = SearchSort.COLLECTS,
            filters = SearchFilters(tags = listOf(TagFilter.Public(PIN_MARKER))))
        harness.layoutRepo.pin(original, "自建货架")
        val id = harness.pinnedId(original)
        awaitUntil("旧请求进入闸门") { pinRequests.get() > 0 }

        val edited = original.copy(filters = SearchFilters(tags = listOf(TagFilter.Public("REPLACEMENT"))))
        harness.layoutRepo.edit(type) { it.withWorkShelfSaved(type, id, edited, "新条件", WorkShelfStyle.POSTER_ROW) }
        awaitUntil("新条件的作品已显示") {
            harness.vm.uiState.value.current.shelf(id).valueOrNull().orEmpty().any { it.id == subjectId(type) }
        }
        awaitUntil("旧请求已取消") { pinCancelled.get() > 0 }
        gate.complete(Unit)
        assertEquals(listOf(subjectId(type)), harness.vm.uiState.value.current.shelf(id).valueOrNull()?.map { it.id })
    }

    /** 合集画法的方块货架（动画的「按题材浏览」）：一轮货架加载不取它，出现在屏上才取每一块的作品。 */
    @Test
    fun `方块货架出现在屏上才取每一块的作品`() = runBlocking(main.dispatcher) {
        val harness = createViewModel()
        awaitInitialLoad(harness)
        val themes = harness.vm.uiState.value.layoutOf(SubjectType.ANIME).shelves
            .filterIsInstance<ExploreShelf.Tiles>()
            .first { it.drawing == TileDrawing.STACK }
        assertTrue("一轮货架加载不取方块的作品", harness.vm.uiState.value.current.tileWorks.isEmpty())

        harness.vm.showTiles(SubjectType.ANIME, themes.id, themes.tiles)

        awaitUntil("每一块的作品都取到了") {
            val content = harness.vm.uiState.value.current
            themes.tiles.all { content.tileWorks(themes.id, it) is Load.Ready }
        }
        val content = harness.vm.uiState.value.current
        for (tile in themes.tiles) {
            assertEquals(tile.label.key, listOf(subjectId(SubjectType.ANIME)), content.tileWorks(themes.id, tile).valueOrNull()?.map { it.id })
        }
    }

    /** 刻度尺画法的方块货架（年代）：只取报上来的那一块（尺子停稳的年代），别的年代不发请求。 */
    @Test
    fun `刻度尺只取停稳的那一块年代的作品`() = runBlocking(main.dispatcher) {
        val harness = createViewModel()
        awaitInitialLoad(harness)
        val decades = harness.vm.uiState.value.layoutOf(SubjectType.ANIME).shelves
            .filterIsInstance<ExploreShelf.Tiles>()
            .first { it.drawing == TileDrawing.RULER }
        val before = requestCounts[SubjectType.ANIME]?.get() ?: 0

        harness.vm.showTiles(SubjectType.ANIME, decades.id, listOf(decades.tiles[2]))

        awaitUntil("停稳的那一块取到了") { harness.vm.uiState.value.current.tileWorks(decades.id, decades.tiles[2]) is Load.Ready }
        assertEquals("只发了一个请求", before + 1, requestCounts[SubjectType.ANIME]?.get() ?: 0)
        val content = harness.vm.uiState.value.current
        for (tile in decades.tiles - decades.tiles[2]) {
            assertEquals("${tile.label.key} 没停过，不取", Load.Pending, content.tileWorks(decades.id, tile))
        }
    }

    /** 标志画法的方块货架（动画的「按动画公司浏览」）：出现在屏上才取每家公司的标志图，一家只取一次。 */
    @Test
    fun `公司方块货架出现在屏上才取标志图`() = runBlocking(main.dispatcher) {
        val harness = createViewModel()
        awaitInitialLoad(harness)
        val companies = harness.vm.uiState.value.layoutOf(SubjectType.ANIME).shelves
            .filterIsInstance<ExploreShelf.Tiles>()
            .first { it.drawing == TileDrawing.LOGO }
        assertEquals("一轮货架加载不取标志图", 0, logos.calls.get())

        harness.vm.showTiles(SubjectType.ANIME, companies.id, companies.tiles)
        harness.vm.showTiles(SubjectType.ANIME, companies.id, companies.tiles)

        val personIds = companies.tiles.map { (it.logo as TileLogo.Company).personId }
        awaitUntil("每家公司的标志图都取到了") {
            val content = harness.vm.uiState.value.current
            personIds.all { content.logo(it) is Load.Ready }
        }
        val content = harness.vm.uiState.value.current
        assertEquals(personIds.map { "logo:$it" }, personIds.map { content.logo(it).valueOrNull() })
        harness.vm.showTiles(SubjectType.ANIME, companies.id, companies.tiles)
        assertEquals("同一家公司只取一次，再出现一次也不重取", personIds.size, logos.calls.get())
    }

    @Test
    fun `刷新取代在途的公司标志且迟到结果不能覆盖新结果`() = runBlocking(main.dispatcher) {
        val oldGate = CompletableDeferred<Unit>()
        val oldJob = CompletableDeferred<Job>()
        val calls = AtomicInteger()
        logos.image = { id ->
            if (calls.incrementAndGet() == 1) {
                oldJob.complete(currentCoroutineContext().job)
                withContext(NonCancellable) { oldGate.await() }
                "old:$id"
            } else {
                "new:$id"
            }
        }
        try {
            val harness = createViewModel()
            awaitInitialLoad(harness)
            val vm = harness.vm
            val shelf = vm.uiState.value.layoutOf(SubjectType.ANIME).shelves
                .filterIsInstance<ExploreShelf.Tiles>().first { it.drawing == TileDrawing.LOGO }
            val tile = shelf.tiles.first()
            val personId = (tile.logo as TileLogo.Company).personId
            vm.showTiles(SubjectType.ANIME, shelf.id, listOf(tile))
            awaitUntil("旧标志请求已发出") { calls.get() == 1 }

            vm.refresh()
            awaitUntil("刷新重新请求标志") { calls.get() >= 2 }
            awaitUntil("新标志已显示") { vm.uiState.value.current.logo(personId).valueOrNull() == "new:$personId" }
            oldGate.complete(Unit)
            oldJob.await().join()
            assertEquals("new:$personId", vm.uiState.value.current.logo(personId).valueOrNull())
            val before = calls.get()
            vm.showTiles(SubjectType.ANIME, shelf.id, listOf(tile))
            assertEquals("刷新轮结算后重复露面不重取", before, calls.get())
        } finally {
            oldGate.complete(Unit)
            logos.image = { "logo:$it" }
        }
    }

    @Test
    fun `刷新取代在途的合集方块请求`() = runBlocking(main.dispatcher) {
        assertRefreshReplacesTileRequest(TileDrawing.STACK)
    }

    @Test
    fun `刷新取代在途的年代刻度请求`() = runBlocking(main.dispatcher) {
        assertRefreshReplacesTileRequest(TileDrawing.RULER)
    }

    private suspend fun assertRefreshReplacesTileRequest(drawing: TileDrawing) {
        val oldGate = CompletableDeferred<Unit>()
        val targetBody = AtomicReference<String?>(null)
        val calls = AtomicInteger()
        val armed = AtomicBoolean()
        val harness = createViewModel(searchOverride = { body, _ ->
            if (!armed.get()) {
                null
            } else {
                targetBody.compareAndSet(null, body)
                if (targetBody.get() != body) {
                    null
                } else if (calls.incrementAndGet() == 1) {
                    oldGate.await()
                    4101
                } else {
                    4102
                }
            }
        })
        try {
            awaitInitialLoad(harness)
            val vm = harness.vm
            val shelves = vm.uiState.value.layoutOf(SubjectType.ANIME).shelves
            val shelf = shelves.filterIsInstance<ExploreShelf.Tiles>().first { it.drawing == drawing }
            // 与页面上某条作品货架同条件的方块和那条货架共用一次请求，不会单独发出：挑一块没有同条件货架的
            val shelfCriteria = shelves.filterIsInstance<ExploreShelf.Works>().mapNotNull { (it.content as? WorkShelfContent.Search)?.criteria }
            val tile = shelf.tiles.first { it.criteria !in shelfCriteria }
            armed.set(true)
            vm.showTiles(SubjectType.ANIME, shelf.id, listOf(tile))
            awaitUntil("旧方块请求已发出") { calls.get() == 1 }
            vm.refresh()
            awaitUntil("刷新重新请求方块") { calls.get() >= 2 }
            awaitUntil("新作品已显示") {
                vm.uiState.value.current.tileWorks(shelf.id, tile).valueOrNull()?.any { it.id == 4102 } == true
            }
            oldGate.complete(Unit)
            assertEquals(listOf(4102), vm.uiState.value.current.tileWorks(shelf.id, tile).valueOrNull()?.map { it.id })
            val unseen = shelf.tiles.filter { it != tile && it.criteria !in shelfCriteria }
            assertTrue(unseen.all { vm.uiState.value.current.tileWorks(shelf.id, it) == Load.Pending })
        } finally {
            oldGate.complete(Unit)
        }
    }

    /** 五个分类当天的版面一开始就都在状态里：翻页途中邻页不用等切过去才有入口行与货架表。 */
    @Test
    fun `五个分类当天的版面一开始就都有`() = runBlocking(main.dispatcher) {
        val state = createViewModel().vm.uiState.value
        for (type in ContentDomains) {
            assertEquals(exploreEntriesFor(type, today), state.layoutOf(type).entries)
            assertEquals(exploreShelvesFor(type, today, defaultShelfLayout(type)), state.layoutOf(type).shelves)
        }
    }

    @Test
    fun `货架异步加载条目并写入state`() = runBlocking(main.dispatcher) {
        val vm = createViewModel().vm

        awaitUntil("等待近期热门货架数据写入") {
            val content = vm.uiState.value.current.shelves[TRENDING_SHELF_ID]
            content is Load.Ready && content.value.isNotEmpty()
        }

        val trendingItems = vm.uiState.value.current.shelf(TRENDING_SHELF_ID).valueOrNull().orEmpty()
        assertEquals(1, trendingItems.size)
        assertEquals(subjectId(SubjectType.ANIME), trendingItems.first().id)
    }

    @Test
    fun `固定一组条件后它成了近期热门下面的第一条货架`() = runBlocking(main.dispatcher) {
        val harness = createViewModel()
        val criteria = SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.COLLECTS)

        harness.layoutRepo.pin(criteria, "我的特选")

        val id = harness.pinnedId(criteria)
        awaitUntil("固定的组合进了布局") {
            harness.vm.uiState.value.layoutOf(SubjectType.ANIME).shelves.any { it.id == id }
        }

        val shelf = harness.vm.uiState.value.layoutOf(SubjectType.ANIME).shelves[1]
        assertEquals(
            ExploreShelf.Works(id, ExploreTitle.Named("我的特选"), WorkShelfContent.Search(criteria), WorkShelfStyle.POSTER_ROW),
            shelf,
        )
    }

    /** 版面按存下来的布局排（新钉的在最上面）；没存过的分类用默认布局。 */
    @Test
    fun `存下来的布局就是版面_没存过的分类用默认布局`() {
        val older = SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.COLLECTS)
        val newer = SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.RANK)
        val stored = defaultShelfLayout(SubjectType.ANIME).pinned(SubjectType.ANIME, older, "先固定的").pinned(SubjectType.ANIME, newer, "后固定的")
        val layouts = ExploreLayout.forDay(today, mapOf(SubjectType.ANIME to stored))
        assertEquals(
            exploreShelvesFor(SubjectType.ANIME, today, stored),
            layouts.getValue(SubjectType.ANIME).shelves,
        )
        assertEquals(listOf(TRENDING_SHELF_ID, "custom:2", "custom:1"), layouts.getValue(SubjectType.ANIME).shelves.take(3).map { it.id })
        assertEquals(
            "其他分类不受影响",
            exploreShelvesFor(SubjectType.BOOK, today, defaultShelfLayout(SubjectType.BOOK)),
            layouts.getValue(SubjectType.BOOK).shelves,
        )
    }

    @Test
    fun `下拉刷新重载各货架`() = runBlocking(main.dispatcher) {
        val releaseRefresh = CompletableDeferred<Unit>()
        var refreshing = false
        val harness = createViewModel(
            gateFor = { releaseRefresh.takeIf { refreshing } },
            searchId = { if (refreshing) 1002 else subjectId(it) },
        )
        val vm = harness.vm
        awaitInitialLoad(harness)
        val firstRequests = requestCounts.getValue(SubjectType.ANIME).get()
        val firstCalendarRequests = calendarRequests.get()
        assertEquals(listOf(1001), vm.uiState.value.current.shelf(RANKING_ID).valueOrNull()?.map { it.id })

        refreshing = true
        vm.refresh()
        awaitUntil("全部搜索货架的刷新请求都到达闸门") {
            requestCounts.getValue(SubjectType.ANIME).get() >= firstRequests + remoteIds(SubjectType.ANIME).size
        }
        assertTrue(vm.uiState.value.refreshing)
        assertEquals("刷新在途保留旧作品", listOf(1001), vm.uiState.value.current.shelf(RANKING_ID).valueOrNull()?.map { it.id })
        releaseRefresh.complete(Unit)

        awaitUntil("新版货架数据写入真实 DAO") { harness.writes.containsKey(1002) }
        harness.roundOf(harness.writes.getValue(1002)).join()
        remoteIds(SubjectType.ANIME).forEach { id ->
            assertEquals(id, listOf(1002), vm.uiState.value.current.shelf(id).valueOrNull()?.map { it.id })
        }
        assertEquals(firstRequests + remoteIds(SubjectType.ANIME).size, requestCounts.getValue(SubjectType.ANIME).get())
        assertEquals(firstCalendarRequests + 1, calendarRequests.get())
        assertFalse(vm.uiState.value.refreshing)
    }

    /**
     * 迟到结果不串台：动画整轮结束；只挂起**书籍排行榜**这一个请求；切回动画（仍选动画）；
     * 放行书籍请求，等它这一轮结束（书籍被取消则该请求永不到达，动画不受影响；书籍没被取消
     * 则会写数据，测试等它落进 `subjectDao`）；断言动画排行榜仍属动画；最后切书籍确认正常。
     *
     * 防的是：切回已缓存的动画却不取消书籍的加载，放行后书籍排行榜直接覆盖
     * 当前（动画）的 state，断言在数据归属上失败。
     */
    @Test
    fun `迟到的书籍排行榜不会写进仍选中动画的货架`() = runBlocking(main.dispatcher) {
        val bookRankGate = CompletableDeferred<Unit>()
        val harness = createViewModel(rankedGate = SubjectType.BOOK to bookRankGate)
        val vm = harness.vm

        // 1. 动画整轮结束（网络货架全部加载完），此后动画数据已可命中缓存
        awaitInitialLoad(harness)

        // 2. 切书籍：只挂起书籍排行榜，其余书籍请求正常
        vm.selectType(SubjectType.BOOK)
        awaitUntil("书籍排行榜请求已进入闸门") { rankedRequests.get() > 0 }

        // 3. 切回动画：书籍那批加载此刻被打断（必须取消它）
        vm.selectType(SubjectType.ANIME)

        // 4. 在仍选动画时放行书籍请求
        bookRankGate.complete(Unit)

        // 5. 写入句柄只用来找到精确的生产加载 Job；join 才是消费与 UI 写回完成的屏障。
        // HTTP 在闸门里被取消时不会再发射结果，取消信号证明这条路径不可能继续写回。
        awaitUntil("找到书籍排行榜加载或确认请求已取消") {
            harness.writes.containsKey(LATE_RANKING_ID) || rankCancelled.get() > 0
        }
        harness.writes[LATE_RANKING_ID]?.let { harness.roundOf(it).join() }

        // 6. 动画的排行榜仍然是动画的条目，没有被书籍的迟到结果覆盖
        assertRankingBelongsTo(vm, SubjectType.ANIME)

        // 7. 再切书籍：selectedType 正确，书籍排行榜最终正常
        vm.selectType(SubjectType.BOOK)
        awaitRankingLoaded(vm, SubjectType.BOOK)
        assertRankingBelongsTo(vm, SubjectType.BOOK)
    }

    /** 再开一次探索页（冷启动）：上次取到的货架当场就有，不等网络。 */
    @Test
    fun `冷启动先出上次取到的货架，不等网络`() = runBlocking(main.dispatcher) {
        val subjects = FakeSubjectDao()
        val pages = FakeSearchFirstPageDao()
        awaitRankingLoaded(createViewModel(subjectDao = subjects, firstPageDao = pages).vm, SubjectType.ANIME)

        // 第二个 ViewModel 的搜索请求一律停在闸门：排行榜只能来自上次存下的第一页
        val gate = CompletableDeferred<Unit>()
        try {
            val vm = createViewModel(subjectDao = subjects, firstPageDao = pages, gateFor = { gate }).vm
            awaitRankingLoaded(vm, SubjectType.ANIME)
            assertRankingBelongsTo(vm, SubjectType.ANIME)
        } finally {
            gate.complete(Unit)
        }
    }

    /**
     * 切回一个被打断的分类，没加载完的货架会被补加载完——独立断言，不靠串台那条的超时兜底。
     */
    @Test
    fun `切回一个被打断的分类会补加载没加载完的货架`() = runBlocking(main.dispatcher) {
        val bookGate = CompletableDeferred<Unit>()
        val vm = createViewModel(gateFor = { if (it == SubjectType.BOOK) bookGate else null }).vm

        awaitRankingLoaded(vm, SubjectType.ANIME)

        // 书籍的请求全部挂起，只发出、不返回
        vm.selectType(SubjectType.BOOK)
        awaitUntil("书籍的请求已发出") { bookRequestCount() > 0 }
        val issuedBefore = bookRequestCount()

        // 切走再切回：被取消的那批必须重新发起
        vm.selectType(SubjectType.ANIME)
        vm.selectType(SubjectType.BOOK)
        awaitUntil("被打断的书籍货架重新发起加载") { bookRequestCount() > issuedBefore }

        // 放行后，书籍所有走网络的货架都要加载完，一个都不许停在骨架
        bookGate.complete(Unit)
        awaitRemoteShelvesLoaded(vm, SubjectType.BOOK)
    }

    /**
     * 图钉放上来的货架的迟到结果：动画的组合搜索挂起 → 切书籍 → 放行动画组合并确认该轮结束
     * → 书籍的组合与货架不被覆盖 → 切回动画能看到动画的组合结果。
     *
     * 这条覆盖 [#ExploreViewModel] 里**在 VM 作用域继续运行、切分类不取消**的固定组合加载——
     * 它是真实存在的一类迟到结果来源。结果若写进「当前 state」，就会污染书籍。
     */
    @Test
    fun `固定组合的迟到结果只写回它自己的分类`() = runBlocking(main.dispatcher) {
        val pinGate = CompletableDeferred<Unit>()
        val harness = createViewModel(pinGate = pinGate)
        val vm = harness.vm

        // 动画下固定一组带唯一关键词的组合：请求体带 PIN_MARKER，只有它走闸门
        val criteria = SearchCriteria(query = PIN_MARKER, subjectType = SubjectType.ANIME, sort = SearchSort.COLLECTS)
        harness.layoutRepo.pin(criteria, "迟到组合")
        awaitUntil("动画组合请求已进入闸门") { pinRequests.get() > 0 }

        // 先让动画整轮就绪，再切书籍（书籍此时有自己的货架）
        awaitInitialLoad(harness)
        vm.selectType(SubjectType.BOOK)
        awaitRankingLoaded(vm, SubjectType.BOOK)

        // 在仍选书籍时放行动画组合；该请求在 VM 作用域运行，不会被切分类取消
        pinGate.complete(Unit)
        awaitUntil("找到动画组合所属加载") { harness.writes.containsKey(PIN_SUBJECT_ID) }
        harness.roundOf(harness.writes.getValue(PIN_SUBJECT_ID)).join()

        val id = harness.pinnedId(criteria)
        // 书籍的货架表里没有动画固定的组合，也没被它的迟到结果写进去
        assertFalse(
            "书籍的货架被动画组合的迟到结果污染：${vm.uiState.value.current.shelves.keys}",
            vm.uiState.value.current.shelves.containsKey(id),
        )
        // 书籍的货架也没被覆盖（排行榜仍是书籍的条目）
        assertRankingBelongsTo(vm, SubjectType.BOOK)

        // 切回动画能看到动画的组合结果
        vm.selectType(SubjectType.ANIME)
        awaitUntil("动画的组合结果可见") {
            val content = vm.uiState.value.current.shelves[id]
            content is Load.Ready && content.value.any { it.id == PIN_SUBJECT_ID }
        }
    }

    /** 固定组合首轮失败后下拉刷新：组合跟着其他货架一起重取，回来就显示。 */
    @Test
    fun `固定组合失败后下拉刷新会重取`() = runBlocking(main.dispatcher) {
        pinFailure = HttpStatusCode.InternalServerError
        val harness = createViewModel()
        val vm = harness.vm
        val criteria = SearchCriteria(query = PIN_MARKER, subjectType = SubjectType.ANIME, sort = SearchSort.COLLECTS)
        harness.layoutRepo.pin(criteria, "断网时固定的组合")
        val id = harness.pinnedId(criteria)
        awaitUntil("组合首轮失败") { vm.uiState.value.current.shelves[id] is Load.Failed }

        pinFailure = null
        vm.refresh()
        awaitUntil("刷新后组合就绪") {
            val content = vm.uiState.value.current.shelves[id]
            content is Load.Ready && content.value.any { it.id == PIN_SUBJECT_ID }
        }
    }

    /** 一轮里两个固定组合都失败：只发一条提示，带「重试」（重试就是下拉刷新）。 */
    @Test
    fun `两个固定组合同一轮失败只提示一次且带重试`() = runBlocking(main.dispatcher) {
        val harness = createViewModel()
        val vm = harness.vm
        val first = SearchCriteria(query = PIN_MARKER, subjectType = SubjectType.ANIME, sort = SearchSort.COLLECTS)
        val second = SearchCriteria(query = PIN_MARKER, subjectType = SubjectType.ANIME, sort = SearchSort.RANK)
        harness.layoutRepo.pin(first, "组合一")
        harness.layoutRepo.pin(second, "组合二")
        val ids = listOf(first, second).map { harness.pinnedId(it) }
        awaitUntil("两个组合都就绪") {
            ids.all { vm.uiState.value.current.shelves[it] is Load.Ready }
        }
        awaitUntil("首轮货架落定") { !vm.uiState.value.refreshing }

        pinFailure = HttpStatusCode.InternalServerError
        // 提示只留最新一条且相等的值会合并，数发射数会漏掉「发了两次」。收集者在 Unconfined 上、每收到一条就消费掉，
        // 每次发布都在它的下一次收集之前被看见：发两次就数两次
        val posted = AtomicInteger()
        val offeredRetry = AtomicInteger()
        val watching = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            vm.failures.current.collect { notice ->
                if (notice != null) {
                    posted.incrementAndGet()
                    if (notice.retry != null) offeredRetry.incrementAndGet()
                    vm.failures.consume(notice)
                }
            }
        }
        val before = pinRequests.get()
        vm.refresh()
        awaitUntil("两个组合都重发了") { pinRequests.get() >= before + 2 }
        awaitUntil("这一轮刷新结束") { !vm.uiState.value.refreshing && posted.get() > 0 }
        watching.cancelAndJoin()

        assertEquals("一轮只提示一次", 1, posted.get())
        assertEquals("提示带重试", 1, offeredRetry.get())
    }

    /**
     * 过期日历缓存：显示缓存后切走，本轮刷新被中断；切回动画必须**重新发起**尚未完成的日历刷新，
     * 放行后显示新数据。若把缓存首拍当整轮完成，切回时就不再刷新。
     */
    @Test
    fun `过期日历缓存的刷新被中断后切回会续载`() = runBlocking(main.dispatcher) {
        val calendarGate = CompletableDeferred<Unit>()
        val harness = createViewModel(
            calendarDao = staleCalendarDao(),
            calendarGate = calendarGate,
        )
        val vm = harness.vm

        // 动画先回显过期缓存（只发、不返回网络），本轮刷新被闸门挂住
        awaitUntil("动画回显了过期的日历缓存") {
            vm.uiState.value.current.todayOnAir.valueOrNull().orEmpty().any { it.subject.id == STALE_CALENDAR_ID }
        }
        awaitUntil("日历刷新请求已进入闸门") { calendarRequests.get() >= 1 }
        val issuedBefore = calendarRequests.get()

        // 切书籍再切回动画
        vm.selectType(SubjectType.BOOK)
        awaitRankingLoaded(vm, SubjectType.BOOK)
        vm.selectType(SubjectType.ANIME)

        // 中断的日历刷新必须重新发起（记成已完成的话，永远不再发）
        awaitUntil("切回动画后重新发起尚未完成的日历刷新") { calendarRequests.get() > issuedBefore }

        // 放行后显示新数据
        calendarGate.complete(Unit)
        awaitUntil("日历显示放行后的新数据") {
            vm.uiState.value.current.todayOnAir.valueOrNull().orEmpty().any { it.subject.id == FRESH_CALENDAR_ID }
        }
    }

    /**
     * 跨北京时间零点：今日放送的列表、副标题用的日子、入口与货架表在同一份状态里一起换到新的一天，
     * 不会副标题已是新的星期、列表还是昨天的。
     */
    @Test
    fun `跨零点时今日放送与货架表一起换到新的一天`() = runBlocking(main.dispatcher) {
        val now = MutableStateFlow(today.atStartOfDay(BANGUMI_ZONE).toInstant().toEpochMilli())
        val vm = createViewModel(now = now).vm
        fun onAirIds() = vm.uiState.value.current.todayOnAir.valueOrNull().orEmpty().map { it.subject.id }
        awaitUntil("今天（周四）的放送已加载") { DAY_CALENDAR_ID_BASE + 4 in onAirIds() }

        val tomorrow = today.plusDays(1)
        now.value = tomorrow.atStartOfDay(BANGUMI_ZONE).toInstant().toEpochMilli()

        awaitUntil("换成明天（周五）的放送") { DAY_CALENDAR_ID_BASE + 5 in onAirIds() }
        val state = vm.uiState.value
        assertEquals("副标题的星期与列表同一个日子", tomorrow, state.today)
        assertTrue("昨天的放送不留在列表里", DAY_CALENDAR_ID_BASE + 4 !in onAirIds())
        assertEquals(exploreEntriesFor(SubjectType.ANIME, tomorrow), state.layoutOf(SubjectType.ANIME).entries)
        assertEquals(
            exploreShelvesFor(SubjectType.ANIME, tomorrow, defaultShelfLayout(SubjectType.ANIME)),
            state.layoutOf(SubjectType.ANIME).shelves,
        )
    }

    /** 无缓存：日历正常走网络并结算；切走再切回不再重复请求（说明这一轮确实结算了）。 */
    @Test
    fun `无日历缓存时结算后切回不重复请求`() = runBlocking(main.dispatcher) {
        val vm = createViewModel().vm

        awaitUntil("日历从网络加载完成") {
            vm.uiState.value.current.todayOnAir.valueOrNull().orEmpty().any { it.subject.id == FRESH_CALENDAR_ID }
        }
        assertEquals(1, calendarRequests.get())

        vm.selectType(SubjectType.BOOK)
        awaitRankingLoaded(vm, SubjectType.BOOK)
        vm.selectType(SubjectType.ANIME)
        awaitRemoteShelvesLoaded(vm, SubjectType.ANIME)

        assertEquals("已结算的日历轮不应因切回而重发", 1, calendarRequests.get())
    }

    /** 新鲜缓存：直接回显、不发网络，且这一轮自然结束，切回也不重复请求。 */
    @Test
    fun `新鲜日历缓存自然结束且不发网络`() = runBlocking(main.dispatcher) {
        val harness = createViewModel(calendarDao = freshCalendarDao())
        val vm = harness.vm

        awaitUntil("新鲜缓存直接回显") {
            vm.uiState.value.current.todayOnAir.valueOrNull().orEmpty().any { it.subject.id == FRESH_CALENDAR_ID }
        }
        assertEquals("新鲜缓存不应触发网络", 0, calendarRequests.get())

        vm.selectType(SubjectType.BOOK)
        awaitRankingLoaded(vm, SubjectType.BOOK)
        vm.selectType(SubjectType.ANIME)
        awaitRemoteShelvesLoaded(vm, SubjectType.ANIME)

        assertEquals("新鲜缓存已结算，切回不应重发", 0, calendarRequests.get())
    }

    /** 下拉刷新只刷新当前分类：当前分类的日历会重发一次。 */
    @Test
    fun `下拉刷新重发当前分类的日历`() = runBlocking(main.dispatcher) {
        val vm = createViewModel().vm

        awaitUntil("首次日历加载完成") {
            vm.uiState.value.current.todayOnAir.valueOrNull().orEmpty().any { it.subject.id == FRESH_CALENDAR_ID }
        }
        assertEquals(1, calendarRequests.get())

        vm.refresh()

        awaitUntil("下拉刷新让当前分类的日历重发") { calendarRequests.get() >= 2 }
    }

    /** 从日历落库回调精确找到初始货架轮的父 Job，等待日历及所有海报和缓存结算结束。 */
    private suspend fun awaitInitialLoad(harness: Harness) {
        harness.roundOf(harness.initialLoad.await()).join()
    }

    /** 等 [type] 的排行榜加载完（有内容、不在 loading），且它正被选中。 */
    private suspend fun awaitRankingLoaded(vm: ExploreViewModel, type: SubjectType) {
        awaitUntil("$type 的排行榜加载完成") {
            val state = vm.uiState.value
            val content = state.current.shelves[RANKING_ID]
            state.selectedType == type && content is Load.Ready && content.value.isNotEmpty()
        }
    }

    /** 等 [type] 的全部「走搜索」的货架（近期热门、排行榜、预设货架……）都加载完。 */
    private suspend fun awaitRemoteShelvesLoaded(vm: ExploreViewModel, type: SubjectType) {
        val ids = remoteIds(type)
        awaitUntil("$type 的全部网络货架加载完成") {
            val state = vm.uiState.value
            state.selectedType == type && ids.all { id ->
                val content = state.current.shelves[id]
                content is Load.Ready && content.value.isNotEmpty()
            }
        }
    }

    /** 断言 [type] 的排行榜里的条目全部属于 [type]。 */
    private fun assertRankingBelongsTo(vm: ExploreViewModel, type: SubjectType) {
        assertEquals(type, vm.uiState.value.selectedType)
        val items = vm.uiState.value.current.shelves[RANKING_ID]?.valueOrNull().orEmpty()
        assertTrue("$type 的排行榜为空", items.isNotEmpty())
        val expected = if (type == SubjectType.BOOK) setOf(subjectId(type), LATE_RANKING_ID) else setOf(subjectId(type))
        assertTrue(
            "$type 的排行榜混进了别类条目：${items.map { it.id }}",
            items.all { it.id in expected },
        )
    }

    private fun remoteIds(type: SubjectType): List<String> =
        exploreShelvesFor(type, today, defaultShelfLayout(type))
            .filter { it is ExploreShelf.Trending || (it is ExploreShelf.Works && it.content is WorkShelfContent.Search) }
            .map { it.id }

    private fun bookRequestCount(): Int = requestCounts[SubjectType.BOOK]?.get() ?: 0

    private fun recordRequest(type: SubjectType) {
        requestCounts.computeIfAbsent(type) { AtomicInteger() }.incrementAndGet()
    }

    /** 过期（半天 TTL 之外）的日历缓存：所有星期各一条旧行，任一「今天」都能回显。 */
    private fun staleCalendarDao() = FakeCalendarDao().apply {
        store += calendarRows(STALE_CALENDAR_ID, updatedAt = 1L)
    }

    private fun freshCalendarDao() = FakeCalendarDao().apply {
        store += calendarRows(FRESH_CALENDAR_ID, updatedAt = System.currentTimeMillis())
    }

    private fun calendarRows(subjectId: Int, updatedAt: Long): List<CalendarEntity> =
        (1..7).map { day ->
            CalendarEntity(
                weekday = day,
                subjectId = subjectId,
                name = "日历$subjectId",
                nameCn = "日历$subjectId",
                watchers = 10,
                updatedAt = updatedAt,
                nsfw = false,
            )
        }

    private suspend fun requestBody(request: HttpRequestData): String =
        if (request.method != HttpMethod.Post) "" else runCatching { request.body.toByteArray().decodeToString() }.getOrDefault("")

    /** 请求属于哪个分类：p1 浏览看查询参数 `type`，两个搜索接口看请求体 `filter.type`。 */
    private suspend fun requestType(request: HttpRequestData): SubjectType? {
        request.url.parameters["type"]?.toIntOrNull()?.let { return SubjectType.from(it) }
        val body = requestBody(request)
        if (body.isEmpty()) return null
        val value = Regex("\"type\"\\s*:\\s*\\[(\\d+)\\]").find(body)?.groupValues?.get(1)?.toIntOrNull()
        return value?.let { SubjectType.from(it) }
    }

    /**
     * 请求体是不是「只有排名、没有任何标签/维度/时间」的搜索——即排行榜货架的那一条（对某分类唯一）。
     * 用来在假接口里单独放行/标注某个分类的排行榜，而不动其它货架。
     */
    private fun isRankedSearch(body: String, typeValue: Int): Boolean {
        val filter = runCatching { Json.parseToJsonElement(body).jsonObject["filter"]?.jsonObject }.getOrNull() ?: return false
        val types = filter["type"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        // 两个搜索接口的字段名：p1 是 tags / metaTags / date，v0 是 tag / meta_tags / air_date
        return types == listOf(typeValue) &&
            filter.containsKey("rank") &&
            listOf("tags", "metaTags", "date", "tag", "meta_tags", "air_date").none(filter::containsKey)
    }

    /** 每个分类一个固定 id：条目 id 就能说明它属于哪个分类。 */
    private fun subjectId(type: SubjectType): Int = when (type) {
        SubjectType.ANIME -> 1001
        SubjectType.BOOK -> 2001
        SubjectType.MUSIC -> 3001
        SubjectType.GAME -> 4001
        SubjectType.REAL -> 6001
        SubjectType.UNKNOWN -> 0
    }

    /** p1 浏览与 p1 搜索都回短行（浏览的 total 是页数、搜索的是条数，这里都是 1）；v0 搜索回完整条目。 */
    private fun searchBody(path: String, type: SubjectType, id: Int = subjectId(type)): String = if (path.startsWith("/p1/")) {
        """{"data":[${p1SubjectJson(id, type)}],"total":1}"""
    } else {
        """{"data":[${v0SubjectJson(id, type)}],"total":1,"limit":20,"offset":0}"""
    }

    /** 每日放送的假响应：1~7 星期都放同一条，无论真实「今天」是星期几都能回显。 */
    /** 每天一条 [FRESH_CALENDAR_ID]，外加一条只在那一天放送的 [DAY_CALENDAR_ID_BASE] + 星期。 */
    private fun calendarBody(): String =
        (1..7).joinToString(prefix = "{", postfix = "}") { day ->
            """"$day":[{"subject":${p1SubjectJson(FRESH_CALENDAR_ID, SubjectType.ANIME)},"watchers":50},""" +
                """{"subject":${p1SubjectJson(DAY_CALENDAR_ID_BASE + day, SubjectType.ANIME)},"watchers":10}]"""
        }

    private fun p1SubjectJson(id: Int, type: SubjectType): String =
        """{"id":$id,"name":"T${type.name}","nameCN":"条目$id","type":${type.value},"info":"","metaTags":[],"rating":{"rank":1,"count":[],"score":8.5,"total":10},"locked":false,"nsfw":false,"images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"}}"""

    private fun v0SubjectJson(id: Int, type: SubjectType): String =
        """{"id":$id,"type":${type.value},"name":"T${type.name}","name_cn":"条目$id","summary":"s","images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"},"series":false,"nsfw":false,"locked":false,"platform":"TV","volumes":0,"eps":0,"total_episodes":0,"meta_tags":[],"tags":[],"rating":{"rank":1,"total":10,"count":{},"score":8.5},"collection":{"wish":0,"collect":100,"doing":0,"on_hold":0,"dropped":0}}"""

    /** 只回标志图的假人物仓库：别的接口探索页用不到，碰到就是接错了。 */
    private class LogoMonoRepository : MonoRepository {
        val calls = AtomicInteger()
        var image: suspend (Int) -> String = { "logo:$it" }
        override suspend fun getPersonImage(id: Int): String {
            calls.incrementAndGet()
            return image(id)
        }
        override suspend fun getDetails(type: MonoType, id: Int): MonoDetailsBo = error("探索页不取人物详情")
        override suspend fun getAllWorks(type: MonoType, id: Int, refresh: Boolean): List<MonoWorkBo> = error("探索页不取作品")
        override suspend fun getPersonCharacters(id: Int, limit: Int, offset: Int, filter: PersonCastFilter): PagedResult<MonoCharacterRefBo> =
            error("探索页不取角色")
        override suspend fun getRelations(type: MonoType, id: Int, limit: Int, offset: Int): PagedResult<MonoRelationBo> = error("探索页不取关联")
        override suspend fun getIndexes(type: MonoType, id: Int, limit: Int, offset: Int): PagedResult<IndexBo> = error("探索页不取目录")
        override suspend fun getComments(type: MonoType, id: Int): List<CommentThreadBo> = error("探索页不取吐槽")
        override suspend fun setCollected(type: MonoType, id: Int, collected: Boolean): Outcome<Unit> = error("探索页不收藏人物")
    }

    private companion object {
        val JSON_HEADERS = headersOf("Content-Type", ContentType.Application.Json.toString())

        /** 「固定组合」迟到结果用的唯一关键词：只有它进 pin 闸门。 */
        const val PIN_MARKER = "PINMARK"

        /** 固定组合响应里那条条目的 id（用于捕获所属加载 Job）。 */
        const val PIN_SUBJECT_ID = 7777

        /** 被单独放行的书籍排行榜响应的唯一 id。 */
        const val LATE_RANKING_ID = 2999

        /** 过期日历缓存里那条条目的 id。 */
        const val STALE_CALENDAR_ID = 111

        /** 网络/新鲜日历响应里那条条目的 id。 */
        const val FRESH_CALENDAR_ID = 222

        /** 只在某一天放送的条目：id = 它 + 星期（1=周一…7=周日）。 */
        const val DAY_CALENDAR_ID_BASE = 3000

        /** 排行榜货架的 id。 */
        val RANKING_ID = fixedWorkShelfEntry(FixedWorkShelf.RANKING).id
    }
}
