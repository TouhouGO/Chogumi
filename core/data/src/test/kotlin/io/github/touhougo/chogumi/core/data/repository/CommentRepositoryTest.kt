package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.testApiClient
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 对象评论区的写：每种评论区发到自己的接口，回复的主楼与人机验证令牌随请求走；草稿按评论区与楼层分开存。 */
class CommentRepositoryTest {

    /** 每个请求记成「方法 路径」，请求体另存。 */
    private val requestRecorder = ConcurrentRequestRecorder()
    private val requests: List<String> get() = requestRecorder.map { "${it.method.value} ${it.url.encodedPath}" }

    private suspend fun TestScope.repository(
        json: String = """{"id":4321}""",
        status: HttpStatusCode = HttpStatusCode.OK,
    ): CommentRepository {
        val tokenStore = testTokenStore(scope = backgroundScope)
        val engine = MockEngine { request ->
            requestRecorder.record(request)
            respond(json, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        tokenStore.completeSession(tokenStore.beginSession("token"), userId = 7, username = "me")
        val drafts = testDraftRepository(backgroundScope, tokens = tokenStore)
        return CommentRepositoryImpl(NextApi(testApiClient(engine)), tokenStore, drafts, TestClock(10), testWebSessionRepository(tokenStore, noWebClient()))
    }

    @Test
    fun `每种评论区发到自己的评论接口，成功是新楼的 id`() = runTest {
        val repo = repository()
        val boxes = listOf(
            CommentSurface.Blog(5),
            CommentSurface.Index(3),
            CommentSurface.Mono(MonoType.CHARACTER, 8),
            CommentSurface.Mono(MonoType.PERSON, 9),
            CommentSurface.Episode(1027),
        )

        boxes.forEach { assertEquals(Outcome.Success(4321), repo.reply(it, "正文", replyTo = 0, turnstileToken = "tk"), "$it") }

        assertEquals(
            listOf(
                "POST /p1/blogs/5/comments",
                "POST /p1/indexes/3/comments",
                "POST /p1/characters/8/comments",
                "POST /p1/persons/9/comments",
                "POST /p1/episodes/1027/comments",
            ),
            requests,
        )
    }

    @Test
    fun `回复带上主楼 id 与人机验证令牌`() = runTest {
        repository().reply(CommentSurface.Episode(1027), "楼中楼", replyTo = 88, turnstileToken = "tk")

        val body = Json.parseToJsonElement(requestRecorder.single().body.toByteArray().decodeToString()).jsonObject
        assertEquals("楼中楼", body.getValue("content").jsonPrimitive.content)
        assertEquals(88, body.getValue("replyTo").jsonPrimitive.int)
        assertEquals("tk", body.getValue("turnstileToken").jsonPrimitive.content)
    }

    @Test
    fun `被 403 拒绝说成禁言`() = runTest {
        val repo = repository(
            """{"code":"NOT_ALLOWED","error":"Forbidden","message":"you don't have permission","statusCode":403}""",
            status = HttpStatusCode.Forbidden,
        )

        assertEquals(
            Outcome.Failed(Failure.Rejected(RejectReason.POSTING_NOT_ALLOWED)),
            repo.reply(CommentSurface.Blog(5), "正文", replyTo = 0, turnstileToken = "tk"),
        )
    }

    @Test
    fun `草稿按评论区与回复的楼层分开存`() = runTest {
        val repo = repository()
        val character = repo.replyDrafts<Int>(CommentSurface.Mono(MonoType.CHARACTER, 8)) { it }
        val person = repo.replyDrafts<Int>(CommentSurface.Mono(MonoType.PERSON, 8)) { it }

        val owner = (character.bind() as Outcome.Success).value
        character.save(owner, 0, ComposerDraft("", "新的一条"))
        character.save(owner, 12, ComposerDraft("", "回 12 楼"))

        assertEquals(Outcome.Success(ComposerDraft("", "新的一条")), character.load(owner, 0))
        assertEquals(Outcome.Success(ComposerDraft("", "回 12 楼")), character.load(owner, 12))
        assertNull((person.load(owner, 0) as Outcome.Success).value)
    }
}
