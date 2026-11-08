package io.github.touhougo.chogumi.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** bangumi API 错误响应体（v0 风格 title/description + OAuth 风格 error/error_description + p1 的错误码 code） */
@Serializable
data class ErrorDetailDto(
    val title: String = "",
    val description: String = "",
    @SerialName("error") val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    val code: String? = null,
)
