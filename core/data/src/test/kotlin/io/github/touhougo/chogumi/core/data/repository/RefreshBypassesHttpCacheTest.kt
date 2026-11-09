package io.github.touhougo.chogumi.core.data.repository

import java.util.concurrent.atomic.AtomicInteger
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.network.TokenProvider
import io.github.touhougo.chogumi.core.network.TokenRefresher
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.github.touhougo.chogumi.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * 下拉刷新（refresh = true）必须真的回源。
 *
 * v0 条目类端点回 `Cache-Control: public, max-age=3600`：客户端若在 HTTP 层缓存它，
 * 一小时内的「刷新」会被旧响应直接应答，引擎根本收不到请求。
 * 本用例跑在**生产客户端** [createHttpClient] 上，假服务端每次返回递增的名字。
 */
class RefreshBypassesHttpCacheTest {
    @get:org.junit.Rule
    val resources = TestResources()


    @Test
    fun `refresh 为 true 时打到服务端取到新数据`() = runTest {
        val hits = AtomicInteger()
        val engine = MockEngine {
            val hit = hits.incrementAndGet()
            respond(
                content = """{"id":8,"type":2,"name":"v$hit","name_cn":"","summary":"",
                    "images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"},
                    "series":false,"nsfw":false,"locked":false,"platform":"TV","volumes":0,"eps":0,"total_episodes":0,
                    "meta_tags":[],"tags":[],"rating":{"rank":0,"total":0,"count":{},"score":0.0},
                    "collection":{"wish":0,"collect":0,"doing":0,"on_hold":0,"dropped":0}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    "Content-Type" to listOf("application/json"),
                    "Cache-Control" to listOf("public, max-age=3600"),
                ),
            )
        }
        val client = createHttpClient(
            tokenProvider = object : TokenProvider {
                override suspend fun accessToken(): String? = null
            },
            tokenRefresher = object : TokenRefresher {
                override suspend fun refresh(rejectedAccessToken: String?): Boolean = false
            },
            engine = engine,
            appVersion = "test",
            customizers = emptyList(),
        ).also { resources.own(it) }
        val repo = testSubjectRepository(resources, SubjectApi(client), NextApi(client), FakeSubjectDao(), FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))

        repo.getSubject(8, refresh = true).toList()
        val refreshed = repo.getSubject(8, refresh = true).toList().last()

        assertEquals(2, hits.get(), "第二次刷新必须打到引擎，而不是由缓存的旧响应应答")
        assertEquals("v2", assertIs<Load.Ready<SubjectBo>>(refreshed, "$refreshed").value.name)
    }
}
