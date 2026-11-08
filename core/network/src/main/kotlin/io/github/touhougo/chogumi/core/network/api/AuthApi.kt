package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.foundation.BGM_SITE
import io.github.touhougo.chogumi.core.network.model.TokenRequestDto
import io.github.touhougo.chogumi.core.network.model.TokenResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.net.URLEncoder

/** OAuth token 端点（已实测：JSON 请求体，字段 client_id/client_secret/...） */
class AuthApi(
    private val client: HttpClient,
    private val config: AuthConfig,
) {

    /** 浏览器 OAuth 的授权页地址；[state] 由调用方存下，回调时核对。 */
    fun authorizeUrl(state: String): String = "$BGM_SITE$AUTHORIZE_PATH" +
        "?client_id=${config.clientId}&response_type=code" +
        "&redirect_uri=${URLEncoder.encode(config.redirectUri, "UTF-8")}&state=$state"

    /** 授权码换取 token */
    suspend fun exchangeCode(code: String): TokenResponseDto =
        client.post("$BGM_SITE/oauth/access_token") {
            contentType(ContentType.Application.Json)
            setBody(
                TokenRequestDto(
                    clientId = config.clientId,
                    clientSecret = config.clientSecret,
                    grantType = "authorization_code",
                    code = code,
                    redirectUri = config.redirectUri,
                )
            )
        }.body()

    /** 用 refresh_token 换新 token */
    suspend fun refresh(refreshToken: String): TokenResponseDto =
        client.post("$BGM_SITE/oauth/access_token") {
            contentType(ContentType.Application.Json)
            setBody(
                TokenRequestDto(
                    clientId = config.clientId,
                    clientSecret = config.clientSecret,
                    grantType = "refresh_token",
                    refreshToken = refreshToken,
                )
            )
        }.body()
}

/** OAuth 授权页的路径：浏览器登录打开它，账号密码登录在网页会话里提交它的表单（WebLoginApi）。 */
internal const val AUTHORIZE_PATH = "/oauth/authorize"
