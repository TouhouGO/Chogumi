package io.github.touhougo.chogumi.core.data.repository

import java.util.concurrent.atomic.AtomicInteger
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.mapper.SubjectField
import io.github.touhougo.chogumi.core.data.db.mapper.SubjectSnapshot
import io.github.touhougo.chogumi.core.data.db.mapper.toBo
import io.github.touhougo.chogumi.core.data.db.mapper.toDomain
import io.github.touhougo.chogumi.core.data.db.mapper.toEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CollectionModify
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.model.p1.P1SlimSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimSubjectInterestDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

class CollectionStoreTest {
    private val engines = RecordedMockEngines()
    @get:org.junit.Rule
    val resources = TestResources()


    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @AfterTest
    fun tearDown() { io.github.touhougo.chogumi.core.data.repository.cancelAndAwait(listOf(scope)) }

    /** 已登录会话：currentSession() 要求 token + userId + username 齐备。 */
    private fun newStore(): TokenStore {
        val store = testTokenStore(scope = scope)
        runBlocking {
            val pending = store.beginSession("token")
            store.completeSession(pending, USER_ID, "fixture_user")
        }
        return store
    }

    private companion object {
        const val USER_ID = 7
    }

    /** 收藏分页 JSON（GET /v0/users/{username}/collections） */
    private val collectionsJson =
        """{"data":[{"subject_id":8,"subject_type":2,"rate":8,"type":3,"comment":"","tags":["TV"],"ep_status":5,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false,"subject":{"id":8,"type":2,"name":"こんにちは アニメ","name_cn":"你好 Anime","short_summary":"简介","images":{"large":"","common":"","medium":"","small":"","grid":""},"volumes":0,"eps":26,"collection_total":10,"score":7.8,"rank":0,"tags":[]}}],"total":1,"limit":50,"offset":0}"""

    private val emptyCollectionsJson = """{"data":[],"total":0,"limit":50,"offset":0}"""

    private fun collectionItemsJson(ids: List<Int>) = ids.joinToString(",") { id ->
        """{"subject_id":$id,"subject_type":2,"rate":0,"type":3,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}"""
    }

    private fun collectionPageJson(ids: List<Int>, total: Int, limit: Int, offset: Int): String =
        """{"data":[${collectionItemsJson(ids)}],"total":$total,"limit":$limit,"offset":$offset}"""

    private fun collectionPageWithoutMetadataJson(ids: List<Int>): String =
        """{"data":[${collectionItemsJson(ids)}]}"""

    /** 单条收藏 JSON（GET /v0/users/{username}/collections/{subjectId}） */
    private val collectionJson =
        """{"subject_id":8,"subject_type":2,"rate":8,"type":3,"comment":"看看","tags":["TV"],"ep_status":5,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false,"subject":{"id":8,"type":2,"name":"こんにちは アニメ","name_cn":"你好 Anime","short_summary":"简介","images":{"large":"","common":"","medium":"","small":"","grid":""},"volumes":0,"eps":26,"collection_total":10,"score":7.8,"rank":0,"tags":[]}}"""

    /** 按 path 路由的 MockEngine 客户端（未命中的 path 返回 404） */
    private fun routedClient(vararg routes: Pair<String, String>): HttpClient {
        val engine = engines.create { request ->
            val body = routes.firstOrNull { it.first == request.url.encodedPath }?.second
            if (body != null) {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            } else {
                respond(content = "not found", status = HttpStatusCode.NotFound)
            }
        }
        return resources.apiClient(engine)
    }

    @Test
    fun `写入响应丢失后回读服务端并保留失败`() = runTest {
        val serverRate = AtomicInteger(5)
        val engine = engines.create { request ->
            if (request.method == io.ktor.http.HttpMethod.Patch) {
                serverRate.set(8)
                throw java.net.SocketTimeoutException("response lost")
            }
            respond(
                collectionJson.replace("\"rate\":8", "\"rate\":${serverRate.get()}"),
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, rate = 5, accountId = USER_ID, updatedAt = 1))
        val repo = testCollections(resources, CollectionApi(resources.apiClient(engine)), FakeSubjectDao(), dao, newStore(), FakeEpisodeWatchStateDao())

        assertEquals(Outcome.Failed(Failure.Timeout), repo.store.updateCollection(8, CollectionModify(rate = 8)))
        assertEquals(8, dao.getBySubjectId(8, USER_ID)?.rate)
        assertEquals(listOf(io.ktor.http.HttpMethod.Patch, io.ktor.http.HttpMethod.Get), engines.requests(engine).map { it.method })
    }

    @Test
    fun `写入中断回读到不同值时仍报失败并发布服务端值`() = runTest {
        val serverRate = AtomicInteger(5)
        val engine = engines.create { request ->
            if (request.method == io.ktor.http.HttpMethod.Patch) {
                serverRate.set(6)
                throw java.io.EOFException("connection lost")
            }
            respond(
                collectionJson.replace("\"rate\":8", "\"rate\":${serverRate.get()}"),
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, rate = 5, accountId = USER_ID, updatedAt = 1))
        val repo = testCollections(resources, CollectionApi(resources.apiClient(engine)), FakeSubjectDao(), dao, newStore(), FakeEpisodeWatchStateDao())

        assertEquals(Outcome.Failed(Failure.Interrupted), repo.store.updateCollection(8, CollectionModify(rate = 8)))
        assertEquals(6, dao.getBySubjectId(8, USER_ID)?.rate)
        assertEquals(listOf(io.ktor.http.HttpMethod.Patch, io.ktor.http.HttpMethod.Get), engines.requests(engine).map { it.method })
    }

