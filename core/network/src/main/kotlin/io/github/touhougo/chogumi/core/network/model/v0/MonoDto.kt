package io.github.touhougo.chogumi.core.network.model.v0

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PersonImagesDto(val large: String, val medium: String, val small: String, val grid: String)
@Serializable
data class StatDto(val comments: Int, val collects: Int)
@Serializable
data class CharacterDto(
    val id: Int, val name: String, val type: Int,
    val images: PersonImagesDto? = null,
    val summary: String, val locked: Boolean,
    val infobox: List<InfoboxItemDto>? = null,
    val gender: String? = null,
    @SerialName("blood_type") val bloodType: Int? = null,
    @SerialName("birth_year") val birthYear: Int? = null,
    @SerialName("birth_mon") val birthMon: Int? = null,
    @SerialName("birth_day") val birthDay: Int? = null,
    val stat: StatDto,
)
@Serializable
data class PersonDto(val id: Int, val name: String, val type: Int, val career: List<String>, val images: PersonImagesDto? = null, @SerialName("short_summary") val shortSummary: String, val locked: Boolean)
@Serializable
data class PersonDetailDto(
    val id: Int, val name: String, val type: Int, val career: List<String>,
    val images: PersonImagesDto? = null,
    val summary: String, val locked: Boolean, @SerialName("last_modified") val lastModified: String,
    val infobox: List<InfoboxItemDto>? = null,
    val gender: String? = null,
    @SerialName("blood_type") val bloodType: Int? = null,
    @SerialName("birth_year") val birthYear: Int? = null,
    @SerialName("birth_mon") val birthMon: Int? = null,
    @SerialName("birth_day") val birthDay: Int? = null,
    val stat: StatDto,
)
@Serializable
data class CharacterPersonDto(val id: Int, val name: String, val type: Int, val images: PersonImagesDto? = null, @SerialName("subject_id") val subjectId: Int, @SerialName("subject_type") val subjectType: Int, @SerialName("subject_name") val subjectName: String, @SerialName("subject_name_cn") val subjectNameCn: String, val staff: String? = null)
@Serializable
data class PersonCharacterDto(val id: Int, val name: String, val type: Int, val images: PersonImagesDto? = null, @SerialName("subject_id") val subjectId: Int, @SerialName("subject_type") val subjectType: Int, @SerialName("subject_name") val subjectName: String, @SerialName("subject_name_cn") val subjectNameCn: String, val staff: String? = null)
@Serializable
data class UserCharacterCollectionDto(val id: Int, val name: String, val type: Int, val images: PersonImagesDto? = null, @SerialName("created_at") val createdAt: String)
@Serializable
data class UserPersonCollectionDto(val id: Int, val name: String, val type: Int, val career: List<String>, val images: PersonImagesDto? = null, @SerialName("created_at") val createdAt: String)
