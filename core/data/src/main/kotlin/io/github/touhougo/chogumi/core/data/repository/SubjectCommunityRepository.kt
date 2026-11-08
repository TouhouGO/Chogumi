package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.mapper.toBo
import io.github.touhougo.chogumi.core.data.db.mapper.toCommentThreadBo
import io.github.touhougo.chogumi.core.data.db.mapper.toCommentThreadBoList
import io.github.touhougo.chogumi.core.data.db.mapper.toEpisodeBo
import io.github.touhougo.chogumi.core.data.db.mapper.toTopicSubjectBo
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.authenticatedOutcome
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.EpisodeThreadBo
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.SlimBlogBo
import io.github.touhougo.chogumi.core.model.SubjectCollectorBo
import io.github.touhougo.chogumi.core.model.SubjectTopicBo
import io.github.touhougo.chogumi.core.network.api.NextApi

/**
 * 条目社区数据（条目的社区 tab）：吐槽、讨论版、评论、目录、谁在看。话题详情见 [TopicRepository]。
 *
 * 只走网络和内存，**不落库**（设计决定：社区内容每次进页现拉，不进 Room）。
 * 分页游标统一由 [nextCursor] 按请求跨度推进。带令牌请求：登录后贴贴的「我贴过」高亮才对得上。
 */
interface SubjectCommunityRepository {
    /** 条目吐槽；[type] 非 null 时只看该收藏状态的。 */
    suspend fun getComments(subjectId: Int, type: CollectionType? = null, limit: Int = 20, offset: Int = 0): PagedResult<CommentThreadBo>
    suspend fun getTopics(subjectId: Int, limit: Int = 20, offset: Int = 0): PagedResult<SubjectTopicBo>
    suspend fun getReviews(subjectId: Int, limit: Int = 20, offset: Int = 0): PagedResult<SlimBlogBo>
    suspend fun getIndexes(subjectId: Int, limit: Int = 20, offset: Int = 0): PagedResult<IndexBo>
    suspend fun getCollectors(
        subjectId: Int,
        type: CollectionType? = null,
        limit: Int = 20,
        offset: Int = 0,
    ): PagedResult<SubjectCollectorBo>

    /** 作品吐槽贴贴：[value] 非 null 是贴（替换我原来那个），null 是取消。[commentId] 即吐槽楼层的 id（收藏 id）。 */
    suspend fun setCommentReaction(commentId: Int, value: Int?): Outcome<Unit>

    /** 单集讨论页的头部：这一集与它属于的作品。 */
    suspend fun getEpisode(episodeId: Int): EpisodeThreadBo

    /** 单集评论（楼层 + 楼中楼，一次全部）。匿名可读，带令牌只为标出「我」的楼层与贴贴。 */
    suspend fun getEpisodeComments(episodeId: Int): List<CommentThreadBo>

    /** 单集评论贴贴，语义同 [setCommentReaction]。 */
    suspend fun setEpisodeCommentReaction(commentId: Int, value: Int?): Outcome<Unit>
}

/** 契约里 `/p1/subjects/{id}/collects` 的 `offset` 上限（`maximum: 500`）：超过即 400，所以在这里当末页。 */
internal const val MAX_COLLECTS_OFFSET = 500

class SubjectCommunityRepositoryImpl(
    private val nextApi: NextApi,
    private val tokenStore: TokenStore,
) : SubjectCommunityRepository {

    override suspend fun getComments(subjectId: Int, type: CollectionType?, limit: Int, offset: Int): PagedResult<CommentThreadBo> =
        tokenStore.withSessionToken { session, token ->
            val page = nextApi.getSubjectComments(subjectId, limit, offset, token, type?.value)
            page.toPagedResult(offset, limit, page.data.map { it.toCommentThreadBo(subjectId, session.userId) })
        }

    override suspend fun getTopics(subjectId: Int, limit: Int, offset: Int): PagedResult<SubjectTopicBo> =
        tokenStore.withSessionToken { _, token ->
            nextApi.getSubjectTopics(subjectId, limit, offset, token).toPagedResult(offset, limit) { it.toBo() }
        }

    override suspend fun getReviews(subjectId: Int, limit: Int, offset: Int): PagedResult<SlimBlogBo> =
        tokenStore.withSessionToken { _, token ->
            nextApi.getSubjectReviews(subjectId, limit, offset, token).toPagedResult(offset, limit) { it.toBo() }
        }

    override suspend fun getIndexes(subjectId: Int, limit: Int, offset: Int): PagedResult<IndexBo> =
        tokenStore.withSessionToken { _, token ->
            nextApi.getSubjectIndexes(subjectId, limit, offset, token).toPagedResult(offset, limit) { it.toBo() }
        }

    override suspend fun getCollectors(
        subjectId: Int,
        type: CollectionType?,
        limit: Int,
        offset: Int,
    ): PagedResult<SubjectCollectorBo> = tokenStore.withSessionToken { _, token ->
        nextApi.getSubjectCollects(subjectId, type?.value, limit, offset, token)
            .toPagedResult(offset, limit) { it.toBo() }
            .let { page -> page.copy(nextOffset = page.nextOffset?.takeIf { it <= MAX_COLLECTS_OFFSET }) }
    }


    override suspend fun setCommentReaction(commentId: Int, value: Int?) = tokenStore.authenticatedOutcome { _, token ->
        outcomeOf<Unit> { nextApi.setSubjectCollectReaction(commentId, value, token) }
    }

    override suspend fun getEpisode(episodeId: Int): EpisodeThreadBo =
        tokenStore.withSessionToken { _, token ->
            val dto = nextApi.getEpisode(episodeId, token)
            EpisodeThreadBo(episode = dto.toEpisodeBo(), subject = dto.subject?.toTopicSubjectBo())
        }

    override suspend fun getEpisodeComments(episodeId: Int): List<CommentThreadBo> =
        tokenStore.withSessionToken { session, token ->
            nextApi.getEpisodeComments(episodeId, token).toCommentThreadBoList(session.userId)
        }

    override suspend fun setEpisodeCommentReaction(commentId: Int, value: Int?) = tokenStore.authenticatedOutcome { _, token ->
        outcomeOf<Unit> { nextApi.setEpisodeCommentReaction(commentId, value, token) }
    }

}
