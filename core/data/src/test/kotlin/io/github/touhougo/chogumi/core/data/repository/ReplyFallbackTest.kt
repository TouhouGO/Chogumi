package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.web.TestWebSite
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.TopicType
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * 回帖的容灾：开放接口回 5xx 时用网页会话把同一条送进网页的回复表单，先查重、后回读。话题（[TopicRepository]）与
 * 评论区（[CommentRepository]）两类消费方各测一遍。网页表单照 2026-11-08 实测裁剪，楼层 JSON 照接口形状裁剪。
 */
class ReplyFallbackTest {
    @get:org.junit.Rule
    val resources = TestResources()

    /** 发送时刻：1794120000 秒。 */
    private val clock = TestClock(1_794_120_000_000L)

    /** 假的开放接口：回复以 [replyStatus] 失败；楼层先是 [before]，网页提交之后是 [after]。 */
    private var replyStatus = HttpStatusCode.InternalServerError
    private var before = listOf(floor(100, creator = 5, content = "楼主的主楼"))
    private var after: List<String>? = null
    private lateinit var site: TestWebSite

    private fun floor(id: Int, creator: Int, content: String, createdAt: Long = 1_700_000_000L, replies: List<String> = emptyList()) =
        """{"id":$id,"creatorID":$creator,"createdAt":$createdAt,"content":"$content","state":0,"replies":[${replies.joinToString(",")}]}"""

    private fun sub(id: Int, creator: Int, content: String, createdAt: Long) =
        """{"id":$id,"creatorID":$creator,"createdAt":$createdAt,"content":"$content","state":0}"""

    private fun comment(id: Int, creator: Int, content: String, createdAt: Long = 1_700_000_000L) =
        """{"id":$id,"mainID":519,"creatorID":$creator,"relatedID":0,"createdAt":$createdAt,"content":"$content","state":0,"replies":[]}"""

    private fun replyForm(action: String) = """<form id="ReplyForm" name="new_comment" method="post" action="$action">
        <input type="hidden" name="formhash" value="560139dc" /><input type="hidden" name="lastview" value="1794120762" />
        <textarea name="content" id="content" class="reply newReply"></textarea>
        <input class="inputBtn" value="加上去" name="submit" type="submit" /></form>"""

    private fun groupTopic(replies: List<String>) =
        """{"id":375793,"title":"测试贴","creatorID":5,"parentID":4215,"replyCount":1,"createdAt":1,"updatedAt":1,"state":0,"display":1,
            "group":{"id":4215,"name":"sandbox","nsfw":false,"title":"沙盒","icon":{"small":"s","medium":"m","large":"l"},"creatorID":1,"members":1,"accessible":true,"createdAt":1},
            "replies":[${replies.joinToString(",")}]}"""

    private fun currentFloors() = if (site.postCount > 0) after ?: before else before

    private suspend fun TestScope.signIn(webSession: Boolean = true): Pair<TokenStore, WebSessionRepository> {
        val tokenStore = testTokenStore(scope = backgroundScope)
        val session = tokenStore.completeSession(tokenStore.beginSession("token"), userId = 7, username = "me")
        site = TestWebSite(userId = 7).apply {
            pages["/group/topic/375793"] = replyForm("/group/topic/375793/new_reply")
            pages["/ep/519"] = replyForm("/subject/ep/519/new_reply")
            pages["/index/3/comments"] = replyForm("/index/3/new_reply")
        }
        val webSessions = testWebSessionRepository(tokenStore, resources.own(site.client))
        if (webSession) webSessions.save(session, WebSession("chii_auth=c00k1e"))
        return tokenStore to webSessions
    }

    private fun api(body: (String) -> String) = NextApi(
        resources.apiClient(
            MockEngine { request ->
                val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                if (request.method == HttpMethod.Post) {
                    respond("""{"statusCode":${replyStatus.value}}""", replyStatus, json)
                } else {
                    respond(body(request.url.encodedPath), HttpStatusCode.OK, json)
                }
            },
        ),
    )

    private suspend fun TestScope.topics(webSession: Boolean = true): TopicRepository {
        val (tokenStore, webSessions) = signIn(webSession)
        return TopicRepositoryImpl(api { groupTopic(currentFloors()) }, tokenStore, webSessions, clock)
    }

    private suspend fun TestScope.comments(): CommentRepository {
        val (tokenStore, webSessions) = signIn()
        val drafts = testDraftRepository(backgroundScope, tokens = tokenStore)
        return CommentRepositoryImpl(api { "[${currentFloors().joinToString(",")}]" }, tokenStore, drafts, clock, webSessions)
    }

