package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.mapper.unescapeHtmlEntities
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.util.onWebIfServerError
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.network.model.p1.CommentDto
import io.github.touhougo.chogumi.core.network.model.p1.ReplyDto
import io.github.touhougo.chogumi.core.network.model.web.WebReplyParent

/** 一层楼里回帖容灾要看的几样：哪一层、谁写的、挂在哪层主楼下（新开的一层是 0）、正文（接口回的原样）。 */
internal data class ReplyFloor(val id: Int, val creatorId: Int, val parentId: Int, val content: String)

/** 话题的楼层（主楼带楼中楼）摊平成 [ReplyFloor]。 */
internal fun List<ReplyDto>.toReplyFloors(): List<ReplyFloor> = flatMap { main ->
    listOf(ReplyFloor(main.id, main.creatorID, 0, main.content)) +
        main.replies.map { ReplyFloor(it.id, it.creatorID, main.id, it.content) }
}

/** 评论区的楼层（主楼带楼中楼）摊平成 [ReplyFloor]。 */
internal fun List<CommentDto>.toCommentFloors(): List<ReplyFloor> = flatMap { main ->
    listOf(ReplyFloor(main.id.toInt(), main.creatorID, 0, main.content)) +
        main.replies.map { ReplyFloor(it.id.toInt(), it.creatorID, main.id.toInt(), it.content) }
}

/**
 * 发一条回复（[send]），带容灾：开放接口回 5xx 时（个别话题在服务端写入时恒 500，开发须知 §1.2），用网页会话把同一条
 * [content] 送进网页 [page] 的回复表单。用户在这之前已经过了人机验证，网页这条路只在接口失败之后走，不是绕开验证的入口。
 *
 * 认「是不是这一次发的」：发送之前先读一遍楼层 [floors] 作基线，之后我在这一层下多出来的、基线里没有的、正文就是 [content] 的楼
 * 才是这一次的。基线排除之前发过的同一句（不按时间窗认）；比正文排除同一账号在别处同时发的另一句（不然会把它当成这一次、丢掉草稿）。
 * 接口回的正文可能转义过 HTML 实体，原样与解开实体后的都算。
 *
 * - 没有网页会话（浏览器授权、令牌登录）不读基线、不试，原样给出接口的结果；基线没读到也一样。
 * - 接口回 5xx：再读一遍楼层，接口其实写进去了就回那一层，不再发第二次；否则照网页的回复表单提交，再读一遍，多出来的那条就是它。
 *   没多出来、网页那一步失败，按 [onWebIfServerError] 给出（要账号密码登录、换了号、断网原样，其余是「去网页端回复」）。
 * - 不是 5xx 的失败（验证没过、禁言、话题关闭）原样。
 *
 * 有网页会话的账号每次回复多读一遍楼层（发送之前），换来补发时不会重复发帖、不会把别的回复当成这一次。设计决定。
 * [replyTo] 与接口同义：0 = 新开一层，否则是所在主楼的 id。
 */
internal suspend fun replyWithWebFallback(
    webSessions: WebSessionRepository,
    session: AccountSession,
    page: String,
    content: String,
    replyTo: Int,
    floors: suspend () -> List<ReplyFloor>,
    send: suspend () -> Int,
): Outcome<Int> {
    val me = session.userId
    val baseline = if (me != null && webSessions.available(session)) outcomeOf { floors() }.valueOrNull() else null
    val sent = outcomeOf { send() }
    if (me == null || baseline == null) return sent
    return sent.onWebIfServerError(RejectReason.REPLY_UNAVAILABLE) {
        fun List<ReplyFloor>.newestMine(known: Set<Int>) =
            filter { it.creatorId == me && it.parentId == replyTo && it.id !in known && it.content.isSameText(content) }.maxByOrNull { it.id }?.id
        val known = baseline.mapTo(HashSet()) { it.id }
        val afterApi = floors()
        afterApi.newestMine(known)?.let { return@onWebIfServerError it }
        val parent = if (replyTo == 0) {
            null
        } else {
            val main = afterApi.firstOrNull { it.id == replyTo && it.parentId == 0 } ?: throw FailureException(Failure.BadResponse)
            WebReplyParent(main.id, main.creatorId)
        }
        webSessions.reply(session, page, content, parent)
        floors().newestMine(known + afterApi.map { it.id }) ?: throw FailureException(Failure.BadResponse)
    }
}

/** 接口回的正文 [this] 是不是提交的 [submitted]：不算首尾空白与换行写法（网页表单按 `\r\n` 提交），原样或解开 HTML 实体后相同都算。 */
private fun String.isSameText(submitted: String): Boolean {
    val wanted = submitted.normalized()
    return normalized() == wanted || unescapeHtmlEntities().normalized() == wanted
}

private fun String.normalized(): String = replace("\r\n", "\n").trim()
