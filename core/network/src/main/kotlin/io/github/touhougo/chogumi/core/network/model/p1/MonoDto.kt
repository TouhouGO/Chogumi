package io.github.touhougo.chogumi.core.network.model.p1

import kotlinx.serialization.Serializable

@Serializable
data class SlimCharacterDto(val id: Int, val name: String, val nameCN: String, val role: Int, val info: String, val images: PersonImagesDto? = null, val comment: Int, val lock: Boolean, val nsfw: Boolean)
@Serializable
data class SlimPersonDto(val id: Int, val name: String, val nameCN: String, val type: Int, val info: String, val career: List<String>, val images: PersonImagesDto? = null, val comment: Int, val lock: Boolean, val nsfw: Boolean)
@Serializable
data class CharacterDto(val id: Int, val name: String, val nameCN: String, val role: Int, val infobox: List<InfoboxItemDto>, val info: String, val summary: String, val images: PersonImagesDto? = null, val comment: Int, val collects: Int, val lock: Boolean, val redirect: Int, val nsfw: Boolean, val collectedAt: Long? = null)
@Serializable
data class PersonDto(val id: Int, val name: String, val nameCN: String, val type: Int, val infobox: List<InfoboxItemDto>, val info: String, val career: List<String>, val summary: String, val images: PersonImagesDto? = null, val comment: Int, val collects: Int, val lock: Boolean, val redirect: Int, val nsfw: Boolean, val collectedAt: Long? = null)
@Serializable
data class CharacterCastDto(val person: SlimPersonDto, val relation: Int, val summary: String)
@Serializable
data class CharacterRelationDto(val character: SlimCharacterDto, val relation: PersonRelationTypeDto, val spoiler: Boolean, val ended: Boolean, val comment: String)
@Serializable
data class CharacterSubjectDto(val subject: P1SlimSubjectDto, val casts: List<CharacterCastDto>, val type: Int)
@Serializable
data class CharacterSubjectRelationDto(val subject: P1SlimSubjectDto, val type: Int)
@Serializable
data class PersonCharacterDto(val character: SlimCharacterDto, val relations: List<CharacterSubjectRelationDto>)
@Serializable
data class PersonCollectDto(val user: P1UserDto, val createdAt: Long)
@Serializable
data class PersonRelationDto(val person: SlimPersonDto, val relation: PersonRelationTypeDto, val spoiler: Boolean, val ended: Boolean, val comment: String)
@Serializable
data class PersonRelationTypeDto(val id: Int, val cn: String, val desc: String, val viceVersaTo: Int? = null, val skipViceVersa: Boolean? = null, val primary: Boolean? = null)
@Serializable
data class PersonWorkDto(val subject: P1SlimSubjectDto, val positions: List<SubjectStaffPositionDto>)
@Serializable
data class MonoPhotoDto(val id: Int, val type: Int, val mainID: Int, val creatorID: Int, val user: P1UserDto? = null, val target: String, val images: MonoPhotoImagesDto, val title: String, val comment: String, val tags: List<String>, val spoiler: Boolean, val createdAt: Long, val updatedAt: Long, val lastPost: Long)
@Serializable
data class MonoPhotoImagesDto(val large: String, val common: String, val medium: String, val small: String, val grid: String)
