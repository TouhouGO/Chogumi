package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.common.draft.ComposerDrafts
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.authenticatedOutcome
import io.github.touhougo.chogumi.core.data.util.forbiddenAs
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.PostDraftKind
import io.github.touhougo.chogumi.core.network.api.CommentOwner
import io.github.touhougo.chogumi.core.network.api.NextApi

/**
 * 对象评论区（[CommentSurface.Box]：日志、目录的留言，角色、人物的吐槽箱，单集评论）的写：发一条，或回复其中一层楼。
 * 楼层的读取各在对象自己的仓库里（与正文一起取）。
 */
interface CommentRepository {
    /**
     * 在 [box] 发一条，成功是新楼的 id。[replyTo] 0 = 新的一条，否则是所在**主楼**的 id（与话题回复同一语义）；
     * [turnstileToken] 是用户刚过的人机验证令牌。禁言说成 [RejectReason.POSTING_NOT_ALLOWED]。
     * 接口回 5xx 时用网页会话把同一条送进评论区的网页（[replyWithWebFallback]）。
     */
    suspend fun reply(box: CommentSurface.Box, content: String, replyTo: Int, turnstileToken: String): Outcome<Int>

    /** 在 [box] 写的草稿：按回复的那层楼（[postIdOf]，0 = 新的一条）存，同一层楼只留一份。 */
    fun <T> replyDrafts(box: CommentSurface.Box, postIdOf: (T) -> Int): ComposerDrafts<T>
}

class CommentRepositoryImpl(
    private val nextApi: NextApi,
    private val tokenStore: TokenStore,
    private val drafts: DraftRepository,
    private val clock: AppClock,
    private val webSessions: WebSessionRepository,
) : CommentRepository {

    override suspend fun reply(box: CommentSurface.Box, content: String, replyTo: Int, turnstileToken: String) =
        tokenStore.authenticatedOutcome { session, token ->
            val (owner, id) = commentOwnerOf(box)
            val floors = suspend { nextApi.getComments(owner, id, token).toCommentFloors() }
            replyWithWebFallback(webSessions, session, BgmWeb.commentsPage(box), content, replyTo, floors) {
                nextApi.replyComments(owner, id, content, replyTo, turnstileToken, token)
            }
        }.forbiddenAs(RejectReason.POSTING_NOT_ALLOWED)

    override fun <T> replyDrafts(box: CommentSurface.Box, postIdOf: (T) -> Int): ComposerDrafts<T> {
        val (owner, id) = commentOwnerOf(box)
        return drafts.postDrafts<T>(PostDraftKind.COMMENT_REPLY, clock) { target -> "${owner.path}/$id/${postIdOf(target)}" }
    }
}

/** 评论区 → 服务端的对象与 id（唯一映射）。 */
private fun commentOwnerOf(box: CommentSurface.Box): Pair<CommentOwner, Int> = when (box) {
    is CommentSurface.Blog -> CommentOwner.BLOG to box.blogId
    is CommentSurface.Index -> CommentOwner.INDEX to box.indexId
    is CommentSurface.Mono -> CommentOwner.mono(box.type.resource) to box.id
    is CommentSurface.Episode -> CommentOwner.EPISODE to box.episodeId
}
