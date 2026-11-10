package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.mark.StillCollectionMarker
import io.github.touhougo.chogumi.core.data.progress.CommitGate
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeStatsModuleDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectSectionDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.FaultablePreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.StatsLayoutRepository
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TestPreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testNsfwFilter
import io.github.touhougo.chogumi.core.data.repository.testSubjectRepository
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.model.CastRole
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 进页加载：条目本体与各区块同时发，区块不等本体的网络拍。 */
class SubjectDetailsLoadTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()

    private val main = TestMain()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = TestPreferencesRepository()

    /** 条目本体的网络应答闸门：关着时本体一直在途。 */
    private val subjectGate = CompletableDeferred<Unit>()
    private lateinit var client: HttpClient
    private lateinit var vm: SubjectDetailsViewModel

    @BeforeTest
    fun setUp() { main.install() }

    @AfterTest
    fun tearDown() {
        subjectGate.complete(Unit)
        main.uninstall(listOfNotNull(if (::vm.isInitialized) vm.viewModelScope else null, scope))
        prefs.close()
    }

    @Test
    fun `条目本体还在途时角色已经上屏，带中文名与定位`() = runBlocking<Unit> {
        val json = headersOf("Content-Type", "application/json")
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v0/subjects/8" -> {
                    subjectGate.await()
                    respond(SUBJECT_JSON, HttpStatusCode.OK, json)
                }
                "/p1/subjects/8/characters" -> respond(CHARACTERS_JSON, HttpStatusCode.OK, json)
                else -> respond("""{"data":[],"total":0}""", HttpStatusCode.OK, json)
            }
        }
        client = resources.apiClient(engine)
        val subjectDao = FakeSubjectDao()
        val store = testTokenStore(scope = scope)
        val subRepo = testSubjectRepository(resources, SubjectApi(client), NextApi(client), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
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

        val ready = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.characters is Load.Ready } }

        assertNull(ready.subject, "本体应仍在途：角色不该等它")
        val character = (ready.characters as Load.Ready).value.single()
        assertEquals("韩立", displayNameOf(character))
        assertEquals(CastRole.MAIN, character.role)
        assertEquals("钱文青", character.actors.single().name)

        subjectGate.complete(Unit)
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.subject != null } }
    }

    @Test
    fun `偏好读不出时作品页提示存储失败 条目照常到达 重试读到后功能开关跟上`() = runBlocking<Unit> {
        val json = headersOf("Content-Type", "application/json")
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v0/subjects/8" -> respond(SUBJECT_JSON, HttpStatusCode.OK, json)
                "/p1/subjects/8/characters" -> respond(CHARACTERS_JSON, HttpStatusCode.OK, json)
                else -> respond("""{"data":[],"total":0}""", HttpStatusCode.OK, json)
            }
        }
        client = resources.apiClient(engine)
        val faultyPrefs = FaultablePreferencesRepository(resources)
        faultyPrefs.repository.setExperienceMode(ExperienceMode.FOCUS)
        faultyPrefs.failReads.set(true)
        val subjectDao = FakeSubjectDao()
        val store = testTokenStore(scope = scope)
        val subRepo = testSubjectRepository(resources, SubjectApi(client), NextApi(client), subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val collRepo = testCollections(resources, CollectionApi(client), subjectDao, FakeUserCollectionDao(), store, FakeEpisodeWatchStateDao())
        val orchestrator = collRepo.progressOrchestrator(subRepo)
        withContext(main.dispatcher) {
            vm = SubjectDetailsViewModel(
                8, subRepo, collRepo.store, collRepo.observer, collRepo.watchStates, collRepo.editing(subRepo, orchestrator),
                collRepo.epTapCoordinator(orchestrator, scope, { 0L }, CommitGate(open = true)::await),
                faultyPrefs.repository, testAppTime(System.currentTimeMillis()), StillCollectionMarker(), SavedStateHandle(),
            statsLayouts = StatsLayoutRepository(FakeStatsModuleDao()),
        )
        }

        awaitUntil("存储失败提示") { vm.failures.current.value?.text == NoticeText.Reason(Failure.Storage) }
        val retry = assertNotNull(vm.failures.current.value?.retry, "提示带重试")
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.subject != null } }
        assertTrue(vm.uiState.value.features.community, "读不出时功能开关留默认值")

        faultyPrefs.failReads.set(false)
        withContext(main.dispatcher) { retry() }
        awaitUntil("重试后功能开关到达") { !vm.uiState.value.features.community }
    }

    private companion object {
        const val SUBJECT_JSON = """{"id":8,"type":2,"name":"n","name_cn":"作品","summary":"",
            "images":{"large":"","common":"","medium":"","small":"","grid":""},
            "series":false,"nsfw":false,"locked":false,"platform":"TV","volumes":0,"eps":0,"total_episodes":0,
            "meta_tags":[],"tags":[],"rating":{"rank":1,"total":5,"count":{},"score":8.0},
            "collection":{"wish":0,"collect":0,"doing":0,"on_hold":0,"dropped":0}}"""

        const val CHARACTERS_JSON = """{"data":[{"character":{"id":157795,"name":"Han Li","nameCN":"韩立","role":1,"info":"",
            "comment":0,"lock":false,"nsfw":false},
            "casts":[{"person":{"id":42528,"name":"钱文青","nameCN":"","type":1,"info":"","career":["seiyu"],
              "comment":0,"lock":false,"nsfw":false},"relation":0,"summary":""}],
            "type":1,"order":0}],"total":1}"""
    }
}
