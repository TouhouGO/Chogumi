package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ServerResponseException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class WebPageApiTest {

    /** 透视页的结构：前面的脚本里先出现一次变量名但不是赋值，字符串里带括号，真正的赋值在后面（数据截自 317613）。 */
    private val statsPage = """
        <html><head><script>/* CHART_SETS 由下方脚本给出 */ var other = {"a":"}{"};</script></head>
        <body><div id="columnInSubjectA">
        <script>
        var CHART_SETS = {"interest_type":{"title":"全站用户评价","desc":"x { y } \"z\"","data":[{"title":10,"1":47,"3":99,"2":3024,"4":1,"5":3},{"title":9,"2":7203,"1":15,"3":99,"5":11,"4":20},{"title":4,"2":30,"5":4},{"title":1,"5":15,"3":5,"2":35,"4":1,"1":1},{"title":0,"2":999}],"series_set":{"看过":"2"}},"airdate":{"title":"t","data":[{"title":8,"1":14,"2":169,"3":9046},{"title":2,"2":3,"3":17}],"series_set":{}}};
        </script></div></body></html>
    """.trimIndent()

    private fun makeApi(vararg responses: Pair<HttpStatusCode, String>): Pair<WebPageApi, MockEngine> {
        val call = AtomicInteger()
        val engine = MockEngine {
            val (status, body) = responses[minOf(call.getAndIncrement(), responses.lastIndex)]
            respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()))
        }
        return WebPageApi(testApiClient(engine)) to engine
    }

    @Test
    fun `透视页按图名拆出各组 1 到 10 分的人数，省略的分数按 0 计`() = runTest {
        val (api, engine) = makeApi(HttpStatusCode.OK to statsPage)

        val charts = api.getSubjectStatsCharts("https://example.test/subject/317613/stats")

        assertEquals(setOf("interest_type", "airdate"), charts.keys)
        val interest = charts.getValue("interest_type").counts
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 0, 0, 15, 47), interest["1"])
        assertEquals(listOf(35, 0, 0, 30, 0, 0, 0, 0, 7203, 3024), interest["2"])
        assertEquals(listOf(15, 0, 0, 4, 0, 0, 0, 0, 11, 3), interest["5"])
        assertEquals(listOf(0, 3, 0, 0, 0, 0, 0, 169, 0, 0), charts.getValue("airdate").counts["2"])
        assertNull(engine.requestHistory.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `页面里没有图表变量按坏响应处理`() = runTest {
        val (api, _) = makeApi(HttpStatusCode.OK to "<html><body>CHART_SETS</body></html>")

        val error = assertFailsWith<FailureException> { api.getSubjectStatsCharts("https://example.test/s") }
        assertEquals(Failure.BadResponse, error.failure)
    }

    @Test
    fun `图表对象没有闭合按坏响应处理`() = runTest {
        val (api, _) = makeApi(HttpStatusCode.OK to """<script>var CHART_SETS = {"a":{"data":[</script>""")

        val error = assertFailsWith<FailureException> { api.getSubjectStatsCharts("https://example.test/s") }
        assertEquals(Failure.BadResponse, error.failure)
    }

    @Test
    fun `冷页面算好之前一直回 502，隔一会儿重发直到 200`() = runTest {
        val bad = HttpStatusCode.BadGateway to "error code: 502"
        val (api, engine) = makeApi(bad, bad, bad, bad, bad, HttpStatusCode.OK to statsPage)

        val charts = api.getSubjectStatsCharts("https://example.test/s")

        assertEquals(6, engine.requestHistory.size)
        assertEquals(setOf("interest_type", "airdate"), charts.keys)
    }

    @Test
    fun `一直是服务端错误时试满次数照常抛出`() = runTest {
        val (api, engine) = makeApi(HttpStatusCode.BadGateway to "error code: 502")

        assertFailsWith<ServerResponseException> { api.getSubjectStatsCharts("https://example.test/s") }
        assertEquals(20, engine.requestHistory.size)
    }
}
