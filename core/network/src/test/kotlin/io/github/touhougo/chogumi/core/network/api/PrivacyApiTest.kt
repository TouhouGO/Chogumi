package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.network.testApiClient
import io.github.touhougo.chogumi.core.network.toFailure
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/** 改 NSFW 偏好被拒时，失败原因由接口层直接给出，经 `toFailure()` 原样取回，不落成「未知错误」。 */
class PrivacyApiTest {

    private suspend fun patchFailure(status: HttpStatusCode): Failure {
        val api = PrivacyApi(testApiClient(MockEngine { respond(content = "{}", status = status) }))
        return runCatching { api.patchShowNsfw("token-a", value = true) }.exceptionOrNull()!!.toFailure()
    }

    @Test
    fun `403 是账号注册未满 60 天`() = runTest {
        assertEquals(Failure.Rejected(RejectReason.NSFW_ACCOUNT_TOO_NEW), patchFailure(HttpStatusCode.Forbidden))
    }

    @Test
    fun `5xx 原样是服务端故障_由数据层改走网页`() = runTest {
        assertEquals(Failure.Http(500), patchFailure(HttpStatusCode.InternalServerError))
    }

    @Test
    fun `其余 4xx 按状态码`() = runTest {
        assertEquals(Failure.Http(401), patchFailure(HttpStatusCode.Unauthorized))
    }
}
