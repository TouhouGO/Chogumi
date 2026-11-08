package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.BANGUMI_DATA_URL
import io.github.touhougo.chogumi.core.network.model.bangumidata.BangumiDataSiteDto
import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.withCharset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class BangumiDataApiTest {

    /** 线上数据的形状（2026-10 取样）：站点元数据、标题、译名这些用不到的键都在。 */
    private val dataJson = """
        {
          "siteMeta": {"bangumi": {"title": "番组计划", "urlTemplate": "https://bangumi.tv/subject/{{id}}", "type": "info"}},
          "items": [
            {
              "title": "ヒロイン？聖女？", "titleTranslate": {"zh-Hans": ["女主角？圣女？"]}, "type": "tv", "lang": "ja",
              "officialSite": "https://example.com/", "begin": "2026-07-01T13:00:00.000Z",
              "broadcast": "R/2026-07-01T13:00:00.000Z/P7D", "end": "2026-09-09T14:00:00.000Z", "comment": "",
              "sites": [
                {"site": "bangumi", "id": "558064"},
                {"site": "crunchyroll", "id": "GT00378069", "begin": "2026-06-24T14:30:00.000Z", "broadcast": "R/2026-06-24T14:30:00.000Z/P7D"},
                {"site": "nicovideo", "url": "https://example.com/ch"}
              ]
            },
            {"title": "映画", "titleTranslate": {}, "type": "movie", "lang": "ja", "officialSite": "", "begin": "2026-08-01T00:00:00.000Z", "end": "", "sites": []}
          ]
        }
    """.trimIndent()

    /** GitHub 的原始文件服务给 JSON 也标 `text/plain`（2026-11 实测）。 */
    private fun engine(status: HttpStatusCode, content: String = "", vararg headers: Pair<String, String>) = MockEngine {
        respond(
            content = content,
            status = status,
            headers = headersOf(
                HttpHeaders.ContentType to listOf(ContentType.Text.Plain.withCharset(Charsets.UTF_8).toString()),
                *headers.map { (name, value) -> name to listOf(value) }.toTypedArray(),
            ),
        )
    }

    @Test
    fun `解析标成纯文本的条目_只取用得到的字段_不带令牌也不带条件头`() = runTest {
        val engine = engine(HttpStatusCode.OK, dataJson, HttpHeaders.ETag to "\"50801fcb\"")
        val result = assertIs<BangumiDataApi.Result.Fresh>(BangumiDataApi(testApiClient(engine)).getData(etag = null))

        assertEquals(2, result.items.size)
        val tv = result.items[0]
        assertEquals("R/2026-07-01T13:00:00.000Z/P7D", tv.broadcast)
        assertEquals("2026-07-01T13:00:00.000Z", tv.begin)
        assertEquals(
            listOf(BangumiDataSiteDto("bangumi", "558064"), BangumiDataSiteDto("crunchyroll", "GT00378069"), BangumiDataSiteDto("nicovideo", null)),
            tv.sites,
        )
        assertNull(result.items[1].broadcast)
        assertEquals("\"50801fcb\"", result.etag)
        val request = engine.requestHistory.single()
        assertEquals(BANGUMI_DATA_URL, request.url.toString())
        assertNull(request.headers[HttpHeaders.Authorization])
        assertNull(request.headers[HttpHeaders.IfNoneMatch])
    }

    @Test
    fun `带上次的ETag_服务端回304就是没变`() = runTest {
        val engine = engine(HttpStatusCode.NotModified)
        val result = BangumiDataApi(testApiClient(engine)).getData(etag = "W/\"78a5f9-1\"")

        assertEquals(BangumiDataApi.Result.NotModified, result)
        assertEquals("W/\"78a5f9-1\"", engine.requestHistory.single().headers[HttpHeaders.IfNoneMatch])
    }
}
