package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.CommentCollectionBo
import io.github.touhougo.chogumi.core.network.api.NextApi
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
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * 条目社区仓库：请求路径与参数、分页游标、楼层映射。
 * JSON 形状照 2026-10-05 实测响应裁剪，用户信息换成虚构值。
 */
class SubjectCommunityRepositoryTest {

    private fun user(id: Int, nickname: String, sign: String = "") =
        """{"id":$id,"username":"u$id","nickname":"$nickname","avatar":{"small":"s$id","medium":"m$id","large":"l$id"},"group":10,"sign":"$sign","joinedAt":1,"isFriend":false}"""

    private val requests = ConcurrentRequestRecorder()

    private suspend fun TestScope.repository(json: String, loggedInUserId: Int? = null): SubjectCommunityRepository {
        val engine = MockEngine { request ->
            requests.record(request)
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = testApiClient(engine)
        val tokenStore = testTokenStore(scope = backgroundScope)
        if (loggedInUserId != null) {
            val session = tokenStore.beginSession("token")
            tokenStore.completeSession(session, userId = loggedInUserId, username = "me")
        }
        return SubjectCommunityRepositoryImpl(NextApi(client), tokenStore)
    }

    @Test
    fun `单集讨论页头部：这一集与它的作品，标题去掉 HTML 实体，作品封面取海报档`() = runTest {
        val json = """{"id":1749254,"subjectID":593565,"sort":2,"type":0,"disc":0,"name":"猫探し巡査長","nameCN":"找猫 &amp; 决斗",
            "duration":"00:23:30","airdate":"2026-10-13","comment":22,"desc":"简介",
            "subject":{"id":593565,"name":"超巡！超条先輩","nameCN":"超巡！超条前辈","type":2,"info":"","metaTags":[],
             "rating":{"rank":0,"count":[],"score":5.8,"total":141},"locked":false,"nsfw":false,
             "images":{"large":"cl","common":"cc","medium":"cm","small":"cs","grid":"cg"}}}"""
        val thread = repository(json).getEpisode(1749254)

        assertEquals("/p1/episodes/1749254", requests.single().url.encodedPath)
        assertEquals("找猫 & 决斗", thread.episode.nameCn)
        assertEquals(593565, thread.episode.subjectId)
        assertEquals(2.0, thread.episode.sort)
        assertEquals("2026-10-13", thread.episode.airdate)
        assertEquals("简介", thread.episode.desc)
        assertEquals(593565, thread.subject?.id)
        assertEquals("超巡！超条前辈", thread.subject?.nameCn)
        assertEquals("cl", thread.subject?.cover)
    }

    @Test
    fun `吐槽：收藏状态与评分进楼层，贴贴按登录者高亮，游标按请求跨度推进`() = runTest {
        val json = """{"data":[
            {"id":53869369,"user":${user(1, "族长", "签名一")},"type":2,"rate":10,"comment":"不是很喜欢 &amp; 一期一会","updatedAt":1791045998,
             "reactions":[{"users":[{"id":7,"username":"u7","nickname":"me"}],"value":0}]},
            {"id":53853021,"user":${user(2, "sun")},"type":9,"rate":0,"comment":"短评","updatedAt":1791018171}
        ],"total":4731}"""
        val page = repository(json, loggedInUserId = 7).getComments(subjectId = 253, limit = 20, offset = 40)

        assertEquals("/p1/subjects/253/comments", requests.single().url.encodedPath)
        assertEquals("20", requests.single().url.parameters["limit"])
        assertEquals("40", requests.single().url.parameters["offset"])
        assertEquals("Bearer token", requests.single().headers[HttpHeaders.Authorization])
        assertEquals(60, page.nextOffset)
        assertEquals(4731, page.total)

        val first = page.items[0].anchor
        assertEquals(CommentCollectionBo(CollectionType.DONE, 10), first.collection)
        assertEquals("不是很喜欢 & 一期一会", first.content)
        assertEquals("签名一", first.creatorSign)
        assertEquals(1_791_045_998_000L, first.createdAt)
        assertTrue(first.reactions.single().selected)
        assertTrue(page.items[0].replies.isEmpty())
        // 未识别的收藏状态不猜成某一档
        assertEquals(CommentCollectionBo(CollectionType.UNKNOWN, 0), page.items[1].anchor.collection)
    }

    @Test
    fun `讨论版、评论、目录：路径与映射`() = runTest {
        val topics = repository(
            """{"data":[{"id":37348,"title":"神作","creatorID":1,"parentID":253,"replyCount":0,"createdAt":1759852769,"updatedAt":1759852769,"state":0,"display":1,"creator":${user(1, "yuki")}}],"total":31}""",
        ).getTopics(253)
        assertEquals("/p1/subjects/253/topics", requests.last().url.encodedPath)
        assertEquals("神作", topics.items.single().title)
        assertEquals("yuki", topics.items.single().creator?.nickname)
        assertEquals(20, topics.nextOffset)

        val reviews = repository(
            """{"data":[{"id":318086,"user":${user(427735, "隽杰")},"entry":{"id":373309,"type":1,"uid":427735,"title":"关于第21集","icon":"","summary":"摘要","replies":1,"public":true,"createdAt":1778006140,"updatedAt":1780275592}}],"total":80}""",
        ).getReviews(253, limit = 20, offset = 60)
        assertEquals("/p1/subjects/253/reviews", requests.last().url.encodedPath)
        assertEquals(373309, reviews.items.single().id)
        assertEquals("隽杰", reviews.items.single().author?.nickname)
        assertNull(reviews.nextOffset, "60 + 20 >= 80 是末页")

        val indexes = repository(
            """{"data":[{"id":104330,"uid":654728,"type":0,"title":"补番目录","private":false,"total":135,"stats":{"subject":{"anime":135}},"createdAt":1791088908,"updatedAt":1791096206,"user":${user(654728, ".boring")}}],"total":1274}""",
        ).getIndexes(253)
        assertEquals("/p1/subjects/253/indexes", requests.last().url.encodedPath)
        assertEquals(135, indexes.items.single().total)
    }

    @Test
    fun `谁在看：按状态筛时带 type，不筛时不带`() = runTest {
        val json = """{"data":[{"user":${user(1244623, "myname")},"interest":{"id":53889467,"rate":0,"type":3,"comment":"","tags":[],"updatedAt":1791180831}}],"total":40986}"""
        val doing = repository(json).getCollectors(253, type = CollectionType.DOING, limit = 5)
        assertEquals("/p1/subjects/253/collects", requests.last().url.encodedPath)
        assertEquals("3", requests.last().url.parameters["type"])
        assertEquals("5", requests.last().url.parameters["limit"])
        assertEquals(CollectionType.DOING, doing.items.single().type)
        assertEquals("myname", doing.items.single().user.nickname)
        assertEquals(1_791_180_831_000L, doing.items.single().updatedAt)

        repository(json).getCollectors(253)
        assertNull(requests.last().url.parameters["type"])
    }

    @Test
    fun `谁在看：契约 offset 上限 500，下一页越过上限就当末页`() = runTest {
        val json = """{"data":[{"user":${user(1, "a")},"interest":{"id":1,"rate":0,"type":3,"comment":"","tags":[],"updatedAt":1}}],"total":40986}"""
        assertEquals(500, repository(json).getCollectors(253, limit = 20, offset = 480).nextOffset)
        assertNull(repository(json).getCollectors(253, limit = 20, offset = 500).nextOffset)
    }
}
