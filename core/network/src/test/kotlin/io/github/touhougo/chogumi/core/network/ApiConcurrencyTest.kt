package io.github.touhougo.chogumi.core.network

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** 生产接口客户端（真 OkHttp 引擎）对同一个域名能同时发出 [API_MAX_REQUESTS_PER_HOST] 个请求。 */
class ApiConcurrencyTest {

    private object NoToken : TokenProvider {
        override suspend fun accessToken(): String? = null
    }

    private object NoRefresh : TokenRefresher {
        override suspend fun refresh(rejectedAccessToken: String?): Boolean = false
    }

    @Test
    fun `同一域名的请求能同时在途 API_MAX_REQUESTS_PER_HOST 个`() = runBlocking {
        val wanted = API_MAX_REQUESTS_PER_HOST
        val arrived = CountDownLatch(wanted)
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newFixedThreadPool(wanted * 2)
            createContext("/") { exchange ->
                peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                arrived.countDown()
                // 到齐了一起应答；并发被卡在上限以下时到不齐，每个请求等满 2 秒再应答
                arrived.await(2, TimeUnit.SECONDS)
                val body = "{}".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                inFlight.decrementAndGet()
            }
            start()
        }
        val client = createHttpClient(NoToken, NoRefresh, appVersion = "test")
        try {
            coroutineScope {
                repeat(wanted) { index ->
                    launch(Dispatchers.IO) { client.get("http://127.0.0.1:${server.address.port}/probe/$index").bodyAsText() }
                }
            }
        } finally {
            client.close()
            server.stop(0)
        }
        assertEquals(wanted, peak.get(), "同时在途的请求被卡在了上限以下")
    }
}
