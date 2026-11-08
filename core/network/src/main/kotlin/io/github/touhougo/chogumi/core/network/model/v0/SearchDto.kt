package io.github.touhougo.chogumi.core.network.model.v0

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchRequestDto(
    val keyword: String,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault
    val sort: String = "match",
    val filter: SearchFilterDto? = null,
)
@Serializable
data class SearchFilterDto(
    val type: List<Int>? = null,
    val tag: List<String>? = null,
    @SerialName("air_date") val airDate: List<String>? = null,
    val rating: List<String>? = null,
    val nsfw: Boolean? = null,
    @SerialName("meta_tags") val metaTags: List<String>? = null,
    @SerialName("rating_count") val ratingCount: List<String>? = null,
    val rank: List<String>? = null,
)
@Serializable
data class CharacterSearchRequestDto(val keyword: String, val filter: CharacterSearchFilterDto? = null)
@Serializable
data class CharacterSearchFilterDto(val nsfw: Boolean? = null)
@Serializable
data class PersonSearchRequestDto(val keyword: String, val filter: PersonSearchFilterDto? = null)
@Serializable
data class PersonSearchFilterDto(val career: List<String>? = null)
