package io.github.touhougo.chogumi.core.network.model.p1

import kotlinx.serialization.Serializable

@Serializable
data class TimelineMemoDto(
    val daily: TimelineDailyDto? = null,
    val wiki: TimelineWikiDto? = null,
    val subject: List<TimelineSubjectDto>? = null,
    val progress: TimelineProgressDto? = null,
    val status: TimelineStatusDto? = null,
    val blog: SlimBlogEntryDto? = null,
    val index: SlimIndexDto? = null,
    val mono: TimelineMonoDto? = null,
)
@Serializable
data class TimelineDailyDto(
    val users: List<P1UserDto>? = null,
    val groups: List<SlimGroupDto>? = null,
)
@Serializable
data class TimelineWikiDto(val subject: P1SlimSubjectDto? = null)
@Serializable
data class TimelineSubjectDto(
    val subject: P1SlimSubjectDto,
    val comment: String,
    val rate: Double? = null,
    val collectID: Int? = null,
)
@Serializable
data class TimelineProgressDto(
    val batch: TimelineProgressBatchDto? = null,
    val single: TimelineProgressSingleDto? = null,
)
@Serializable
data class TimelineProgressBatchDto(
    val epsTotal: String,
    val volsTotal: String,
    val subject: P1SlimSubjectDto,
    val epsUpdate: Int? = null,
    val volsUpdate: Int? = null,
)
@Serializable
data class TimelineProgressSingleDto(val episode: EpisodeDto, val subject: P1SlimSubjectDto)
@Serializable
data class TimelineStatusDto(
    val sign: String? = null,
    val tsukkomi: String? = null,
    val nickname: TimelineNicknameDto? = null,
)
@Serializable
data class TimelineNicknameDto(val before: String, val after: String)
@Serializable
data class TimelineMonoDto(val characters: List<SlimCharacterDto>, val persons: List<SlimPersonDto>)
@Serializable
data class TimelineSourceDto(val name: String, val url: String? = null)
@Serializable
data class SimpleUserDto(val id: Int, val username: String, val nickname: String)
@Serializable
data class ReactionDto(val users: List<SimpleUserDto>, val value: Int)
@Serializable
data class CommentBaseDto(
    val id: Long,
    val mainID: Long,
    val creatorID: Int,
    val relatedID: Long,
    val createdAt: Long,
    val content: String,
    val state: Int,
    val relatedPhotoID: Int? = null,
    val user: P1UserDto? = null,
    val reactions: List<ReactionDto>? = null,
)
/** allOf(CommentBase, replies) 的完整结构，不丢父结构字段。 */
@Serializable
data class CommentDto(
    val id: Long,
    val mainID: Long,
    val creatorID: Int,
    val relatedID: Long,
    val createdAt: Long,
    val content: String,
    val state: Int,
    val replies: List<CommentBaseDto>,
    val relatedPhotoID: Int? = null,
    val user: P1UserDto? = null,
    val reactions: List<ReactionDto>? = null,
)
@Serializable
data class CreateTimelineSayRequestDto(val content: String, val turnstileToken: String)
/** 楼层回复的请求体：动态、讨论版、单集、日志、目录、角色人物的回复同一个形状（契约 CreateReply + TurnstileToken）。 */
@Serializable
data class CreateTimelineReplyRequestDto(
    val content: String,
    val turnstileToken: String,
    val replyTo: Long? = null,
)
@Serializable
data class TimelineLikeRequestDto(val value: Int)
@Serializable
data class CreatedIdDto(val id: Long)
@Serializable
class EmptyObjectDto
@Serializable
data class TimelineEventDto(val event: String, val timeline: TimelineDto? = null)
