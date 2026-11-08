package io.github.touhougo.chogumi.core.network.model.v0

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ImagesDto(@Required val large: String = "", @Required val common: String = "", @Required val medium: String = "", @Required val small: String = "", @Required val grid: String = "")
/** JSON 文本分数键必须保留，不按 p1 的整数数组解释。 */
@Serializable
data class RatingDto(@Required val rank: Int = 0, @Required val total: Int = 0, @Required val score: Double = 0.0, @Required val count: Map<String, Int> = emptyMap())
@Serializable
data class SubjectCollectionCountDto(@Required val wish: Int = 0, @Required val collect: Int = 0, @Required val doing: Int = 0, @Required @SerialName("on_hold") val onHold: Int = 0, @Required val dropped: Int = 0)
@Serializable
data class TagDto(@Required val name: String = "", @Required val count: Int = 0, @Required @SerialName("total_count") val totalCount: Int = 0)
@Serializable
data class SubjectDto(
    val id: Int, val type: Int, val name: String,
    @Required @SerialName("name_cn") val nameCn: String = "",
    @Required val summary: String = "", @Required val series: Boolean = false,
    @Required val nsfw: Boolean = false, @Required val locked: Boolean = false,
    val date: String? = null,
    @Required val platform: String = "", @Required val images: ImagesDto = ImagesDto(),
    @Required val volumes: Int = 0, @Required val eps: Int = 0,
    @Required @SerialName("total_episodes") val totalEpisodes: Int = 0,
    @Required val rating: RatingDto = RatingDto(),
    @Required val collection: SubjectCollectionCountDto = SubjectCollectionCountDto(),
    @Required @SerialName("meta_tags") val metaTags: List<String> = emptyList(),
    @Required val tags: List<TagDto> = emptyList(),
    val infobox: List<InfoboxItemDto>? = null,
)
@Serializable
data class SlimSubjectDto(
    val id: Int, val type: Int, val name: String,
    @Required @SerialName("name_cn") val nameCn: String = "",
    @Required @SerialName("short_summary") val shortSummary: String = "",
    val date: String? = null,
    @Required val images: ImagesDto = ImagesDto(), @Required val volumes: Int = 0, @Required val eps: Int = 0,
    @Required @SerialName("collection_total") val collectionTotal: Int = 0,
    @Required val score: Double = 0.0, @Required val rank: Int = 0, @Required val tags: List<TagDto> = emptyList(),
)
