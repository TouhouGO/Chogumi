package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.settled
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.network.TokenRefresher
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest

/** v0 先分页收藏记录，再跳过不存在的条目；回包长度不能充当数据库游标。 */
class CollectionPagingCursorTest {
    @get:org.junit.Rule
    val resources = TestResources()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())


    @AfterTest
    fun tearDown() {

        io.github.touhougo.chogumi.core.data.repository.cancelAndAwait(listOf(scope))
    }

    private class Fixture(
        val collections: TestCollections,
        val others: OtherUserCollectionsCache,
        val requests: ConcurrentRequestRecorder,
        val dao: FakeUserCollectionDao,
    ) {
        val offsets: Set<Int> get() = requests.map { it.url.parameters["offset"]!!.toInt() }.toSet()

        suspend fun load(self: Boolean): Outcome<List<UserCollectionBo>> = if (self) {
            collections.store.getUserCollections(SubjectType.ANIME, null, refresh = true).settled()
        } else {
            others.collectionsFor("other", SubjectType.ANIME, refresh = true)
        }
    }

    private suspend fun fixture(
        total: Int,
        missing: Set<Int> = emptySet(),
        pageSize: Int = 50,
        includeMetadata: Boolean = true,
        failAt: Int? = null,
        repeatPage: Boolean = false,
    ): Fixture {
        val store = testTokenStore(scope = scope)
        val pending = store.beginSession("test-token")
        store.completeSession(pending, 7, "self")
        val requests = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            val offset = request.url.parameters["offset"]!!.toInt()
            requests.record(request)
            check(requests.count() <= 12) { "分页请求未能结束" }
            assertEquals("50", request.url.parameters["limit"])
            assertEquals("Bearer test-token", request.headers["Authorization"])
            if (offset == failAt) {
                respond("{}", HttpStatusCode.InternalServerError, headersOf("Content-Type", "application/json"))
            } else {
                val start = if (repeatPage) 0 else offset
                // 模拟服务端：先切数据库结果集，然后略过查不到的条目。
                val ids = (start until minOf(start + pageSize, total)).filter { it !in missing }
                val data = ids.joinToString(",") { index ->
                    val id = index + 1
                    """{"subject_id":$id,"subject_type":2,"type":3,"rate":0,"comment":"","tags":[],
                        "ep_status":0,"vol_status":0,"updated_at":"2026-01-01T00:00:00Z","private":false,
                        "subject":{"id":$id,"type":2,"name":"S$id","name_cn":"条目$id","short_summary":"",
                        "images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"},
                        "eps":12,"volumes":0,"collection_total":0,"score":0,"rank":0,"tags":[]}}"""
                }
                val metadata = if (includeMetadata) ",\"total\":$total,\"limit\":$pageSize,\"offset\":$offset" else ""
                respond("""{"data":[$data]$metadata}""", HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()))
            }
        }
        val client = createHttpClient(store, object : TokenRefresher {
            override suspend fun refresh(rejectedAccessToken: String?) = false
        }, engine = engine, appVersion = "test", customizers = emptyList()).also { resources.own(it) }
        val dao = FakeUserCollectionDao()
        val subjects = FakeSubjectDao()
        return Fixture(
            testCollections(resources, CollectionApi(client), subjects, dao, store, FakeEpisodeWatchStateDao()),
            testOtherUserCollectionsCache(CollectionApi(client), subjects, store),
            requests,
            dao,
        )
    }

    @Test
    fun `正常末页不再多请求一次`() = runTest {
        for (self in listOf(false, true)) {
            val fixture = fixture(total = 120)
            val result = assertIs<Outcome.Success<List<UserCollectionBo>>>(fixture.load(self))
            assertEquals(setOf(0, 50, 100), fixture.offsets)
            assertEquals(120, result.value.size)
            assertEquals(120, result.value.last().subjectId)
        }
    }

    @Test
    fun `短页按数据库跨度推进而非返回条数`() = runTest {
        for (self in listOf(false, true)) {
            val fixture = fixture(total = 120, missing = setOf(50, 51))
            val result = assertIs<Outcome.Success<List<UserCollectionBo>>>(fixture.load(self))
            assertEquals(setOf(0, 50, 100), fixture.offsets)
            assertEquals(118, result.value.size)
            assertEquals(120, result.value.last().subjectId)
            assertTrue(result.value.none { it.subjectId in setOf(51, 52) })
        }
    }

    @Test
    fun `中间整页缺失仍能取到后续可见收藏`() = runTest {
        for (self in listOf(false, true)) {
            val fixture = fixture(total = 120, missing = (50 until 100).toSet())
            val result = assertIs<Outcome.Success<List<UserCollectionBo>>>(fixture.load(self))
            assertEquals(setOf(0, 50, 100), fixture.offsets)
            assertEquals(70, result.value.size)
            assertEquals(120, result.value.last().subjectId)
        }
    }

    @Test
    fun `第一页为空不能被误判为没有收藏`() = runTest {
        for (self in listOf(false, true)) {
            val fixture = fixture(total = 51, missing = (0 until 50).toSet())
            val result = assertIs<Outcome.Success<List<UserCollectionBo>>>(fixture.load(self))
            assertEquals(setOf(0, 50), fixture.offsets)
            assertEquals(listOf(51), result.value.map { it.subjectId })
        }
    }

    @Test
    fun `尾部全被略过时按总数结束`() = runTest {
        for (self in listOf(false, true)) {
            val fixture = fixture(total = 120, missing = (50 until 120).toSet())
            val result = assertIs<Outcome.Success<List<UserCollectionBo>>>(fixture.load(self))
            assertEquals(setOf(0, 50, 100), fixture.offsets)
            assertEquals(50, result.value.size)
        }
    }

    @Test
    fun `服务端返回较小有效页大小时以响应为准`() = runTest {
        for (self in listOf(false, true)) {
            val fixture = fixture(total = 21, pageSize = 10, missing = setOf(10, 11))
            val result = assertIs<Outcome.Success<List<UserCollectionBo>>>(fixture.load(self))
            assertEquals(setOf(0, 10, 20), fixture.offsets)
            assertEquals(19, result.value.size)
        }
    }

    @Test
    fun `缺省分页元数据时使用请求页大小并由空页结束`() = runTest {
        for (self in listOf(false, true)) {
            val fixture = fixture(total = 51, includeMetadata = false, missing = setOf(0, 1))
            val result = assertIs<Outcome.Success<List<UserCollectionBo>>>(fixture.load(self))
            assertEquals(setOf(0, 50, 100), fixture.offsets)
            assertEquals(49, result.value.size)
        }
    }

    @Test
    fun `第二页失败不提交不完整的本人收藏快照`() = runTest {
        val fixture = fixture(total = 120, failAt = 50)
        val saved = UserCollectionEntity(subjectId = 999, subjectType = 2, type = 3, accountId = 7, updatedAt = System.currentTimeMillis())
        fixture.dao.upsert(saved)
        assertEquals(Outcome.Failed(Failure.Http(500)), fixture.load(self = true))
        assertTrue(0 in fixture.offsets, "第一页必须取到，才能检验第二页失败")
        assertTrue(50 in fixture.offsets, "设置的第二页故障必须真正到达")
        assertEquals(mapOf((7 to 999) to saved), fixture.dao.store)
    }

    @Test
    fun `服务端重复整页时仍然报错而非写入残缺快照`() = runTest {
        for (self in listOf(false, true)) {
            val fixture = fixture(total = 120, repeatPage = true)
            assertIs<Outcome.Failed>(fixture.load(self))
            assertEquals(setOf(0, 50, 100), fixture.offsets)
            // 他人收藏不经本人收藏表（OtherUserCollectionsCache 没有这张表），只有本人那一路的断言有意义
            if (self) assertTrue(fixture.dao.store.isEmpty())
        }
    }
}
