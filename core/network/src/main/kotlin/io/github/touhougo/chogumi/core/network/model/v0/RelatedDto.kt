package io.github.touhougo.chogumi.core.network.model.v0

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RelatedSubjectDto(val id: Int, val type: Int, val name: String, @Required @SerialName("name_cn") val nameCn: String = "", val images: ImagesDto? = null, @Required val relation: String = "")
@Serializable
data class RelatedCharacterDto(val id: Int, val name: String, @Required val summary: String = "", @Required val type: Int = 1, val images: PersonImagesDto? = null, @Required val relation: String = "", val actors: List<PersonDto>? = null)
@Serializable
data class RelatedPersonDto(val id: Int, val name: String, val type: Int, val career: List<String>, val images: PersonImagesDto? = null, val relation: String, val eps: String)
@Serializable
data class MonoRelatedSubjectDto(val id: Int, val type: Int, val staff: String, val eps: String, val name: String, @SerialName("name_cn") val nameCn: String, val image: String? = null)


