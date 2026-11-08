package io.github.touhougo.chogumi.core.network.model.p1

import kotlinx.serialization.Serializable

@Serializable
data class SlimBlogEntryDto(val id: Int, val type: Int, val uid: Int, val user: P1UserDto? = null, val title: String, val icon: String, val summary: String, val replies: Int, val `public`: Boolean, val createdAt: Long, val updatedAt: Long)
@Serializable
data class BlogEntryDto(val id: Int, val type: Int, val uid: Int, val user: P1UserDto, val title: String, val icon: String, val content: String, val tags: List<String>, val views: Int, val replies: Int, val createdAt: Long, val updatedAt: Long, val noreply: Int, val related: Int, val `public`: Boolean)
@Serializable
data class BlogPhotoDto(val id: Int, val target: String, val icon: String, val vote: Int, val createdAt: Long)
@Serializable
data class SlimGroupDto(val id: Int, val name: String, val nsfw: Boolean, val title: String, val icon: AvatarDto, val creatorID: Int, val members: Int, val accessible: Boolean, val createdAt: Long)
@Serializable
data class GroupDto(val id: Int, val cat: Int, val name: String, val nsfw: Boolean, val title: String, val icon: AvatarDto, val creatorID: Int, val creator: P1UserDto? = null, val topics: Int, val posts: Int, val members: Int, val description: String, val accessible: Boolean, val createdAt: Long, val membership: GroupMemberDto? = null)
@Serializable
data class GroupMemberDto(val uid: Int, val user: P1UserDto? = null, val role: Int, val joinedAt: Long)
@Serializable
data class TopicDto(val id: Int, val title: String, val creatorID: Int, val creator: P1UserDto? = null, val parentID: Int, val replyCount: Int, val createdAt: Long, val updatedAt: Long, val state: Int, val display: Int)
@Serializable
data class GroupTopicDto(val id: Int, val title: String, val creatorID: Int, val creator: P1UserDto? = null, val parentID: Int, val replyCount: Int, val createdAt: Long, val updatedAt: Long, val state: Int, val display: Int, val group: SlimGroupDto, val replies: List<ReplyDto>)
@Serializable
data class SubjectTopicDto(val id: Int, val title: String, val creatorID: Int, val creator: P1UserDto? = null, val parentID: Int, val replyCount: Int, val createdAt: Long, val updatedAt: Long, val state: Int, val display: Int, val subject: P1SlimSubjectDto, val replies: List<ReplyDto>)
@Serializable
data class ReplyBaseDto(val id: Int, val creatorID: Int, val creator: P1UserDto? = null, val createdAt: Long, val content: String, val state: Int, val reactions: List<ReactionDto>? = null)
@Serializable
data class ReplyDto(val id: Int, val creatorID: Int, val creator: P1UserDto? = null, val createdAt: Long, val content: String, val state: Int, val reactions: List<ReactionDto>? = null, val replies: List<ReplyBaseDto>)
@Serializable
data class PostDto(val id: Int, val creatorID: Int, val creator: P1UserDto, val createdAt: Long, val content: String, val state: Int, val topic: TopicDto)
@Serializable
data class SlimIndexDto(val id: Int, val uid: Int, val user: P1UserDto? = null, val type: Int, val title: String, val `private`: Boolean, val total: Int, val stats: IndexStatsDto, val createdAt: Long, val updatedAt: Long)
@Serializable
data class IndexDto(val id: Int, val uid: Int, val user: P1UserDto? = null, val type: Int, val title: String, val desc: String, val `private`: Boolean, val total: Int, val replies: Int, val collects: Int, val stats: IndexStatsDto, val award: Int, val createdAt: Long, val updatedAt: Long, val collectedAt: Long? = null)
@Serializable
data class IndexRelatedDto(
    val id: Int, val cat: Int, val rid: Int, val type: Int, val sid: Int, val order: Int, val comment: String, val award: String, val createdAt: Long,
    val subject: P1SlimSubjectDto? = null,
    val character: SlimCharacterDto? = null,
    val person: SlimPersonDto? = null,
    val episode: EpisodeDto? = null,
    val blog: SlimBlogEntryDto? = null,
    val groupTopic: GroupTopicDto? = null,
    val subjectTopic: SubjectTopicDto? = null,
)
@Serializable
data class IndexStatsDto(
    val subject: IndexSubjectStatsDto,
    val character: Int? = null,
    val person: Int? = null,
    val episode: Int? = null,
    val blog: Int? = null,
    val groupTopic: Int? = null,
    val subjectTopic: Int? = null,
)
@Serializable
data class IndexSubjectStatsDto(
    val anime: Int? = null,
    val book: Int? = null,
    val music: Int? = null,
    val game: Int? = null,
    val real: Int? = null,
)
