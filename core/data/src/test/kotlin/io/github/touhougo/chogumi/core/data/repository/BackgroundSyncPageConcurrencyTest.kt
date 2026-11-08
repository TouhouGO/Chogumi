package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withTimeout

class BackgroundSyncPageConcurrencyTest {
    @get:org.junit.Rule
    val resources = TestResources()

    @Test
    fun `同步逐集记录的所有页共用并发预算`() = runBlocking {
        val gate = ConcurrencyGate()
        val offsets = ConcurrentLinkedQueue<Int>()
        val client = resources.apiClient(MockEngine { request ->
            val offset = request.url.parameters["offset"]?.toInt() ?: 0
            val body = if (request.url.encodedPath.endsWith("/episodes")) {
                offsets.add(offset)
                if (offset > 0) gate.enter()
                """{"data":[{"episode":{"id":${offset + 1},"type":0,"name":"","name_cn":"","sort":${offset + 1},"airdate":"2024-01-01","comment":0,"duration":"","desc":"","disc":0},"type":2,"updated_at":1}],"total":600,"limit":100,"offset":$offset}"""
            } else if (request.url.parameters["subject_type"] == "2") {
                """{"data":[{"subject_id":8,"subject_type":2,"type":3,"rate":0,"comment":"","tags":[],"ep_status":1,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}],"total":1,"limit":50,"offset":0}"""
            } else """{"data":[],"total":0,"limit":50,"offset":0}"""
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        })
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(),
            testTokenStore(resources, userId = 7), FakeEpisodeWatchStateDao())
        val job = async(Dispatchers.Default) { repo.sync.refreshAllUserCollections(force = true) }
        gate.releaseAfterBudget(4)
        assertIs<Outcome.Success<Unit>>(withTimeout(WAIT_BUDGET_MS) { job.await() })
        assertEquals((0..500 step 100).toList(), offsets.sorted())
        assertTrue(gate.peak <= 4, "逐集分页峰值应不超过 4，实际为 ${gate.peak}")
    }

    @Test
    fun `同步阵容角色的所有页共用并发预算`() = runBlocking {
        val gate = ConcurrencyGate()
        val offsets = ConcurrentLinkedQueue<Int>()
        val client = resources.apiClient(MockEngine { request ->
            val offset = request.url.parameters["offset"]?.toInt() ?: 0
            val body = when {
                request.url.encodedPath.endsWith("/characters") -> {
                    offsets.add(offset)
                    if (offset > 0) gate.enter()
                    """{"data":[{"character":{"id":${offset + 1},"name":"c","nameCN":"","role":1,"info":"","comment":0,"lock":false,"nsfw":false},"casts":[],"type":2,"order":0}],"total":600}"""
                }
                request.url.encodedPath.endsWith("/persons") -> "[]"
                request.url.parameters["subject_type"] == "2" ->
                    """{"data":[{"subject_id":8,"subject_type":2,"type":3,"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}],"total":1,"limit":50,"offset":0}"""
                else -> """{"data":[],"total":0,"limit":50,"offset":0}"""
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        })
        val token = testTokenStore(resources, userId = 7)
        val subjects = testSubjectRepository(resources, SubjectApi(client), NextApi(client), FakeSubjectDao(), FakeEpisodeDao(),
            FakeSubjectSectionDao(), testNsfwFilter(resources), tokenStore = token)
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), token,
            FakeEpisodeWatchStateDao(), lineupSource = subjects)
        val job = async(Dispatchers.Default) { repo.sync.refreshAllUserCollections(force = true) }
        gate.releaseAfterBudget(4)
        assertIs<Outcome.Success<Unit>>(withTimeout(WAIT_BUDGET_MS) { job.await() })
        assertEquals((0..500 step 100).toList(), offsets.sorted())
        assertTrue(gate.peak <= 4, "阵容分页峰值应不超过 4，实际为 ${gate.peak}")
    }
    @Test
    fun `前台逐集按需取全不使用后台预算`() = runBlocking {
        val gate = ConcurrencyGate()
        val client = resources.apiClient(MockEngine { request ->
            val offset = request.url.parameters["offset"]!!.toInt()
            if (offset > 0) gate.enter()
            val body = """{"data":[{"episode":{"id":${offset + 1},"type":0,"name":"","name_cn":"","sort":${offset + 1},"airdate":"2024-01-01","comment":0,"duration":"","desc":"","disc":0},"type":2,"updated_at":1}],"total":600,"limit":100,"offset":$offset}"""
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        })
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(),
            testTokenStore(resources, userId = 7), FakeEpisodeWatchStateDao())
        val job = async(Dispatchers.Default) { repo.watchStates.refreshEpisodeWatchStates(8) }
        gate.releaseAfter(5)
        assertIs<Outcome.Success<*>>(withTimeout(WAIT_BUDGET_MS) { job.await() })
        assertEquals(5, gate.calls)
        assertTrue(gate.peak >= 5, "前台逐集余页应并发发出，实际峰值 ${gate.peak}")
    }

    @Test
    fun `前台角色按需取全不使用后台预算`() = runBlocking {
        val gate = ConcurrencyGate()
        val client = resources.apiClient(MockEngine { request ->
            val offset = request.url.parameters["offset"]!!.toInt()
            if (offset > 0) gate.enter()
            val body = """{"data":[{"character":{"id":${offset + 1},"name":"c","nameCN":"","role":1,"info":"","comment":0,"lock":false,"nsfw":false},"casts":[],"type":2,"order":0}],"total":600}"""
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        })
        val repo = testSubjectRepository(resources, SubjectApi(client), NextApi(client), FakeSubjectDao(), FakeEpisodeDao(),
            FakeSubjectSectionDao(), testNsfwFilter(resources))
        val job = async(Dispatchers.Default) { repo.getCharacters(8).last() }
        gate.releaseAfter(5)
        assertIs<Load.Ready<*>>(withTimeout(WAIT_BUDGET_MS) { job.await() })
        assertEquals(5, gate.calls)
        assertTrue(gate.peak >= 5, "前台角色余页应并发发出，实际峰值 ${gate.peak}")
    }

    @Test
    fun `不同作品的逐集第一页共用预算`() = runBlocking {
        val gate = ConcurrencyGate()
        val client = resources.apiClient(MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/episodes") -> {
                    gate.enter()
                    val id = path.removePrefix("/v0/users/-/collections/").removeSuffix("/episodes").toInt()
                    """{"data":[{"episode":{"id":$id,"type":0,"name":"","name_cn":"","sort":1,"airdate":"2024-01-01","comment":0,"duration":"","desc":"","disc":0},"type":2,"updated_at":1}],"total":1,"limit":100,"offset":0}"""
                }
                request.url.parameters["subject_type"] == "2" -> {
                    val rows = (1..5).joinToString(",") { id ->
                        """{"subject_id":$id,"subject_type":2,"type":3,"rate":0,"comment":"","tags":[],"ep_status":1,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}"""
                    }
                    """{"data":[$rows],"total":5,"limit":50,"offset":0}"""
                }
                else -> """{"data":[],"total":0,"limit":50,"offset":0}"""
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        })
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(),
            testTokenStore(resources, userId = 7), FakeEpisodeWatchStateDao())
        val job = async(Dispatchers.Default) { repo.sync.refreshAllUserCollections(force = true) }
        gate.releaseAfterBudget(4)
        assertIs<Outcome.Success<Unit>>(withTimeout(WAIT_BUDGET_MS) { job.await() })
        assertEquals(5, gate.calls)
        assertTrue(gate.peak <= 4, "逐集首屏峰值应不超过 4，实际为 ${gate.peak}")
    }

    @Test
    fun `不同作品的阵容角色第一页共用预算`() = runBlocking { checkLineupFirstRequests("characters") }

    @Test
    fun `不同作品的阵容人物请求共用预算`() = runBlocking { checkLineupFirstRequests("persons") }

    private suspend fun checkLineupFirstRequests(section: String) = kotlinx.coroutines.coroutineScope {
        val gate = ConcurrencyGate()
        val client = resources.apiClient(MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/characters") || path.endsWith("/persons") -> {
                    if (path.endsWith("/$section")) gate.enter()
                    if (path.endsWith("/persons")) "[]" else """{"data":[],"total":0}"""
                }
                request.url.parameters["subject_type"] == "2" -> {
                    val rows = (1..5).joinToString(",") { id ->
                        """{"subject_id":$id,"subject_type":2,"type":3,"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}"""
                    }
                    """{"data":[$rows],"total":5,"limit":50,"offset":0}"""
                }
                else -> """{"data":[],"total":0,"limit":50,"offset":0}"""
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        })
        val token = testTokenStore(resources, userId = 7)
        val subjects = testSubjectRepository(resources, SubjectApi(client), NextApi(client), FakeSubjectDao(), FakeEpisodeDao(),
            FakeSubjectSectionDao(), testNsfwFilter(resources), tokenStore = token)
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), token,
            FakeEpisodeWatchStateDao(), lineupSource = subjects)
        val job = async(Dispatchers.Default) { repo.sync.refreshAllUserCollections(force = true) }
        gate.releaseAfterBudget(4)
        assertIs<Outcome.Success<Unit>>(withTimeout(WAIT_BUDGET_MS) { job.await() })
        assertEquals(5, gate.calls)
        assertTrue(gate.peak <= 4, "$section 请求峰值应不超过 4，实际为 ${gate.peak}")
    }

}
