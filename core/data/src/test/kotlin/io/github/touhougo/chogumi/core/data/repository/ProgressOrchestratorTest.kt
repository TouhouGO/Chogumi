package io.github.touhougo.chogumi.core.data.repository

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeProgressTarget
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.github.touhougo.chogumi.core.network.model.v0.BatchEpisodeStatusDto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class ProgressOrchestratorTest {
    @get:org.junit.Rule
    val resources = TestResources()


    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @AfterTest
    fun tearDown() { io.github.touhougo.chogumi.core.data.repository.cancelAndAwait(listOf(scope)) }

    private fun newStore(): TokenStore {
        val store = testTokenStore(scope = scope)
        runBlocking {
            val pending = store.beginSession("token")
            store.completeSession(pending, ACCOUNT_ID, "fixture_user")
        }
        return store
    }

    private companion object {
        const val ACCOUNT_ID = 7
    }

    private fun ConcurrentRequestRecorder.bodies(): List<String> =
        map { (it.body as OutgoingContent.ByteArrayContent).bytes().decodeToString() }

    private fun client(engine: MockEngine) = resources.apiClient(engine)

    private fun watchStatesPageJson(states: Map<Int, Int> = emptyMap()): String {
        val items = states.entries.map { (epId, type) ->
            """{"episode":{"id":$epId,"type":0,"sort":0.0,"name":"","name_cn":"","duration":"","airdate":"","comment":0,"desc":"","disc":0},"type":$type,"updated_at":1000}"""
        }.joinToString(",")
        return """{"total":${states.size},"limit":100,"offset":0,"data":[$items]}"""
    }

    /** 写入方动手时取账号（章节点击、收藏面板保存都这样做）：用例里就是 [orchestrator] 建出来的那个账号。 */
    private var actingStore: TokenStore? = null

    private suspend fun ProgressOrchestrator.markWatchedUpTo(subjectId: Int, target: EpisodeProgressTarget): Outcome<Unit> =
        markWatchedUpTo(subjectId, target, LocalPublish.Immediate, checkNotNull(actingStore).session())

    private suspend fun ProgressOrchestrator.applyWatchStates(
        subjectId: Int,
        episodes: List<io.github.touhougo.chogumi.core.model.EpisodeBo>,
        current: Map<Int, EpisodeStatus>,
        target: Map<Int, EpisodeStatus>,
    ): Outcome<Unit> = applyWatchStates(subjectId, episodes, current, target, LocalPublish.Immediate, checkNotNull(actingStore).session())

    private fun orchestrator(
        engine: MockEngine,
        userCollectionDao: FakeUserCollectionDao = FakeUserCollectionDao(),
        subjectDao: FakeSubjectDao = FakeSubjectDao(),
        watchDao: FakeEpisodeWatchStateDao = FakeEpisodeWatchStateDao(),
        store: TokenStore = newStore(),
    ): ProgressOrchestrator {
        actingStore = store
        val subjectApi = SubjectApi(client(engine))
        val nextApi = NextApi(client(engine))
        val collectionApi = CollectionApi(client(engine))
        val subjectRepo = testSubjectRepository(resources, subjectApi, nextApi, subjectDao, FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val collectionRepo = testCollections(resources, collectionApi, subjectDao, userCollectionDao, store, watchDao)
        return collectionRepo.progressOrchestrator(subjectRepo)
    }

    private val tenEpisodesJson = """
        {"data":[
          {"id":101,"subject_id":8,"type":0,"name":"ep1","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":1.0,"airdate":""},
          {"id":102,"subject_id":8,"type":0,"name":"ep2","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":2.0,"airdate":""},
          {"id":103,"subject_id":8,"type":0,"name":"ep3","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":3.0,"airdate":""},
          {"id":104,"subject_id":8,"type":0,"name":"ep4","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":4.0,"airdate":""},
          {"id":105,"subject_id":8,"type":0,"name":"ep5","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":5.0,"airdate":""},
          {"id":106,"subject_id":8,"type":0,"name":"ep6","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":6.0,"airdate":""},
          {"id":107,"subject_id":8,"type":0,"name":"ep7","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":7.0,"airdate":""},
          {"id":108,"subject_id":8,"type":0,"name":"ep8","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":8.0,"airdate":""},
          {"id":109,"subject_id":8,"type":0,"name":"ep9","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":9.0,"airdate":""},
          {"id":110,"subject_id":8,"type":0,"name":"ep10","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":10.0,"airdate":""}],
         "total":10,"limit":100,"offset":0}
    """.trimIndent()

    private val zeroEpisodesJson = """
        {"data":[
          {"id":100,"subject_id":8,"type":0,"name":"ep0","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":0.0,"airdate":""},
          {"id":101,"subject_id":8,"type":0,"name":"ep1","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":1.0,"airdate":""},
          {"id":201,"subject_id":8,"type":1,"name":"sp","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":0.0,"airdate":""}],
         "total":3,"limit":100,"offset":0}
    """.trimIndent()

    @Test
    fun `看到第零话只标该正片并保留特别篇`() = runTest {
        val batches = ConcurrentRequestRecorder()
        val serverStates = ConcurrentHashMap(mapOf(201 to EpisodeStatus.DONE.value))
        val watchDao = FakeEpisodeWatchStateDao()
        watchDao.upsertAll(listOf(EpisodeWatchStateEntity(201, 8, ACCOUNT_ID, EpisodeStatus.DONE.value, 1)))
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, epStatus = 1, accountId = ACCOUNT_ID, updatedAt = 1))
        val orchestrator = orchestrator(statefulEngine(zeroEpisodesJson, serverStates, batches), dao, watchDao = watchDao)

        assertIs<Outcome.Success<Unit>>(orchestrator.markWatchedUpTo(8, EpisodeProgressTarget.Episode(100, 0.0)))

        val batch = Json.decodeFromString<BatchEpisodeStatusDto>(batches.bodies().single())
        assertEquals(listOf(100), batch.episodeIds)
        assertEquals(EpisodeStatus.DONE.value, batch.type)
        assertEquals(EpisodeStatus.DONE.value, serverStates[100])
        assertEquals(EpisodeStatus.DONE.value, serverStates[201])
        assertEquals(EpisodeStatus.DONE.value, watchDao.store[ACCOUNT_ID to 100]?.status)
        assertEquals(EpisodeStatus.NONE.value, watchDao.store[ACCOUNT_ID to 101]?.status)
        assertEquals(EpisodeStatus.DONE.value, watchDao.store[ACCOUNT_ID to 201]?.status)
        assertEquals(2, dao.store[ACCOUNT_ID to 8]?.epStatus)
    }

    @Test
    fun `空进度清除已看第零话并保留特别篇`() = runTest {
        val batches = ConcurrentRequestRecorder()
        val serverStates = ConcurrentHashMap(mapOf(100 to EpisodeStatus.DONE.value, 201 to EpisodeStatus.DONE.value))
        val watchDao = FakeEpisodeWatchStateDao()
        watchDao.upsertAll(listOf(
            EpisodeWatchStateEntity(100, 8, ACCOUNT_ID, EpisodeStatus.DONE.value, 1),
            EpisodeWatchStateEntity(201, 8, ACCOUNT_ID, EpisodeStatus.DONE.value, 1),
        ))
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, epStatus = 2, accountId = ACCOUNT_ID, updatedAt = 1))
        val orchestrator = orchestrator(statefulEngine(zeroEpisodesJson, serverStates, batches), dao, watchDao = watchDao)

        assertIs<Outcome.Success<Unit>>(orchestrator.markWatchedUpTo(8, EpisodeProgressTarget.Empty))

        val batch = Json.decodeFromString<BatchEpisodeStatusDto>(batches.bodies().single())
        assertEquals(listOf(100), batch.episodeIds)
        assertEquals(EpisodeStatus.NONE.value, batch.type)
        assertEquals(EpisodeStatus.NONE.value, serverStates[100])
        assertEquals(EpisodeStatus.DONE.value, serverStates[201])
        assertEquals(EpisodeStatus.NONE.value, watchDao.store[ACCOUNT_ID to 100]?.status)
        assertEquals(EpisodeStatus.DONE.value, watchDao.store[ACCOUNT_ID to 201]?.status)
        assertEquals(1, dao.store[ACCOUNT_ID to 8]?.epStatus)
    }

    @Test
    fun `全未看标到 3 只发 1 次 DONE 请求`() = runTest {
        val batchBodies = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v0/episodes" -> respond(
                    content = tenEpisodesJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/v0/users/-/collections/8/episodes" -> {
                    if (request.method == HttpMethod.Get) {
                        respond(
                            content = watchStatesPageJson(emptyMap()),
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    } else {
                        batchBodies.record(request)
                        respond(content = "", status = HttpStatusCode.NoContent)
                    }
                }
                else -> respond(content = "", status = HttpStatusCode.NoContent)
            }
        }
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, epStatus = 0, accountId = ACCOUNT_ID, updatedAt = System.currentTimeMillis()))
        val watchDao = FakeEpisodeWatchStateDao()
        val orchestrator = orchestrator(engine, dao, watchDao = watchDao)

        val result = orchestrator.markWatchedUpTo(8, EpisodeProgressTarget.Episode(103, 3.0))
        assertIs<Outcome.Success<Unit>>(result)
        // 限流要求：4..10 本来就是 NONE，不发无意义的清空请求，只发 1 次 DONE 请求
        assertEquals(1, batchBodies.bodies().size, "全未看标到3只发1次DONE请求")
        val doneReq = Json.decodeFromString<BatchEpisodeStatusDto>(batchBodies.bodies()[0])
        assertEquals(listOf(101, 102, 103), doneReq.episodeIds)
        assertEquals(EpisodeStatus.DONE.value, doneReq.type)
        assertEquals(3, dao.store[ACCOUNT_ID to 8]?.epStatus)
    }

    @Test
    fun `已看到 10 回退到 8 只发 1 次 NONE 请求且只含 9、10`() = runTest {
        val batchBodies = ConcurrentRequestRecorder()
        val initialWatchStates = (101..110).associateWith { EpisodeStatus.DONE.value }
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v0/episodes" -> respond(
                    content = tenEpisodesJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/v0/users/-/collections/8/episodes" -> {
                    if (request.method == HttpMethod.Get) {
                        respond(
                            content = watchStatesPageJson(initialWatchStates),
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    } else {
                        batchBodies.record(request)
                        respond(content = "", status = HttpStatusCode.NoContent)
                    }
                }
                else -> respond(content = "", status = HttpStatusCode.NoContent)
            }
        }
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, epStatus = 10, accountId = ACCOUNT_ID, updatedAt = System.currentTimeMillis()))
        val watchDao = FakeEpisodeWatchStateDao()
        val orchestrator = orchestrator(engine, dao, watchDao = watchDao)

        val result = orchestrator.markWatchedUpTo(8, EpisodeProgressTarget.Episode(108, 8.0))
        assertIs<Outcome.Success<Unit>>(result)
        // 限流要求：1..8 本来已是 DONE，不重复发 DONE 请求；只发 1 次清空 9、10 的 NONE 请求
        assertEquals(1, batchBodies.bodies().size, "已看到10回退到8只发1次NONE请求")
        val noneReq = Json.decodeFromString<BatchEpisodeStatusDto>(batchBodies.bodies()[0])
        assertEquals(listOf(109, 110), noneReq.episodeIds, "只包含需要变化的9、10")
        assertEquals(EpisodeStatus.NONE.value, noneReq.type)
        assertEquals(8, dao.store[ACCOUNT_ID to 8]?.epStatus)
    }

    @Test
    fun `未播集已是 NONE 不进请求`() = runTest {
        val batchBodies = ConcurrentRequestRecorder()
        // 1..3 已看 (DONE)，4 已播未看 (NONE)，5 未播 (NONE)
        val initialWatchStates = mapOf(101 to EpisodeStatus.DONE.value, 102 to EpisodeStatus.DONE.value, 103 to EpisodeStatus.DONE.value)
        val fiveEpisodesJson = """
            {"data":[
              {"id":101,"subject_id":8,"type":0,"name":"ep1","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":1.0,"airdate":""},
              {"id":102,"subject_id":8,"type":0,"name":"ep2","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":2.0,"airdate":""},
              {"id":103,"subject_id":8,"type":0,"name":"ep3","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":3.0,"airdate":""},
              {"id":104,"subject_id":8,"type":0,"name":"ep4","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":4.0,"airdate":""},
              {"id":105,"subject_id":8,"type":0,"name":"ep5","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":5.0,"airdate":""}],
             "total":5,"limit":100,"offset":0}
        """.trimIndent()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v0/episodes" -> respond(
                    content = fiveEpisodesJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/v0/users/-/collections/8/episodes" -> {
                    if (request.method == HttpMethod.Get) {
                        respond(
                            content = watchStatesPageJson(initialWatchStates),
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    } else {
                        batchBodies.record(request)
                        respond(content = "", status = HttpStatusCode.NoContent)
                    }
                }
                else -> respond(content = "", status = HttpStatusCode.NoContent)
            }
        }
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, epStatus = 3, accountId = ACCOUNT_ID, updatedAt = System.currentTimeMillis()))
        val watchDao = FakeEpisodeWatchStateDao()
        val orchestrator = orchestrator(engine, dao, watchDao = watchDao)

        // 标到第 4 集：1..3 已看，4 标 DONE；5 是未播集且已是 NONE，绝不能进 NONE 请求
        val result = orchestrator.markWatchedUpTo(8, EpisodeProgressTarget.Episode(104, 4.0))
        assertIs<Outcome.Success<Unit>>(result)
        assertEquals(1, batchBodies.bodies().size)
        val doneReq = Json.decodeFromString<BatchEpisodeStatusDto>(batchBodies.bodies()[0])
        assertEquals(listOf(104), doneReq.episodeIds)
        assertEquals(EpisodeStatus.DONE.value, doneReq.type)
        assertFalse(batchBodies.bodies().any { it.contains("105") }, "未播集已是 NONE 严禁进请求")
        assertEquals(4, dao.store[ACCOUNT_ID to 8]?.epStatus)
    }

    @Test
    fun `两组状态均有变更时分别发两组批量`() = runTest {
        val batchBodies = ConcurrentRequestRecorder()
        // 1..2 已看 (DONE)，3..7 未看 (NONE)，8 已看 (DONE，用户之前跳看)
        val initialWatchStates = mapOf(101 to EpisodeStatus.DONE.value, 102 to EpisodeStatus.DONE.value, 108 to EpisodeStatus.DONE.value)
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v0/episodes" -> respond(
                    content = tenEpisodesJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/v0/users/-/collections/8/episodes" -> {
                    if (request.method == HttpMethod.Get) {
                        respond(
                            content = watchStatesPageJson(initialWatchStates),
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    } else {
                        batchBodies.record(request)
                        respond(content = "", status = HttpStatusCode.NoContent)
                    }
                }
                else -> respond(content = "", status = HttpStatusCode.NoContent)
            }
        }
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, epStatus = 2, accountId = ACCOUNT_ID, updatedAt = System.currentTimeMillis()))
        val watchDao = FakeEpisodeWatchStateDao()
        val orchestrator = orchestrator(engine, dao, watchDao = watchDao)

        // 看到第 5 集：3..5 标 DONE，8 清 NONE
        val result = orchestrator.markWatchedUpTo(8, EpisodeProgressTarget.Episode(105, 5.0))
        assertIs<Outcome.Success<Unit>>(result)
        assertEquals(2, batchBodies.bodies().size)
        val doneReq = Json.decodeFromString<BatchEpisodeStatusDto>(batchBodies.bodies()[0])
        assertEquals(listOf(103, 104, 105), doneReq.episodeIds)
        assertEquals(EpisodeStatus.DONE.value, doneReq.type)
        val noneReq = Json.decodeFromString<BatchEpisodeStatusDto>(batchBodies.bodies()[1])
        assertEquals(listOf(108), noneReq.episodeIds)
        assertEquals(EpisodeStatus.NONE.value, noneReq.type)
        assertEquals(5, dao.store[ACCOUNT_ID to 8]?.epStatus)
    }


    @Test
    fun `markWatchedUpTo 状态拉取失败返回错误且不发出任何写入请求`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            requests.record(request)
            when (request.url.encodedPath) {
                "/v0/episodes" -> respond(
                    content = tenEpisodesJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/v0/users/-/collections/8/episodes" -> {
                    if (request.method == HttpMethod.Get) {
                        respond("Internal Server Error", HttpStatusCode.InternalServerError)
                    } else {
                        respond(content = "", status = HttpStatusCode.NoContent)
                    }
                }
                else -> respond(content = "", status = HttpStatusCode.NoContent)
            }
        }
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, epStatus = 1, accountId = ACCOUNT_ID, updatedAt = System.currentTimeMillis()))
        val watchDao = FakeEpisodeWatchStateDao()
        val orchestrator = orchestrator(engine, dao, watchDao = watchDao)

        val result = orchestrator.markWatchedUpTo(8, EpisodeProgressTarget.Episode(102, 2.0))
        assertEquals(Outcome.Failed(Failure.Http(500)), result)
        assertEquals(
            listOf("/v0/episodes", "/v0/users/-/collections/8/episodes"),
            requests.map { it.url.encodedPath },
            "章节取回后确实请求逐集状态，500 必须来自这一步",
        )
        assertTrue(requests.all { it.method == HttpMethod.Get }, "状态拉取失败绝不能发出任何写入请求")
    }

    @Test
    fun `markWatchedUpTo 目标集已不在拉到的章节里时以失败结束且不发写入`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            requests.record(request)
            when (request.url.encodedPath) {
                "/v0/episodes" -> respond(
                    content = tenEpisodesJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/v0/users/-/collections/8/episodes" -> respond(
                    content = watchStatesPageJson(mapOf(101 to 2, 102 to 2)),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                else -> respond(content = "", status = HttpStatusCode.NoContent)
            }
        }
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, epStatus = 2, accountId = ACCOUNT_ID, updatedAt = System.currentTimeMillis()))
        val orchestrator = orchestrator(engine, dao)

        val result = orchestrator.markWatchedUpTo(8, EpisodeProgressTarget.Episode(999, 3.0))

        assertEquals(Outcome.Failed(Failure.BadResponse), result)
        assertTrue(requests.none { it.method == HttpMethod.Patch }, "目标集找不到时不得发出任何写入（尤其不能当成清空）")
        assertEquals(2, dao.store[ACCOUNT_ID to 8]?.epStatus)
    }

    @Test
    fun `markWatchedUpTo 非正片不参与任何路径的请求体`() = runTest {
        val bodies = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v0/episodes" -> respond(
                    content = """
                        {"data":[
                          {"id":101,"subject_id":8,"type":0,"name":"ep1","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":1.0,"airdate":""},
                          {"id":102,"subject_id":8,"type":0,"name":"ep2","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":2.0,"airdate":""},
                          {"id":103,"subject_id":8,"type":0,"name":"ep3","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":3.0,"airdate":""},
                          {"id":104,"subject_id":8,"type":0,"name":"ep4","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":4.0,"airdate":""},
                          {"id":201,"subject_id":8,"type":1,"name":"sp1","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":4.0,"airdate":""},
                          {"id":202,"subject_id":8,"type":2,"name":"op1","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":2.0,"airdate":""},
                          {"id":203,"subject_id":8,"type":4,"name":"ed1","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":3.0,"airdate":""}],
                         "total":7,"limit":100,"offset":0}
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/v0/users/-/collections/8/episodes" -> {
                    if (request.method == HttpMethod.Get) {
                        respond(
                            content = watchStatesPageJson(emptyMap()),
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    } else {
                        bodies.record(request)
                        respond(content = "", status = HttpStatusCode.NoContent)
                    }
                }
                else -> respond(content = "", status = HttpStatusCode.NoContent)
            }
        }
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, epStatus = 2, accountId = ACCOUNT_ID, updatedAt = System.currentTimeMillis()))
        val watchDao = FakeEpisodeWatchStateDao()
        val orchestrator = orchestrator(engine, dao, watchDao = watchDao)

        assertIs<Outcome.Success<Unit>>(
            orchestrator.markWatchedUpTo(8, EpisodeProgressTarget.Episode(104, 4.0)),
        )
        assertEquals(listOf(101, 102, 103, 104), Json.decodeFromString<BatchEpisodeStatusDto>(bodies.bodies().single()).episodeIds)
        assertFalse(bodies.bodies().any { it.contains("201") || it.contains("202") || it.contains("203") })
    }

    @Test
    fun `markWatchedUpTo 批量端点404不回退创建收藏`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val batchCount = AtomicInteger()
        // 初始已看 101、102，现在显式空目标会触发清空 101、102 的 NONE 请求
        val initialWatchStates = mapOf(101 to EpisodeStatus.DONE.value, 102 to EpisodeStatus.DONE.value)
        val engine = MockEngine { request ->
            requests.record(request)
            when (request.url.encodedPath) {
                "/v0/episodes" -> respond(
                    content = """
                        {"data":[
                          {"id":101,"subject_id":8,"type":0,"name":"ep1","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":1.0,"airdate":""},
                          {"id":102,"subject_id":8,"type":0,"name":"ep2","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":2.0,"airdate":""}],
                          "total":2,"limit":100,"offset":0}
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/v0/users/-/collections/8/episodes" -> {
                    if (request.method == HttpMethod.Get) {
                        respond(
                            content = watchStatesPageJson(initialWatchStates),
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    } else {
                        batchCount.incrementAndGet()
                        respond("Not Found", HttpStatusCode.NotFound)
                    }
                }
                else -> respond(content = "", status = HttpStatusCode.NoContent)
            }
        }
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, epStatus = 2, accountId = ACCOUNT_ID, updatedAt = System.currentTimeMillis()))
        val watchDao = FakeEpisodeWatchStateDao()
        val orchestrator = orchestrator(engine, dao, watchDao = watchDao)

        val result = orchestrator.markWatchedUpTo(8, EpisodeProgressTarget.Empty)
        assertIs<Outcome.Failed>(result)
        assertEquals(1, batchCount.get(), "404 严禁重试")
        assertTrue(
            requests.none { it.url.encodedPath == "/v0/users/-/collections/8" },
            "批量端点 404 不得回退创建收藏",
        )
        assertEquals(2, dao.store[ACCOUNT_ID to 8]?.epStatus)
    }

    @Test
    fun `markWatchedUpTo 没有本篇章节时清空进度不发批量请求且静默写穿透`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            requests.record(request)
            when (request.url.encodedPath) {
                "/v0/episodes" -> respond(
                    content = """
                        {"data":[
                          {"id":201,"subject_id":8,"type":1,"name":"sp1","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":2.0,"airdate":""}],
                         "total":1,"limit":100,"offset":0}
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/v0/users/-/collections/8/episodes" -> {
                    if (request.method == HttpMethod.Get) {
                        respond(
                            content = watchStatesPageJson(emptyMap()),
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    } else {
                        respond("", HttpStatusCode.NoContent)
                    }
                }
                else -> respond("", HttpStatusCode.NoContent)
            }
        }
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, epStatus = 1, accountId = ACCOUNT_ID, updatedAt = System.currentTimeMillis()))
        val watchDao = FakeEpisodeWatchStateDao()
        val orchestrator = orchestrator(engine, dao, watchDao = watchDao)

        val result = orchestrator.markWatchedUpTo(8, EpisodeProgressTarget.Empty)
        assertIs<Outcome.Success<Unit>>(result)
        assertTrue(
            requests.none { it.url.encodedPath == "/v0/users/-/collections/8/episodes" && it.method == HttpMethod.Patch },
            "没有本篇章节时严禁发送批量章节请求",
        )
        // 只有非正片（SP）：写穿的已看记录数为 0
        assertEquals(0, dao.store[ACCOUNT_ID to 8]?.epStatus)
    }

    /** 第二季集号从 13 开始：标第 13 集与看到第 15 集都按集 id 写，不按集号推。 */
    @Test
    fun `第二季集号从13开始时标第13集与看到这一集都正确`() = runTest {
        val batchBodies = ConcurrentRequestRecorder()
        val currentServerStates = ConcurrentHashMap<Int, Int>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v0/episodes" -> respond(
                    content = """
                        {"data":[
                          {"id":213,"subject_id":8,"type":0,"name":"ep13","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":13.0,"airdate":""},
                          {"id":214,"subject_id":8,"type":0,"name":"ep14","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":14.0,"airdate":""},
                          {"id":215,"subject_id":8,"type":0,"name":"ep15","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":15.0,"airdate":""},
                          {"id":216,"subject_id":8,"type":0,"name":"ep16","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":16.0,"airdate":""},
                          {"id":301,"subject_id":8,"type":1,"name":"sp1","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":13.5,"airdate":""}],
                         "total":5,"limit":100,"offset":0}
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/v0/users/-/collections/8/episodes" -> {
                    if (request.method == HttpMethod.Get) {
                        respond(
                            content = watchStatesPageJson(currentServerStates),
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    } else {
                        val body = request.body.toByteArray().decodeToString()
                        batchBodies.record(request)
                        val req = Json.decodeFromString<BatchEpisodeStatusDto>(body)
                        req.episodeIds.forEach { currentServerStates[it] = req.type }
                        respond(content = "", status = HttpStatusCode.NoContent)
                    }
                }
                else -> respond(content = "", status = HttpStatusCode.NoContent)
            }
        }
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, epStatus = 0, accountId = ACCOUNT_ID, updatedAt = System.currentTimeMillis()))
        val watchDao = FakeEpisodeWatchStateDao()
        val orchestrator = orchestrator(engine, dao, watchDao = watchDao)

        // 1. 标第 13 集：全未看时，标第 13 集
        val mark13Result = orchestrator.markWatchedUpTo(8, EpisodeProgressTarget.Episode(213, 13.0))
        assertIs<Outcome.Success<Unit>>(mark13Result)
        // 看到 13：sort <= 13 (213) 为 DONE；其余 214..216 本来就是 NONE，不发清空请求
        assertEquals(1, batchBodies.bodies().size, "全未看标第13集只发1次DONE请求")
        val doneBody = Json.decodeFromString<BatchEpisodeStatusDto>(batchBodies.bodies().first())
        assertEquals(listOf(213), doneBody.episodeIds, "全未看时标第13集")
        assertEquals(EpisodeStatus.DONE.value, doneBody.type)
        assertFalse(batchBodies.bodies().any { it.contains("301") }, "SP严禁混入")
        // 写穿值是「目标为非无记录的正片集数」——13 是唯一被标过的正片，故为 1。
        assertEquals(1, dao.store[ACCOUNT_ID to 8]?.epStatus)

        // 2. 看到这一集（直接点第 15 集，先读服务端逐集状态再差分写入）：
        batchBodies.clear()
        val markResult = orchestrator.markWatchedUpTo(8, EpisodeProgressTarget.Episode(215, 15.0))
        assertIs<Outcome.Success<Unit>>(markResult)
        // 此时服务端 213 已是 DONE，214、215 是 NONE；216 是 NONE
        // 因此只需要发 1 次 DONE 请求标 214、215
        assertEquals(1, batchBodies.bodies().size, "213已看、216未看，标到15只发1次DONE请求")
        val markDoneBody = Json.decodeFromString<BatchEpisodeStatusDto>(batchBodies.bodies().first())
        assertEquals(listOf(214, 215), markDoneBody.episodeIds, "点第15集只需把未看的14、15标为DONE")
        // 写穿值为目标非无记录的正片集数：213、214、215 被标过 = 3（不是 sort.toInt()=15）
        assertEquals(3, dao.store[ACCOUNT_ID to 8]?.epStatus)

        // 逐集状态缓存写穿透断言
        val watchMap = watchDao.store.values.associate { it.episodeId to it.status }
        assertEquals(EpisodeStatus.DONE.value, watchMap[213])
        assertEquals(EpisodeStatus.DONE.value, watchMap[214])
        assertEquals(EpisodeStatus.DONE.value, watchMap[215])
        assertEquals(EpisodeStatus.NONE.value, watchMap[216])
        assertNull(watchMap[301], "SP不写入逐集状态")
    }

    /**
     * 全仓唯一写核心的共享请求构造：`/v0/episodes` 给 [episodesJson]，
     * 逐集端点 GET 给 [serverStates]、写请求记进 [batchBodies] 并回写 [serverStates]。
     */
    private fun statefulEngine(
        episodesJson: String,
        serverStates: MutableMap<Int, Int>,
        batchBodies: ConcurrentRequestRecorder,
    ) = MockEngine { request ->
        when (request.url.encodedPath) {
            "/v0/episodes" -> respond(
                content = episodesJson,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
            "/v0/users/-/collections/8/episodes" -> {
                if (request.method == HttpMethod.Get) {
                    respond(
                        content = watchStatesPageJson(serverStates),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                } else {
                    val body = request.body.toByteArray().decodeToString()
                    batchBodies.record(request)
                    val req = Json.decodeFromString<BatchEpisodeStatusDto>(body)
                    req.episodeIds.forEach { serverStates[it] = req.type }
                    respond(content = "", status = HttpStatusCode.NoContent)
                }
            }
            else -> respond(content = "", status = HttpStatusCode.NoContent)
        }
    }

    private suspend fun orchestratorWith(engine: MockEngine, epStatus: Int, watchDao: FakeEpisodeWatchStateDao): ProgressOrchestrator {
        val dao = FakeUserCollectionDao()
        dao.upsert(
            UserCollectionEntity(
                subjectId = 8, subjectType = 2, type = CollectionType.DOING.value,
                epStatus = epStatus, accountId = ACCOUNT_ID,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return orchestrator(engine, dao, watchDao = watchDao)
    }

    @Test
    fun `服务端已看到这一集时不发出任何写请求`() = runTest {
        val batchBodies = ConcurrentRequestRecorder()
        val serverStates = ConcurrentHashMap<Int, Int>().apply { (101..103).forEach { put(it, EpisodeStatus.DONE.value) } }
        val engine = statefulEngine(tenEpisodesJson, serverStates, batchBodies)
        val watchDao = FakeEpisodeWatchStateDao()
        val orchestrator = orchestratorWith(engine, epStatus = 3, watchDao = watchDao)

        assertIs<Outcome.Success<Unit>>(orchestrator.markWatchedUpTo(8, EpisodeProgressTarget.Episode(103, 3.0)))
        assertTrue(batchBodies.bodies().isEmpty(), "没有变化的集绝不进请求（频率限制）")
    }

    @Test
    fun `想看与抛弃按各自分组批量 与看过分开`() = runTest {
        val batchBodies = ConcurrentRequestRecorder()
        val serverStates = ConcurrentHashMap(mapOf(
            101 to EpisodeStatus.DONE.value,
            102 to EpisodeStatus.WISH.value,
            103 to EpisodeStatus.DROPPED.value,
        ))
        val engine = statefulEngine(tenEpisodesJson, serverStates, batchBodies)
        val watchDao = FakeEpisodeWatchStateDao()
        val orchestrator = orchestratorWith(engine, epStatus = 3, watchDao = watchDao)

        val current = mapOf(
            101 to EpisodeStatus.DONE,
            102 to EpisodeStatus.WISH,
            103 to EpisodeStatus.DROPPED,
        )
        // 目标：103 改回看过，102 保持不变
        val target = mapOf(
            101 to EpisodeStatus.DONE,
            102 to EpisodeStatus.WISH,
            103 to EpisodeStatus.DONE,
        )
        assertIs<Outcome.Success<Unit>>(
            orchestrator.applyWatchStates(8, episodes = tenEpisodes(), current = current, target = target),
        )

        val doneReq = Json.decodeFromString<BatchEpisodeStatusDto>(batchBodies.bodies().single())
        assertEquals(listOf(103), doneReq.episodeIds)
        assertEquals(EpisodeStatus.DONE.value, doneReq.type)
    }

    /** [tenEpisodesJson] 的领域形态：id 101..110、sort 1..10。 */
    private fun tenEpisodes(): List<io.github.touhougo.chogumi.core.model.EpisodeBo> =
        (1..10).map { i ->
            io.github.touhougo.chogumi.core.model.EpisodeBo(
                id = 100 + i, subjectId = 8, type = 0, name = "ep$i", nameCn = "", sort = i.toDouble(),
            )
        }

    @Test
    fun `章节写入限流 429 报 Http 429 且不自动重试`() = runTest {
        val batchCount = AtomicInteger()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v0/episodes" -> respond(
                    content = tenEpisodesJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/v0/users/-/collections/8/episodes" -> {
                    if (request.method == HttpMethod.Get) {
                        respond(
                            content = watchStatesPageJson(emptyMap()),
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    } else {
                        batchCount.incrementAndGet()
                        respond(
                            content = """{"title":"Too Many Requests","description":"rate limited"}""",
                            status = HttpStatusCode.TooManyRequests,
                            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    }
                }
                else -> respond(content = "", status = HttpStatusCode.NoContent)
            }
        }
        val watchDao = FakeEpisodeWatchStateDao()
        val orchestrator = orchestratorWith(engine, epStatus = 0, watchDao = watchDao)

        val result = orchestrator.markWatchedUpTo(8, EpisodeProgressTarget.Episode(103, 3.0))
        assertIs<Outcome.Failed>(result)
        // 原因来自 core:network 的唯一映射：429 就是 429，不透传服务端原文
        assertEquals(Failure.Http(429), result.failure)
        assertEquals(1, batchCount.get(), "限流严禁自动重试")
    }
}
