package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.model.p1.SearchSubjectDto
import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class NextApiTest {

    private val calendarJson = """
        {
          "1": [
            {
              "subject": {
                "id": 8, "name": "こんにちは アニメ", "nameCN": "你好 Anime", "type": 2,
                "images": {"large": "https://lain.bgm.tv/pic/cover/l/98.jpg", "common": "", "medium": "", "small": "", "grid": ""},
                "info": "简介", "metaTags": ["TV"],
                "rating": {"rank": 0, "count": [0,0,0,0,0,0,0,0,0,1], "score": 7.8, "total": 1},
                "locked": false, "nsfw": false
              },
              "watchers": 100
            }
          ]
        }
    """.trimIndent()

    private fun makeApi(content: String): Pair<NextApi, MockEngine> {
        val engine = MockEngine {
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = testApiClient(engine)
        return NextApi(client) to engine
    }

    @Test
    fun `getCalendar 解析星期 map 与内嵌条目`() = runTest {
        val (api, engine) = makeApi(calendarJson)
        val calendar = api.getCalendar()
        assertEquals(1, calendar["1"]?.size)
        assertEquals(100, calendar["1"]?.first()?.watchers)
        assertEquals("你好 Anime", calendar["1"]?.first()?.subject?.nameCN)
        assertEquals("/p1/calendar", engine.requestHistory.single().url.encodedPath)
    }

    @Test
    fun `getChannelTags 请求路径参数与解析`() = runTest {
        val tagsJson = """
            {"data":[{"name":"TV","count":1426943},{"name":"漫画改","count":879170}],"total":212454}
        """.trimIndent()
        val engine = MockEngine {
            respond(
                content = tagsJson,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = testApiClient(engine)
        val page = NextApi(client).getChannelTags(type = 2, limit = 100, offset = 200)
        assertEquals(2, page.data.size)
        assertEquals("TV", page.data.first().name)
        assertEquals(1426943, page.data.first().count)
        assertEquals(212454, page.total)
        val request = engine.requestHistory.single()
        assertEquals("/p1/channels/2/tags", request.url.encodedPath)
        assertEquals("100", request.url.parameters["limit"])
        assertEquals("200", request.url.parameters["offset"])
    }

    @Test
    fun `NextApi 公开读接口携带与不携带 Authorization 头测试`() = runTest {
        val emptyPagedJson = """{"data":[],"total":0}"""
        var capturedAuth: String? = null
        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            val content = when {
                request.url.encodedPath.endsWith("/calendar") -> calendarJson
                else -> emptyPagedJson
            }
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = testApiClient(engine)
        val api = NextApi(client)

        // 1. browseSubjects
        api.browseSubjects(type = 2, sort = "rank", accessToken = "token_browse")
        assertEquals("Bearer token_browse", capturedAuth)
        api.browseSubjects(type = 2, sort = "rank", accessToken = null)
        assertNull(capturedAuth)

        // 2. searchSubjects
        api.searchSubjects(SearchSubjectDto(keyword = ""), limit = 20, offset = 0, accessToken = "token_search")
        assertEquals("Bearer token_search", capturedAuth)
        api.searchSubjects(SearchSubjectDto(keyword = ""), limit = 20, offset = 0, accessToken = null)
        assertNull(capturedAuth)

        // 3. getSubjectRelations
        api.getSubjectRelations(subjectId = 8, offprint = false, accessToken = "token_relations")
        assertEquals("Bearer token_relations", capturedAuth)
        api.getSubjectRelations(subjectId = 8, offprint = false, accessToken = null)
        assertNull(capturedAuth)

        // 4. getSubjectCharacters
        api.getSubjectCharacters(subjectId = 8, accessToken = "token_characters")
        assertEquals("Bearer token_characters", capturedAuth)
        api.getSubjectCharacters(subjectId = 8, accessToken = null)
        assertNull(capturedAuth)

        // 5. getCalendar
        api.getCalendar(accessToken = "token_cal")
        assertEquals("Bearer token_cal", capturedAuth)
        api.getCalendar(accessToken = null)
        assertNull(capturedAuth)
    }

    @Test
    fun `条目角色：路径、分页参数与中文名、定位、声优`() = runTest {
        val (api, engine) = makeApi(
            """{"data":[{"character":{"id":157795,"name":"韩立","nameCN":"韩立","role":1,"info":"性别 男","comment":23,"lock":false,"nsfw":false,
                 "images":{"large":"l","medium":"m","small":"s","grid":"g"}},
               "casts":[{"person":{"id":42528,"name":"钱文青","nameCN":"","type":1,"info":"","career":["seiyu"],"comment":2,"lock":false,"nsfw":false},
                 "relation":0,"summary":""}],
               "type":1,"order":0}],"total":28}""",
        )
        val page = api.getSubjectCharacters(607915, offset = 100)
        val request = engine.requestHistory.single()
        assertEquals("/p1/subjects/607915/characters", request.url.encodedPath)
        assertEquals("100", request.url.parameters["limit"])
        assertEquals("100", request.url.parameters["offset"])
        assertEquals(28, page.total)
        val item = page.data.single()
        assertEquals("韩立", item.character.nameCN)
        assertEquals(1, item.type)
        assertEquals("钱文青", item.casts.single().person.name)
    }

    @Test
    fun `条目社区端点：路径与分页参数`() = runTest {
        val (api, engine) = makeApi("""{"data":[],"total":0}""")
        api.getSubjectComments(253, limit = 20, offset = 40)
        api.getSubjectTopics(253, limit = 30, offset = 0)
        api.getSubjectReviews(253, limit = 20, offset = 20)
        api.getSubjectIndexes(253, limit = 10, offset = 0)
        api.getSubjectCollects(253, type = 3, limit = 5, offset = 0)
        api.getSubjectCollects(253)
        val requests = engine.requestHistory
        assertEquals(
            listOf(
                "/p1/subjects/253/comments",
                "/p1/subjects/253/topics",
                "/p1/subjects/253/reviews",
                "/p1/subjects/253/indexes",
                "/p1/subjects/253/collects",
                "/p1/subjects/253/collects",
            ),
            requests.map { it.url.encodedPath },
        )
        assertEquals("40", requests[0].url.parameters["offset"])
        assertEquals("30", requests[1].url.parameters["limit"])
        assertEquals("3", requests[4].url.parameters["type"])
        assertNull(requests[5].url.parameters["type"])
    }

    @Test
    fun `条目推荐：路径、一次取满 10 部、解析推荐条目`() = runTest {
        val (api, engine) = makeApi(
            """{"data":[{"subject":{"id":315574,"name":"ぼっち・ざ・ろっく！","nameCN":"孤独摇滚！","type":2,
            "info":"","metaTags":[],"rating":{"rank":30,"count":[0,0,0,0,0,0,0,0,0,1],"score":8.36,"total":1},
            "locked":false,"nsfw":false},"sim":8,"count":0}],"total":1}""",
        )
        val recs = api.getSubjectRecs(317613)
        val request = engine.requestHistory.single()
        assertEquals("/p1/subjects/317613/recs", request.url.encodedPath)
        assertEquals("10", request.url.parameters["limit"])
        assertEquals("孤独摇滚！", recs.data.single().subject.nameCN)
        assertEquals(8.36, recs.data.single().subject.rating.score)
    }

    @Test
    fun `话题详情：路径带 - 占位，一次取全部楼层`() = runTest {
        val (api, engine) = makeApi(
            """{"id":36704,"title":"t","creatorID":1,"parentID":253,"replyCount":1,"createdAt":1,"updatedAt":1,"state":0,"display":1,
               "subject":{"id":253,"name":"n","nameCN":"","type":2,"info":"","metaTags":[],
                 "rating":{"rank":0,"count":[],"score":0,"total":0},"locked":false,"nsfw":false},
               "replies":[{"id":2,"creatorID":1,"createdAt":1,"content":"c","state":0,"replies":[]}]}""",
        )
        val topic = api.getSubjectTopic(36704)
        assertEquals("/p1/subjects/-/topics/36704", engine.requestHistory.single().url.encodedPath)
        assertEquals(1, topic.replies.size)
        assertEquals(253, topic.subject.id)
    }

    @Test
    fun `小组话题详情：路径带 - 占位，上级是小组`() = runTest {
        val (api, engine) = makeApi(
            """{"id":472971,"title":"t","creatorID":1,"parentID":364,"replyCount":1,"createdAt":1,"updatedAt":1,"state":0,"display":1,
               "group":{"id":364,"name":"boring","nsfw":false,"title":"g","icon":{"small":"s","medium":"m","large":"l"},
                 "creatorID":2,"members":3,"accessible":true,"createdAt":1},
               "replies":[{"id":2,"creatorID":1,"createdAt":1,"content":"c","state":0,"replies":[]}]}""",
        )
        val topic = api.getGroupTopic(472971)
        assertEquals("/p1/groups/-/topics/472971", engine.requestHistory.single().url.encodedPath)
        assertEquals(1, topic.replies.size)
        assertEquals("boring", topic.group.name)
    }
}
