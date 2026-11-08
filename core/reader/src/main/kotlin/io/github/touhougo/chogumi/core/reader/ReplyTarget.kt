package io.github.touhougo.chogumi.core.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.common.bbcode.QuoteHeader
import io.github.touhougo.chogumi.core.common.bbcode.mentionOf
import io.github.touhougo.chogumi.core.common.bbcode.quoteExcerptOf
import io.github.touhougo.chogumi.core.common.bbcode.quoteOf
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.TopicReplyDraft
import io.github.touhougo.chogumi.core.model.TopicType
import io.github.touhougo.chogumi.core.model.userDisplayNameOf
import io.github.touhougo.chogumi.core.model.userKeyOf
import io.github.touhougo.chogumi.core.res.R

/**
 * 回复的对象：话题本身（[Topic]），或其中一层楼（[replyTargetOf]）。
 *
 * 话题的楼层分两层：服务端把 `replyTo` 原样存成被回复的主楼，楼中楼只挂一层：回复楼中楼时 [replyTo] 仍是它所在的主楼，
 * 被回复的那条以引用写进正文开头（[quote]，与网页一致），楼层里画成能点回去的引用条。
 * 动态的回复是平铺的：回复哪一条都新开一条（[replyTo] 为 0），开头 @ 被回复的人（[mentionOf]，与网页一致）。
 *
 * @property postId 点的是哪一层楼（草稿按它认：同一层楼只留一份草稿）；回复话题本身为 0。
 * @property replyTo 挂在哪层主楼下；0 = 回复话题本身，新开一层主楼。
 * @property floor 被回复楼的楼层号（「#6」「#6-2」）；回复话题、或楼层不标号时为 null。
 * @property preview 被回复的人与那层楼的摘要，编辑器顶上画成引用条；回复话题时为 null。
 * @property quote 写在正文开头的那段：回复楼中楼时的引用块，或平铺评论区里的 @；回复主楼、话题本身时没有。
 */
data class ReplyTarget(
    val postId: Int,
    val replyTo: Int,
    val floor: String? = null,
    val preview: QuoteHeader? = null,
    val quote: String? = null,
) {
    /** 提交的正文：引用块（有的话）+ 用户写的。 */
    fun contentOf(text: String): String = quote.orEmpty() + text

    companion object {
        val Topic = ReplyTarget(postId = 0, replyTo = 0)
    }
}

/**
 * 点了一层楼要回复谁（全仓唯一的构造处）：帖子正文（话题 #1 楼）就是回复话题本身。
 * 分两层的评论区（[threaded]，`ReplyStyle.THREADED`）：主楼挂在它自己下面，楼中楼挂在所在的主楼 [anchorId] 下并引用它；
 * 平铺的评论区：新开一条，开头 @ 这层楼的人。
 */
internal fun replyTargetOf(comment: CommentItemBo, floorNumber: String, anchorId: Int, asPost: Boolean, threaded: Boolean): ReplyTarget {
    if (asPost) return ReplyTarget.Topic
    val nickname = userDisplayNameOf(comment.creatorUsername, comment.creatorNickname, comment.creatorId)
    val isReply = comment.id != anchorId
    return ReplyTarget(
        postId = comment.id,
        replyTo = if (threaded) anchorId else 0,
        floor = floorNumber.ifEmpty { null },
        preview = QuoteHeader(author = nickname, excerpt = quoteExcerptOf(comment.content)),
        quote = when {
            !threaded -> mentionOf(userKeyOf(comment.creatorUsername, comment.creatorId), nickname)
            isReply -> quoteOf(nickname, comment.content)
            else -> null
        },
    )
}

/** 这个回复对象写了 [text] 的草稿：写给谁的几项原样存进去（[TopicReplyDraft]），改动时刻由仓库记。 */
fun ReplyTarget.draftOf(type: TopicType, topicId: Int, topicTitle: String, text: String) = TopicReplyDraft(
    topicType = type,
    topicId = topicId,
    topicTitle = topicTitle,
    postId = postId,
    replyTo = replyTo,
    floor = floor,
    quoteAuthor = preview?.author,
    quoteExcerpt = preview?.excerpt,
    quote = quote,
    text = text,
    updatedAt = 0L,
)

/** 从草稿还原回复对象（[draftOf] 的反向）。 */
fun TopicReplyDraft.replyTarget() = ReplyTarget(
    postId = postId,
    replyTo = replyTo,
    floor = floor,
    preview = quoteAuthor?.let { QuoteHeader(author = it, excerpt = quoteExcerpt.orEmpty()) },
    quote = quote,
)

/**
 * 编辑器面板的标题：回复某一层楼是「回复 #6 昵称」；新的一条在话题、动态里是「回复楼主」，
 * 在对象的评论区（[CommentSurface.Box]：日志、目录、角色、人物、单集）是「发表评论」。
 */
@Composable
fun ReplyTarget.title(surface: CommentSurface): String {
    val who = preview?.author
        ?: return stringResource(if (surface is CommentSurface.Box) R.string.composer_comment else R.string.composer_reply_topic)
    return stringResource(R.string.composer_reply_floor, listOfNotNull(floor, who).joinToString(" "))
}
