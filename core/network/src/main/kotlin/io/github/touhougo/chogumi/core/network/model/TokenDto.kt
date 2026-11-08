package io.github.touhougo.chogumi.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** POST /oauth/access_token 请求体（authorization_code 与 refresh_token 两种 grant 共用） */
@Serializable
data class TokenRequestDto(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
    @SerialName("grant_type") val grantType: String,
    @SerialName("code") val code: String? = null,
    @SerialName("redirect_uri") val redirectUri: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

/** token 端点响应（已实测：user_id 为字符串） */
@Serializable
data class TokenResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("scope") val scope: String? = null,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("user_id") val userId: String,
)
