package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.mark.StillCollectionMarker
import io.github.touhougo.chogumi.core.data.progress.CommitGate
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectSectionDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TestPreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testNsfwFilter
import io.github.touhougo.chogumi.core.data.repository.testSubjectRepository
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.github.touhougo.chogumi.core.network.api.WebPageApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** 统计 tab 的数据：系列进页就从本页的关联走出来（关联重试、下拉刷新后重走，内容没变的网络拍不重走），透视页、推荐与最近收藏第一次切到统计 tab 才取。 */
class SubjectDetailsStatsLoadTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()

    private val main = TestMain()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = TestPreferencesRepository()
    private val apiPaths = ConcurrentLinkedQueue<String>()
    private val webPages = ConcurrentLinkedQueue<String>()
    private val relationsDown = AtomicBoolean(false)
    /** 作品 9 的关联：[nineHasSequel] 时续集是 10，[nineDown] 时服务端出错。 */
    private val nineHasSequel = AtomicBoolean(false)
    private val nineDown = AtomicBoolean(false)
    private val ratingSplitDown = AtomicBoolean(false)
    private val recsDown = AtomicBoolean(false)
    private val recentCollectTimesDown = AtomicBoolean(false)
    private val webRequests = AtomicInteger()
    private val requests = ConcurrentRequestRecorder()

    /** 作品 8 关联的网络拍等作品 9 的关联请求发出才答；作品 9 的第一次关联请求等 [nineReleased] 才答。 */
    private val holdRelations = AtomicBoolean(false)
    private val nineAsked = CompletableDeferred<Unit>()
    private val nineReleased = CompletableDeferred<Unit>()
    private val nineRequests = AtomicInteger()
    private lateinit var client: HttpClient
    private lateinit var webClient: HttpClient
    private lateinit var vm: SubjectDetailsViewModel

    @BeforeTest
    fun setUp() { main.install() }

    @AfterTest
    fun tearDown() {
        main.uninstall(listOfNotNull(if (::vm.isInitialized) vm.viewModelScope else null, scope))
        prefs.close()
    }

    @Test
    fun `系列进页就有，统计几项等切到统计 tab 才请求`() = runBlocking<Unit> {
        openPage()

        val withSeries = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.series is Load.Ready } }
        assertEquals(listOf(9), (withSeries.series as Load.Ready).value.after.map { it.id })
        assertEquals(emptyList(), webPages.toList(), "没看统计 tab 不请求透视页")
        assertEquals(emptyList(), apiPaths.filter { it.endsWith("/recs") || it.endsWith("/collects") })

        withContext(main.dispatcher) { vm.selectSection(SubjectDetailsSection.STATS) }
        val stats = withTimeout(WAIT_BUDGET_MS) {
            vm.uiState.first { it.ratingSplit is Load.Ready && it.recs is Load.Ready && it.recentCollectTimes is Load.Ready }
        }
        assertEquals(
            listOf(0, 0, 0, 0, 0, 0, 0, 0, 7203, 0),
            (stats.ratingSplit as Load.Ready).value.byCollection[CollectionType.DONE],
        )
        assertEquals(listOf("https://bgm.tv/subject/8/stats"), webPages.toList())
    }

    @Test
    fun `点选的选项卡记进页面存档 进程回收后重建的页面停在原来的统计tab并取统计`() = runBlocking<Unit> {
        val saved = SavedStateHandle()
        openPage(saved)
        withContext(main.dispatcher) { vm.selectSection(SubjectDetailsSection.STATS) }
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.ratingSplit is Load.Ready } }

        // 进程回收后恢复：同一份存档交给新建的 ViewModel
        cancelAndAwait(listOf(vm.viewModelScope))
        webPages.clear()
        openPage(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        assertEquals(SubjectDetailsSection.STATS, vm.uiState.value.requestedSection)
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.ratingSplit is Load.Ready && it.recs is Load.Ready } }
        assertEquals(listOf("https://bgm.tv/subject/8/stats"), webPages.toList(), "停在统计 tab 时照样取统计")
    }

    @Test
    fun `关联取不到时系列也取不到，关联重试成功后系列跟着走出来`() = runBlocking<Unit> {
        relationsDown.set(true)
        openPage()

        val failed = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.series is Load.Failed } }
        assertTrue(failed.relations is Load.Failed)

        relationsDown.set(false)
        withContext(main.dispatcher) { vm.retryRelated() }
        val recovered = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.series is Load.Ready } }
        assertEquals(listOf(9), (recovered.series as Load.Ready).value.after.map { it.id })
    }

    @Test
    fun `关联的网络拍与过期缓存拍内容相同时，正在走的系列接着走，不取消重来`() = runBlocking<Unit> {
        val sections = FakeSubjectSectionDao()
        staleRelationsIn(sections)
        holdRelations.set(true)
        openPage(sections = sections)

        // 缓存拍走出的这一轮正停在作品 9 的关联上，作品 8 关联的网络拍（内容没变）到了
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { (it.relations as? Load.Ready)?.fromCache == false } }
        // 系列的收集者整轮在 Main 上：排空 Main 之后，要重走的话这时已经取消了在途的那一轮
        withContext(main.dispatcher) {}
        nineReleased.complete(Unit)

        val withSeries = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.series is Load.Ready } }
        assertEquals(listOf(9), (withSeries.series as Load.Ready).value.after.map { it.id })
        assertEquals(1, nineRequests.get(), "正在走的一轮不应取消重来，作品 9 的关联只取一次")
    }

    @Test
    fun `下游关联取不到时系列走短了，下拉刷新时关联没变也重走一次`() = runBlocking<Unit> {
        nineHasSequel.set(true)
        nineDown.set(true)
        openPage()
        val short = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.series is Load.Ready && !it.isRefreshing } }
        assertEquals(listOf(9), (short.series as Load.Ready).value.after.map { it.id }, "作品 9 的关联取不到，系列停在 9")

        nineDown.set(false)
        withContext(main.dispatcher) { vm.refresh() }
        withTimeoutOrNull(WAIT_BUDGET_MS) { vm.uiState.first { (it.series as? Load.Ready)?.value?.after?.size == 2 } }
        assertEquals(listOf(9, 10), (vm.uiState.value.series as Load.Ready).value.after.map { it.id }, "刷新后系列重走，接上作品 10")
    }

    @Test
    fun `三个统计辅助块各自重试只重发自己的请求`() = runBlocking<Unit> {
        ratingSplitDown.set(true)
        recsDown.set(true)
        recentCollectTimesDown.set(true)
        openPage()
        withContext(main.dispatcher) { vm.selectSection(SubjectDetailsSection.STATS) }
        withTimeout(WAIT_BUDGET_MS) {
            vm.uiState.first {
                it.ratingSplit is Load.Failed && it.recs is Load.Failed && it.recentCollectTimes is Load.Failed
            }
        }
        val initialRating = webRequests.get()
        val initialRecs = requestCount("/p1/subjects/8/recs")
        val initialCollects = requestCount("/p1/subjects/8/collects")

        ratingSplitDown.set(false)
        withContext(main.dispatcher) { vm.retryRatingSplit() }
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.ratingSplit is Load.Ready } }
        assertEquals(initialRating + 1, webRequests.get())
        assertEquals(initialRecs, requestCount("/p1/subjects/8/recs"))
        assertEquals(initialCollects, requestCount("/p1/subjects/8/collects"))

        recsDown.set(false)
        withContext(main.dispatcher) { vm.retryRecs() }
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.recs is Load.Ready } }
        assertEquals(initialRating + 1, webRequests.get())
        assertEquals(initialRecs + 1, requestCount("/p1/subjects/8/recs"))
        assertEquals(initialCollects, requestCount("/p1/subjects/8/collects"))

        recentCollectTimesDown.set(false)
        withContext(main.dispatcher) { vm.retryRecentCollectTimes() }
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.recentCollectTimes is Load.Ready } }
        assertEquals(initialRating + 1, webRequests.get())
        assertEquals(initialRecs + 1, requestCount("/p1/subjects/8/recs"))
        assertEquals(initialCollects + 1, requestCount("/p1/subjects/8/collects"))
    }

    private fun requestCount(path: String): Int = requests.count { it.url.encodedPath == path }

    /** 作品 8 的关联已在 [sections] 里、早已过期（取回时刻是 1 毫秒），内容与网络上的一样。 */
    private suspend fun staleRelationsIn(sections: FakeSubjectSectionDao) {
        val seedClient = resources.apiClient { respond(SEQUEL_JSON, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        val seeded = testSubjectRepository(
            resources,
            SubjectApi(seedClient), NextApi(seedClient), FakeSubjectDao(), FakeEpisodeDao(), sections, testNsfwFilter(resources),
            clock = AppClock { 1L },
        ).getRelations(8).last()
        assertTrue(seeded is Load.Ready, "预置缓存应取到：$seeded")
    }

    /** [holdRelations] 下作品 9 的关联：第一次等 [nineReleased]（按真实时间等），等不到按服务端出错答。 */
    private suspend fun nineRelations(): Boolean {
        if (nineRequests.incrementAndGet() > 1) return true
        nineAsked.complete(Unit)
        return withTimeoutOrNull(WAIT_BUDGET_MS) { nineReleased.await() } != null
    }

    private suspend fun openPage(savedState: SavedStateHandle = SavedStateHandle(), sections: FakeSubjectSectionDao = FakeSubjectSectionDao()) {
        val json = headersOf("Content-Type", "application/json")
        client = resources.apiClient(
            MockEngine { request ->
                requests.record(request)
                val path = request.url.encodedPath
                apiPaths += path
                when {
                    path == "/v0/subjects/8" -> respond(SUBJECT_JSON, HttpStatusCode.OK, json)
                    path == "/p1/subjects/8/relations" && relationsDown.get() ->
                        respond("{}", HttpStatusCode.InternalServerError, json)
                    path == "/p1/subjects/8/relations" && request.url.parameters["offprint"] != "true" -> {
                        if (holdRelations.get()) withTimeoutOrNull(WAIT_BUDGET_MS) { nineAsked.await() }
                        respond(SEQUEL_JSON, HttpStatusCode.OK, json)
                    }
                    path == "/p1/subjects/9/relations" && nineDown.get() ->
                        respond("{}", HttpStatusCode.InternalServerError, json)
                    path == "/p1/subjects/9/relations" && request.url.parameters["offprint"] != "true" && nineHasSequel.get() ->
                        respond(SEQUEL_OF_NINE_JSON, HttpStatusCode.OK, json)
                    path == "/p1/subjects/9/relations" && request.url.parameters["offprint"] != "true" && holdRelations.get() ->
                        if (nineRelations()) respond("""{"data":[],"total":0}""", HttpStatusCode.OK, json)
                        else respond("{}", HttpStatusCode.InternalServerError, json)
                    path == "/p1/subjects/8/recs" && recsDown.get() ->
                        respond("{}", HttpStatusCode.InternalServerError, json)
                    path == "/p1/subjects/8/collects" && recentCollectTimesDown.get() ->
                        respond("{}", HttpStatusCode.InternalServerError, json)
                    else -> respond("""{"data":[],"total":0}""", HttpStatusCode.OK, json)
                }
            },
        )
        webClient = resources.apiClient { request ->
            webRequests.incrementAndGet()
            webPages += request.url.toString()
            if (ratingSplitDown.get()) respond("not stats", HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
            else respond(STATS_PAGE, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
        }
        val subjectDao = FakeSubjectDao()
        val store = testTokenStore(scope = scope)
        val subRepo = testSubjectRepository(
            resources,
            SubjectApi(client), NextApi(client), subjectDao, FakeEpisodeDao(), sections, testNsfwFilter(resources),
            webPageApi = WebPageApi(webClient),
        )
        val collRepo = testCollections(resources, CollectionApi(client), subjectDao, FakeUserCollectionDao(), store, FakeEpisodeWatchStateDao())
        val orchestrator = collRepo.progressOrchestrator(subRepo)
        withContext(main.dispatcher) {
            vm = SubjectDetailsViewModel(
                8, subRepo, collRepo.store, collRepo.observer, collRepo.watchStates, collRepo.editing(subRepo, orchestrator),
                collRepo.epTapCoordinator(orchestrator, scope, { 0L }, CommitGate(open = true)::await),
                prefs.repository, testAppTime(System.currentTimeMillis()), StillCollectionMarker(), savedState,
            )
        }
    }

    private companion object {
        const val SUBJECT_JSON = """{"id":8,"type":2,"name":"n","name_cn":"作品","summary":"",
            "images":{"large":"","common":"","medium":"","small":"","grid":""},
            "series":false,"nsfw":false,"locked":false,"platform":"TV","volumes":0,"eps":0,"total_episodes":0,
            "meta_tags":[],"tags":[],"rating":{"rank":1,"total":5,"count":{},"score":8.0},
            "collection":{"wish":0,"collect":0,"doing":0,"on_hold":0,"dropped":0}}"""

        /** 作品 8 的续集是 9（同为动画）。 */
        const val SEQUEL_JSON = """{"data":[{"subject":{"id":9,"name":"s9","nameCN":"","type":2,"info":"","metaTags":[],
            "rating":{"rank":0,"count":[],"score":7.5,"total":0},"locked":false,"nsfw":false},
            "relation":{"id":3,"en":"","cn":"","jp":"","desc":""},"order":0}],"total":1}"""

        /** 作品 9 的续集是 10（同为动画）。 */
        const val SEQUEL_OF_NINE_JSON = """{"data":[{"subject":{"id":10,"name":"s10","nameCN":"","type":2,"info":"","metaTags":[],
            "rating":{"rank":0,"count":[],"score":7.5,"total":0},"locked":false,"nsfw":false},
            "relation":{"id":3,"en":"","cn":"","jp":"","desc":""},"order":0}],"total":1}"""

        const val STATS_PAGE = """<script>var CHART_SETS = {"interest_type":{"data":[{"title":9,"2":7203}]}};</script>"""
    }
}
