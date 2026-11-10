package io.github.touhougo.chogumi.feature.subjectdetails

import io.github.touhougo.chogumi.core.data.repository.StatsLayoutRepository
import io.github.touhougo.chogumi.core.data.repository.FakeStatsModuleDao

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.mark.StillCollectionMarker
import io.github.touhougo.chogumi.core.data.progress.CommitGate
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectSectionDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TestPreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testNsfwFilter
import io.github.touhougo.chogumi.core.data.repository.testSubjectRepository
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** 下拉刷新：章节列表强制重取；章节在屏上时逐集状态取不到要提示。 */
class SubjectDetailsRefreshTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()

    private val main = TestMain()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = TestPreferencesRepository()
    private lateinit var client: HttpClient
    private lateinit var vm: SubjectDetailsViewModel

    @Volatile private var watchStatesFail = false
    private val episodeRequests = AtomicInteger()

    @BeforeTest
    fun setUp() { main.install() }

    @AfterTest
    fun tearDown() {
        main.uninstall(listOfNotNull(if (::vm.isInitialized) vm.viewModelScope else null, scope))
        prefs.close()
    }

    @Test
    fun `下拉刷新重取章节列表`() = runBlocking<Unit> {
        open()
        awaitUntil("章节首轮到了") { vm.uiState.value.episodes is Load.Ready }
        val before = episodeRequests.get()

        withContext(main.dispatcher) { vm.refresh() }
        awaitUntil("刷新后又请求了章节") { episodeRequests.get() > before }
    }

    @Test
    fun `章节在屏上时逐集状态刷新失败 发一条带重试的提示`() = runBlocking<Unit> {
        open()
        awaitUntil("章节首轮到了") { vm.uiState.value.episodes is Load.Ready }
        awaitUntil("刷新指示器落下") { !vm.uiState.value.isRefreshing && vm.uiState.value.subject != null }
        assertNull(vm.failures.current.value)

        watchStatesFail = true
        withContext(main.dispatcher) { vm.refresh() }
        awaitUntil("失败提示发出") { vm.failures.current.value != null }

        val notice = assertNotNull(vm.failures.current.value)
        assertEquals(NoticeText.Reason(Failure.Http(500)), notice.text)
        assertNotNull(notice.retry, "提示带重试")
    }

    private suspend fun open() {
        val json = headersOf("Content-Type", "application/json")
        val handler: MockRequestHandler = { request ->
            when (request.url.encodedPath) {
                "/v0/subjects/8" -> respond(SUBJECT_JSON, HttpStatusCode.OK, json)
                "/v0/episodes" -> {
                    episodeRequests.incrementAndGet()
                    respond(EPISODES_JSON, HttpStatusCode.OK, json)
                }
                "/v0/users/-/collections/8/episodes" ->
                    if (watchStatesFail) respond("{}", HttpStatusCode.InternalServerError)
                    else respond(WATCH_STATES_JSON, HttpStatusCode.OK, json)
                else -> respond("""{"data":[],"total":0}""", HttpStatusCode.OK, json)
            }
        }
        client = resources.apiClient(MockEngine(handler))
        val subjectDao = FakeSubjectDao()
        val store = testTokenStore(scope = scope)
        store.completeSession(store.beginSession("test-token"), 1, "fixture_user")
        val subRepo = testSubjectRepository(
            resources,
            SubjectApi(client), NextApi(client), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources), tokenStore = store,
        )
        val collRepo = testCollections(resources, CollectionApi(client), subjectDao, FakeUserCollectionDao(), store, FakeEpisodeWatchStateDao())
        val orchestrator = collRepo.progressOrchestrator(subRepo)
        withContext(main.dispatcher) {
            vm = SubjectDetailsViewModel(
                8, subRepo, collRepo.store, collRepo.observer, collRepo.watchStates, collRepo.editing(subRepo, orchestrator),
                collRepo.epTapCoordinator(orchestrator, scope, { 0L }, CommitGate(open = true)::await),
                prefs.repository, testAppTime(System.currentTimeMillis()), StillCollectionMarker(), SavedStateHandle(),
            statsLayouts = StatsLayoutRepository(FakeStatsModuleDao()),
        )
        }
    }

    private companion object {
        const val SUBJECT_JSON = """{"id":8,"type":2,"name":"n","name_cn":"作品","summary":"",
            "images":{"large":"","common":"","medium":"","small":"","grid":""},
            "series":false,"nsfw":false,"locked":false,"platform":"TV","volumes":0,"eps":1,"total_episodes":1,
            "meta_tags":[],"tags":[],"rating":{"rank":1,"total":5,"count":{},"score":8.0},
            "collection":{"wish":0,"collect":0,"doing":0,"on_hold":0,"dropped":0}}"""

        const val EPISODE = """{"id":101,"type":0,"sort":1.0,"ep":1,"name":"","name_cn":"","duration":"","airdate":"2020-01-01",
            "comment":0,"desc":"","disc":0,"subject_id":8}"""

        const val EPISODES_JSON = """{"data":[$EPISODE],"total":1,"limit":100,"offset":0}"""

        const val WATCH_STATES_JSON = """{"data":[{"episode":$EPISODE,"type":2,"updated_at":1000}],"total":1,"limit":100,"offset":0}"""
    }
}
