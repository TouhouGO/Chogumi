package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.CommentCollectionBo
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.userAvatarUrl
import io.github.touhougo.chogumi.core.network.model.p1.CommentBaseDto
import io.github.touhougo.chogumi.core.network.model.p1.CommentDto
import io.github.touhougo.chogumi.core.network.model.p1.P1UserDto
import io.github.touhougo.chogumi.core.network.model.p1.ReactionDto
import io.github.touhougo.chogumi.core.network.model.p1.ReplyBaseDto
import io.github.touhougo.chogumi.core.network.model.p1.ReplyDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectInterestCommentDto

// 楼层（角色/人物吐槽、话题楼层、条目吐槽）→ 领域楼层模型。
// 各个 DTO 的字段名不同，但「一层楼长什么样」只在 [commentItem] 里定义一次。

/**
 * 后端 9 种状态映射（对齐 `bangumi-server-private/lib/comment.ts`）：
 * - NORMAL: 0(正常), 3(置顶), 4(合并) 以及未知数值兜底
 * - DELETED: 1(管理员关闭), 2(重开), 5(下沉), 6(用户删除), 7(管理员删除)
 * - FOLDED: 8(折叠)
 */
fun Int.toCommentState(): CommentState = when (this) {
    0, 3, 4 -> CommentState.NORMAL
    1, 2, 5, 6, 7 -> CommentState.DELETED
    8 -> CommentState.FOLDED
    else -> CommentState.NORMAL
}

/**
 * 评论正文映射与敏感数据拦截：
 * 当状态为 DELETED 时，按服务端契约置为空字符串 ""，严禁泄露被删原始内容。
 * UI 层统一引用 R.string.comment_deleted 作为唯一真源。
 */
private fun mapCommentContent(rawContent: String, state: CommentState, rawState: Int): String {
    if (state == CommentState.DELETED) {
        return ""
    }
    val decoded = rawContent.unescapeHtmlEntities()
    if (decoded.isBlank() && rawState !in setOf(0, 3, 4, 8)) {
        return ""
    }
    return decoded
}

/**
 * 贴贴反应映射（楼层与动态共用）：数量按去重后的用户数。
 */
fun ReactionDto.toReactionBo(currentUserId: Int? = null) = CommentReactionBo(
    value = value,
    count = users.distinctBy { it.id }.size,
    selected = currentUserId != null && users.any { it.id == currentUserId },
)

/** 一层楼的唯一组装处：发帖人、正文拦截、时间单位、贴贴、是不是我发的都在这里定（藏不藏按页面当前的绝交名单在显示时定，见 `visibleThread`）。 */
private fun commentItem(
    id: Int,
    mainId: Int,
    relatedId: Int,
    creatorId: Int,
    user: P1UserDto?,
    content: String,
    createdAt: Long,
    rawState: Int,
    reactions: List<ReactionDto>?,
    currentUserId: Int?,
    collection: CommentCollectionBo? = null,
): CommentItemBo {
    val commentState = rawState.toCommentState()
    return CommentItemBo(
        id = id,
        mainId = mainId,
        relatedId = relatedId,
        creatorId = creatorId,
        creatorNickname = user?.nickname?.unescapeHtmlEntities().orEmpty(),
        creatorUsername = user?.username.orEmpty(),
        creatorAvatar = user?.avatar?.let { av -> userAvatarUrl(av.large, av.medium, av.small) },
        content = mapCommentContent(content, commentState, rawState),
        createdAt = createdAt.epochSecondsToMillis(),
        state = commentState,
        reactions = reactions.orEmpty().map { it.toReactionBo(currentUserId) },
        creatorSign = user?.sign?.unescapeHtmlEntities().orEmpty(),
        collection = collection,
        isOwn = currentUserId != null && creatorId == currentUserId,
    )
}

/**
 * 主楼评论映射（角色/人物吐槽）。
 */
fun CommentDto.toCommentItemBo(currentUserId: Int? = null): CommentItemBo = commentItem(
    id = id.toInt(), mainId = mainID.toInt(), relatedId = relatedID.toInt(), creatorId = creatorID,
    user = user, content = content, createdAt = createdAt, rawState = state, reactions = reactions,
    currentUserId = currentUserId,
)

/**
 * 楼中楼子回复映射（角色/人物吐槽）。
 */
fun CommentBaseDto.toCommentItemBo(currentUserId: Int? = null): CommentItemBo = commentItem(
    id = id.toInt(), mainId = mainID.toInt(), relatedId = relatedID.toInt(), creatorId = creatorID,
    user = user, content = content, createdAt = createdAt, rawState = state, reactions = reactions,
    currentUserId = currentUserId,
)

/**
 * 两层评论树映射。
 */
fun CommentDto.toCommentThreadBo(currentUserId: Int? = null): CommentThreadBo =
    CommentThreadBo(
        anchor = toCommentItemBo(currentUserId),
        replies = replies.map { it.toCommentItemBo(currentUserId) },
    )

fun List<CommentDto>.toCommentThreadBoList(currentUserId: Int? = null): List<CommentThreadBo> =
    map { it.toCommentThreadBo(currentUserId) }

/**
 * 话题楼层（两层）。p1 的楼层没有 mainID/relatedID 字段：主楼与楼中楼的 [CommentItemBo.mainId] 都填话题 id，
 * 楼中楼的 [CommentItemBo.relatedId] 填它所在主楼的 id（主楼自己填 0）。
 */
fun ReplyDto.toCommentThreadBo(
    topicId: Int,
    currentUserId: Int? = null,
): CommentThreadBo = CommentThreadBo(
    anchor = commentItem(
        id = id, mainId = topicId, relatedId = 0, creatorId = creatorID, user = creator, content = content,
        createdAt = createdAt, rawState = state, reactions = reactions, currentUserId = currentUserId,
    ),
    replies = replies.map { it.toCommentItemBo(topicId = topicId, parentId = id, currentUserId = currentUserId) },
)

private fun ReplyBaseDto.toCommentItemBo(topicId: Int, parentId: Int, currentUserId: Int?): CommentItemBo =
    commentItem(
        id = id, mainId = topicId, relatedId = parentId, creatorId = creatorID, user = creator, content = content,
        createdAt = createdAt, rawState = state, reactions = reactions, currentUserId = currentUserId,
    )

/**
 * 条目吐槽（短评）→ 一层没有楼中楼的楼。正文是纯文本收藏短评（非 BBCode），状态恒为正常；
 * 收藏状态与评分放进 [CommentItemBo.collection]，未识别的状态值回落 [CollectionType.UNKNOWN]。
 */
fun SubjectInterestCommentDto.toCommentThreadBo(
    subjectId: Int,
    currentUserId: Int? = null,
): CommentThreadBo =
    CommentThreadBo(
        anchor = commentItem(
            id = id, mainId = subjectId, relatedId = 0, creatorId = user.id, user = user, content = comment,
            createdAt = updatedAt, rawState = 0, reactions = reactions, currentUserId = currentUserId,
            collection = CommentCollectionBo(type = CollectionType.from(type) ?: CollectionType.UNKNOWN, rate = rate),
        ),
    )