    @Test
    fun `确定拒绝的写入不回读`() = runTest {
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Forbidden, HttpStatusCode.NotFound)) {
            val engine = engines.create { respond("", status) }
            val dao = FakeUserCollectionDao()
            dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, rate = 5, accountId = USER_ID, updatedAt = 1))
            val repo = testCollections(resources, CollectionApi(resources.apiClient(engine)), FakeSubjectDao(), dao, newStore(), FakeEpisodeWatchStateDao())
            assertEquals(Outcome.Failed(Failure.Http(status.value)), repo.store.updateCollection(8, CollectionModify(rate = 8)))
            assertEquals(5, dao.getBySubjectId(8, USER_ID)?.rate)
            assertEquals(listOf(io.ktor.http.HttpMethod.Patch), engines.requests(engine).map { it.method })
        }
    }

    @Test
    fun `回读失败保留原写入失败和缓存`() = runTest {
        val engine = engines.create { request ->
            if (request.method == io.ktor.http.HttpMethod.Patch) throw java.net.SocketTimeoutException("lost")
            respond("", HttpStatusCode.Forbidden)
        }
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, rate = 5, accountId = USER_ID, updatedAt = 1))
        val repo = testCollections(resources, CollectionApi(resources.apiClient(engine)), FakeSubjectDao(), dao, newStore(), FakeEpisodeWatchStateDao())
        assertEquals(Outcome.Failed(Failure.Timeout), repo.store.updateCollection(8, CollectionModify(rate = 8)))
        assertEquals(5, dao.getBySubjectId(8, USER_ID)?.rate)
        assertEquals(listOf(io.ktor.http.HttpMethod.Patch, io.ktor.http.HttpMethod.Get), engines.requests(engine).map { it.method })
    }

    @Test
    fun `回读途中换号返回会话已变且不发布`() = runTest {
        val tokenStore = newStore()
        val engine = engines.create { request ->
            if (request.method == io.ktor.http.HttpMethod.Patch) throw java.net.SocketTimeoutException("lost")
            tokenStore.completeSession(tokenStore.beginSession("other-token"), USER_ID + 1, "other")
            respond(collectionJson, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, rate = 5, accountId = USER_ID, updatedAt = 1))
        val repo = testCollections(resources, CollectionApi(resources.apiClient(engine)), FakeSubjectDao(), dao, tokenStore, FakeEpisodeWatchStateDao())
        assertEquals(Outcome.Failed(Failure.SessionChanged), repo.store.updateCollection(8, CollectionModify(rate = 8)))
        assertEquals(5, dao.getBySubjectId(8, USER_ID)?.rate)
    }

    @Test
    fun `updateCollection 网络成功后写穿透更新缓存`() = runTest {
        val engine = engines.create { respond(content = "", status = HttpStatusCode.NoContent) }
        val client = resources.apiClient(engine)
        val tokenStore = newStore()
        val dao = FakeUserCollectionDao()
        dao.upsert(
            UserCollectionEntity(
                subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, rate = 0,
                accountId = USER_ID,
                updatedAt = System.currentTimeMillis(),
            )
        )
        val repo = testCollections(
            resources,
            CollectionApi(client),
            FakeSubjectDao(),
            dao,
            tokenStore,
            FakeEpisodeWatchStateDao(),
        )
        val result = repo.store.updateCollection(8, CollectionModify(rate = 8))
        assertTrue(result is Outcome.Success)
        assertEquals(8, dao.store[USER_ID to 8]?.rate)
        assertEquals(1, engines.requests(engine).count())
    }

    @Test
    fun `updateCollection 写入途中换号返回换号失败，不把取消抛给调用方`() = runTest {
        val tokenStore = newStore()
        val engine = engines.create {
            // PATCH 在途时另一个账号登录：写入结果属于旧账号，作废
            val pending = tokenStore.beginSession("replacement-token")
            tokenStore.completeSession(pending, USER_ID + 1, "other")
            respond("", HttpStatusCode.NoContent)
        }
        val client = resources.apiClient(engine)
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, rate = 0, accountId = USER_ID, updatedAt = System.currentTimeMillis()))
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), dao, tokenStore, FakeEpisodeWatchStateDao())

        // 调用方（详情页保存）据此复位「保存中」并提示；换号若当取消抛出，调用方的协程会悄悄结束、「保存中」永远不复位
        assertEquals(Outcome.Failed(Failure.SessionChanged), repo.store.updateCollection(8, CollectionModify(rate = 8)))
        assertEquals(0, dao.store[USER_ID to 8]?.rate)
    }

    @Test
    fun `updateCollection 浅缓存不能以默认进度和公开性冒充完整收藏`() = runTest {
        val fullJson = collectionJson.replace("\"private\":false", "\"private\":true")
            .replace("\"vol_status\":0", "\"vol_status\":4")
        val engine = engines.create { request ->
            when (request.method) {
                io.ktor.http.HttpMethod.Patch -> respond("", HttpStatusCode.NoContent)
                else -> respond(fullJson, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val client = resources.apiClient(engine)
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(
            subjectId = 8, subjectType = 2, type = 3, accountId = USER_ID, detailsLoaded = false,
            updatedAt = System.currentTimeMillis(),
        ))
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), dao, newStore(), FakeEpisodeWatchStateDao())

        assertIs<Outcome.Success<Unit>>(repo.store.updateCollection(8, CollectionModify(rate = 8)))

        val updated = assertNotNull(dao.getBySubjectId(8, USER_ID))
        assertEquals(8, updated.rate)
        assertEquals(5, updated.epStatus)
        assertEquals(4, updated.volStatus)
        assertTrue(updated.isPrivate)
        assertTrue(updated.detailsLoaded)
        assertEquals(listOf(io.ktor.http.HttpMethod.Patch, io.ktor.http.HttpMethod.Get), engines.requests(engine).map { it.method })
    }

    @Test
    fun `updateCollection 网络失败返回错误且缓存不变`() = runTest {
        val engine = engines.create { respond(content = "err", status = HttpStatusCode.InternalServerError) }
        val client = resources.apiClient(engine)
        val tokenStore = newStore()
        val dao = FakeUserCollectionDao()
        dao.upsert(
            UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, rate = 5, accountId = USER_ID, updatedAt = System.currentTimeMillis())
        )
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), dao, tokenStore, FakeEpisodeWatchStateDao())
        val result = repo.store.updateCollection(8, CollectionModify(rate = 10))
        assertTrue(result is Outcome.Failed)
        assertEquals(5, dao.store[USER_ID to 8]?.rate)
    }

    @Test
    fun `getUserCollections 成功落表嵌套条目并全量替换`() = runTest {
        val client = routedClient("/v0/users/fixture_user/collections" to collectionsJson)
        val tokenStore = newStore()
        val subjectDao = FakeSubjectDao()
        val dao = FakeUserCollectionDao()
        // 预置陈旧行：全量替换后应被清掉
        dao.upsert(UserCollectionEntity(subjectId = 99, subjectType = 2, type = CollectionType.DOING.value, rate = 0, accountId = USER_ID, updatedAt = System.currentTimeMillis()))
        val repo = testCollections(resources, CollectionApi(client), subjectDao, dao, tokenStore, FakeEpisodeWatchStateDao())
        val results = mutableListOf<Load<List<UserCollectionBo>>>()
        repo.store.getUserCollections(SubjectType.ANIME, CollectionType.DOING).collect { results.add(it) }
        assertTrue(results.last() is Load.Ready)
        val last = results.last() as Load.Ready<List<UserCollectionBo>>
        assertEquals(1, last.value.size)
        assertEquals(8, last.value.single().subjectId)
        // 内嵌 subject 已随写缓存落表
        assertEquals("你好 Anime", subjectDao.store[8]?.nameCn)
        // 新行落表、陈旧行被 deleteNotIn 清除（全量替换）
        assertEquals(8, dao.store[USER_ID to 8]?.subjectId)
        assertEquals(null, dao.store[USER_ID to 99])
    }

    @Test
    fun `getUserCollections 新鲜期内再进不取_下拉刷新照取`() = runTest {
        val engine = engines.create {
            respond(collectionsJson, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val client = resources.apiClient(engine)
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), newStore(), FakeEpisodeWatchStateDao())

        repo.store.getUserCollections(SubjectType.ANIME, CollectionType.DOING).toList()
        val again = repo.store.getUserCollections(SubjectType.ANIME, CollectionType.DOING).toList()

        assertEquals(1, engines.requests(engine).count(), "刚取过，第二次只出本地的")
        assertEquals(listOf(8), (again.single() as Load.Ready).value.map { it.subjectId })

        repo.store.getUserCollections(SubjectType.ANIME, CollectionType.DOING, refresh = true).toList()
        assertEquals(2, engines.requests(engine).count())
    }

    @Test
    fun `getUserCollections 刚全量同步过_有本地收藏时不取`() = runTest {
        val engine = engines.create { error("新鲜期内不发请求") }
        val client = resources.apiClient(engine)
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, accountId = USER_ID, updatedAt = 1L))
        val sync = InMemorySyncPreferenceStore().apply { markSynced(USER_ID, System.currentTimeMillis()) }
        val repo = testCollections(
            resources,
            CollectionApi(client), FakeSubjectDao(), dao, newStore(), FakeEpisodeWatchStateDao(), syncPreferenceStore = sync,
        )

        val loads = repo.store.getUserCollections(SubjectType.ANIME, CollectionType.DOING).toList()

        assertEquals(listOf(8), (loads.single() as Load.Ready).value.map { it.subjectId })
    }

    @Test
    fun `getUserCollections 空结果清空本地缓存`() = runTest {
        val client = routedClient("/v0/users/fixture_user/collections" to emptyCollectionsJson)
        val tokenStore = newStore()
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 99, subjectType = 2, type = CollectionType.DOING.value, rate = 0, accountId = USER_ID, updatedAt = System.currentTimeMillis()))
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), dao, tokenStore, FakeEpisodeWatchStateDao())
        val results = mutableListOf<Load<List<UserCollectionBo>>>()
        repo.store.getUserCollections(SubjectType.ANIME, CollectionType.DOING).collect { results.add(it) }
        assertTrue(results.last() is Load.Ready)
        val last = results.last() as Load.Ready<List<UserCollectionBo>>
        assertTrue(last.value.isEmpty())
        // 网络返回空必须清空本地缓存（deleteAll 分支），否则幽灵行离线时照常展示
        assertTrue(dao.store.isEmpty())
    }

    @Test
    fun `refreshEpisodeWatchStates 将 v0 秒级更新时间转换为毫秒且保留零`() = runTest {
        val watchDao = FakeEpisodeWatchStateDao()
        val page = """{"total":2,"limit":100,"offset":0,"data":[
            {"episode":{"id":101,"subject_id":8,"type":0,"name":"e1","name_cn":"","sort":1.0,"airdate":"","comment":0,"duration":"","desc":"","disc":0},"type":2,"updated_at":1700000000},
            {"episode":{"id":102,"subject_id":8,"type":0,"name":"e2","name_cn":"","sort":2.0,"airdate":"","comment":0,"duration":"","desc":"","disc":0},"type":0,"updated_at":0}
        ]}"""
        val client = routedClient("/v0/users/-/collections/8/episodes" to page)
        val repo = testCollections(
            resources,
            CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), newStore(), watchDao,
        )

        assertIs<Outcome.Success<*>>(repo.watchStates.refreshEpisodeWatchStates(8))

        assertEquals(1_700_000_000_000L, watchDao.store[USER_ID to 101]?.updatedAt)
        assertEquals(0L, watchDao.store[USER_ID to 102]?.updatedAt)
    }

    @Test
    fun `分页拉取聚合两页结果`() = runTest {
        val call = AtomicInteger()
        val engine = engines.create {
            val callNumber = call.incrementAndGet()
            // 断言分页 offset 正确串联：首次请求 offset=0，第二次 offset=1
            val offset = it.url.parameters["offset"]
            if (callNumber == 1) assertEquals("0", offset) else assertEquals("1", offset)
            val body = if (callNumber == 1) {
                """{"data":[{"subject_id":1,"subject_type":2,"type":3,"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}],"total":2,"limit":1,"offset":0}"""
            } else {
                """{"data":[{"subject_id":2,"subject_type":2,"type":3,"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}],"total":2,"limit":1,"offset":1}"""
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val tokenStore = newStore()
        val dao = FakeUserCollectionDao()
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), dao, tokenStore, FakeEpisodeWatchStateDao())
        val results = mutableListOf<Load<*>>()
        repo.store.getUserCollections(SubjectType.ANIME, CollectionType.DOING).collect { results.add(it) }
        val last = results.last() as Load.Ready<*>
        @Suppress("UNCHECKED_CAST")
        assertEquals(2, (last.value as List<*>).size)
        assertEquals(2, engines.requests(engine).count())
        assertEquals(2, dao.store.size)
    }

    @Test
    fun `先取首屏后并发取其余分页并按游标排序`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val pageOneRelease = CompletableDeferred<Unit>()
        val pageTwoRelease = CompletableDeferred<Unit>()
        val pageOneReturned = CompletableDeferred<Unit>()
        val pageTwoReturned = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            requests.record(request)
            val offset = request.url.parameters["offset"]!!.toInt()
            when (offset) {
                0 -> respond(collectionPageJson(listOf(1), total = 3, limit = 1, offset = 0), HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()))
                1 -> {
                    pageOneRelease.await()
                    pageOneReturned.complete(Unit)
                    respond(collectionPageJson(listOf(2), total = 3, limit = 1, offset = 1), HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()))
                }
                2 -> {
                    pageTwoRelease.await()
                    pageTwoReturned.complete(Unit)
                    respond(collectionPageJson(listOf(3), total = 3, limit = 1, offset = 2), HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()))
                }
                else -> error("unexpected offset=$offset")
            }
        }
        val client = resources.apiClient(engine)
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), newStore(), FakeEpisodeWatchStateDao())
        val results = mutableListOf<Load<List<UserCollectionBo>>>()
        backgroundScope.launch {
            repo.store.getUserCollections(SubjectType.ANIME, CollectionType.DOING).collect { results += it }
        }

        awaitUntil("首屏先发且两个后续页都已发出") {
            val offsets = requests.map { it.url.parameters["offset"]!!.toInt() }
            offsets.firstOrNull() == 0 && offsets.drop(1).toSet() == setOf(1, 2)
        }
        assertFalse(pageOneReturned.isCompleted, "第 1 页还挂起时应已发出第 2 页")

        pageTwoRelease.complete(Unit)
        awaitUntil("第 2 页先返回") { pageTwoReturned.isCompleted }
        pageOneRelease.complete(Unit)
        awaitUntil("全部分页按序聚合完成") { results.lastOrNull() is Load.Ready }

        val success = assertIs<Load.Ready<List<UserCollectionBo>>>(results.last())
        assertEquals(listOf(1, 2, 3), success.value.map { it.subjectId })
    }

    @Test
    fun `后续分页最多四路并发`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val enteredOffsets = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val releases = (1..6).associateWith { CompletableDeferred<Unit>() }
        val engine = MockEngine { request ->
            requests.record(request)
            val offset = request.url.parameters["offset"]!!.toInt()
            if (offset == 0) {
                respond(collectionPageJson(listOf(1), total = 7, limit = 1, offset = 0), HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()))
            } else {
                enteredOffsets += offset
                releases.getValue(offset).await()
                respond(collectionPageJson(listOf(offset + 1), total = 7, limit = 1, offset = offset), HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()))
            }
        }
        val client = resources.apiClient(engine)
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), newStore(), FakeEpisodeWatchStateDao())
        val results = mutableListOf<Load<List<UserCollectionBo>>>()
        backgroundScope.launch {
            repo.store.getUserCollections(SubjectType.ANIME, CollectionType.DOING).collect { results += it }
        }

        awaitUntil("至少四个后续请求已进入") { enteredOffsets.size >= 4 }
        assertEquals(5, requests.count(), "首屏加四个在途请求")

        releases.values.forEach { it.complete(Unit) }
        awaitUntil("所有分页完成") { results.lastOrNull() is Load.Ready }
        val success = assertIs<Load.Ready<List<UserCollectionBo>>>(results.last())
        assertEquals((1..7).toList(), success.value.map { it.subjectId })
    }

    @Test
    fun `任一并发页失败保留已有本人收藏快照`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            requests.record(request)
            val offset = request.url.parameters["offset"]!!.toInt()
            if (offset == 0) {
                respond(collectionPageJson(listOf(1), total = 3, limit = 1, offset = 0), HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()))
            } else if (offset == 1) {
                respond("{}", HttpStatusCode.InternalServerError, headersOf("Content-Type", ContentType.Application.Json.toString()))
            } else {
                respond(collectionPageJson(listOf(3), total = 3, limit = 1, offset = 2), HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()))
            }
        }
        val client = resources.apiClient(engine)
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 999, subjectType = SubjectType.ANIME.value, type = CollectionType.DOING.value, accountId = USER_ID, updatedAt = System.currentTimeMillis()))
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), dao, newStore(), FakeEpisodeWatchStateDao())
        val results = mutableListOf<Load<List<UserCollectionBo>>>()
        repo.store.getUserCollections(SubjectType.ANIME, CollectionType.DOING, refresh = true).collect { results += it }

        assertIs<Load.Failed>(results.last())
        assertEquals(setOf(USER_ID to 999), dao.store.keys)
    }

    @Test
    fun `并发分页期间切换会话时整体放弃且不提交缓存`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val tokenStore = newStore()
        val engine = MockEngine { request ->
            requests.record(request)
            val offset = request.url.parameters["offset"]!!.toInt()
            when (offset) {
                0 -> respond(collectionPageJson(listOf(1), total = 2, limit = 1, offset = 0), HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()))
                1 -> {
                    val pending = tokenStore.beginSession("replacement-token")
                    tokenStore.completeSession(pending, USER_ID + 1, "other")
                    respond(collectionPageJson(listOf(2), total = 2, limit = 1, offset = 1), HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()))
                }
                else -> error("unexpected offset=$offset")
            }
        }
        val client = resources.apiClient(engine)
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(
            subjectId = 999,
            subjectType = SubjectType.ANIME.value,
            type = CollectionType.DOING.value,
            accountId = USER_ID,
            updatedAt = System.currentTimeMillis(),
        ))
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), dao, tokenStore, FakeEpisodeWatchStateDao())

        // 换号的结果作废：最后一拍是 SessionChanged 失败，不当作协程取消悄悄停掉
        assertEquals(
            Load.Failed(Failure.SessionChanged),
            repo.store.getUserCollections(SubjectType.ANIME, CollectionType.DOING, refresh = true).toList().last(),
        )
        assertEquals(setOf(0, 1), requests.map { it.url.parameters["offset"]!!.toInt() }.toSet())
        assertEquals(setOf(USER_ID to 999), dao.store.keys)
    }

    @Test
    fun `后续页全与之前页面重复时返回分页未前进错误`() = runTest {
        val engine = engines.create { request ->
            val offset = request.url.parameters["offset"]!!.toInt()
            respond(collectionPageJson(listOf(1), total = 2, limit = 1, offset = offset), HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val client = resources.apiClient(engine)
        val results = mutableListOf<Load<List<UserCollectionBo>>>()
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), newStore(), FakeEpisodeWatchStateDao())

        repo.store.getUserCollections(SubjectType.ANIME, CollectionType.DOING, refresh = true).collect { results += it }

        assertEquals(Failure.BadResponse, assertIs<Load.Failed>(results.last()).failure)
    }

    @Test
    fun `缺少分页元数据时重复页立即报未前进错误`() = runTest {
        for (selfPath in listOf(true, false)) {
            val requests = ConcurrentRequestRecorder()
            val engine = MockEngine { request ->
                requests.record(request)
                check(requests.count() <= 2) { "无元数据的重复页不应继续请求" }
                respond(collectionPageWithoutMetadataJson(listOf(1)), HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()))
            }
            val client = resources.apiClient(engine)
            val repo = testCollections(
                resources,
                CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), newStore(), FakeEpisodeWatchStateDao(),
            )
            val failure = if (selfPath) {
                val results = mutableListOf<Load<List<UserCollectionBo>>>()
                repo.store.getUserCollections(SubjectType.ANIME, CollectionType.DOING, refresh = true).collect { results += it }
                assertIs<Load.Failed>(results.last()).failure
            } else {
                val others = testOtherUserCollectionsCache(CollectionApi(client), FakeSubjectDao(), newStore())
                assertIs<Outcome.Failed>(others.collectionsFor("other", SubjectType.ANIME)).failure
            }

            assertEquals(Failure.BadResponse, failure)
            assertEquals(setOf(0, 50), requests.map { it.url.parameters["offset"]!!.toInt() }.toSet())
        }
    }

    @Test
    fun `getCollection 联查 subject 详情`() = runTest {
        val client = routedClient("/v0/users/fixture_user/collections/8" to collectionJson)
        val tokenStore = newStore()
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 8, type = 2, name = "n", nameCn = "中", updatedAt = System.currentTimeMillis()))
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, rate = 0, accountId = USER_ID, updatedAt = System.currentTimeMillis()))
        val repo = testCollections(resources, CollectionApi(client), subjectDao, dao, tokenStore, FakeEpisodeWatchStateDao())
        val results = mutableListOf<Load<UserCollectionBo?>>()
        repo.store.getCollection(8).collect { results.add(it) }
        // 缓存一拍：读路径联查 subjectDao 补全条目中文名
        val first = results.first()
        assertTrue(first is Load.Ready)
        assertEquals("中", first.value?.subject?.nameCn)
        // 末拍：网络单条直出（内嵌 subject 原样返回，不经过 mapCache）
        val last = results.last()
        assertTrue(last is Load.Ready)
        assertEquals(8, last.value?.subjectId)
        assertEquals("你好 Anime", last.value?.subject?.nameCn)
        // 内嵌 subject 已落表，覆盖预置行
        assertEquals("你好 Anime", subjectDao.store[8]?.nameCn)
    }

    @Test
    fun `updatedAt 从 DTO 解析并往返无损`() {
        val dto = io.github.touhougo.chogumi.core.network.model.v0.UserCollectionDto(
            subjectId = 8, subjectType = 2, type = 3,
            updatedAt = "2026-09-15T10:00:00+08:00",
        )
        val bo = dto.toDomain()
        val back = bo.toEntity(accountId = 1).toBo()
        assertEquals(1789437600000L, bo.updatedAt, "按偏移解析成 2026-09-15T02:00:00Z")
        assertEquals(bo.updatedAt, back.updatedAt)
    }

    @Test
    fun `syncEpisodeProgress 更新缓存进度`() = runTest {
        val engine = engines.create { respond(content = "", status = HttpStatusCode.NoContent) }
        val client = resources.apiClient(engine)
        val dao = FakeUserCollectionDao()
        dao.upsert(
            UserCollectionEntity(subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, epStatus = 2, accountId = USER_ID, updatedAt = System.currentTimeMillis()),
        )
        val store = newStore()
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), dao, store, FakeEpisodeWatchStateDao())
        repo.watchStates.syncEpisodeProgress(subjectId = 8, session = store.session(), epStatus = 3)
        assertEquals(3, dao.store[USER_ID to 8]?.epStatus)
    }

    @Test
    fun `updateCollection 对非书籍条目不向PATCH请求体包含ep_status与vol_status以满足服务端语义`() = runTest {
        val engine = engines.create { request ->
            if (request.method == io.ktor.http.HttpMethod.Patch) {
                respond("", HttpStatusCode.NoContent)
            } else {
                respond(collectionJson, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val client = resources.apiClient(engine)
        val subjectDao = FakeSubjectDao()
        // 条目 8 为动画（type = 2，非书籍）
        subjectDao.upsert(SubjectEntity(id = 8, type = 2, name = "动画", nameCn = "动画", updatedAt = System.currentTimeMillis()))
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, rate = 7, epStatus = 5, accountId = USER_ID, updatedAt = System.currentTimeMillis()))
        val repo = testCollections(resources, CollectionApi(client), subjectDao, dao, newStore(), FakeEpisodeWatchStateDao())

        val result = repo.store.updateCollection(8, CollectionModify(type = CollectionType.ON_HOLD, epStatus = 5, volStatus = 0))
        assertIs<Outcome.Success<Unit>>(result)
        val patchBody = assertNotNull(engines.requests(engine).singleOrNull { it.method == io.ktor.http.HttpMethod.Patch }).body.toByteArray().decodeToString()
        // 服务端实测 400 依据：can't set 'vol_status' or 'ep_status' on non-book subject
        assertFalse(patchBody.contains("ep_status"), "非书籍条目严禁在 PATCH 请求体中包含 ep_status")
        assertFalse(patchBody.contains("vol_status"), "非书籍条目严禁在 PATCH 请求体中包含 vol_status")
    }

    @Test
    fun `updateCollection 对书籍条目允许在PATCH请求体中包含ep_status与vol_status`() = runTest {
        val engine = engines.create { request ->
            if (request.method == io.ktor.http.HttpMethod.Patch) {
                respond("", HttpStatusCode.NoContent)
            } else {
                respond(collectionJson, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val client = resources.apiClient(engine)
        val subjectDao = FakeSubjectDao()
        // 条目 9 为书籍（type = 1）
        subjectDao.upsert(SubjectEntity(id = 9, type = 1, name = "漫画", nameCn = "漫画", updatedAt = System.currentTimeMillis()))
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 9, subjectType = 1, type = 3, rate = 7, epStatus = 5, volStatus = 2, accountId = USER_ID, updatedAt = System.currentTimeMillis()))
        val repo = testCollections(resources, CollectionApi(client), subjectDao, dao, newStore(), FakeEpisodeWatchStateDao())

        val result = repo.store.updateCollection(9, CollectionModify(type = CollectionType.ON_HOLD, epStatus = 10, volStatus = 3))
        assertIs<Outcome.Success<Unit>>(result)
        val patchBody = assertNotNull(engines.requests(engine).singleOrNull { it.method == io.ktor.http.HttpMethod.Patch }).body.toByteArray().decodeToString()
        assertTrue(patchBody.contains("\"ep_status\":10"), "书籍条目允许在 PATCH 请求体中包含 ep_status")
        assertTrue(patchBody.contains("\"vol_status\":3"), "书籍条目允许在 PATCH 请求体中包含 vol_status")
    }

    @Test
    fun `updateCollection 未收藏条目PATCH404回退POST且只回退一次`() = runTest {
        val engine = engines.create { request ->
            when (request.method) {
                io.ktor.http.HttpMethod.Patch -> respond("", HttpStatusCode.NotFound)
                io.ktor.http.HttpMethod.Post -> respond("", HttpStatusCode.NoContent)
                else -> error("不应发出其他请求：${request.method}")
            }
        }
        val client = resources.apiClient(engine)
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, rate = 0, accountId = USER_ID, updatedAt = System.currentTimeMillis()))
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), dao, newStore(), FakeEpisodeWatchStateDao())

        val result = repo.store.updateCollection(8, CollectionModify(type = CollectionType.ON_HOLD, rate = 9))
        assertIs<Outcome.Success<Unit>>(result)
        // PATCH 必须先于 POST；POST 只发一次（无谓双写是缺陷）
        assertEquals(
            listOf(io.ktor.http.HttpMethod.Patch, io.ktor.http.HttpMethod.Post),
            engines.requests(engine).map { it.method },
        )
    }

    @Test
    fun `放送表的收藏摘要整批合并_不逐部查库_不覆盖完整收藏`() = runTest {
        val dao = FakeUserCollectionDao()
        val full = UserCollectionEntity(
            subjectId = 1, subjectType = 2, type = 3, rate = 9, epStatus = 7, accountId = USER_ID, detailsLoaded = true,
            updatedAt = 1L,
        )
        dao.upsert(full)
        val tokenStore = newStore()
        val repo = testCollections(resources, CollectionApi(routedClient()), FakeSubjectDao(), dao, tokenStore, FakeEpisodeWatchStateDao())
        val interest = SlimSubjectInterestDto(
            id = 0, rate = 5, type = 2, comment = "", tags = emptyList(), updatedAt = 10L,
        )
        val subjects = (1..3).map { id ->
            P1SlimSubjectDto(id = id, name = "S$id", type = 2, interest = interest)
        } + P1SlimSubjectDto(id = 4, name = "未收藏", type = 2)
        val before = dao.getBySubjectIdCallCount

        repo.store.mergeP1Interests(subjects, tokenStore.session())

        assertEquals(0, dao.getBySubjectIdCallCount - before, "已有行一次批量读出")
        assertEquals(full, dao.store[USER_ID to 1], "完整收藏不被摘要覆盖")
        assertEquals(listOf(2, 3), dao.store.keys.filter { it.first == USER_ID && it.second != 1 }.map { it.second }.sorted())
        assertFalse(dao.store.getValue(USER_ID to 2).detailsLoaded)
    }

    @Test
    fun `updateCollection 只改进度遇到未收藏_不替用户建收藏`() = runTest {
        val engine = engines.create { request ->
            when (request.method) {
                io.ktor.http.HttpMethod.Patch -> respond("", HttpStatusCode.NotFound)
                io.ktor.http.HttpMethod.Post -> respond("", HttpStatusCode.NoContent)
                else -> error("不应发出其他请求：${request.method}")
            }
        }
        val client = resources.apiClient(engine)
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 8, type = 1, name = "书", nameCn = "书", updatedAt = System.currentTimeMillis()))
        val repo = testCollections(resources, CollectionApi(client), subjectDao, FakeUserCollectionDao(), newStore(), FakeEpisodeWatchStateDao())

        val result = repo.store.updateCollection(8, CollectionModify(volStatus = 3))

        assertEquals(Outcome.Failed(Failure.Http(404)), result)
        assertEquals(listOf(io.ktor.http.HttpMethod.Patch), engines.requests(engine).map { it.method })
    }

    @Test
    fun `updateCollection 回退POST成功后缓存写穿透与PATCH成功路径一致`() = runTest {
        val engine = engines.create { request ->
            when (request.method) {
                io.ktor.http.HttpMethod.Patch -> respond("", HttpStatusCode.NotFound)
                io.ktor.http.HttpMethod.Post -> respond("", HttpStatusCode.NoContent)
                else -> error("不应发出其他请求：${request.method}")
            }
        }
        val client = resources.apiClient(engine)
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 8, type = 2, name = "动画", nameCn = "动画", updatedAt = System.currentTimeMillis()))
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(
            subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, rate = 7,
            epStatus = 5, comment = "旧吐槽", accountId = USER_ID, detailsLoaded = true,
            updatedAt = System.currentTimeMillis(),
        ))
        val repo = testCollections(resources, CollectionApi(client), subjectDao, dao, newStore(), FakeEpisodeWatchStateDao())

        val result = repo.store.updateCollection(8, CollectionModify(type = CollectionType.ON_HOLD, rate = 9, epStatus = 12, comment = "新吐槽"))
        assertIs<Outcome.Success<Unit>>(result)
        val updated = assertNotNull(dao.getBySubjectId(8, USER_ID))
        assertEquals(4, updated.type)
        assertEquals(9, updated.rate)
        assertEquals(12, updated.epStatus)
        assertEquals("新吐槽", updated.comment)
        assertTrue(updated.detailsLoaded)
    }

    @Test
    fun `updateCollection 非书籍条目POST回退请求体同样不含ep_status与vol_status`() = runTest {
        val engine = engines.create { request ->
            when (request.method) {
                io.ktor.http.HttpMethod.Patch -> respond("", HttpStatusCode.NotFound)
                io.ktor.http.HttpMethod.Post -> {
                    respond("", HttpStatusCode.NoContent)
                }
                else -> error("不应发出其他请求：${request.method}")
            }
        }
        val client = resources.apiClient(engine)
        val subjectDao = FakeSubjectDao()
        // 条目 8 为动画（type = 2，非书籍）
        subjectDao.upsert(SubjectEntity(id = 8, type = 2, name = "动画", nameCn = "动画", updatedAt = System.currentTimeMillis()))
        val dao = FakeUserCollectionDao()
        dao.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, accountId = USER_ID, updatedAt = System.currentTimeMillis()))
        val repo = testCollections(resources, CollectionApi(client), subjectDao, dao, newStore(), FakeEpisodeWatchStateDao())

        val result = repo.store.updateCollection(8, CollectionModify(type = CollectionType.ON_HOLD, epStatus = 5, volStatus = 0))
        assertIs<Outcome.Success<Unit>>(result)
        val postBody = assertNotNull(engines.requests(engine).singleOrNull { it.method == io.ktor.http.HttpMethod.Post }).body.toByteArray().decodeToString()
        // POST 与 PATCH 共用 UserSubjectCollectionModifyPayload，剥离规则必须同样生效：
        // 服务端实测 400 依据：can't set 'vol_status' or 'ep_status' on non-book subject
        assertFalse(postBody.contains("ep_status"), "非书籍条目严禁在 POST 请求体中包含 ep_status")
        assertFalse(postBody.contains("vol_status"), "非书籍条目严禁在 POST 请求体中包含 vol_status")
    }

    @Test
    fun `updateCollection PATCH返回400不回退POST`() = runTest {
        val engine = engines.create { request ->
            when (request.method) {
                io.ktor.http.HttpMethod.Patch -> respond("", HttpStatusCode.BadRequest)
                io.ktor.http.HttpMethod.Post -> respond("", HttpStatusCode.NoContent)
                else -> error("不应发出其他请求：${request.method}")
            }
        }
        val client = resources.apiClient(engine)
        val dao = FakeUserCollectionDao()
        val cached = UserCollectionEntity(
            subjectId = 8, subjectType = 2, type = 3, rate = 5, accountId = USER_ID,
            detailsLoaded = true, updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(cached)
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), dao, newStore(), FakeEpisodeWatchStateDao())

        val result = repo.store.updateCollection(8, CollectionModify(type = CollectionType.ON_HOLD, rate = 9))

        assertEquals(Outcome.Failed(Failure.Http(400)), result)
        assertEquals(listOf(io.ktor.http.HttpMethod.Patch), engines.requests(engine).map { it.method })
        assertEquals(cached, dao.store[USER_ID to 8])
    }

    @Test
    fun `updateCollection 非法评分不发送网络请求`() = runTest {
        val engine = engines.create { respond("", HttpStatusCode.NoContent) }
        val client = resources.apiClient(engine)
        val dao = FakeUserCollectionDao()
        val cached = UserCollectionEntity(
            subjectId = 8, subjectType = 2, type = 3, rate = 5, accountId = USER_ID,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(cached)
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), dao, newStore(), FakeEpisodeWatchStateDao())

        val result = repo.store.updateCollection(8, CollectionModify(rate = 99))

        assertIs<Outcome.Failed>(result)
        assertTrue(engines.requests(engine).none(), "非法评分在客户端校验，不发请求")
        assertEquals(cached, dao.store[USER_ID to 8])
    }

    /** 全量同步用 MockEngine：按 subject_type 返回该类型单条，记录请求顺序与 token。 */
    private fun 全量同步客户端(
        failForSubjectType: Int? = null,
    ): Pair<HttpClient, ConcurrentRequestRecorder> {
        val engine = engines.create { request ->
            val subjectType = request.url.parameters["subject_type"]!!.toInt()
            // 测试 fake 验证请求使用当前 session token（beginSession 写入 "token"）
            assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
            if (subjectType == failForSubjectType) {
                respond(content = "boom", status = HttpStatusCode.InternalServerError)
            } else {
                val subjectId = 1000 + subjectType
                respond(
                    content = """{"data":[{"subject_id":$subjectId,"subject_type":$subjectType,"type":3,"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}],"total":1,"limit":50,"offset":0}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
        }
        return resources.apiClient(engine) to engines.requests(engine)
    }

    @Test
    fun `全量同步并发请求所有条目类型并在全部成功后返回成功`() = runTest {
        // MockEngine 在多个线程上并发回调五类请求：记录表必须线程安全
        val (client, requestedSubjectTypes) = 全量同步客户端()
        val dao = FakeUserCollectionDao()
        val repo = testCollections(
            resources,
            CollectionApi(client),
            FakeSubjectDao(),
            dao,
            newStore(),
            FakeEpisodeWatchStateDao(),
        )

        val result = repo.sync.refreshAllUserCollections(force = true)

        assertIs<Outcome.Success<Unit>>(result)
        // BOOK(1)、ANIME(2)、MUSIC(3)、GAME(4)、REAL(6)，不含 UNKNOWN；五类并发，顺序不固定
        assertEquals(setOf(1, 2, 3, 4, 6), requestedSubjectTypes.map { it.url.parameters["subject_type"]!!.toInt() }.toSet())
        // 每个分区仍由 getUserCollections 经 replaceSnapshot 原子替换落表：五种类型各一条
        val expectedSnapshot = listOf(1, 2, 3, 4, 6).associate { type ->
            (USER_ID to (1000 + type)) to type
        }
        assertEquals(expectedSnapshot.keys, dao.store.keys)
        expectedSnapshot.forEach { (key, type) ->
            assertEquals(type, dao.store[key]?.subjectType)
        }
    }

    @Test
    fun `某一类型同步失败时返回错误且不清空其他类型已同步缓存`() = runTest {
        // MockEngine 在多个线程上并发回调五类请求：记录表必须线程安全
        val (client, requestedSubjectTypes) = 全量同步客户端(failForSubjectType = SubjectType.MUSIC.value)
        val dao = FakeUserCollectionDao()
        val repo = testCollections(
            resources,
            CollectionApi(client),
            FakeSubjectDao(),
            dao,
            newStore(),
            FakeEpisodeWatchStateDao(),
        )

        val result = repo.sync.refreshAllUserCollections(force = true)

        assertIs<Outcome.Failed>(result)
        // 五类**同时**开始：MUSIC 失败不阻断其他类型，五类都会被请求。
        assertEquals(
            setOf(SubjectType.BOOK.value, SubjectType.ANIME.value, SubjectType.MUSIC.value, SubjectType.GAME.value, SubjectType.REAL.value),
            requestedSubjectTypes.map { it.url.parameters["subject_type"]!!.toInt() }.toSet(),
        )
        // 成功类型已落表（replaceSnapshot 只替换成功分区）
        assertEquals(1002, dao.get(SubjectType.ANIME.value, null, USER_ID).single().subjectId)
        // MUSIC 分区失败未落表
        assertTrue(dao.get(SubjectType.MUSIC.value, null, USER_ID).isEmpty())
    }

    @Test
    fun `observeEpisodeWatchStates 在数据写入时响应式发射最新快照`() = runTest {
        val watchDao = FakeEpisodeWatchStateDao()
        val engine = engines.create { respond("{}", HttpStatusCode.OK) }
        val client = resources.apiClient(engine)
        val tokenStore = newStore()
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), tokenStore, watchDao)

        // 初始为空（挂起等待首个快照发射）
        val initial = repo.observer.observeEpisodeWatchStates(8).first().valueOrNull().orEmpty()
        assertTrue(initial.isEmpty())

        // 写入一条记录
        watchDao.upsertAll(listOf(
            io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity(
                episodeId = 101, subjectId = 8, accountId = USER_ID, status = 2, updatedAt = 1000L,
            ),
        ))

        // 响应式等待最新快照发射
        val updated = repo.observer.observeEpisodeWatchStates(8)
            .mapNotNull { it.valueOrNull()?.takeIf { states -> states.containsKey(101) } }
            .first()
        assertEquals(io.github.touhougo.chogumi.core.model.EpisodeStatus.DONE, updated[101])
    }

    @Test
    fun `observeEpisodeWatchStateSnapshot 同一份快照提供逐集状态和最新看过时刻`() = runTest {
        val watchDao = FakeEpisodeWatchStateDao()
        val client = resources.apiClient { respond("{}", HttpStatusCode.OK) }
        val repo = testCollections(
            resources,
            CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), newStore(), watchDao,
        )
        watchDao.upsertAll(listOf(
            io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity(
                episodeId = 101, subjectId = 8, accountId = USER_ID, status = 2, updatedAt = 1_000L,
            ),
            io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity(
                episodeId = 102, subjectId = 8, accountId = USER_ID, status = 2, updatedAt = 2_000L,
            ),
            io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity(
                episodeId = 103, subjectId = 8, accountId = USER_ID, status = 1, updatedAt = 9_000L,
            ),
            io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity(
                episodeId = 201, subjectId = 9, accountId = USER_ID, status = 2, updatedAt = 2_500L,
            ),
            io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity(
                episodeId = 104, subjectId = 8, accountId = USER_ID + 1, status = 2, updatedAt = 10_000L,
            ),
        ))

        val snapshot = repo.observer.observeEpisodeWatchStateSnapshot()
            .mapNotNull { it.valueOrNull()?.takeIf { snapshot -> snapshot.statesBySubject.isNotEmpty() } }
            .first()

        assertEquals(
            mapOf(
                8 to mapOf(
                    101 to io.github.touhougo.chogumi.core.model.EpisodeStatus.DONE,
                    102 to io.github.touhougo.chogumi.core.model.EpisodeStatus.DONE,
                    103 to io.github.touhougo.chogumi.core.model.EpisodeStatus.WISH,
                ),
                9 to mapOf(201 to io.github.touhougo.chogumi.core.model.EpisodeStatus.DONE),
            ),
            snapshot.statesBySubject,
        )
        assertEquals(mapOf(8 to 2_000L, 9 to 2_500L), snapshot.latestWatchedAtBySubject)
    }

    @Test
    fun `syncEpisodeWatchStates 严格只upsert正片行保留既有非正片记录`() = runTest {
        val watchDao = FakeEpisodeWatchStateDao()
        // 预置 SP 行（901）
        watchDao.upsertAll(listOf(
            io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity(
                episodeId = 901, subjectId = 8, accountId = USER_ID, status = 2, updatedAt = 500L,
            ),
        ))
        val engine = engines.create { respond("{}", HttpStatusCode.OK) }
        val client = resources.apiClient(engine)
        val tokenStore = newStore()
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), tokenStore, watchDao)

        val eps = listOf(
            io.github.touhougo.chogumi.core.model.EpisodeBo(id = 101, subjectId = 8, type = 0, name = "e1", nameCn = "", sort = 1.0),
            io.github.touhougo.chogumi.core.model.EpisodeBo(id = 102, subjectId = 8, type = 0, name = "e2", nameCn = "", sort = 2.0),
            io.github.touhougo.chogumi.core.model.EpisodeBo(id = 901, subjectId = 8, type = 1, name = "sp1", nameCn = "", sort = 1.5),
        )

        // 目标状态表（由 ProgressOrchestrator 用 statesAfterWatchedUpTo 算出）：101 看过，102 无记录
        repo.watchStates.syncEpisodeWatchStates(
            8, eps,
            mapOf(
                101 to io.github.touhougo.chogumi.core.model.EpisodeStatus.DONE,
                102 to io.github.touhougo.chogumi.core.model.EpisodeStatus.NONE,
            ),
            tokenStore.session(),
        )

        val store = watchDao.store
        assertEquals(2, store[USER_ID to 101]?.status, "正片101必须为DONE")
        assertEquals(0, store[USER_ID to 102]?.status, "正片102必须为NONE")
        assertNotNull(store[USER_ID to 901], "既有SP行绝对不能被删除")
        assertEquals(2, store[USER_ID to 901]?.status, "既有SP状态必须保留")
    }

    @Test
    fun `别处写入与收藏无关的条目时收藏流不再发一拍，收藏引用的条目变了照发`() = runTest {
        val subjectDao = FakeSubjectDao()
        val collectionDao = FakeUserCollectionDao()
        // 映射与收集都不调度（Unconfined）：一次写入在 upsert 返回前就传到了收集方，写完即可断言这一拍发没发
        val repo = testCollections(
            resources,
            CollectionApi(resources.apiClient { error("只读本地") }),
            subjectDao, collectionDao, newStore(), FakeEpisodeWatchStateDao(),
            computationDispatcher = Dispatchers.Unconfined,
        )
        subjectDao.upsert(SubjectEntity(id = 8, type = 2, name = "Anime", nameCn = "", updatedAt = 0))
        collectionDao.upsert(
            UserCollectionEntity(subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, updatedAt = 0, accountId = USER_ID),
        )
        val emissions = CopyOnWriteArrayList<Load<List<UserCollectionBo>>>()
        backgroundScope.launch(Dispatchers.Unconfined) { repo.observer.observeCollections().collect { emissions += it } }
        awaitUntil("收藏流发出首拍") { emissions.isNotEmpty() }

        // 探索页的一个货架落库：subjects 表整表失效，但收藏引用的那一行没变
        subjectDao.upsertAll(listOf(SubjectEntity(id = 9, type = 2, name = "Other", nameCn = "", updatedAt = 0)))
        assertEquals(1, emissions.size)

        subjectDao.upsert(SubjectEntity(id = 8, type = 2, name = "Anime", nameCn = "动画", updatedAt = 0))
        assertEquals(2, emissions.size)
        assertEquals("动画", emissions.last().valueOrNull()!!.single().subject?.nameCn)
    }

    @Test
    fun `同一天里收藏引用的条目被别处原样写到，收藏流不再发一拍`() = runTest {
        val subjectDao = FakeSubjectDao()
        val collectionDao = FakeUserCollectionDao()
        val repo = testCollections(
            resources,
            CollectionApi(resources.apiClient { error("只读本地") }),
            subjectDao, collectionDao, newStore(), FakeEpisodeWatchStateDao(),
            computationDispatcher = Dispatchers.Unconfined,
        )
        val dayStart = 100L * 24 * 60 * 60 * 1000
        val summary = { at: Long ->
            SubjectSnapshot(SubjectEntity(id = 8, type = 2, name = "Anime", nameCn = "动画", updatedAt = at), setOf(SubjectField.NAME, SubjectField.NAME_CN))
        }
        subjectDao.mergeAll(listOf(summary(dayStart)))
        collectionDao.upsert(
            UserCollectionEntity(subjectId = 8, subjectType = 2, type = CollectionType.DOING.value, updatedAt = 0, accountId = USER_ID),
        )
        val emissions = CopyOnWriteArrayList<Load<List<UserCollectionBo>>>()
        backgroundScope.launch(Dispatchers.Unconfined) { repo.observer.observeCollections().collect { emissions += it } }
        awaitUntil("收藏流发出首拍") { emissions.isNotEmpty() }

        // 放送表、探索页货架几小时后又写到这部在看的作品，内容没变
        subjectDao.mergeAll(listOf(summary(dayStart + 3_600_000)))
        assertEquals(1, emissions.size)
    }

    @Test
    fun `取在看列表的读写与映射跑在注入的计算调度器上`() = runTest {
        val computation = CountingDispatcher()
        val repo = testCollections(
            resources,
            CollectionApi(resources.apiClient { respond(emptyCollectionsJson, headers = headersOf(HttpHeaders.ContentType, "application/json")) }),
            FakeSubjectDao(), FakeUserCollectionDao(), newStore(), FakeEpisodeWatchStateDao(),
            computationDispatcher = computation,
        )
        assertTrue(repo.store.getUserCollections(SubjectType.GAME, CollectionType.DOING).toList().last() is Load.Ready)
        assertTrue(computation.dispatches > 0, "在看列表没有切到计算调度器")
    }
}
