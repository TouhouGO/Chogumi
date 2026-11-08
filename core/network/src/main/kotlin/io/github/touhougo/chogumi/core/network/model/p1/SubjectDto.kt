package io.github.touhougo.chogumi.core.network.model.p1

import kotlinx.serialization.Serializable

/** p1 的固定图片字段；所有字段均由该 schema 要求，外层 images 可省略。 */
@Serializable
data class SubjectImagesDto(val large: String, val common: String, val medium: String, val small: String, val grid: String)
@Serializable
data class PersonImagesDto(val large: String, val medium: String, val small: String, val grid: String)
@Serializable
data class InfoboxValueDto(val k: String? = null, val v: String)
@Serializable
data class InfoboxItemDto(val key: String, val values: List<InfoboxValueDto>)
@Serializable
data class SlimSubjectInterestDto(val id: Int, val rate: Int, val type: Int, val comment: String, val tags: List<String>, val updatedAt: Long)
@Serializable
data class SubjectInterestDto(val id: Int, val rate: Int, val type: Int, val comment: String, val tags: List<String>, val epStatus: Int, val volStatus: Int, val `private`: Boolean, val updatedAt: Long)
@Serializable
data class SubjectDto(
    val airtime: SubjectAirtimeDto, val collection: Map<String, Int>, val eps: Int, val id: Int,
    val images: SubjectImagesDto? = null,
    val infobox: List<InfoboxItemDto>, val info: String, val metaTags: List<String>, val locked: Boolean,
    val name: String, val nameCN: String, val nsfw: Boolean, val platform: SubjectPlatformDto,
    val rating: P1RatingDto, val redirect: Int, val series: Boolean, val seriesEntry: Int,
    val summary: String, val type: Int, val volumes: Int, val tags: List<SubjectTagDto>,
    val interest: SubjectInterestDto? = null,
)
@Serializable
data class SubjectAirtimeDto(val date: String, val month: Int, val weekday: Int, val year: Int)
@Serializable
data class SubjectPlatformDto(
    val id: Int, val type: String, val typeCN: String, val alias: String,
    val order: Int? = null,
    val enableHeader: Boolean? = null,
    val wikiTpl: String? = null,
    val searchString: String? = null,
    val sortKeys: List<String>? = null,
)
@Serializable
data class SubjectTagDto(val name: String, val count: Int)
@Serializable
data class EpisodeDto(
    val id: Int, val subjectID: Int, val sort: Double, val type: Int, val disc: Int,
    val name: String, val nameCN: String, val duration: String, val airdate: String, val comment: Int, val desc: String,
    val subject: P1SlimSubjectDto? = null,
    val collection: EpisodeCollectionDto? = null,
)
@Serializable
data class EpisodeCollectionDto(val status: Int, val updatedAt: Long? = null)
@Serializable
data class SubjectCharacterDto(val character: SlimCharacterDto, val casts: List<CharacterCastDto>, val type: Int, val order: Int)
@Serializable
data class SubjectCollectDto(val user: P1UserDto, val interest: SlimSubjectInterestDto)
@Serializable
data class SubjectInterestCommentDto(val id: Int, val user: P1UserDto, val type: Int, val rate: Int, val comment: String, val updatedAt: Long, val reactions: List<ReactionDto>? = null)
@Serializable
data class SubjectPositionDto(val position: SubjectStaffPositionTypeDto, val staffs: List<SubjectPositionStaffDto>)
@Serializable
data class SubjectPositionStaffDto(val person: SlimPersonDto, val summary: String, val appearEps: String)
@Serializable
data class SubjectRecDto(val subject: P1SlimSubjectDto, val sim: Double, val count: Int)
@Serializable
data class SubjectRelationDto(val subject: P1SlimSubjectDto, val relation: SubjectRelationTypeDto, val order: Int)
@Serializable
data class SubjectRelationTypeDto(val id: Int, val en: String, val cn: String, val jp: String, val desc: String)
@Serializable
data class SubjectReviewDto(val id: Int, val user: P1UserDto, val entry: SlimBlogEntryDto)
@Serializable
data class SubjectStaffDto(val staff: SlimPersonDto, val positions: List<SubjectStaffPositionDto>)
@Serializable
data class SubjectStaffPositionDto(val type: SubjectStaffPositionTypeDto, val summary: String, val appearEps: String)
@Serializable
data class SubjectStaffPositionTypeDto(val id: Int, val en: String, val cn: String, val jp: String)
@Serializable
data class ProgressSubjectDto(
    val id: Int, val name: String, val nameCN: String, val type: Int,
    val images: SubjectImagesDto? = null,
    val info: String, val metaTags: List<String>, val rating: P1RatingDto, val locked: Boolean, val nsfw: Boolean,
    val interest: SlimSubjectInterestDto? = null,
    val eps: Int, val volumes: Int, val series: Boolean, val doing: Int, val airDate: String, val weekday: Int,
)
@Serializable
data class ProgressItemDto(val subject: ProgressSubjectDto, val interest: SubjectInterestDto, val percent: Double, val todayOnAir: Boolean, val lastUnwatchedEp: LastUnwatchedEpisodeDto?, val eps: List<EpisodeDto>)
@Serializable
data class LastUnwatchedEpisodeDto(val id: Int, val sort: Double)
@Serializable
data class HomeResponseDto(val progress: List<ProgressItemDto>, val timeline: List<TimelineDto>, val groupTopics: List<GroupTopicDto>, val famousGroups: List<SlimGroupDto>, val hotSubjectTopics: List<SubjectTopicDto>, val calendar: Map<String, List<CalendarItemDto>>)
@Serializable
data class SubjectHomeResponseDto(val subject: SubjectDto, val episodes: List<EpisodeDto>, val characters: List<SubjectCharacterDto>, val staff: List<SubjectStaffDto>, val relations: List<SubjectRelationDto>, val recs: List<SubjectRecDto>, val comments: List<SubjectInterestCommentDto>, val reviews: List<SubjectReviewDto>, val indexes: List<SlimIndexDto>, val topics: List<TopicDto>)
@Serializable
data class ChannelSubjectTopicDto(val id: Int, val title: String, val replyCount: Int, val updatedAt: Long, val creator: P1UserDto? = null, val subject: P1SlimSubjectDto)
@Serializable
data class FriendSubjectCollectionActivityDto(val user: P1UserDto, val subject: P1SlimSubjectDto, val collectionType: Int, val updatedAt: Long)
