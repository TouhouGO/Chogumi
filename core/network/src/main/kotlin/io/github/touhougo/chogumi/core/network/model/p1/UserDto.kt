package io.github.touhougo.chogumi.core.network.model.p1

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponseDto(val code: String, val error: String, val message: String, val statusCode: Int)
@Serializable
data class PermissionsDto(val subjectWikiEdit: Boolean)
@Serializable
data class ProfileDto(val id: Int, val username: String, val nickname: String, val avatar: AvatarDto, val sign: String, val group: Int, val joinedAt: Long, val site: String, val location: String, val permissions: PermissionsDto)
@Serializable
data class UserDto(val id: Int, val username: String, val nickname: String, val avatar: AvatarDto, val group: Int, val joinedAt: Long, val sign: String, val site: String, val location: String, val bio: String, val networkServices: List<UserNetworkServiceDto>, val homepage: UserHomepageDto, val stats: UserStatsDto, val isFriend: Boolean)

/**
 * 好友/关注列表里的用户（契约里的 `SlimUser`）。
 *
 * 与 [UserDto] 是**两个不同的契约类型**，不能互换：
 * 这里没有 `site`/`location`/`bio`/`networkServices`/`homepage`/`stats`。
 * 复用 [UserDto] 会让这些字段恒为默认值，界面上看起来像"服务端没返回"。
 */
@Serializable
data class SlimUserDto(val id: Int, val username: String, val nickname: String, val avatar: AvatarDto, val group: Int, val joinedAt: Long, val sign: String, val isFriend: Boolean)
@Serializable
data class UserNetworkServiceDto(val name: String, val title: String, val url: String, val color: String, val account: String)
@Serializable
data class UserHomepageDto(val left: List<String>, val right: List<String>)
@Serializable
data class UserIndexStatsDto(val create: Int, val collect: Int)
@Serializable
data class UserMonoCollectionStatsDto(val character: Int, val person: Int)
@Serializable
data class UserStatsDto(val subject: Map<String, Map<String, Int>>, val mono: UserMonoCollectionStatsDto, val blog: Int, val friend: Int, val group: Int, val index: UserIndexStatsDto)
@Serializable
data class FriendDto(val user: P1UserDto, val grade: Int, val createdAt: Long, val description: String)
@Serializable
data class NoticeDto(val id: Int, val type: Int, val sender: P1UserDto, val title: String, val mainID: Int, val relatedID: Int, val createdAt: Long, val unread: Boolean)
@Serializable
data class LoginRequestBodyDto(val email: String, val password: String, val turnstileToken: String)
