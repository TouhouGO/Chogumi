package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.airing.AiringTimeEntity
import io.github.touhougo.chogumi.core.data.db.airing.AiringTimeMetaEntity
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import io.github.touhougo.chogumi.core.network.api.BangumiDataApi
import io.github.touhougo.chogumi.core.network.model.bangumidata.BangumiDataItemDto
import io.github.touhougo.chogumi.core.network.model.bangumidata.BangumiDataSiteDto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

/** 放送时刻表仓库：到期才取、304 只记成功、失败留旧表并退避；整份数据到表行的筛选与去重。 */
class AiringTimeRepositoryTest {
    private val engines = RecordedMockEngines()

    @get:org.junit.Rule
    val resources = TestResources()

    private val clock = TestClock(Instant.parse("2026-10-15T04:00:00Z").toEpochMilli())
    private val dao = FakeAiringTimeDao()

    private val dataJson = """
        {"items": [
          {"begin": "2026-10-01T13:00:00.000Z", "broadcast": "R/2026-10-01T13:00:00.000Z/P7D", "sites": [{"site": "bangumi", "id": "100"}]},
          {"begin": "2026-10-02T13:00:00.000Z", "broadcast": "R/2026-10-02T13:00:00.000Z/P7D", "sites": [{"site": "mal", "id": "7"}]},
          {"begin": "2026-10-03T13:00:00.000Z", "broadcast": "R/2026-10-03T13:00:00.000Z/P0D", "sites": [{"site": "bangumi", "id": "300"}]}
        ]}
    """.trimIndent()

    private fun engine(status: HttpStatusCode, content: String = "", etag: String? = null) = engines.create {
        respond(
            content = content,
            status = status,
            headers = headersOf(
                HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                *listOfNotNull(etag?.let { HttpHeaders.ETag to listOf(it) }).toTypedArray(),
            ),
        )
    }

    private fun repository(engine: MockEngine) = testAiringTimeRepository(BangumiDataApi(resources.apiClient(engine)), dao, clock)

    private fun requestCount(engine: MockEngine) = engines.requests(engine).count()

    @Test
    fun `新鲜期内不请求`() = runTest {
        dao.meta = AiringTimeMetaEntity(refreshedAt = clock.time - 60 * 60 * 1000, attemptedAt = clock.time - 60 * 60 * 1000)
        val engine = engine(HttpStatusCode.OK, dataJson)

        assertEquals(Outcome.Success(Unit), repository(engine).refreshIfStale())
        assertEquals(0, requestCount(engine))
    }

    @Test
    fun `过期后取整份_整张表换成新的_记下ETag与时刻`() = runTest {
        dao.upsertAll(listOf(AiringTimeEntity(subjectId = 999, broadcastStart = 1L)))
        dao.meta = AiringTimeMetaEntity(refreshedAt = clock.time - 25 * 60 * 60 * 1000, attemptedAt = clock.time - 25 * 60 * 60 * 1000, etag = "W/\"old\"")
        val engine = engine(HttpStatusCode.OK, dataJson, etag = "W/\"new\"")

        assertEquals(Outcome.Success(Unit), repository(engine).refreshIfStale())

        assertEquals(listOf(AiringTimeEntity(100, Instant.parse("2026-10-01T13:00:00Z").toEpochMilli())), dao.store)
        assertEquals(AiringTimeMetaEntity(refreshedAt = clock.time, attemptedAt = clock.time, etag = "W/\"new\""), dao.meta)
        assertEquals("W/\"old\"", engines.requests(engine).single().headers[HttpHeaders.IfNoneMatch])
    }

    @Test
    fun `服务端回304_旧表不动_只记一次成功`() = runTest {
        val rows = listOf(AiringTimeEntity(subjectId = 100, broadcastStart = 1L))
        dao.upsertAll(rows)
        val stale = clock.time - 25 * 60 * 60 * 1000
        dao.meta = AiringTimeMetaEntity(refreshedAt = stale, attemptedAt = stale, etag = "W/\"same\"")

        assertEquals(Outcome.Success(Unit), repository(engine(HttpStatusCode.NotModified)).refreshIfStale())

        assertEquals(rows, dao.store)
        assertEquals(AiringTimeMetaEntity(refreshedAt = clock.time, attemptedAt = clock.time, etag = "W/\"same\""), dao.meta)
    }

