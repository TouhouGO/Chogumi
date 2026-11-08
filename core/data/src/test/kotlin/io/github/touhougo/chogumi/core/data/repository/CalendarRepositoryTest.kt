package io.github.touhougo.chogumi.core.data.repository

import java.util.concurrent.atomic.AtomicInteger
import io.github.touhougo.chogumi.core.data.db.calendar.CalendarEntity
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.settled
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class CalendarRepositoryTest {
    @get:org.junit.Rule
    val resources = TestResources()


    private val calendarJson = """
        {"1":[{"subject":{"id":8,"name":"こんにちは","nameCN":"你好","type":2,
           "images":{"large":"l.jpg","common":"c.jpg","medium":"m.jpg","small":"s.jpg","grid":"g.jpg"},"info":"","metaTags":[],
           "rating":{"rank":0,"count":[],"score":7.0,"total":1},
           "locked":false,"nsfw":false},"watchers":100}]}
    """.trimIndent()

    @Test
    fun `读缓存、取网络、落库与映射跑在注入的计算调度器上`() = runTest {
        val computation = CountingDispatcher()
        val engine = MockEngine {
            respond(calendarJson, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val client = resources.apiClient(engine)
        val repo = testCalendarRepository(resources, NextApi(client), FakeCalendarDao(), FakeSubjectDao(), testNsfwFilter(resources), computationDispatcher = computation)
        assertTrue(repo.getCalendar().toList().last() is Load.Ready)
        assertTrue(computation.dispatches > 0, "放送表没有切到计算调度器")
    }

    @Test
    fun `放送数据落缓存并按星期分组`() = runTest {
        val engine = MockEngine {
            respond(
                content = calendarJson,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val dao = FakeCalendarDao()
        // 预置陈旧行：末拍写缓存须整体替换，不得残留
        dao.upsertAll(listOf(CalendarEntity(weekday = 2, subjectId = 99, name = "旧")))
        val repo = testCalendarRepository(resources, NextApi(client), dao, FakeSubjectDao(), testNsfwFilter(resources))
        // 预置后首拍是缓存拍，收全两拍取末拍（网络拍）断言
        val result = repo.getCalendar().toList().last { it !is Load.Pending }
        assertTrue(result is Load.Ready)
        assertEquals(1, result.value[1]?.size)
        assertEquals(1, dao.store.size)
        assertEquals("你好", dao.store.first().nameCn)
        assertTrue(dao.store.none { it.subjectId == 99 })
    }


    @Test
    fun `时间戳为 0 的存量行走一次后台刷新后获得真实时间戳`() = runTest {
        val networkCalls = AtomicInteger()
        val engine = MockEngine {
            networkCalls.incrementAndGet()
            respond(
                content = calendarJson,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val dao = FakeCalendarDao()
        dao.upsertAll(listOf(CalendarEntity(weekday = 2, subjectId = 99, name = "存量行", updatedAt = 0L)))
        val clock = TestClock(time = 20_000L)
        val repo = testCalendarRepository(resources, NextApi(client), dao, FakeSubjectDao(), testNsfwFilter(resources), clock = clock)

        val results = mutableListOf<Load<*>>()
        repo.getCalendar().collect { results.add(it) }

        assertEquals(2, results.size)
        assertTrue(results[0] is Load.Ready && (results[0] as Load.Ready<*>).fromCache)
        assertTrue(results[1] is Load.Ready && !(results[1] as Load.Ready<*>).fromCache)
        assertEquals(1, networkCalls.get())
        assertEquals(20_000L, dao.store.first().updatedAt)
    }

    @Test
    fun `新鲜缓存直接直出不打网络`() = runTest {
        val networkCalls = AtomicInteger()
        val engine = MockEngine {
            networkCalls.incrementAndGet()
            respond(
                content = calendarJson,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val dao = FakeCalendarDao()
        val clock = TestClock(time = 10_000_000L)
        // 5分钟前写入，TTL 为 12 小时
        dao.upsertAll(listOf(CalendarEntity(weekday = 1, subjectId = 8, name = "新鲜", updatedAt = 10_000_000L - 5 * 60 * 1000L)))
        val repo = testCalendarRepository(resources, NextApi(client), dao, FakeSubjectDao(), testNsfwFilter(resources), clock = clock)

        val results = mutableListOf<Load<*>>()
        repo.getCalendar().collect { results.add(it) }

        assertEquals(1, results.size)
        assertTrue(results[0] is Load.Ready && (results[0] as Load.Ready<*>).fromCache)
        assertEquals(0, networkCalls.get())
    }

    @Test
    fun `过期缓存只要一个答案时_拿到缓存就停_不发请求`() = runTest {
        val networkCalls = AtomicInteger()
        val engine = MockEngine {
            networkCalls.incrementAndGet()
            respond(calendarJson, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val client = resources.apiClient(engine)
        val dao = FakeCalendarDao()
        dao.upsertAll(listOf(CalendarEntity(weekday = 1, subjectId = 8, name = "过期", updatedAt = 1L)))
        // 立即执行的调度器：计算若隔着 flowOn 的缓冲，交出缓存拍后会当场接着发网络
        val repo = testCalendarRepository(
            resources,
            NextApi(client), dao, FakeSubjectDao(), testNsfwFilter(resources), clock = TestClock(time = 10_000_000_000L),
            computationDispatcher = Dispatchers.Unconfined,
        )

        assertTrue(repo.getCalendar().settled() is Outcome.Success)
        assertEquals(0, networkCalls.get())
    }

    @Test
    fun `每日放送在登录态下携带 Authorization 头，未登录态不携带`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            requests.record(request)
            respond(
                content = calendarJson,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val tokenStore = testTokenStore(scope = backgroundScope)
        val repo = testCalendarRepository(
            resources,
            NextApi(client),
            FakeCalendarDao(),
            FakeSubjectDao(),
            testNsfwFilter(resources),
            tokenStore = tokenStore,
        )

        // 1. 未登录
        repo.getCalendar(refresh = true).collect {}
        assertEquals(listOf(null), requests.map { it.headers[io.ktor.http.HttpHeaders.Authorization] }, "未登录发出一次、不带令牌")

        // 2. 登录
        val session = tokenStore.beginSession("cal_token_123")
        tokenStore.completeSession(session, userId = 1, username = "caluser")

        repo.getCalendar(refresh = true).collect {}
        assertEquals(listOf(null, "Bearer cal_token_123"), requests.map { it.headers[io.ktor.http.HttpHeaders.Authorization] })
    }

    @Test
    fun `每日放送网络请求期间换号以换号失败收尾且数据不落库`() = runTest {
        val calendarDao = FakeCalendarDao()
        val tokenStore = testTokenStore(scope = backgroundScope)
        val sessionA = tokenStore.beginSession("token_a")
        tokenStore.completeSession(sessionA, userId = 1, username = "usera")

        val engine = MockEngine {
            // 请求期间换号
            val sessionB = tokenStore.beginSession("token_b")
            tokenStore.completeSession(sessionB, userId = 2, username = "userb")
            respond(
                content = calendarJson,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val repo = testCalendarRepository(
            resources,
            NextApi(client),
            calendarDao,
            FakeSubjectDao(),
            testNsfwFilter(resources),
            tokenStore = tokenStore,
        )

        assertEquals(
            Load.Failed(Failure.SessionChanged),
            repo.getCalendar(refresh = true).toList().last(),
        )
        // 关键断言：缓存不得落地
        assertTrue(calendarDao.store.isEmpty())
    }
}
