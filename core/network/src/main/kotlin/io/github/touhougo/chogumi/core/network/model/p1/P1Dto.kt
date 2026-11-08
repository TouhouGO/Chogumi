package io.github.touhougo.chogumi.core.network.model.p1

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/** p1 评分分布为整数数组，不能与 v0 的分数字典互换。 */
@Serializable
data class P1RatingDto(
    @Required val rank: Int = 0,
    @Required val count: List<Int> = emptyList(),
    @Required val score: Double = 0.0,
    @Required val total: Int = 0,
)

@Serializable
data class P1SlimSubjectDto(
    val id: Int,
    val name: String,
    @Required val nameCN: String = "",
    val type: Int,
    val images: SubjectImagesDto? = null,
    @Required val info: String = "",
    @Required val metaTags: List<String> = emptyList(),
    @Required val rating: P1RatingDto = P1RatingDto(),
    @Required val locked: Boolean = false,
    @Required val nsfw: Boolean = false,
    val interest: SlimSubjectInterestDto? = null,
)

@Serializable
data class CalendarItemDto(val subject: P1SlimSubjectDto, @Required val watchers: Int = 0)

/** p1 自有头像类型；不跨 host 复用 transport DTO。 */
@Serializable
data class AvatarDto(
    @Required val small: String = "",
    @Required val medium: String = "",
    @Required val large: String = "",
)

@Serializable
data class P1UserDto(
    val id: Int,
    val username: String,
    val nickname: String,
    @Required val avatar: AvatarDto = AvatarDto(),
    @Required val group: Int = 10,
    @Required val sign: String = "",
    @Required val joinedAt: Long = 0,
    @Required val isFriend: Boolean = false,
)

/** cat 在外层；memo 是一组可选字段，不是带 discriminator 的联合。时间单位为 Unix 秒。 */
@Serializable
data class TimelineDto(
    val id: Long,
    val uid: Int,
    val user: P1UserDto? = null,
    @Required val cat: Int = 0,
    @Required val type: Int = 0,
    @Required val memo: TimelineMemoDto = TimelineMemoDto(),
    @Required val batch: Boolean = false,
    @Required val source: TimelineSourceDto = TimelineSourceDto(""),
    @Required val replies: Int = 0,
    @Required val createdAt: Long = 0,
    val reactions: List<ReactionDto>? = null,
)