    private fun submittedFields(): Map<String, String> = site.submitted.single().second.split('&').associate { field ->
        val (name, value) = field.split('=', limit = 2)
        name to java.net.URLDecoder.decode(value, "UTF-8")
    }

    @Test
    fun `话题回复遇服务端5xx时用网页会话回复_回读出新楼的id`() = runTest {
        val repo = topics()
        after = before + floor(4103407, creator = 7, content = "网页送进去的", createdAt = 1_794_120_001L)

        assertEquals(Outcome.Success(4103407), repo.reply(TopicType.GROUP, 375793, "网页送进去的", replyTo = 0, turnstileToken = "tk"))

        assertEquals("https://bgm.tv/group/topic/375793/new_reply", site.submitted.single().first)
        assertEquals("网页送进去的", submittedFields().getValue("content"))
        assertTrue("related" !in submittedFields(), "新开一层不带主楼")
    }

    @Test
    fun `回复一层楼时网页表单带上主楼与它的作者`() = runTest {
        val repo = topics()
        after = listOf(floor(100, creator = 5, content = "楼主的主楼", replies = listOf(sub(4103408, 7, "楼中楼", 1_794_120_001L))))

        assertEquals(Outcome.Success(4103408), repo.reply(TopicType.GROUP, 375793, "楼中楼", replyTo = 100, turnstileToken = "tk"))

        val fields = submittedFields()
        assertEquals("100", fields.getValue("related"))
        assertEquals("5", fields.getValue("sub_reply_uid"))
        assertEquals("5", fields.getValue("post_uid"))
    }

    @Test
    fun `接口其实写进去了时不再从网页发第二次`() = runTest {
        val repo = topics()
        before = before + floor(4103400, creator = 7, content = "已经写进去的", createdAt = 1_794_120_002L)

        assertEquals(Outcome.Success(4103400), repo.reply(TopicType.GROUP, 375793, "已经写进去的\n", replyTo = 0, turnstileToken = "tk"))

        assertEquals(0, site.postCount)
    }

    @Test
    fun `早先发过的同样一句不算这一次写进去了`() = runTest {
        val repo = topics()
        before = before + floor(4000000, creator = 7, content = "顶", createdAt = 1_700_000_000L)
        after = before + floor(4103407, creator = 7, content = "顶", createdAt = 1_794_120_001L)

        assertEquals(Outcome.Success(4103407), repo.reply(TopicType.GROUP, 375793, "顶", replyTo = 0, turnstileToken = "tk"))

        assertEquals(1, site.postCount)
    }

    @Test
    fun `网页提交之后回读不到新楼时说去网页端回复`() = runTest {
        val repo = topics()
        after = before

        assertEquals(
            Outcome.Failed(Failure.Rejected(RejectReason.REPLY_UNAVAILABLE)),
            repo.reply(TopicType.GROUP, 375793, "没进去", replyTo = 0, turnstileToken = "tk"),
        )
    }

    @Test
    fun `没有网页会话时原样给出接口的5xx_不碰网页`() = runTest {
        val repo = topics(webSession = false)

        assertEquals(Outcome.Failed(Failure.Http(500)), repo.reply(TopicType.GROUP, 375793, "正文", replyTo = 0, turnstileToken = "tk"))

        assertTrue(site.opened.isEmpty())
        assertEquals(0, site.postCount)
    }

    @Test
    fun `不是5xx的失败原样给出_不走网页`() = runTest {
        val repo = topics()
        replyStatus = HttpStatusCode.Forbidden

        assertEquals(
            Outcome.Failed(Failure.Rejected(RejectReason.POSTING_NOT_ALLOWED)),
            repo.reply(TopicType.GROUP, 375793, "正文", replyTo = 0, turnstileToken = "tk"),
        )

        assertEquals(0, site.postCount)
    }

    @Test
    fun `评论区回复遇服务端5xx时送进对象网页的回复表单_目录在留言页`() = runTest {
        for ((box, action) in listOf(CommentSurface.Episode(519) to "https://bgm.tv/subject/ep/519/new_reply", CommentSurface.Index(3) to "https://bgm.tv/index/3/new_reply")) {
            val repo = comments()
            before = listOf(comment(1, creator = 5, content = "别人的"))
            after = before + comment(9001, creator = 7, content = "评论区的一条", createdAt = 1_794_120_001L)

            assertEquals(Outcome.Success(9001), repo.reply(box, "评论区的一条", replyTo = 0, turnstileToken = "tk"), "$box")
            assertEquals(action, site.submitted.single().first, "$box")
        }
    }
}
