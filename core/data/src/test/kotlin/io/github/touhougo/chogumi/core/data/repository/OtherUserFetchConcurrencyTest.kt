package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Exercises the actual cache and paging path with suspended network requests. */
class OtherUserFetchConcurrencyTest {
    @get:org.junit.Rule
    val resources = TestResources()

    @Test
    fun `不同用户与类型的取全共享四个分页许可`() = runBlocking {
        val gate = ConcurrencyGate()
        val client = resources.apiClient(MockEngine { request ->
            gate.enter()
            val type = request.url.parameters["subject_type"]!!.toInt()
            respond(page(type), HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        })
        val cache = testOtherUserCollectionsCache(CollectionApi(client), FakeSubjectDao(), testTokenStore(resources, userId = 7))
        val jobs = ContentDomains.mapIndexed { index, type ->
            async(Dispatchers.Default) { cache.collectionsFor("user$index", type) }
        }
        gate.releaseAfterBudget(4)
        val results = withTimeout(WAIT_BUDGET_MS) { jobs.awaitAll() }
        assertTrue(results.all { it is Outcome.Success }, "所有类型都完成")
        assertEquals(5, gate.calls, "每个类型都请求一次")
        assertTrue(gate.peak <= 4, "分页请求峰值应不超过 4，实际为 ${gate.peak}")
    }

    @Test
    fun `相同用户类型的两个冷读共用在途请求`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val client = resources.apiClient(MockEngine {
            when (calls.incrementAndGet()) {
                1 -> firstStarted.complete(Unit)
                2 -> secondStarted.complete(Unit)
            }
            release.await()
            respond(page(SubjectType.ANIME.value), HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        })
        val cache = testOtherUserCollectionsCache(CollectionApi(client), FakeSubjectDao(), testTokenStore(resources, userId = 7))
        val first = async(Dispatchers.Default) { cache.collectionsFor("fixture_user", SubjectType.ANIME) }
        withTimeout(WAIT_BUDGET_MS) { firstStarted.await() }
        val second = async(Dispatchers.Default) { cache.collectionsFor("fixture_user", SubjectType.ANIME) }
        try {
            withTimeoutOrNull(500) { secondStarted.await() }
        } finally {
            release.complete(Unit)
        }
        val a = assertIs<Outcome.Success<*>>(withTimeout(WAIT_BUDGET_MS) { first.await() })
        val b = assertIs<Outcome.Success<*>>(withTimeout(WAIT_BUDGET_MS) { second.await() })
        assertEquals(a.value, b.value)
        assertEquals(1, calls.get(), "两个冷读应只发一套请求")
    }

    @Test
    fun `后加入的冷读收到当前进度和后续进度`() = runBlocking {
        val pageGate = CompletableDeferred<Unit>()
        val secondPageStarted = CompletableDeferred<Unit>()
        val joined = CompletableDeferred<Unit>()
        val firstUpdates = java.util.concurrent.CopyOnWriteArrayList<SyncProgress>()
        val secondUpdates = java.util.concurrent.CopyOnWriteArrayList<SyncProgress>()
        val calls = AtomicInteger()
        val client = resources.apiClient(MockEngine { request ->
            calls.incrementAndGet()
            val offset = request.url.parameters["offset"]!!.toInt()
            if (offset > 0) { secondPageStarted.complete(Unit); pageGate.await() }
            respond(pageAt(offset + 1, offset, 100), HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        })
        val cache = testOtherUserCollectionsCache(CollectionApi(client), FakeSubjectDao(), testTokenStore(resources, userId = 7))
        val first = async(Dispatchers.Default) { cache.statisticsFor("fixture_user", SubjectType.ANIME, currentYear = 2026, onProgress = { firstUpdates.add(it) }) }
        withTimeout(WAIT_BUDGET_MS) { secondPageStarted.await() }
        val second = async(Dispatchers.Default) {
            cache.statisticsFor("fixture_user", SubjectType.ANIME, currentYear = 2026, onProgress = { secondUpdates.add(it); joined.complete(Unit) })
        }
        try { withTimeout(WAIT_BUDGET_MS) { joined.await() } } finally { pageGate.complete(Unit) }
        assertEquals(first.await(), second.await())
        assertEquals(2, calls.get())
        assertTrue(firstUpdates.any { it.completedPages == 2 && it.totalPages == 2 })
        assertTrue(secondUpdates.any { it.completedPages == 2 && it.totalPages == 2 })
    }

    @Test
    fun `主动刷新不接旧冷读且旧结果不能覆盖新结果`() = runBlocking {
        val firstGate = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val client = resources.apiClient(MockEngine {
            val number = calls.incrementAndGet()
            if (number == 1) { firstStarted.complete(Unit); firstGate.await() }
            respond(pageAt(number, 0, 1), HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        })
        val cache = testOtherUserCollectionsCache(CollectionApi(client), FakeSubjectDao(), testTokenStore(resources, userId = 7))
        val first = async(Dispatchers.Default) { cache.collectionsFor("fixture_user", SubjectType.ANIME) }
        withTimeout(WAIT_BUDGET_MS) { firstStarted.await() }
        val refresh = withTimeout(WAIT_BUDGET_MS) { cache.collectionsFor("fixture_user", SubjectType.ANIME, refresh = true) }
        firstGate.complete(Unit)
        val old = withTimeout(WAIT_BUDGET_MS) { first.await() }
        assertTrue(old != refresh)
        assertEquals(refresh, cache.collectionsFor("fixture_user", SubjectType.ANIME))
        assertEquals(2, calls.get())
    }

    @Test
    fun `发起者取消后已加入的等待者重试`() = runBlocking {
        val pageStarted = CompletableDeferred<Unit>()
        val joined = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val progress = java.util.concurrent.CopyOnWriteArrayList<SyncProgress>()
        val calls = AtomicInteger()
        val client = resources.apiClient(MockEngine { request ->
            val number = calls.incrementAndGet()
            val offset = request.url.parameters["offset"]!!.toInt()
            if (number == 2) { pageStarted.complete(Unit); release.await() }
            respond(pageAt(offset + 1, offset, 100), HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        })
        val cache = testOtherUserCollectionsCache(CollectionApi(client), FakeSubjectDao(), testTokenStore(resources, userId = 7))
        val first = launch(Dispatchers.Default) { cache.collectionsFor("fixture_user", SubjectType.ANIME) }
        withTimeout(WAIT_BUDGET_MS) { pageStarted.await() }
        val second = async(Dispatchers.Default) {
            cache.collectionsFor("fixture_user", SubjectType.ANIME, onProgress = { progress.add(it); joined.complete(Unit) })
        }
        withTimeout(WAIT_BUDGET_MS) { joined.await() }
        first.cancelAndJoin()
        assertIs<Outcome.Success<*>>(withTimeout(WAIT_BUDGET_MS) { second.await() })
        assertEquals(4, calls.get(), "取消的两页加上等待者重试的两页")
        assertEquals(1, progress.first().completedPages, "等待者先收到上一轮进度")
        assertTrue(progress.drop(1).any { it.completedPages == 0 }, "重试开始时进度应重新开始")
        assertTrue(progress.any { it.completedPages == 2 })
    }

    @Test
    fun `失败不缓存并允许下一次重试`() = runBlocking {
        val calls = AtomicInteger()
        val client = resources.apiClient(MockEngine {
            if (calls.incrementAndGet() == 1) respond("{}", HttpStatusCode.ServiceUnavailable)
            else respond(page(SubjectType.ANIME.value), HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        })
        val cache = testOtherUserCollectionsCache(CollectionApi(client), FakeSubjectDao(), testTokenStore(resources, userId = 7))
        assertIs<Outcome.Failed>(cache.collectionsFor("fixture_user", SubjectType.ANIME))
        assertIs<Outcome.Success<*>>(cache.collectionsFor("fixture_user", SubjectType.ANIME))
        assertEquals(2, calls.get())
    }

    @Test
    fun `重新登录同账号或切换账号时旧在途不会被新会话复用或覆盖`() = runBlocking {
        for (newUserId in listOf(7, 8)) {
            val firstStarted = CompletableDeferred<Unit>()
            val firstGate = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val client = resources.apiClient(MockEngine {
                val number = calls.incrementAndGet()
                if (number == 1) { firstStarted.complete(Unit); firstGate.await() }
                respond(pageAt(number, 0, 1), HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
            })
            val tokenStore = testTokenStore(resources, userId = 7)
            val cache = testOtherUserCollectionsCache(CollectionApi(client), FakeSubjectDao(), tokenStore)
            val first = async(Dispatchers.Default) { cache.collectionsFor("fixture_user", SubjectType.ANIME) }
            withTimeout(WAIT_BUDGET_MS) { firstStarted.await() }
            val pending = tokenStore.beginSession("other-token")
            tokenStore.completeSession(pending, newUserId, "other")
            val current = withTimeout(WAIT_BUDGET_MS) { cache.collectionsFor("fixture_user", SubjectType.ANIME) }
            firstGate.complete(Unit)
            assertIs<Outcome.Failed>(withTimeout(WAIT_BUDGET_MS) { first.await() })
            assertIs<Outcome.Success<*>>(current)
            assertEquals(current, cache.collectionsFor("fixture_user", SubjectType.ANIME))
            assertEquals(2, calls.get())
        }
    }

    private fun pageAt(id: Int, offset: Int, total: Int) =
        """{"data":[{"subject_id":$id,"subject_type":2,"type":3,"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}],"total":$total,"limit":50,"offset":$offset}"""

    private fun page(type: Int) =
        """{"data":[{"subject_id":$type,"subject_type":$type,"type":3,"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}],"total":1,"limit":50,"offset":0}"""
}
