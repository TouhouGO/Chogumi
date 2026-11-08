package io.github.touhougo.chogumi.core.network

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.OAuthError
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException

/**
 * 异常 → 失败原因（[toFailure]）。文案不在这一层（core:ui `Failure.text()`），这里只钉「认成哪一种」：
 * 服务端原文（英文、内部 URL）在类型层面就上不了屏。
 */
class FailureMappingTest {

    private fun recordingLogger(lines: MutableList<String>) = object : Logger {
        override fun log(message: String) { lines += message }
    }

    // ---- 传输层 ----

    @Test
    fun `连不上服务器是离线`() = runTest {
        assertEquals(Failure.Offline, UnknownHostException("x").toFailure())
        assertEquals(Failure.Offline, ConnectException("refused").toFailure())
    }

    @Test
    fun `读超时与 OkHttp 的 SocketTimeoutException 是超时 不被通用 IO 分支吞掉`() = runTest {
        // 实测：OkHttp 的读超时不是 Ktor 的 HttpRequestTimeoutException，消息里带内部 URL
        val e = SocketTimeoutException("Socket timeout has expired [url=https://api.bgm.tv/v0/users/1/collections, socket_timeout=unknown] ms")
        assertEquals(Failure.Timeout, e.toFailure())
    }

    @Test
    fun `TLS 握手被关闭、EOF、连接被重置是传输中断`() = runTest {
        // 线上实测：javax.net.ssl.SSLHandshakeException: connection closed（原因链 EOFException）
        assertEquals(Failure.Interrupted, SSLHandshakeException("connection closed").apply { initCause(java.io.EOFException()) }.toFailure())
        assertEquals(Failure.Interrupted, java.io.EOFException().toFailure())
        assertEquals(Failure.Interrupted, SocketException("Connection reset").toFailure())
    }

    @Test
    fun `解析失败是响应读不懂`() = runTest {
        assertEquals(Failure.BadResponse, SerializationException("bad").toFailure())
    }

    @Test
    fun `数据层抛出的原因原样取回`() = runTest {
        assertEquals(Failure.NotLoggedIn, FailureException(Failure.NotLoggedIn).toFailure())
    }

    // ---- 未分类 ----

    @Test
    fun `认不出的异常是未知 原文不进结果`() = runTest {
        assertEquals(Failure.Unknown, IllegalStateException("Unexpected response from https://api.bgm.tv/v0/me").toFailure())
    }

    @Test
    fun `未分类异常记录类型与代码位置但不记录消息中的凭据`() = runTest {
        val lines = mutableListOf<String>()
        val error = IllegalStateException("https://example.invalid/?token=SENSITIVE_TEST_VALUE")
        error.stackTrace = arrayOf(StackTraceElement("test.Loader", "fetch", "Loader.kt", 17))
        assertEquals(Failure.Unknown, error.toFailure(recordingLogger(lines)))
        val line = lines.single()
        assertTrue(line.contains("java.lang.IllegalStateException"))
        assertTrue(line.contains("test.Loader.fetch(Loader.kt:17)"))
        assertFalse(line.contains("SENSITIVE_TEST_VALUE") || line.contains("http"))
    }

    @Test
    fun `处理位置是第一个不属于本文件与库的帧 混淆后的类名也认`() {
        val frames = arrayOf(
            StackTraceElement("com.x.FailureMappingKt", "unknownErrorDiagnostic", "SourceFile", 80),
            StackTraceElement("com.x.FailureMappingKt\$toFailure\$1", "invokeSuspend", "SourceFile", 40),
            StackTraceElement("kotlinx.coroutines.DispatchedTask", "run", "SourceFile", 1),
            StackTraceElement("io.ktor.client.HttpClient", "execute", "SourceFile", 2),
            StackTraceElement("jf2", "a", "SourceFile", 9),
        )
        assertEquals("jf2", handlingFrame(frames)?.className)
    }

    @Test
    fun `循环异常原因的诊断不会无限追溯且不泄漏消息`() = runTest {
        val lines = mutableListOf<String>()
        val first = IllegalStateException("SENSITIVE_FIRST_VALUE")
        val second = java.io.IOException("SENSITIVE_SECOND_VALUE", first)
        first.initCause(second)
        assertEquals(Failure.Unknown, first.toFailure(recordingLogger(lines)))
        val line = lines.single()
        assertTrue(line.contains("java.lang.IllegalStateException") && line.contains("java.io.IOException"))
        assertFalse(line.contains("SENSITIVE_FIRST_VALUE") || line.contains("SENSITIVE_SECOND_VALUE"))
    }

