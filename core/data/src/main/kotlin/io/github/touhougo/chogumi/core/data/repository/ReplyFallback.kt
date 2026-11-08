package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.util.isServerError
import io.github.touhougo.chogumi.core.data.util.onWebIfServerError
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.network.model.p1.CommentDto
import io.github.touhougo.chogumi.core.network.model.p1.ReplyDto
import io.github.touhougo.chogumi.core.network.model.web.WebReplyParent

/** 一层楼里回帖容灾要看的几样：谁写的、挂在哪层主楼下（新开的一层是 0）、写了什么、什么时候（秒）。 */
internal data class ReplyFloor(val id: Int, val creatorId: Int, val parentId: Int, val content: String, val createdAt: Long)

/** 话题的楼层（主楼带楼中楼）摊平成 [ReplyFloor]。 */
internal fun List<ReplyDto>.toReplyFloors(): List<ReplyFloor> = flatMap { main ->
    listOf(ReplyFloor(main.id, main.creatorID, 0, main.content, main.createdAt)) +
        main.replies.map { ReplyFloor(it.id, it.creatorID, main.id, it.content, it.createdAt) }
}

/** 评论区的楼层（主楼带楼中楼）摊平成 [ReplyFloor]。 */
internal fun List<CommentDto>.toCommentFloors(): List<ReplyFloor> = flatMap { main ->
    listOf(ReplyFloor(main.id.toInt(), main.creatorID, 0, main.content, main.createdAt)) +
        main.replies.map { ReplyFloor(it.id.toInt(), it.creatorID, main.id.toInt(), it.content, it.createdAt) }
}

/**
 * 回帖的容灾：开放接口回 5xx 时（个别话题在服务端写入时恒 500，开发须知 §1.2），用网页会话把同一条送进网页 [page] 的回复表单。
 * 用户在这之前已经过了人机验证，网页这条路只在接口失败之后走，不是绕开验证的入口。
 *
 * - 没有网页会话（浏览器授权、令牌登录）不试，原样给出接口的失败；不是 5xx 的失败（验证没过、禁言、话题关闭）也原样。
 * - 先读一遍楼层 [floors]：接口其实写进去了（发送之后 [sentAtSeconds] 起，我在同一层下的同一段正文）就回那一层，不再发第二次。
 * - 网页提交之后再读一遍：我在这一层下多出来的那条就是它，回它的 id。没多出来、网页那一步失败，按 [onWebIfServerError] 给出
 *   （要账号密码登录、换了号、断网原样，其余是「去网页端回复」）。
 *
 * [replyTo] 与接口同义：0 = 新开一层，否则是所在主楼的 id。
 */
internal suspend fun Outcome<Int>.replyOnWebIfServerError(
    webSessions: WebSessionRepository,
    session: AccountSession,
    page: String,
    content: String,
    replyTo: Int,
    sentAtSeconds: Long,
    floors: suspend () -> List<ReplyFloor>,
): Outcome<Int> {
    val failure = (this as? Outcome.Failed)?.failure ?: return this
    val me = session.userId
    if (!failure.isServerError() || me == null || !webSessions.available(session)) return this
    return onWebIfServerError(RejectReason.REPLY_UNAVAILABLE) {
        val before = floors()
        val written = before.filter {
            it.creatorId == me && it.parentId == replyTo && it.createdAt >= sentAtSeconds - CLOCK_SKEW_SECONDS &&
                it.content.normalized() == content.normalized()
        }
        written.maxByOrNull { it.id }?.let { return@onWebIfServerError it.id }
        val parent = if (replyTo == 0) {
            null
        } else {
            val main = before.firstOrNull { it.id == replyTo && it.parentId == 0 } ?: throw FailureException(Failure.BadResponse)
            WebReplyParent(main.id, main.creatorId)
        }
        webSessions.reply(session, page, content, parent)
        val known = before.mapTo(HashSet()) { it.id }
        floors().filter { it.creatorId == me && it.parentId == replyTo && it.id !in known }.maxByOrNull { it.id }?.id
            ?: throw FailureException(Failure.BadResponse)
    }
}

/** 比正文时不算首尾空白与换行写法（网页表单按 `\r\n` 提交）。 */
private fun String.normalized(): String = replace("\r\n", "\n").trim()

/** 本机钟与服务端钟允许差多少：查「接口其实写进去了」时，比发送时刻早这么多以内的楼也算。设计决定。 */
private const val CLOCK_SKEW_SECONDS = 600L
