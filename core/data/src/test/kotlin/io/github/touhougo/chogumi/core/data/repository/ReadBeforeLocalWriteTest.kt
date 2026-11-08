package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CollectionModify
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule

/**
 * 服务端读取在本地写之前发出、在写之后才回来：回来的是写之前的样子，落库时不能盖掉这次写。
 * 每条用例在请求到达假服务端时卡住它，期间用生产的本地写入口写一次，再放行请求。
 */
class ReadBeforeLocalWriteTest {
    @get:Rule
    val resources = TestResources()

    private val json = headersOf("Content-Type", "application/json")

    /** 服务端眼里的书 9：在读，读到第 0 话，没评分。 */
    private val serverBook =
        """{"subject_id":9,"subject_type":1,"rate":0,"type":3,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}"""

    private class Fixture(
        val tokens: TokenStore,
        val collections: TestCollections,
        val collectionDao: FakeUserCollectionDao,
        val stateDao: FakeEpisodeWatchStateDao,
        /** 被卡住的读取请求到了假服务端。 */
        val arrived: CompletableDeferred<Unit>,
        /** 放行被卡住的读取请求。 */
        val release: CompletableDeferred<Unit>,
    )

    /** [held] 为真的 GET 请求到达后等 [Fixture.release] 才回；书 9 在本地是「读到第 1 话」。 */
    private fun fixture(
        singleStatus: HttpStatusCode = HttpStatusCode.OK,
        singleError: String? = null,
        held: (path: String, subjectType: String?) -> Boolean,
    ): Fixture {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = resources.apiClient(MockEngine { request ->
            val path = request.url.encodedPath
            if (request.method != HttpMethod.Get) return@MockEngine respond("", HttpStatusCode.NoContent)
            if (held(path, request.url.parameters["subject_type"])) {
                arrived.complete(Unit)
                release.await()
            }
            when {
                path == "/v0/users/fixture_user/collections" ->
                    if (request.url.parameters["subject_type"] == "1") respond("""{"data":[$serverBook],"total":1,"limit":100,"offset":0}""", HttpStatusCode.OK, json)
                    else respond("""{"data":[],"total":0,"limit":100,"offset":0}""", HttpStatusCode.OK, json)
                path == "/v0/users/fixture_user/collections/9" -> respond(singleError ?: serverBook, singleStatus, json)
                path == "/v0/users/-/collections/12/episodes" -> respond(
                    """{"data":[{"episode":${episode(121)},"type":0,"updated_at":0}],"total":1,"limit":100,"offset":0}""",
                    HttpStatusCode.OK, json,
                )
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        })
        val tokens = testTokenStore(resources, userId = ACCOUNT)
        val collectionDao = FakeUserCollectionDao()
        val stateDao = FakeEpisodeWatchStateDao()
        val collections = testCollections(resources, CollectionApi(client), FakeSubjectDao(), collectionDao, tokens, stateDao)
        runBlocking {
            collectionDao.upsert(UserCollectionEntity(9, SubjectType.BOOK.value, CollectionType.DOING.value, epStatus = 1, updatedAt = 1L, accountId = ACCOUNT, detailsLoaded = true))
        }
        return Fixture(tokens, collections, collectionDao, stateDao, arrived, release)
    }

    /** 发出 [read]，等它卡在假服务端，做 [write]，再放行，等 [read] 落库完。 */
    private suspend fun <T> Fixture.writeWhileReading(read: suspend () -> T, write: suspend () -> Unit): T = coroutineScope {
        val pending: Deferred<T> = async(Dispatchers.Default) { read() }
        withTimeout(WAIT_BUDGET_MS) { arrived.await() }
        write()
        release.complete(Unit)
        withTimeout(WAIT_BUDGET_MS) { pending.await() }
    }

    private fun Fixture.bookProgress(): Int? = collectionDao.store[ACCOUNT to 9]?.epStatus

    @Test
    fun `分类列表取第一页后本地标了进度 迟到的快照不盖掉`() = runBlocking {
        val f = fixture { path, _ -> path == "/v0/users/fixture_user/collections" }
        f.writeWhileReading(
            read = { f.collections.store.getUserCollections(SubjectType.BOOK, CollectionType.DOING, refresh = true).last() },
            write = { f.collections.watchStates.syncEpisodeProgress(9, f.tokens.session(), epStatus = 2) },
        )
        assertEquals(2, f.bookProgress())
    }

    @Test
    fun `全量同步取第一页后本地标了进度 迟到的快照不盖掉`() = runBlocking {
        val f = fixture { path, type -> path == "/v0/users/fixture_user/collections" && type == "1" }
        val synced = f.writeWhileReading(
            read = { f.collections.sync.refreshAllUserCollections(force = true) },
            write = { f.collections.watchStates.syncEpisodeProgress(9, f.tokens.session(), epStatus = 2) },
        )
        assertEquals(Outcome.Success(Unit), synced)
        assertEquals(2, f.bookProgress())
    }

    @Test
    fun `分类列表取第一页后在面板上改了评分 迟到的快照不盖掉`() = runBlocking {
        val f = fixture { path, _ -> path == "/v0/users/fixture_user/collections" }
        f.writeWhileReading(
            read = { f.collections.store.getUserCollections(SubjectType.BOOK, CollectionType.DOING, refresh = true).last() },
            write = { assertEquals(Outcome.Success(Unit), f.collections.store.updateCollection(9, CollectionModify(rate = 8))) },
        )
        assertEquals(8, f.collectionDao.store[ACCOUNT to 9]?.rate)
    }

    @Test
    fun `单部收藏读取途中本地标了进度 迟到的结果不落库 页面拿到本地这一行`() = runBlocking {
        val f = fixture { path, _ -> path == "/v0/users/fixture_user/collections/9" }
        val shown = f.writeWhileReading(
            read = { f.collections.store.getCollection(9).last() },
            write = { f.collections.watchStates.syncEpisodeProgress(9, f.tokens.session(), epStatus = 2) },
        )
        assertEquals(2, f.bookProgress())
        assertEquals(2, (shown as Load.Ready).value?.epStatus)
    }

    @Test
    fun `单部收藏的迟到404不能盖掉读取期间已经保存的收藏`() = runBlocking {
        val f = fixture(singleStatus = HttpStatusCode.NotFound) { path, _ -> path.endsWith("/collections/9") }
        val shown = f.writeWhileReading(
            read = { f.collections.store.getCollection(9).last() },
            write = {
                assertEquals(Outcome.Success(Unit), f.collections.store.updateCollection(9, CollectionModify(rate = 8)))
            },
        )
        assertEquals(8, f.collectionDao.store[ACCOUNT to 9]?.rate)
        assertEquals(8, (shown as Load.Ready).value?.rate)
    }

    @Test
    fun `收藏面板组合真实收藏流时迟到404仍保留期间保存的评分`() = runBlocking {
        val f = fixture(singleStatus = HttpStatusCode.NotFound) { path, _ -> path.endsWith("/collections/9") }
        val shown = f.writeWhileReading(
            read = {
                workOf(
                    subject = flowOf(Load.Ready(SubjectBo(9, SubjectType.BOOK, "作品", ""))),
                    collection = f.collections.store.getCollection(9),
                    episodes = flowOf(Load.Ready(emptyList())),
                    watchStates = flowOf(Load.Ready(emptyMap())),
                    refreshWatchStates = { null },
                ).last()
            },
            write = {
                assertEquals(Outcome.Success(Unit), f.collections.store.updateCollection(9, CollectionModify(rate = 8)))
            },
        )
        assertEquals(8, (shown as Load.Ready).value.collection?.rate)
    }

    @Test
    fun `单部收藏的404在换号后返回会话已变而不是未收藏`() = runBlocking {
        val f = fixture(singleStatus = HttpStatusCode.NotFound) { path, _ -> path.endsWith("/collections/9") }
        val shown = f.writeWhileReading(
            read = { f.collections.store.getCollection(9).last() },
            write = {
                val next = f.tokens.beginSession("next-fixture-token")
                f.tokens.completeSession(next, ACCOUNT + 1, "next_fixture")
            },
        )
        assertEquals(Load.Failed(Failure.SessionChanged), shown)
        assertEquals(1, f.bookProgress())
    }

    @Test
    fun `单部收藏读取期间换号时其他网络失败也属于旧会话`() = runBlocking {
        val f = fixture(singleStatus = HttpStatusCode.InternalServerError) { path, _ -> path.endsWith("/collections/9") }
        val shown = f.writeWhileReading(
            read = { f.collections.store.getCollection(9).last() },
            write = {
                val next = f.tokens.beginSession("next-fixture-token")
                f.tokens.completeSession(next, ACCOUNT + 1, "next_fixture")
            },
        )
        assertEquals(Load.Failed(Failure.SessionChanged), shown)
    }

    @Test
    fun `单部收藏无并发写入时404仍表示未收藏且不改本地缓存`() = runBlocking {
        val f = fixture(singleStatus = HttpStatusCode.NotFound) { _, _ -> false }
        assertEquals(Load.Ready(null), f.collections.store.getCollection(9).last())
        assertEquals(1, f.bookProgress())
    }

    @Test
    fun `单部收藏同账号服务端失败仍报告原始失败`() = runBlocking {
        val f = fixture(singleStatus = HttpStatusCode.InternalServerError) { _, _ -> false }
        assertEquals(Load.Failed(Failure.Http(500)), f.collections.store.getCollection(9).last())
        assertEquals(1, f.bookProgress())
    }

    @Test
    fun `单部收藏404附带错误码时仍按状态码表示未收藏`() = runBlocking {
        val f = fixture(
            singleStatus = HttpStatusCode.NotFound,
            singleError = """{"error":"invalid_request"}""",
        ) { _, _ -> false }
        assertEquals(Load.Ready(null), f.collections.store.getCollection(9).last())
        assertEquals(1, f.bookProgress())
    }

    @Test
    fun `单部收藏读取期间本地写入不会把服务端500改成成功`() = runBlocking {
        val f = fixture(singleStatus = HttpStatusCode.InternalServerError) { path, _ -> path.endsWith("/collections/9") }
        val shown = f.writeWhileReading(
            read = { f.collections.store.getCollection(9).last() },
            write = { f.collections.watchStates.syncEpisodeProgress(9, f.tokens.session(), epStatus = 2) },
        )
        assertEquals(Load.Failed(Failure.Http(500)), shown)
        assertEquals(2, f.bookProgress())
    }

    @Test
    fun `逐集刷新途中本地标了一集 迟到的逐集状态不盖掉`() = runBlocking {
        val f = fixture { path, _ -> path == "/v0/users/-/collections/12/episodes" }
        val server = f.writeWhileReading(
            read = { f.collections.watchStates.refreshEpisodeWatchStates(12) },
            write = { f.collections.watchStates.syncSingleEpisodeWatchState(12, 121, EpisodeStatus.DONE, f.tokens.session()) },
        )
        assertEquals(Outcome.Success(mapOf(121 to EpisodeStatus.NONE)), server, "返回值仍是服务端给的")
        assertEquals(EpisodeStatus.DONE.value, f.stateDao.store.values.single { it.episodeId == 121 }.status)
    }

    @Test
    fun `本地写完之后才发出的读取照常落库`() = runBlocking {
        val f = fixture { _, _ -> false }
        f.collections.watchStates.syncEpisodeProgress(9, f.tokens.session(), epStatus = 2)
        f.collections.watchStates.syncSingleEpisodeWatchState(12, 121, EpisodeStatus.DONE, f.tokens.session())

        f.collections.store.getUserCollections(SubjectType.BOOK, CollectionType.DOING, refresh = true).last()
        f.collections.watchStates.refreshEpisodeWatchStates(12)

        assertEquals(0, f.bookProgress(), "服务端的新样子照常写进本地")
        assertEquals(EpisodeStatus.NONE.value, f.stateDao.store.values.single { it.episodeId == 121 }.status)
    }

    private fun episode(id: Int) =
        """{"id":$id,"subject_id":12,"type":0,"name":"ep","name_cn":"","sort":1,"ep":1,"airdate":"2020-01-01","comment":0,"duration":"","desc":"","disc":0}"""

    private companion object {
        const val ACCOUNT = 7
    }
}
