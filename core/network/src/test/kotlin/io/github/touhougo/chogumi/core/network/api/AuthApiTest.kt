package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.network.createTokenHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AuthApiTest {

    private val config = AuthConfig(
        clientId = "bgmtest",
        clientSecret = "secret",
        redirectUri = "chogumi://oauth/callback",
    )

    private val tokenJson =
        """{"access_token":"at123","expires_in":604800,"token_type":"Bearer",
            "scope":null,"refresh_token":"rt456","user_id":"42"}"""

    private fun makeApi(status: HttpStatusCode, content: String): Pair<AuthApi, MockEngine> {
        val engine = MockEngine {
            respond(
                content = content,
                status = status,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = createTokenHttpClient("test", engine, customizers = emptyList())
        return AuthApi(client, config) to engine
    }

    @Test
    fun `授权码换 token 请求路径与响应解析`() = runTest {
        val (api, engine) = makeApi(HttpStatusCode.OK, tokenJson)
        val result = api.exchangeCode("the_code")
        assertEquals("at123", result.accessToken)
        assertEquals("rt456", result.refreshToken)
        assertEquals(604800, result.expiresIn)
        assertEquals("42", result.userId)
        val request = engine.requestHistory.single()
        assertEquals("bgm.tv", request.url.host)
        assertEquals("/oauth/access_token", request.url.encodedPath)
    }

    @Test
    fun `授权页地址_路径与参数`() {
        val (api, _) = makeApi(HttpStatusCode.OK, tokenJson)
        val url = io.ktor.http.Url(api.authorizeUrl("st-1"))
        assertEquals("bgm.tv", url.host)
        assertEquals("/oauth/authorize", url.encodedPath)
        assertEquals("bgmtest", url.parameters["client_id"])
        assertEquals("code", url.parameters["response_type"])
        assertEquals("chogumi://oauth/callback", url.parameters["redirect_uri"])
        assertEquals("st-1", url.parameters["state"])
    }

    @Test
    fun `refresh 请求带齐文档要求的参数`() = runTest {
        val (api, engine) = makeApi(HttpStatusCode.OK, tokenJson)
        api.refresh("old_rt")
        val request = engine.requestHistory.single()
        assertEquals("/oauth/access_token", request.url.encodedPath)
        val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
        assertEquals("refresh_token", body["grant_type"]?.jsonPrimitive?.content)
        assertEquals("bgmtest", body["client_id"]?.jsonPrimitive?.content)
        assertEquals("secret", body["client_secret"]?.jsonPrimitive?.content)
        assertEquals("old_rt", body["refresh_token"]?.jsonPrimitive?.content)
        assertEquals("chogumi://oauth/callback", body["redirect_uri"]?.jsonPrimitive?.content)
    }

    @Test
    fun `token 端点 401 抛出客户端异常`() = runTest {
        val (api, _) = makeApi(
            HttpStatusCode.Unauthorized,
            """{"error":"invalid_client","error_description":"bad secret"}""",
        )
        assertFailsWith<ClientRequestException> { api.exchangeCode("code") }
    }
}