    @Test
    fun `认得出的异常不写未分类诊断`() = runTest {
        val lines = mutableListOf<String>()
        UnknownHostException("x").toFailure(recordingLogger(lines))
        SSLHandshakeException("connection closed").toFailure(recordingLogger(lines))
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `诊断日志写入失败不改变结果`() = runTest {
        val logger = object : Logger { override fun log(message: String) = error("日志器失效") }
        assertEquals(Failure.Unknown, IllegalStateException("broken stream").toFailure(logger))
    }

    // ---- 服务端回非 2xx ----

    /**
     * 跑真实异常构造路径：MockEngine 返回 [status]+[body]，用**生产客户端** [createHttpClient]——
     * 错误体解析依赖 ContentNegotiation，裸客户端解不出错误码，测试会「因为错误的原因通过」。
     * 响应体一律用线上实测抓到的原始字节。
     */
    private suspend fun failureFor(
        status: HttpStatusCode,
        body: String,
        url: String = "https://api.bgm.tv/v0/me",
    ): Failure {
        val engine = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        val client = testApiClient(engine)
        return runCatching { client.get(url) }.exceptionOrNull()!!.toFailure()
    }

    @Test
    fun `v0 的 401 只认状态码`() = runTest {
        // curl https://api.bgm.tv/v0/me 实测
        val failure = failureFor(
            HttpStatusCode.Unauthorized,
            """{"title":"Unauthorized","details":{"path":"/v0/me","method":"GET"},"request_id":"x","description":"need Login"}""",
        )
        assertEquals(Failure.Http(401), failure)
    }

    @Test
    fun `p1 的 401 认出错误码`() = runTest {
        // curl https://next.bgm.tv/p1/me 实测
        val failure = failureFor(
            HttpStatusCode.Unauthorized,
            """{"code":"NEED_LOGIN","error":"Unauthorized","message":"you need to login before get current user","statusCode":401}""",
            url = "https://next.bgm.tv/p1/me",
        )
        assertEquals(Failure.Http(401, OAuthError.UNAUTHORIZED), failure)
    }

    @Test
    fun `p1 人机验证没过是验证失败 不当成登录失效`() = runTest {
        // 错误体照服务端 lib/error.ts 的 CaptchaError（code CAPTCHA_ERROR，401）
        val failure = failureFor(
            HttpStatusCode.Unauthorized,
            """{"code":"CAPTCHA_ERROR","error":"Unauthorized","message":"wrong captcha","statusCode":401}""",
            url = "https://next.bgm.tv/p1/subjects/-/topics/1/replies",
        )
        assertEquals(Failure.Rejected(RejectReason.VERIFICATION_FAILED), failure)
    }

    @Test
    fun `p1 非公开小组没加入是只限成员 不当成登录失效`() = runTest {
        // 错误体照服务端 lib/error.ts 的 NotJoinPrivateGroupError（401）
        val failure = failureFor(
            HttpStatusCode.Unauthorized,
            """{"code":"NOT_JOIN_PRIVATE_GROUP_ERROR","error":"Unauthorized","message":"you need to join private group 'x' before you create a post or reply","statusCode":401}""",
            url = "https://next.bgm.tv/p1/groups/-/topics/1/replies",
        )
        assertEquals(Failure.Rejected(RejectReason.GROUP_MEMBERS_ONLY), failure)
    }

    @Test
    fun `p1 的 404 只认状态码`() = runTest {
        // curl -X POST https://next.bgm.tv/p1/subjects/8/collect 实测
        val failure = failureFor(
            HttpStatusCode.NotFound,
            """{"message":"Route POST:/p1/subjects/8/collect not found","error":"Not Found","statusCode":404}""",
            url = "https://next.bgm.tv/p1/subjects/8/collect",
        )
        assertEquals(Failure.Http(404), failure)
    }

    @Test
    fun `OAuth 错误码随状态码一起带出`() = runTest {
        val failure = failureFor(HttpStatusCode.BadRequest, """{"error":"invalid_grant","error_description":"code expired"}""")
        assertEquals(Failure.Http(400, OAuthError.INVALID_GRANT), failure)
    }

    @Test
    fun `不认识的错误码只留状态码`() = runTest {
        val failure = failureFor(HttpStatusCode.Unauthorized, """{"error":"something_new_from_server"}""")
        assertEquals(Failure.Http(401), failure)
    }

    @Test
    fun `500 是服务端错误 不带原文`() = runTest {
        // DELETE /p1/collections/persons/{id} 500 实测
        val failure = failureFor(
            HttpStatusCode.InternalServerError,
            """{"error":"Internal Server Error","message":"internal error, please contact admin","statusCode":500}""",
            url = "https://next.bgm.tv/p1/collections/persons/26574",
        )
        assertEquals(Failure.Http(500), failure)
    }
}
