package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TopicParent
import io.github.touhougo.chogumi.core.model.TopicType
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
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
 * 帖子页仓库：作品讨论版与小组话题各走各的接口，映射成同一种详情；贴贴发到话题所属的那一类楼层。
 * JSON 形状照实测响应裁剪（作品话题 2026-10-05 实测，小组话题 2026-10-13 实测），用户信息换成虚构值。
 */
class TopicRepositoryTest {
    @get:org.junit.Rule
    val resources = TestResources()


    private fun user(id: Int, nickname: String, sign: String = "") =
        """{"id":$id,"username":"u$id","nickname":"$nickname","avatar":{"small":"s$id","medium":"m$id","large":"l$id"},"group":10,"sign":"$sign","joinedAt":1,"isFriend":false}"""

    /** 每个请求记成「方法 路径 请求体」。 */
    private val requestRecorder = ConcurrentRequestRecorder()
    private suspend fun requests(): List<String> = requestRecorder.map {
        "${it.method.value} ${it.url.encodedPath} ${it.body.toByteArray().decodeToString()}".trim()
    }

    private suspend fun TestScope.repository(
        json: String,
        loggedInUserId: Int? = null,
        status: HttpStatusCode = HttpStatusCode.OK,
        duringRequest: suspend (TokenStore) -> Unit = {},
    ): TopicRepository {
        val tokenStore = testTokenStore(scope = backgroundScope)
        val engine = MockEngine { request ->
            requestRecorder.record(request)
            duringRequest(tokenStore)
            respond(json, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = resources.apiClient(engine)
        if (loggedInUserId != null) {
            val session = tokenStore.beginSession("token")
            tokenStore.completeSession(session, userId = loggedInUserId, username = "me")
        }
        return TopicRepositoryImpl(NextApi(client), tokenStore)
    }

    @Test
    fun `作品话题：两层楼、楼中楼挂在主楼下、删除楼不泄露原文`() = runTest {
        val json = """{"id":36704,"title":"你们是怎么看得下去的？","creatorID":317643,"parentID":253,"replyCount":3,
            "createdAt":1755082519,"updatedAt":1786612599,"state":0,"display":1,
            "subject":{"id":253,"name":"カウボーイビバップ","nameCN":"星际牛仔","type":2,"info":"","metaTags":[],
              "rating":{"rank":1,"count":[],"score":8.9,"total":1},"locked":false,"nsfw":false,
              "images":{"large":"L","common":"C","medium":"M","small":"S","grid":"G"}},
            "creator":${user(317643, "楼主")},
            "replies":[
              {"id":373498,"creatorID":317643,"createdAt":1755082519,"content":"主楼正文","state":0,"creator":${user(317643, "楼主", "健身")},
               "replies":[{"id":373525,"creatorID":999,"createdAt":1755094360,"content":"[quote][b]楼主[/b] 说: 主楼正文[/quote]不如","state":0,"creator":${user(999, "路人")}}]},
              {"id":373600,"creatorID":5,"createdAt":1755100000,"content":"被删的原文","state":6,"replies":[]}
            ]}"""
        val topic = repository(json).getTopic(TopicType.SUBJECT, 36704)

        assertEquals(listOf("GET /p1/subjects/-/topics/36704"), requests())
        assertEquals("你们是怎么看得下去的？", topic.title)
        val subject = (topic.parent as TopicParent.Subject).subject
        assertEquals(253, subject.id)
        assertEquals(SubjectType.ANIME, subject.type)
        assertEquals("L", subject.cover)
        assertEquals(317643, topic.creatorId)
        assertEquals(2, topic.threads.size)

        val first = topic.threads[0]
        assertEquals(373498, first.anchor.id)
        assertEquals(36704, first.anchor.mainId)
        assertEquals("健身", first.anchor.creatorSign)
        assertNull(first.anchor.collection)
        val reply = first.replies.single()
        assertEquals(36704, reply.mainId)
        assertEquals(373498, reply.relatedId)
        assertEquals("路人", reply.creatorNickname)

        val deleted = topic.threads[1].anchor
        assertEquals(CommentState.DELETED, deleted.state)
        assertEquals("", deleted.content)
    }

    @Test
    fun `小组话题：走小组接口，上级是小组，楼层与作品话题同一套映射`() = runTest {
        val json = """{"id":472971,"title":"说说你觉得会永远保持小众的东西吧","creatorID":824793,"parentID":364,"replyCount":2,
            "createdAt":1791869791,"updatedAt":1791931179,"state":0,"display":1,
            "group":{"id":364,"name":"boring","nsfw":false,"title":"靠谱人生茶话会 &amp; 闲聊",
              "icon":{"small":"S","medium":"M","large":"L"},"creatorID":5974,"members":16144,"accessible":true,"createdAt":1255065958},
            "creator":${user(824793, "楼主")},
            "replies":[
              {"id":4071249,"creatorID":824793,"createdAt":1791869791,"content":"主楼正文","state":0,"creator":${user(824793, "楼主")},
               "reactions":[{"users":[{"id":7,"username":"u7","nickname":"me"}],"value":54}],
               "replies":[{"id":4071300,"creatorID":9,"createdAt":1791869900,"content":"楼中楼","state":0,"creator":${user(9, "路人")}}]}
            ]}"""
        val topic = repository(json, loggedInUserId = 7).getTopic(TopicType.GROUP, 472971)

        assertEquals(listOf("GET /p1/groups/-/topics/472971"), requests())
        assertEquals(TopicParent.Group(GroupSummaryBo(name = "boring", title = "靠谱人生茶话会 & 闲聊", icon = "L", members = 16144)), topic.parent)
        assertEquals(824793, topic.creatorId)
        assertEquals(1_791_869_791_000L, topic.createdAt)
        val first = topic.threads.single()
        assertEquals(472971, first.anchor.mainId)
        assertTrue(first.anchor.reactions.single().selected)
        assertEquals(4071249, first.replies.single().relatedId)
    }

    @Test
    fun `贴贴发到话题所属的那一类楼层`() = runTest {
        val repo = repository("{}", loggedInUserId = 7)

        assertEquals(Outcome.Success(Unit), repo.setPostReaction(TopicType.SUBJECT, 11, 54))
        assertEquals(Outcome.Success(Unit), repo.setPostReaction(TopicType.GROUP, 22, 140))
        assertEquals(Outcome.Success(Unit), repo.setPostReaction(TopicType.GROUP, 22, null))
        assertEquals(
            listOf(
                """PUT /p1/subjects/-/posts/11/like {"value":54}""",
                """PUT /p1/groups/-/posts/22/like {"value":140}""",
                "DELETE /p1/groups/-/posts/22/like",
            ),
            requests(),
        )
    }

    @Test
    fun `回复发到话题所属的那一类接口，请求体带正文、主楼 id 与验证令牌，回新楼 id`() = runTest {
        val repo = repository("""{"id":4321}""", loggedInUserId = 7)

        assertEquals(Outcome.Success(4321), repo.reply(TopicType.SUBJECT, 36704, "同意", replyTo = 20, turnstileToken = "tk"))
        assertEquals(Outcome.Success(4321), repo.reply(TopicType.GROUP, 400, "新开一层", replyTo = 0, turnstileToken = "tk2"))
        assertEquals(
            listOf(
                """POST /p1/subjects/-/topics/36704/replies {"content":"同意","turnstileToken":"tk","replyTo":20}""",
                """POST /p1/groups/-/topics/400/replies {"content":"新开一层","turnstileToken":"tk2","replyTo":0}""",
            ),
            requests(),
        )
    }

    @Test
    fun `回复被 403 拒绝说成禁言或话题已关闭`() = runTest {
        // 错误体照服务端 lib/topic/post.ts 关闭话题时的 NotAllowedError（403）
        val repo = repository(
            """{"code":"NOT_ALLOWED","error":"Forbidden","message":"you don't have permission to reply to a closed topic","statusCode":403}""",
            loggedInUserId = 7,
            status = HttpStatusCode.Forbidden,
        )

        assertEquals(
            Outcome.Failed(Failure.Rejected(RejectReason.POSTING_NOT_ALLOWED)),
            repo.reply(TopicType.SUBJECT, 1, "正文", replyTo = 0, turnstileToken = "tk"),
        )
    }

    private suspend fun TestScope.assertSwitchedWrite(action: suspend (TopicRepository, TopicType) -> Outcome<*>) {
        for (type in TopicType.entries) {
            val repo = repository("""{"id":4321}""", loggedInUserId = 7, duringRequest = { store ->
                store.completeSession(store.beginSession("other"), userId = 8, username = "bob")
            })
            assertEquals(Outcome.Failed(Failure.SessionChanged), action(repo, type), type.name)
        }
    }

    @Test
    fun `贴贴响应期间换号不返回成功`() = runTest {
        assertSwitchedWrite { repo, type -> repo.setPostReaction(type, 11, 54) }
    }

    @Test
    fun `回复响应期间换号不返回新楼成功`() = runTest {
        assertSwitchedWrite { repo, type -> repo.reply(type, 11, "正文", replyTo = 0, turnstileToken = "tk") }
    }

    @Test
    fun `改楼响应期间换号不返回成功`() = runTest {
        assertSwitchedWrite { repo, type -> repo.editPost(type, 11, "正文") }
    }

    @Test
    fun `删楼响应期间换号不返回成功`() = runTest {
        assertSwitchedWrite { repo, type -> repo.deletePost(type, 11) }
    }

}
