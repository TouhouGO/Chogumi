package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.mapper.toBo
import io.github.touhougo.chogumi.core.data.db.mapper.toCommentThreadBoList
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.authenticatedOutcome
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.data.util.LruMap
import io.github.touhougo.chogumi.core.data.util.onWebIfServerError
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.model.MonoCharacterRefBo
import io.github.touhougo.chogumi.core.model.MonoDetailsBo
import io.github.touhougo.chogumi.core.model.MonoRelationBo
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.MonoWorkBo
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.PersonCastFilter
import io.github.touhougo.chogumi.core.network.api.MonoApi
import io.github.touhougo.chogumi.core.network.api.MonoResource

/** 作品接口一页的上限（实测 `limit` ≤ 100，101 返回 400）。 */
private const val WORKS_PAGE_LIMIT = 100

/** 全部作品在内存里留几个实体：够一串角色、人物页来回走（设计决定）。 */
internal const val WORKS_CACHE_SIZE = 8

/** 人物图在内存里留几个：探索页两份公司名单（动画、游戏）一共 37 家，都放得下（设计决定）。 */
internal const val PERSON_IMAGE_CACHE_SIZE = 64

/**
 * 人物与角色详情数据仓库。
 *
 * 遵循手规约定：
 * 1. 分页通过 [nextCursor] 推进游标，严格按请求跨度而不是返回条数推进；
 * 2. 收藏写操作未登录时抛出「需要登录」（[TokenStore.requireSession]），不发裸请求；
 * 3. 评论数据使用通用 9 态与脏数据隔离映射。
 */
interface MonoRepository {
    suspend fun getDetails(type: MonoType, id: Int): MonoDetailsBo

    /**
     * 人物条目的图（探索页公司方块的标志）：取自人物详情，同一个人物进程内只取一次（留最近 [PERSON_IMAGE_CACHE_SIZE] 个）；
     * 没有图是 null。图与查看者无关，不按账号分开存。
     */
    suspend fun getPersonImage(id: Int): String?

    /**
     * 全部作品：角色的出演作品 / 人物的参与作品，逐页取齐（每页 [WORKS_PAGE_LIMIT]）。
     * 两个接口都没有排序参数，按评分排、按职位筛都得在全量上做（2026-10-05 实测）。
     * 同一实体的结果留在内存里，详情页与「全部」子页共用一次请求；[refresh] 为 true 时重取。
     * NSFW 在读出时过滤，切换开关后不必重取。
     */
    suspend fun getAllWorks(type: MonoType, id: Int, refresh: Boolean = false): List<MonoWorkBo>

    /** 人物出演的角色；[filter] 走接口的 `subjectType` / `type` 参数筛选（不在已加载的页上本地过滤）。 */
    suspend fun getPersonCharacters(
        id: Int,
        limit: Int = 30,
        offset: Int = 0,
        filter: PersonCastFilter = PersonCastFilter(),
    ): PagedResult<MonoCharacterRefBo>

    /** 关联：角色关联角色，人物关联人物（未按实体合并，同一人多种关系由调用方合成一张卡）。 */
    suspend fun getRelations(type: MonoType, id: Int, limit: Int = 30, offset: Int = 0): PagedResult<MonoRelationBo>

    suspend fun getIndexes(type: MonoType, id: Int, limit: Int = 30, offset: Int = 0): PagedResult<IndexBo>

    suspend fun getComments(type: MonoType, id: Int): List<CommentThreadBo>

    /**
     * 收藏 / 取消收藏；未登录是「需要登录」，不发请求。取消收藏的接口在服务端恒 5xx，改用网页会话取消
     * （[onWebIfServerError]）。
     */
    suspend fun setCollected(type: MonoType, id: Int, collected: Boolean): Outcome<Unit>
}

