package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.TopicDetailBo
import io.github.touhougo.chogumi.core.model.TopicParent
import io.github.touhougo.chogumi.core.network.model.p1.GroupTopicDto
import io.github.touhougo.chogumi.core.network.model.p1.ReplyDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectTopicDto

// 话题详情（作品讨论版、小组）→ 领域模型。p1 时间一律 Unix 秒。

fun SubjectTopicDto.toDetailBo(currentUserId: Int? = null) = topicDetail(
    id, title, TopicParent.Subject(subject.toTopicSubjectBo()), creatorID, replyCount, createdAt, replies, currentUserId,
)

fun GroupTopicDto.toDetailBo(currentUserId: Int? = null) = topicDetail(
    id, title, TopicParent.Group(group.toGroupSummaryBo()), creatorID, replyCount, createdAt, replies, currentUserId,
)

/** 两种话题共用的映射：楼层按服务端顺序（即楼层号顺序）映射成两层楼。 */
private fun topicDetail(
    id: Int,
    title: String,
    parent: TopicParent,
    creatorId: Int,
    replyCount: Int,
    createdAt: Long,
    replies: List<ReplyDto>,
    currentUserId: Int?,
) = TopicDetailBo(
    id = id,
    title = title.unescapeHtmlEntities(),
    parent = parent,
    creatorId = creatorId,
    replyCount = replyCount,
    createdAt = createdAt.epochSecondsToMillis(),
    threads = replies.map { it.toCommentThreadBo(topicId = id, currentUserId = currentUserId) },
)
