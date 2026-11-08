package io.github.touhougo.chogumi.feature.monodetails

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.mark.StillCollectionEditing
import io.github.touhougo.chogumi.core.data.mark.StillCollectionMarker
import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.FakeCommentRepository
import io.github.touhougo.chogumi.core.data.repository.MonoRepositoryImpl
import io.github.touhougo.chogumi.core.data.repository.NoBlockedUsers
import io.github.touhougo.chogumi.core.data.repository.NsfwFilter
import io.github.touhougo.chogumi.core.data.repository.NsfwPreferenceRepository
import io.github.touhougo.chogumi.core.data.repository.StillUserModeration
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.data.repository.testWebClient
import io.github.touhougo.chogumi.core.data.repository.testWebSessionRepository
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.network.api.MonoApi
import io.github.touhougo.chogumi.core.network.api.PrivacyApi
import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 收藏与详情刷新共用真实接口、序列化、会话和仓库，旧响应在传输层确定延迟。 */
class MonoCollectionRaceTest {
    private val main = TestMain()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val viewModels = mutableListOf<MonoDetailsViewModel>()
    private val clients = mutableListOf<HttpClient>()
    private val engines = mutableListOf<HttpClientEngine>()
    private val directories = mutableListOf<File>()

    @BeforeTest
    fun setUp() = main.install()

    @AfterTest
    fun tearDown() {
        val scopes = viewModels.map { it.viewModelScope } + scope
        cancelAndAwait(scopes)
        clients.forEach { it.close() }
        engines.forEach { it.close() }
        cancelAndAwait(clients.map { CoroutineScope(it.coroutineContext) } + engines.map { CoroutineScope(it.coroutineContext) })
        main.uninstall(scopes)
        directories.forEach { it.deleteRecursively() }
    }

    private class Harness(
        val vm: MonoDetailsViewModel,
        val oldGetEntered: CompletableDeferred<Unit>,
        val releaseOldGet: CompletableDeferred<Unit>,
        val failDelete: AtomicBoolean,
        val requests: ConcurrentRequestRecorder,
    )

    private suspend fun harness(type: MonoType): Harness {
        val dir = File(System.getProperty("java.io.tmpdir"), "mono-race-${System.nanoTime()}").apply { mkdirs(); directories += this }
        fun dataStore(name: String) = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "$name.preferences_pb") }
        val store = TokenStore(dataStore("tokens"))
        store.completeSession(store.beginSession("test-token"), 42, "viewer")
        val getCount = AtomicInteger()
        val collected = AtomicBoolean(false)
        val failDelete = AtomicBoolean(true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            requests.record(request)
            val path = request.url.encodedPath
            if (path.startsWith("/p1/collections/")) {
                if (request.method == HttpMethod.Delete && failDelete.get()) {
                    return@MockEngine respond("{}", HttpStatusCode.BadRequest, headersOf("Content-Type", "application/json"))
                }
                collected.set(request.method == HttpMethod.Put)
                return@MockEngine respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
            val body = when {
                path == "/p1/privacy" -> """{"settings":{"privateMessage":"all","timelineReply":"all","timelineCollectReply":"all","follow":"all","mentionNotification":"all","commentNotification":"all","friendNotification":"all"},"preferences":{"showNsfwSubject":false,"canSetNsfwSubject":true,"allowNsfw":false}}"""
                path.endsWith("/99") -> {
                    val snapshot = collected.get()
                    if (getCount.incrementAndGet() == 2) { entered.complete(Unit); release.await() }
                    detailsJson(type, snapshot)
                }
                else -> """{"data":[],"total":0}"""
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }.also { engines += it }
        val client = testApiClient(engine).also { clients += it }
        val web = testWebClient { error("本用例不走网页补偿") }.also { clients += it; engines += it.engine }
        val sessions = testWebSessionRepository(store, web)
        val nsfw = NsfwPreferenceRepository(dataStore("nsfw"), PrivacyApi(client), store, sessions)
        assertIs<Outcome.Success<*>>(nsfw.refresh())
        val repository = MonoRepositoryImpl(MonoApi(client), store, NsfwFilter(nsfw), sessions)
        val vm = withContext(Dispatchers.Main) {
            MonoDetailsViewModel(type, 99, repository, FakeCommentRepository(scope), StillCollectionMarker(), StillCollectionEditing(), testAppTime(1_000L), StillUserModeration(), NoBlockedUsers())
                .also { viewModels += it }
        }
        withTimeout(WAIT_BUDGET_MS) {
            vm.uiState.first { state ->
                state.details !is Load.Pending &&
                    !state.indexes.isLoading &&
                    listOf(state.works, state.characters, state.relations).none { it is Load.Pending }
            }
        }
        return Harness(vm, entered, release, failDelete, requests)
    }

    private fun detailsJson(type: MonoType, collected: Boolean): String {
        val kind = if (type == MonoType.CHARACTER) "\"role\":1" else "\"type\":1,\"career\":[]"
        return """{"id":99,"name":"Mono","nameCN":"实体",$kind,"infobox":[],"info":"","summary":"","images":null,"comment":0,"collects":${if (collected) 21 else 20},"lock":false,"redirect":0,"nsfw":false,"collectedAt":${if (collected) 1 else "null"}}"""
    }

    private suspend fun MonoDetailsViewModel.actionJobs(action: () -> Unit): List<Job> = withContext(Dispatchers.Main) {
        val owner = viewModelScope.coroutineContext[Job]!!
        val before = owner.children.toSet()
        action()
        owner.children.filter { it !in before }.toList()
    }

    private suspend fun List<Job>.finished() = withTimeout(WAIT_BUDGET_MS) { forEach { it.join() } }

    private suspend fun verify(type: MonoType) {
        val h = harness(type)
        val refresh = h.vm.actionJobs { h.vm.refresh() }
        withTimeout(WAIT_BUDGET_MS) { h.oldGetEntered.await() }
        h.vm.actionJobs { h.vm.toggleCollection() }.finished()
        assertTrue(h.vm.uiState.value.isCollected)
        assertEquals(21, h.vm.uiState.value.collectCount)
        h.releaseOldGet.complete(Unit)
        refresh.finished()
        assertTrue(h.vm.uiState.value.isCollected, "迟到GET不得把成功收藏按钮翻回去")
        assertEquals(21, h.vm.uiState.value.collectCount, "迟到GET不得恢复旧计数")
        h.vm.actionJobs { h.vm.toggleCollection() }.finished()
        assertTrue(h.vm.uiState.value.isCollected, "取消失败回滚到已确认收藏")
        assertEquals(21, h.vm.uiState.value.collectCount)
        h.failDelete.set(false)
        h.vm.actionJobs { h.vm.toggleCollection() }.finished()
        assertFalse(h.vm.uiState.value.isCollected)
        assertEquals(20, h.vm.uiState.value.collectCount)
        assertEquals(listOf(HttpMethod.Put, HttpMethod.Delete, HttpMethod.Delete), h.requests.filter { it.url.encodedPath.startsWith("/p1/collections/") }.map { it.method })
    }

    @Test
    fun `角色旧详情不覆盖收藏且取消失败后下次仍取消`() = runBlocking { verify(MonoType.CHARACTER) }

    @Test
    fun `人物旧详情不覆盖收藏且取消失败后下次仍取消`() = runBlocking { verify(MonoType.PERSON) }
}