    @Test
    fun `页面一直开着_过了刷新间隔的那一跳再取一次_取失败也接着跳`() = runTest {
        val okEngine = engine(HttpStatusCode.OK, dataJson, etag = "\"v1\"")
        val repository = repository(okEngine)
        val ticks = flow {
            emit(clock.time)
            clock.advance(AiringTimeRepository.REFRESH_TTL_MILLIS - 1)
            emit(clock.time)
            clock.advance(1)
            emit(clock.time)
        }

        repository.keepFresh(ticks)

        assertEquals(2, requestCount(okEngine))
        assertEquals("\"v1\"", engines.requests(okEngine).last().headers[HttpHeaders.IfNoneMatch])

        val failingEngine = engine(HttpStatusCode.ServiceUnavailable)
        val failing = repository(failingEngine)
        clock.advance(AiringTimeRepository.REFRESH_TTL_MILLIS)
        failing.keepFresh(
            flow {
                emit(clock.time)
                clock.advance(AiringTimeRepository.FAILURE_BACKOFF_MILLIS)
                emit(clock.time)
            },
        )
        assertEquals(2, requestCount(failingEngine))
    }

    @Test
    fun `取失败_旧表不动_退避期内不再试_过了再试`() = runTest {
        val rows = listOf(AiringTimeEntity(subjectId = 100, broadcastStart = 1L))
        dao.upsertAll(rows)
        val stale = clock.time - 25 * 60 * 60 * 1000
        dao.meta = AiringTimeMetaEntity(refreshedAt = stale, attemptedAt = stale, etag = "W/\"same\"")
        val engine = engine(HttpStatusCode.ServiceUnavailable)
        val repository = repository(engine)

        val failed = assertIs<Outcome.Failed>(repository.refreshIfStale())
        assertEquals(Failure.Http(503), failed.failure)
        assertEquals(rows, dao.store)
        assertEquals(AiringTimeMetaEntity(refreshedAt = stale, attemptedAt = clock.time, etag = "W/\"same\""), dao.meta)

        clock.advance(AiringTimeRepository.FAILURE_BACKOFF_MILLIS - 1)
        assertEquals(Outcome.Success(Unit), repository.refreshIfStale())
        assertEquals(1, requestCount(engine))

        clock.advance(1)
        assertIs<Outcome.Failed>(repository.refreshIfStale())
        assertEquals(2, requestCount(engine))
    }

    @Test
    fun `表行只留有条目id且每周播出的`() {
        val rows = airingTimeRows(
            listOf(
                item(begin = "2026-10-01T13:00:00.000Z", broadcast = "R/2026-10-01T13:00:00.000Z/P7D", bangumiId = "100"),
                item(begin = "2026-10-02T13:00:00.000Z", broadcast = "R/2026-10-02T13:00:00.000Z/P7D", bangumiId = null),
                item(begin = "2026-10-03T13:00:00.000Z", broadcast = "R/2026-10-03T13:00:00.000Z/P0D", bangumiId = "300"),
                item(begin = "2026-10-04T13:00:00.000Z", broadcast = null, bangumiId = "400"),
                item(begin = "2026-10-05T13:00:00.000Z", broadcast = "R/2026-10-05T13:00:00.000Z/P7D", bangumiId = "not-a-number"),
            ),
        )
        assertEquals(listOf(AiringTimeEntity(100, Instant.parse("2026-10-01T13:00:00Z").toEpochMilli())), rows)
    }

    @Test
    fun `同一条目录了两次取开播晚的那条`() {
        val rows = airingTimeRows(
            listOf(
                item(begin = "2026-10-08T13:00:00.000Z", broadcast = "R/2026-10-08T13:00:00.000Z/P7D", bangumiId = "100"),
                item(begin = "2026-10-01T13:00:00.000Z", broadcast = "R/2026-10-01T12:00:00.000Z/P7D", bangumiId = "100"),
            ),
        )
        assertEquals(listOf(AiringTimeEntity(100, Instant.parse("2026-10-08T13:00:00Z").toEpochMilli())), rows)
        assertNull(rows.firstOrNull { it.broadcastStart == Instant.parse("2026-10-01T12:00:00Z").toEpochMilli() })
    }

    private fun item(begin: String, broadcast: String?, bangumiId: String?) = BangumiDataItemDto(
        begin = begin,
        broadcast = broadcast,
        sites = listOfNotNull(bangumiId?.let { BangumiDataSiteDto("bangumi", it) }, BangumiDataSiteDto("mal", "1")),
    )
}
