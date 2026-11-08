package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** 他人公开收藏现拉、会话内 LRU 缓存、自动汇总门槛。 */
class OtherUserCollectionsTest {
    @get:org.junit.Rule
    val resources = TestResources()


    private fun client(routes: Map<Pair<String, Int>, String>, requests: ConcurrentRequestRecorder? = null): HttpClient {
        val engine = MockEngine { request ->
            requests?.record(request)
            val username = request.url.encodedPath.removePrefix("/v0/users/").removeSuffix("/collections")
            val subjectType = request.url.parameters["subject_type"]!!.toInt()
            val body = routes[username to subjectType]
            if (body == null) respond("not found", HttpStatusCode.NotFound)
            else respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        return resources.apiClient(engine)
    }

    private fun page(ids: List<Int>, type: Int, total: Int = ids.size) =
        """{"data":[${ids.joinToString(",") { """{"subject_id":$it,"subject_type":$type,"type":3,"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}""" }}],"total":$total,"limit":50,"offset":0}"""

    /** 带内嵌条目摘要的一页动画收藏（type=2 看过）。 */
    private fun withSubjects(ids: List<Int>) = """{"data":[""" +
        ids.joinToString(",") { """{"subject_id":$it,"subject_type":2,"type":2,"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false,"subject":{"id":$it,"type":2,"name":"n$it","name_cn":"中$it","short_summary":"","date":"2024-01-01","images":{"large":"","common":"","medium":"","small":"","grid":""},"volumes":0,"eps":12,"collection_total":0,"score":0,"rank":0,"tags":[]}}""" } +
        """],"total":${ids.size},"limit":50,"offset":0}"""

    private fun cache(client: HttpClient, username: String = "fixture_user", subjectDao: FakeSubjectDao = FakeSubjectDao()) =
        testOtherUserCollectionsCache(CollectionApi(client), subjectDao, testTokenStore(resources, userId = 7, username = username))

    @Test
    fun `现拉他人某一类公开收藏`() = runTest {
        val client = client(mapOf(("fixture_user" to SubjectType.ANIME.value) to page(listOf(1, 2), SubjectType.ANIME.value)))

        val result = cache(client).collectionsFor("fixture_user", SubjectType.ANIME)

        val data = assertIs<Outcome.Success<List<io.github.touhougo.chogumi.core.model.UserCollectionBo>>>(result).value
        assertEquals(listOf(1, 2), data.map { it.subjectId })
    }

    @Test
    fun `同一用户第二次取走会话内缓存不再打网络`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val client = client(mapOf(("fixture_user" to SubjectType.ANIME.value) to page(listOf(1), SubjectType.ANIME.value)), requests)
        val cache = cache(client)

        assertFalse(cache.hasCached("fixture_user", SubjectType.ANIME))
        assertIs<Outcome.Success<*>>(cache.collectionsFor("fixture_user", SubjectType.ANIME))
        assertTrue(cache.hasCached("fixture_user", SubjectType.ANIME), "新开的生涯 VM 靠它直接读缓存")
        assertFalse(cache.hasCached("fixture_user", SubjectType.BOOK))
        val afterFirst = requests.count()
        assertIs<Outcome.Success<*>>(cache.collectionsFor("fixture_user", SubjectType.ANIME))
        assertEquals(afterFirst, requests.count(), "第二次命中共有缓存，不得再发请求")
    }

    @Test
    fun `登出后再取同一用户 缓存作废重新现拉 不拿上一个视角的列表`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val client = client(mapOf(("fixture_user" to SubjectType.ANIME.value) to page(listOf(1), SubjectType.ANIME.value)), requests)
        val tokenStore = testTokenStore(resources, userId = 7, username = "me")
        val cache = testOtherUserCollectionsCache(CollectionApi(client), FakeSubjectDao(), tokenStore)

        cache.collectionsFor("fixture_user", SubjectType.ANIME)
        val afterFirst = requests.count()
        tokenStore.clear()
        assertIs<Outcome.Success<*>>(cache.collectionsFor("fixture_user", SubjectType.ANIME))

        assertTrue(requests.count() > afterFirst, "换成游客视角后必须重新现拉")
    }

    @Test
    fun `下拉刷新跳过缓存重拉并覆盖`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val response = AtomicReference(page(listOf(1), SubjectType.ANIME.value))
        val engine = MockEngine { request ->
            requests.record(request)
            respond(response.get(), HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val cache = cache(resources.apiClient(engine))

        assertEquals(listOf(1), assertIs<Outcome.Success<List<io.github.touhougo.chogumi.core.model.UserCollectionBo>>>(
            cache.collectionsFor("fixture_user", SubjectType.ANIME),
        ).value.map { it.subjectId })
        assertEquals(1, requests.count())

        response.set(page(listOf(2, 3), SubjectType.ANIME.value))
        val refreshed = assertIs<Outcome.Success<List<io.github.touhougo.chogumi.core.model.UserCollectionBo>>>(
            cache.collectionsFor("fixture_user", SubjectType.ANIME, refresh = true),
        )
        assertEquals(listOf(2, 3), refreshed.value.map { it.subjectId })
        assertEquals(2, requests.count(), "刷新必须重新请求")

        val cached = assertIs<Outcome.Success<List<io.github.touhougo.chogumi.core.model.UserCollectionBo>>>(
            cache.collectionsFor("fixture_user", SubjectType.ANIME),
        )
        assertEquals(listOf(2, 3), cached.value.map { it.subjectId }, "普通读取必须得到刷新后的内容")
        assertEquals(2, requests.count(), "刷新后的普通读取应命中缓存")
    }

    @Test
    fun `他人收藏带回的条目合并进条目表，且摘要不算详情`() = runTest {
        val client = client(mapOf(("fixture_user" to SubjectType.ANIME.value) to withSubjects(listOf(1, 2))))
        val subjectDao = FakeSubjectDao()

        assertIs<Outcome.Success<*>>(cache(client, username = "me", subjectDao = subjectDao).collectionsFor("fixture_user", SubjectType.ANIME))

        assertEquals(setOf(1, 2), subjectDao.store.keys, "条目表不分账号，详情页与本人收藏都能复用")
        assertEquals("中1", subjectDao.store.getValue(1).nameCn)
        assertTrue(subjectDao.store.values.all { it.updatedAt == 0L }, "摘要不能让详情 TTL 视为新鲜")
    }

    @Test
    fun `缓存最多保留三个用户按LRU淘汰`() = runTest {
        val routes = mapOf(
            ("u1" to SubjectType.ANIME.value) to page(listOf(1), SubjectType.ANIME.value),
            ("u2" to SubjectType.ANIME.value) to page(listOf(2), SubjectType.ANIME.value),
            ("u3" to SubjectType.ANIME.value) to page(listOf(3), SubjectType.ANIME.value),
            ("u4" to SubjectType.ANIME.value) to page(listOf(4), SubjectType.ANIME.value),
        )
        val requests = ConcurrentRequestRecorder()
        val client = client(routes, requests)
        val cache = cache(client)

        listOf("u1", "u2", "u3").forEach { cache.collectionsFor(it, SubjectType.ANIME) }
        // u4 加入 → 淘汰最久未用的 u1。
        cache.collectionsFor("u4", SubjectType.ANIME)
        val beforeReFetchU2 = requests.count()
        cache.collectionsFor("u2", SubjectType.ANIME)
        assertEquals(beforeReFetchU2, requests.count(), "u2 应在缓存中（最近用过）")
        // u1 被淘汰：再取 u1 必须重新打网络。
        cache.collectionsFor("u1", SubjectType.ANIME)
        assertTrue(requests.count() > beforeReFetchU2, "u1 已被 LRU 淘汰，必须重新现拉")
    }

    @Test
    fun `自动汇总门槛只对不超过一千条为真`() {
        assertFalse(shouldAutoSummarize(0))
        assertTrue(shouldAutoSummarize(1))
        assertTrue(shouldAutoSummarize(1000))
        assertFalse(shouldAutoSummarize(1001))
    }

    @Test
    fun `他人统计走同一纯函数且观看集数标近似`() = runTest {
        val client = client(mapOf(("fixture_user" to SubjectType.ANIME.value) to page(listOf(1, 2), SubjectType.ANIME.value)))
        val cache = cache(client)

        val stats = assertIs<Outcome.Success<io.github.touhougo.chogumi.core.model.CollectionStatistics>>(
            cache.statisticsFor("fixture_user", SubjectType.ANIME, currentYear = 2026),
        ).value
        assertEquals(2, stats.totalCount)
        assertTrue(stats.watchTime.estimated, "他人数据没有每集时长，投入时间必须标近似")
    }
}
