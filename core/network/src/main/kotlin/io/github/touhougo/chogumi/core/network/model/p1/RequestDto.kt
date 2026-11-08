package io.github.touhougo.chogumi.core.network.model.p1

import kotlinx.serialization.Serializable

@Serializable
data class CharacterCreateDto(val name: String, val infobox: String, val summary: String, val type: Int, val img: String? = null)
@Serializable
data class CharacterEditDto(val name: String, val infobox: String, val summary: String)
@Serializable
data class CharacterSearchFilterDto(val nsfw: Boolean? = null)
@Serializable
data class CollectSubjectDto(
    val type: Int? = null,
    val rate: Int? = null,
    val comment: String? = null,
    val `private`: Boolean? = null,
    val tags: List<String>? = null,
    val progress: Boolean? = null,
)
@Serializable
data class CreateBlogDto(val title: String, val content: String, val tags: List<String>? = null, val `public`: Boolean? = null, val subjectIDs: List<Int>? = null)
@Serializable
data class CreateContentDto(val content: String)
@Serializable
data class CreateIndexDto(val title: String, val desc: String, val `private`: Boolean? = null)
@Serializable
data class CreateIndexRelatedDto(val cat: Int, val sid: Int, val order: Int? = null, val comment: String? = null, val award: String? = null)
@Serializable
data class CreateReplyDto(val content: String, val replyTo: Long? = null)
@Serializable
data class CreateReportDto(val type: Int, val id: Int, val value: Int, val comment: String? = null)
@Serializable
data class CreateSubjectCommentDto(val comment: String, val type: Int? = null, val rate: Int? = null)
@Serializable
data class CreateTopicDto(val title: String, val content: String)
@Serializable
data class ProfessionDto(
    val producer: Boolean? = null,
    val mangaka: Boolean? = null,
    val artist: Boolean? = null,
    val seiyu: Boolean? = null,
    val writer: Boolean? = null,
    val illustrator: Boolean? = null,
    val actor: Boolean? = null,
)
@Serializable
data class PersonCreateDto(val name: String, val infobox: String, val summary: String, val type: Int, val profession: ProfessionDto? = null, val img: String? = null)
@Serializable
data class PersonEditDto(val name: String, val infobox: String, val summary: String, val profession: ProfessionDto)
@Serializable
data class PersonSearchFilterDto(val career: List<String>? = null)
@Serializable
data class SearchCharacterDto(val keyword: String, val filter: CharacterSearchFilterDto? = null)
@Serializable
data class SearchPersonDto(val keyword: String, val filter: PersonSearchFilterDto? = null)
@Serializable
data class SearchSubjectDto(val keyword: String, val sort: String? = null, val filter: SubjectSearchFilterDto? = null)
@Serializable
data class SubjectSearchFilterDto(
    val type: List<Int>? = null,
    val tags: List<String>? = null,
    val metaTags: List<String>? = null,
    val date: List<String>? = null,
    val rating: List<String>? = null,
    val rank: List<String>? = null,
    val nsfw: Boolean? = null,
)
@Serializable
data class TurnstileTokenDto(val turnstileToken: String)
@Serializable
data class UpdateContentDto(val content: String)
@Serializable
data class UpdateEpisodeProgressDto(val type: Int? = null, val batch: Boolean? = null)
@Serializable
data class UpdateBlogDto(val title: String? = null, val content: String? = null, val tags: List<String>? = null, val `public`: Boolean? = null, val subjectIDs: List<Int>? = null)
@Serializable
data class UpdateIndexDto(val title: String? = null, val desc: String? = null, val `private`: Boolean? = null)
@Serializable
data class UpdateIndexRelatedDto(val order: Int, val comment: String)
@Serializable
data class UpdateSubjectCommentDto(val comment: String)
@Serializable
data class UpdateSubjectProgressDto(val epStatus: Int? = null, val volStatus: Int? = null)
@Serializable
data class UpdateTopicDto(val title: String, val content: String)
