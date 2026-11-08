package io.github.touhougo.chogumi.core.network.model.v0

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionDto(
    @SerialName("subject_id") val subjectId: Int, @SerialName("subject_type") val subjectType: Int,
    @Required val rate: Int = 0, val type: Int, val comment: String = "",
    @Required val tags: List<String> = emptyList(),
    @Required @SerialName("ep_status") val epStatus: Int = 0,
    @Required @SerialName("vol_status") val volStatus: Int = 0,
    @Required @SerialName("updated_at") val updatedAt: String = "",
    @Required val `private`: Boolean = false,
    val subject: SlimSubjectDto? = null,
)
/** 可选非空字段：null 表示不更新；清空评论需传空字符串，标签需传空列表。 */
@Serializable
data class CollectionModifyDto(
    val type: Int? = null,
    val rate: Int? = null,
    @SerialName("ep_status") val epStatus: Int? = null,
    @SerialName("vol_status") val volStatus: Int? = null,
    val comment: String? = null,
    val `private`: Boolean? = null,
    val tags: List<String>? = null,
)
@Serializable
data class EpisodeStatusDto(val type: Int)
@Serializable
data class BatchEpisodeStatusDto(@SerialName("episode_id") val episodeIds: List<Int>, val type: Int)
@Serializable
data class UserEpisodeCollectionDto(val episode: EpisodeDto, val type: Int, @SerialName("updated_at") val updatedAt: Long)
@Serializable
data class EpisodeCollectionPageDto(val total: Int, val limit: Int, val offset: Int, val data: List<UserEpisodeCollectionDto> = emptyList())
