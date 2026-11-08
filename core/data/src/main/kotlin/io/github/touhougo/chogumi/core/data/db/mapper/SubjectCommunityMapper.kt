package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SlimBlogBo
import io.github.touhougo.chogumi.core.model.SubjectCollectorBo
import io.github.touhougo.chogumi.core.model.SubjectTopicBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TopicSubjectBo
import io.github.touhougo.chogumi.core.network.model.p1.P1SlimSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectCollectDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectReviewDto
import io.github.touhougo.chogumi.core.network.model.p1.TopicDto

// 条目社区（讨论版、评论、谁在看）→ 领域模型。p1 时间一律 Unix 秒。

fun TopicDto.toBo() = SubjectTopicBo(
    id = id,
    title = title.unescapeHtmlEntities(),
    creator = creator?.toUserBo(),
    replyCount = replyCount,
    createdAt = createdAt.epochSecondsToMillis(),
    updatedAt = updatedAt.epochSecondsToMillis(),
)

/** 话题 / 日志眉题里的作品。 */
fun P1SlimSubjectDto.toTopicSubjectBo() = TopicSubjectBo(
    id = id,
    type = SubjectType.from(type) ?: SubjectType.UNKNOWN,
    name = name,
    nameCn = nameCN,
    cover = images?.toRelatedImagesBo()?.posterUrl(),
)

/** 条目评论 = 用户日志：沿用日志列表项，作者取评论外层的 user（日志条目自带的 user 可能缺省）。 */
fun SubjectReviewDto.toBo(): SlimBlogBo = entry.toBo().copy(author = user.toUserBo())

/** 收藏者：未识别的状态值回落 [CollectionType.UNKNOWN]，不猜成某一档。 */
fun SubjectCollectDto.toBo() = SubjectCollectorBo(
    user = user.toUserBo(),
    type = CollectionType.from(interest.type) ?: CollectionType.UNKNOWN,
    rate = interest.rate,
    updatedAt = interest.updatedAt.epochSecondsToMillis(),
)