class MonoRepositoryImpl(
    private val monoApi: MonoApi,
    private val tokenStore: TokenStore,
    private val nsfwFilter: NsfwFilter,
    private val webSessions: WebSessionRepository,
) : MonoRepository {

    override suspend fun getDetails(type: MonoType, id: Int): MonoDetailsBo = getDetails(type, id, knownSession = null)

    private suspend fun getDetails(type: MonoType, id: Int, knownSession: AccountSession?): MonoDetailsBo =
        tokenStore.withSessionToken(knownSession) { _, token ->
            when (type) {
                MonoType.CHARACTER -> monoApi.getCharacter(id, token).toBo()
                MonoType.PERSON -> monoApi.getPerson(id, token).toBo()
            }
        }

    /** 人物图的内存缓存：键是人物 id，值可以是 null（这个人物没有图，也不再取）。 */
    private val personImages = LruMap<Int, String?>(PERSON_IMAGE_CACHE_SIZE)

    override suspend fun getPersonImage(id: Int): String? {
        synchronized(personImages) { if (personImages.containsKey(id)) return personImages[id] }
        val image = getDetails(MonoType.PERSON, id).image
        synchronized(personImages) { personImages[id] = image }
        return image
    }

    /**
     * 全量作品的内存缓存：键是（查看者, 类别, id），值带着每条的 NSFW 标记，读出时再过滤。
     * 服务端按账号决定给不给 NSFW 条目，游客取到的列表不能给登录后的账号用，所以键带 [AccountSession.viewerId]。
     * 详情页与它的「全部作品」页共用一份；只留最近 [WORKS_CACHE_SIZE] 项。
     */
    private val worksCache = LruMap<WorksKey, List<Pair<Boolean, MonoWorkBo>>>(WORKS_CACHE_SIZE)

    private data class WorksKey(val viewerId: Int, val type: MonoType, val id: Int)

    override suspend fun getAllWorks(type: MonoType, id: Int, refresh: Boolean): List<MonoWorkBo> {
        val session = tokenStore.session()
        val key = WorksKey(session.viewerId, type, id)
        val cached = if (refresh) null else synchronized(worksCache) { worksCache[key] }
        val all = cached ?: fetchAllWorks(type, id, session).also { synchronized(worksCache) { worksCache[key] = it } }
        return nsfwFilter.apply(all) { it.first }.map { it.second }
    }

    /** 逐页取齐（见 [fetchAllPages]），用 [session] 的令牌：取到的列表存在它的查看者名下。 */
    private suspend fun fetchAllWorks(type: MonoType, id: Int, session: AccountSession): List<Pair<Boolean, MonoWorkBo>> =
        tokenStore.withSessionToken(session) { _, token ->
            fetchAllPages(WORKS_PAGE_LIMIT) { offset, limit ->
                when (type) {
                    MonoType.CHARACTER -> monoApi.getCharacterCasts(id, limit, offset, accessToken = token)
                        .let { page -> PageSlice(page.data.map { it.subject.nsfw to it.toBo() }, page.total) }
                    MonoType.PERSON -> monoApi.getPersonWorks(id, limit, offset, accessToken = token)
                        .let { page -> PageSlice(page.data.map { it.subject.nsfw to it.toBo() }, page.total) }
                }
            }
        }

    override suspend fun getPersonCharacters(
        id: Int,
        limit: Int,
        offset: Int,
        filter: PersonCastFilter,
    ): PagedResult<MonoCharacterRefBo> =
        tokenStore.withSessionToken { _, token ->
            val response = monoApi.getPersonCasts(
                id,
                limit,
                offset,
                subjectType = filter.type?.value,
                type = filter.role?.code,
                accessToken = token,
            )
            response.toPagedResult(offset, limit, nsfwFilter.apply(response.data) { it.character.nsfw }.map { it.toBo() })
        }

    override suspend fun getRelations(type: MonoType, id: Int, limit: Int, offset: Int): PagedResult<MonoRelationBo> =
        tokenStore.withSessionToken { _, token ->
            when (type) {
                MonoType.CHARACTER -> monoApi.getCharacterRelations(id, limit, offset, token).toPagedResult(offset, limit) { it.toBo() }
                MonoType.PERSON -> monoApi.getPersonRelations(id, limit, offset, token).toPagedResult(offset, limit) { it.toBo() }
            }
        }

    override suspend fun getIndexes(type: MonoType, id: Int, limit: Int, offset: Int): PagedResult<IndexBo> =
        tokenStore.withSessionToken { _, token ->
            monoApi.getIndexes(type.resource, id, limit, offset, token).toPagedResult(offset, limit) { it.toBo() }
        }

    override suspend fun getComments(type: MonoType, id: Int): List<CommentThreadBo> =
        tokenStore.withSessionToken { session, token ->
            monoApi.getComments(type.resource, id, token).toCommentThreadBoList(session.userId)
        }

    override suspend fun setCollected(type: MonoType, id: Int, collected: Boolean): Outcome<Unit> =
        tokenStore.authenticatedOutcome { session, token ->
            val result = outcomeOf { monoApi.setCollected(type.resource, id, collected, token) }
            if (collected) result else result.onWebIfServerError(RejectReason.MONO_UNCOLLECT_UNAVAILABLE) {
                webSessions.perform(session) { formhash -> BgmWeb.monoUncollect(type, id, formhash) }
                if (getDetails(type, id, session).isCollected) {
                    throw FailureException(Failure.Rejected(RejectReason.MONO_UNCOLLECT_UNAVAILABLE))
                }
            }
        }
}

/** 角色 / 人物 → 接口资源段（唯一映射）。 */
internal val MonoType.resource: MonoResource
    get() = when (this) {
        MonoType.CHARACTER -> MonoResource.CHARACTERS
        MonoType.PERSON -> MonoResource.PERSONS
    }
