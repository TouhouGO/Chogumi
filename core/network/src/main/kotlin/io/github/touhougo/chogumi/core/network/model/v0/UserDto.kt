package io.github.touhougo.chogumi.core.network.model.v0

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvatarDto(@Required val large: String = "", @Required val medium: String = "", @Required val small: String = "")
/** 普通用户响应；原有 /me 扩展字段保留为可选，完整 /me 合同由 MeDto 单独绑定。 */
@Serializable
data class UserDto(
    val id: Int, val username: String, val nickname: String,
    @Required @SerialName("user_group") val userGroup: Int = 10,
    @Required val avatar: AvatarDto = AvatarDto(), @Required val sign: String = "",
    val email: String? = null,
    @SerialName("reg_time") val regTime: String? = null,
    @SerialName("time_offset") val timeOffset: Int? = null,
)
@Serializable
data class MeDto(val id: Int, val username: String, val nickname: String, @SerialName("user_group") val userGroup: Int, val avatar: AvatarDto, val sign: String, val email: String, @SerialName("reg_time") val regTime: String, @SerialName("time_offset") val timeOffset: Int? = null)
@Serializable
data class CreatorDto(val username: String, val nickname: String)
