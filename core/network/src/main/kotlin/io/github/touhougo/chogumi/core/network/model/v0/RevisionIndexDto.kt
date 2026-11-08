package io.github.touhougo.chogumi.core.network.model.v0

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RevisionDto(val id: Int, val type: Int, val creator: CreatorDto? = null, val summary: String, @SerialName("created_at") val createdAt: String)
/** 此组件明确声明 data 内容不固定；只在这一真正自由的字典保存 JSON 值。 */
@Serializable
data class DetailedRevisionDto(val id: Int, val type: Int, val creator: CreatorDto? = null, val summary: String, @SerialName("created_at") val createdAt: String, val data: Map<String, JsonElement>? = null)
@Serializable
data class PersonRevisionDto(val id: Int, val type: Int, val creator: CreatorDto? = null, val summary: String, @SerialName("created_at") val createdAt: String, val data: Map<String, PersonRevisionDataItemDto>? = null)
@Serializable
data class PersonRevisionDataItemDto(@SerialName("prsn_infobox") val prsnInfobox: String, @SerialName("prsn_summary") val prsnSummary: String, val profession: PersonRevisionProfessionDto, val extra: RevisionExtraDto, @SerialName("prsn_name") val prsnName: String)
@Serializable
data class PersonRevisionProfessionDto(
    val producer: String? = null,
    val mangaka: String? = null,
    val artist: String? = null,
    val seiyu: String? = null,
    val writer: String? = null,
    val illustrator: String? = null,
    val actor: String? = null,
)
@Serializable
data class RevisionExtraDto(val img: String? = null)
@Serializable
data class SubjectRevisionDto(val id: Int, val type: Int, val creator: CreatorDto? = null, val summary: String, @SerialName("created_at") val createdAt: String, val data: SubjectRevisionDataDto? = null)
@Serializable
data class SubjectRevisionDataDto(@SerialName("field_eps") val fieldEps: Int, @SerialName("field_infobox") val fieldInfobox: String, @SerialName("field_summary") val fieldSummary: String, val name: String, @SerialName("name_cn") val nameCn: String, val platform: Int, @SerialName("subject_id") val subjectId: Int, val type: Int, @SerialName("type_id") val typeId: Int, @SerialName("vote_field") val voteField: String)
@Serializable
data class CharacterRevisionDto(val id: Int, val type: Int, val creator: CreatorDto? = null, val summary: String, @SerialName("created_at") val createdAt: String, val data: Map<String, CharacterRevisionDataItemDto>? = null)
@Serializable
data class CharacterRevisionDataItemDto(val infobox: String, val summary: String, val name: String, val extra: RevisionExtraDto)
@Serializable
data class IndexDto(val id: Int, val title: String, val desc: String, val total: Int? = null, val stat: StatDto, @SerialName("created_at") val createdAt: String, @SerialName("updated_at") val updatedAt: String, val creator: CreatorDto, val ban: Boolean, val nsfw: Boolean)
@Serializable
data class IndexSubjectDto(val id: Int, val type: Int, val name: String, val images: ImagesDto? = null, val infobox: List<InfoboxItemDto>? = null, val date: String? = null, val comment: String, @SerialName("added_at") val addedAt: String)
@Serializable
data class IndexBasicInfoDto(val title: String? = null, val description: String? = null)
@Serializable
data class IndexSubjectAddInfoDto(@SerialName("subject_id") val subjectId: Int? = null, val sort: Int? = null, val comment: String? = null)
@Serializable
data class IndexSubjectEditInfoDto(val sort: Int? = null, val comment: String? = null)
