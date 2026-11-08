package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.mapper.toDetailBo
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.authenticatedOutcome
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.data.util.forbiddenAs
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.TopicDetailBo
import io.github.touhougo.chogumi.core.model.TopicType
import io.github.touhougo.chogumi.core.network.api.NextApi

/**
 * 帖子页的数据：作品讨论版与小组的话题详情、楼层贴贴、回复、改与删自己的楼。两种话题形状相同，只是接口不同。
 *
 * 只走网络，不落库（社区内容每次进页现拉）。带令牌请求：登录后贴贴的「我贴过」高亮才对得上。
 */
interface TopicRepository {
    suspend fun getTopic(type: TopicType, topicId: Int): TopicDetailBo

    /** 楼层（主楼与楼中楼）贴贴：[value] 非 null 是贴（替换我原来那个），null 是取消。 */
    suspend fun setPostReaction(type: TopicType, postId: Int, value: Int?): Outcome<Unit>

    /**
     * 回复话题，成功是新楼的 id。[replyTo] 0 = 新开一层，否则是所在**主楼**的 id（服务端把它原样存成楼中楼的归属）；
     * [turnstileToken] 是用户刚过的人机验证令牌。禁言、话题已关闭说成 [RejectReason.POSTING_NOT_ALLOWED]。
     * 接口回 5xx 时用网页会话把同一条送进话题的网页（[replyWithWebFallback]）。
     */
    suspend fun reply(type: TopicType, topicId: Int, content: String, replyTo: Int, turnstileToken: String): Outcome<Int>

    /**
     * 改自己的一层楼（不需要人机验证）。楼下已有回复、话题已关闭（服务端 403）说成 [RejectReason.EDIT_NOT_ALLOWED]；
     * 哪些楼能改由界面按 `ownFloorActionsOf` 先判，这里是服务端的最后一道。
     */
    suspend fun editPost(type: TopicType, postId: Int, content: String): Outcome<Unit>

    /** 删除自己的一层楼（不需要人机验证）。 */
    suspend fun deletePost(type: TopicType, postId: Int): Outcome<Unit>
}

class TopicRepositoryImpl(
    private val nextApi: NextApi,
    private val tokenStore: TokenStore,
    private val webSessions: WebSessionRepository,
) : TopicRepository {

    override suspend fun getTopic(type: TopicType, topicId: Int): TopicDetailBo =
        tokenStore.withSessionToken { session, token ->
            endpoints(type).fetch(topicId, token, session.userId)
        }

    override suspend fun setPostReaction(type: TopicType, postId: Int, value: Int?) =
        tokenStore.authenticatedOutcome { _, token -> outcomeOf<Unit> { endpoints(type).react(postId, value, token) } }

    override suspend fun reply(type: TopicType, topicId: Int, content: String, replyTo: Int, turnstileToken: String) =
        tokenStore.authenticatedOutcome { session, token ->
            val endpoints = endpoints(type)
            val page = BgmWeb.topic(type, topicId)
            replyWithWebFallback(webSessions, session, page, content, replyTo, floors = { endpoints.floors(topicId, token) }) {
                endpoints.reply(topicId, content, replyTo, turnstileToken, token)
            }
        }.forbiddenAs(RejectReason.POSTING_NOT_ALLOWED)

    override suspend fun editPost(type: TopicType, postId: Int, content: String) =
        tokenStore.authenticatedOutcome { _, token -> outcomeOf<Unit> { endpoints(type).edit(postId, content, token) } }
            .forbiddenAs(RejectReason.EDIT_NOT_ALLOWED)

    override suspend fun deletePost(type: TopicType, postId: Int) =
        tokenStore.authenticatedOutcome { _, token -> outcomeOf<Unit> { endpoints(type).delete(postId, token) } }

    /** 话题类型 → 服务端的那一组接口：按 [TopicType] 选接口全仓只在这里。 */
    private fun endpoints(type: TopicType): TopicEndpoints = when (type) {
        TopicType.SUBJECT -> TopicEndpoints(
            fetch = { id, token, me -> nextApi.getSubjectTopic(id, token).toDetailBo(me) },
            floors = { id, token -> nextApi.getSubjectTopic(id, token).replies.toReplyFloors() },
            react = nextApi::setSubjectPostReaction,
            reply = nextApi::replySubjectTopic,
            edit = nextApi::editSubjectPost,
            delete = nextApi::deleteSubjectPost,
        )
        TopicType.GROUP -> TopicEndpoints(
            fetch = { id, token, me -> nextApi.getGroupTopic(id, token).toDetailBo(me) },
            floors = { id, token -> nextApi.getGroupTopic(id, token).replies.toReplyFloors() },
            react = nextApi::setGroupPostReaction,
            reply = nextApi::replyGroupTopic,
            edit = nextApi::editGroupPost,
            delete = nextApi::deleteGroupPost,
        )
    }

    private class TopicEndpoints(
        /** 取话题并按当前用户映射（[currentUserId] 标出「我」的楼）。 */
        val fetch: suspend (topicId: Int, token: String?, currentUserId: Int?) -> TopicDetailBo,
        /** 楼层原样（回帖容灾的查重与回读用）。 */
        val floors: suspend (topicId: Int, token: String) -> List<ReplyFloor>,
        val react: suspend (postId: Int, value: Int?, token: String) -> Unit,
        val reply: suspend (topicId: Int, content: String, replyTo: Int, turnstileToken: String, token: String) -> Int,
        val edit: suspend (postId: Int, content: String, token: String) -> Unit,
        val delete: suspend (postId: Int, token: String) -> Unit,
    )
}
