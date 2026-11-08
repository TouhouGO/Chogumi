package io.github.touhougo.chogumi.core.network.model.p1

import kotlinx.serialization.Serializable

@Serializable
data class EpisodeWikiInfoDto(val id: Int, val subjectID: Int, val name: String, val nameCN: String, val type: Int, val ep: Double, val disc: Double? = null, val duration: String, val date: String? = null, val summary: String)
@Serializable
data class WikiImageExtraDto(val img: String? = null)
@Serializable
data class CharacterRevisionWikiInfoDto(val name: String, val infobox: String, val summary: String, val extra: WikiImageExtraDto)
@Serializable
data class CharacterWikiInfoDto(val id: Int, val name: String, val infobox: String, val summary: String, val locked: Boolean, val redirect: Int)
@Serializable
data class PersonRevisionWikiInfoDto(val name: String, val infobox: String, val summary: String, val profession: ProfessionDto, val extra: WikiImageExtraDto)
@Serializable
data class PersonWikiInfoDto(val id: Int, val name: String, val typeID: Int, val infobox: String, val summary: String, val locked: Boolean, val redirect: Int, val profession: ProfessionDto)
@Serializable
data class RevisionCreatorDto(val username: String, val nickname: String)
@Serializable
data class RevisionHistoryDto(val id: Int, val creator: RevisionCreatorDto, val type: Int, val commitMessage: String, val createdAt: Long)
@Serializable
data class SubjectEditDto(val name: String, val infobox: String, val platform: Int, val series: Boolean? = null, val nsfw: Boolean, val date: String? = null, val metaTags: List<String>, val summary: String)
@Serializable
data class WikiPlatformDto(val id: Int, val text: String, val wiki_tpl: String? = null)
@Serializable
data class SubjectWikiInfoDto(val id: Int, val name: String, val typeID: Int, val infobox: String, val locked: Boolean, val redirect: Int, val platform: Int, val availablePlatform: List<WikiPlatformDto>, val metaTags: List<String>, val summary: String, val series: Boolean? = null, val nsfw: Boolean)
@Serializable
data class RevisionSubjectDto(val id: Int, val typeID: Int, val name: String, val nameCN: String)
@Serializable
data class RevisionMonoDto(val id: Int, val name: String, val nameCN: String)
@Serializable
data class SubjectRelationRevisionWikiInfoDto(val subject: RevisionSubjectDto, val type: Int, val order: Int)
@Serializable
data class SubjectCharacterRevisionWikiInfoDto(val character: RevisionMonoDto, val type: Int, val order: Int)
@Serializable
data class SubjectPersonRevisionWikiInfoDto(val person: RevisionMonoDto, val position: Int)
@Serializable
data class SubjectNewDto(val name: String, val type: Int, val platform: Int, val infobox: String, val series: Boolean? = null, val nsfw: Boolean, val metaTags: List<String>, val summary: String, val date: String? = null)
@Serializable
data class SubjectRevisionWikiInfoDto(val id: Int, val name: String, val infobox: String, val metaTags: List<String>, val summary: String)
/** 上游组件拼写 Epsisodes 原样绑定，不更改合同。 */
@Serializable
data class EpsisodesNewDto(val episodes: List<NewEpisodeDto>)
@Serializable
data class NewEpisodeDto(
    val name: String? = null,
    val nameCN: String? = null,
    val type: Int? = null,
    val disc: Double? = null,
    val duration: String? = null,
    val date: String? = null,
    val summary: String? = null,
    val ep: Double,
)
@Serializable
data class EpsisodesEditDto(val commitMessage: String, val episodes: List<EditEpisodeDto>, val expectedRevision: List<ExpectedEpisodeRevisionDto>? = null)
@Serializable
data class EditEpisodeDto(
    val name: String? = null,
    val nameCN: String? = null,
    val type: Int? = null,
    val ep: Double? = null,
    val disc: Double? = null,
    val duration: String? = null,
    val date: String? = null,
    val summary: String? = null,
    val id: Int,
)
@Serializable
data class ExpectedEpisodeRevisionDto(
    val name: String? = null,
    val nameCN: String? = null,
    val duration: String? = null,
    val date: String? = null,
    val summary: String? = null,
)
@Serializable
data class UserSubjectContributionDto(val id: Int, val type: Int, val subjectID: Int, val name: String, val commitMessage: String, val createdAt: Long)
@Serializable
data class UserCharacterContributionDto(val id: Int, val type: Int, val characterID: Int, val name: String, val commitMessage: String, val createdAt: Long)
@Serializable
data class UserPersonContributionDto(val id: Int, val type: Int, val personID: Int, val name: String, val commitMessage: String, val createdAt: Long)
@Serializable
data class CharacterCastRevisionWikiInfoDto(val subject: RevisionSubjectDto, val person: RevisionMonoDto)
@Serializable
data class PersonSubjectRevisionWikiInfoDto(val subject: RevisionSubjectDto, val position: Int)
@Serializable
data class PersonCastRevisionWikiInfoDto(val subject: RevisionSubjectDto, val character: RevisionMonoDto)
