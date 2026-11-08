package io.github.touhougo.chogumi.core.data.web

import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.network.api.WebSessionApi
import io.github.touhougo.chogumi.core.network.createWebHttpClient
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import java.io.EOFException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class WebOperationRetryTest {
    private val session = WebSession("chii_auth=test-account")

    private class Harness(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) : AutoCloseable {
        val requests = ConcurrentRequestRecorder()
        private val engine = MockEngine { request ->
            requests.record(request)
            handler(request)
        }
        private val client = createWebHttpClient("test", engine)
        val api = WebSessionApi(client)

        override fun close() {
            client.close()
            engine.close()
            cancelAndAwait(listOf(client, engine))
        }
    }

    @Test
    fun `操作链接 GET 响应中断不自动重发`() = runTest {
        Harness { throw EOFException("响应中断") }.use { h ->
            var sessionChecks = 0
            assertFailsWith<EOFException> {
                h.api.follow(session, "https://bgm.tv/act?gh=f0") { sessionChecks++ }
            }
            assertEquals(1, h.requests.count(), "操作可能已生效，不能重发")
            assertEquals(1, sessionChecks)
        }
    }

    @Test
    fun `取消绝交 GET 响应中断不自动重发`() = runTest {
        Harness { request ->
            if (request.url.parameters["ignore_reset"] != null) throw EOFException("响应中断")
            respond("""<table><tr><td><a class="tip_i" href="/settings/privacy?ignore_reset=42&amp;gh=f0">取消绝交</a></td></tr></table>""")
        }.use { h ->
            var sessionChecks = 0
            assertFailsWith<EOFException> { h.api.unblock(session, 42) { sessionChecks++ } }
            assertEquals(
                listOf("https://bgm.tv/settings/privacy", "https://bgm.tv/settings/privacy?ignore_reset=42&gh=f0"),
                h.requests.map { it.url.toString() },
            )
            assertEquals(2, sessionChecks, "名单读取与取消发送各检查一次")
        }
    }

    @Test
    fun `只读网页 GET 响应中断仍可自动重试`() = runTest {
        val first = AtomicBoolean(true)
        Harness {
            if (first.getAndSet(false)) throw EOFException("响应中断")
            respond("page")
        }.use { h ->
            assertEquals("page", h.api.page(session, "https://bgm.tv/settings/privacy"))
            assertEquals(2, h.requests.count())
        }
    }
}
