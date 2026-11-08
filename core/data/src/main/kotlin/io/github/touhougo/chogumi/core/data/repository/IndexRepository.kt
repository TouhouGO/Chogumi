package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.mapper.toBo
import io.github.touhougo.chogumi.core.data.db.mapper.toCommentThreadBoList
import io.github.touhougo.chogumi.core.data.db.mapper.toDetailsBo
import io.github.touhougo.chogumi.core.data.db.mapper.toEntryBo
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.authenticatedOutcome
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.IndexCategory
import io.github.touhougo.chogumi.core.model.IndexDetailsBo
import io.github.touhougo.chogumi.core.model.IndexEntryBo
import io.github.touhougo.chogumi.core.model.IndexMembership
import io.github.touhougo.chogumi.core.model.IndexOrderChange
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.network.api.IndexApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore

/** 契约里收录端点的 `limit` 上限（`maximum: 100`）；「按评分」要取全量，页越大请求越少。 */
const val INDEX_ENTRIES_PAGE_LIMIT = 100

/** 目录名称的长度上限（契约 `CreateIndex.title` 的 `maxLength`）：界面的输入框按它截断。 */
const val INDEX_TITLE_MAX_LENGTH = IndexApi.MAX_INDEX_TITLE_LENGTH

/** 契约里 `/p1/users/{username}/indexes` 的 `limit` 上限（`maximum: 100`）。 */
private const val USER_INDEXES_PAGE_LIMIT = 100

/** 查勾选状态时同时在途的请求数上限：目录多的人一次打开收录面板不把几十个请求同时压上去（设计决定）。 */
private const val MEMBERSHIP_PARALLEL_REQUESTS = 4

/**
 * 目录：详情页的头部、收录条目、留言、收藏，以及自己目录的建、改、删、收录、移出。只走网络，不落库。
 *
 * 用户创建 / 收藏的目录**列表**按用户维度留在 [UserRepository]。
 * 带令牌请求：登录后头部多出「是否已收藏」，留言的贴贴「我贴过」高亮也才对得上。
 * 写操作都要登录，固定在操作起点的会话上（[authenticatedOutcome]）：中途换号或登出，这次操作以
 * [io.github.touhougo.chogumi.core.foundation.Failure.SessionChanged] 失败，不会写进下一个账号。
 * 每次写成功都让 [writes] 加一：别的页面（我创建的目录列表）观察它重取，不在打开时读一次就不管。
 */
class IndexRepository(
    private val indexApi: IndexApi,
    private val userApi: UserApi,
    private val tokenStore: TokenStore,
) {

    private val writeCount = MutableStateFlow(0)

    /** 目录写成功的次数（建、改、删、收藏、取消收藏、收录、移出、改收录）：只用来让列表知道该重取了，数值本身没有意义。 */
    val writes: StateFlow<Int> = writeCount.asStateFlow()

    private fun <T> Outcome<T>.counted(): Outcome<T> = also { if (it is Outcome.Success) writeCount.update { n -> n + 1 } }

    suspend fun getIndex(indexId: Int): IndexDetailsBo =
        tokenStore.withSessionToken { session, token -> indexApi.getIndex(indexId, token).toDetailsBo(session.userId) }

    /** 某一类别的收录，一页；在服务端按类别过滤，`total` 与分页才对得上。 */
    suspend fun getEntries(
        indexId: Int,
        category: IndexCategory,
        offset: Int = 0,
        limit: Int = INDEX_ENTRIES_PAGE_LIMIT,
    ): PagedResult<IndexEntryBo> =
        tokenStore.withSessionToken { _, token ->
            val page = indexApi.getIndexRelated(indexId, cat = category.code, limit = limit, offset = offset, accessToken = token)
            page.toPagedResult(offset, limit, page.data.mapNotNull { it.toEntryBo() })
        }

    /** 全部留言（接口不分页）。 */
    suspend fun getComments(indexId: Int): List<CommentThreadBo> =
        tokenStore.withSessionToken { session, token ->
            indexApi.getIndexComments(indexId, token).toCommentThreadBoList(session.userId)
        }

    /** 收藏 / 取消收藏（需要登录；未登录是 [io.github.touhougo.chogumi.core.foundation.Failure.NotLoggedIn]）。 */
    suspend fun setCollected(indexId: Int, collected: Boolean) = tokenStore.authenticatedOutcome { _, token ->
        outcomeOf<Unit> { if (collected) indexApi.collectIndex(indexId, token) else indexApi.uncollectIndex(indexId, token) }
    }.counted()

    /** 新建目录，回新目录的 id。 */
    suspend fun createIndex(title: String, description: String, isPrivate: Boolean): Outcome<Int> =
        tokenStore.authenticatedOutcome { _, token ->
            outcomeOf { indexApi.createIndex(title.trim(), description.trim(), isPrivate, token) }
        }.counted()

    /** 改自己的目录：名称、介绍、公开 / 私密一起写。 */
    suspend fun updateIndex(indexId: Int, title: String, description: String, isPrivate: Boolean): Outcome<Unit> =
        tokenStore.authenticatedOutcome { _, token ->
            outcomeOf { indexApi.updateIndex(indexId, title.trim(), description.trim(), isPrivate, token) }
        }.counted()

    /** 删除自己的目录。 */
    suspend fun deleteIndex(indexId: Int): Outcome<Unit> = tokenStore.authenticatedOutcome { _, token ->
        outcomeOf { indexApi.deleteIndex(indexId, token) }
    }.counted()

    /** 把 [target] 收进自己的目录 [indexId]，回这条收录自己的 id（移出用）。 */
    suspend fun addEntry(indexId: Int, target: IndexTarget, comment: String = ""): Outcome<Int> =
        tokenStore.authenticatedOutcome { _, token ->
            outcomeOf { indexApi.addRelated(indexId, target.category.code, target.id, comment.trim(), token) }
        }.counted()

    /** 从自己的目录 [indexId] 移出一条收录（[relatedId] 是收录行自己的 id，[IndexEntryBo.id]）。 */
    suspend fun removeEntry(indexId: Int, relatedId: Int): Outcome<Unit> = tokenStore.authenticatedOutcome { _, token ->
        outcomeOf { indexApi.deleteRelated(indexId, relatedId, token) }
    }.counted()

    /** 改一条收录的排序与评语（接口要求两项一起写：只改一项时另一项传原值）。 */
    suspend fun updateEntry(indexId: Int, relatedId: Int, order: Int, comment: String): Outcome<Unit> =
        tokenStore.authenticatedOutcome { _, token ->
            outcomeOf { indexApi.updateRelated(indexId, relatedId, order, comment.trim(), token) }
        }.counted()

    /**
     * 拖动排序要写的几条（[changes]，[io.github.touhougo.chogumi.core.model.indexReorderOf] 算出的）：固定在同一个会话上逐条改排序值，
     * 评语原样带上。中途有一条失败，已经写成的逐条写回原值（尽力而为），回那条失败的原因；写回也没成的，
     * 界面重取收录时以服务端为准。
     */
    suspend fun reorderEntries(indexId: Int, changes: List<IndexOrderChange>): Outcome<Unit> =
        tokenStore.authenticatedOutcome { _, token ->
            val written = mutableListOf<IndexOrderChange>()
            for (change in changes) {
                val result = outcomeOf { indexApi.updateRelated(indexId, change.relatedId, change.order, change.comment, token) }
                if (result is Outcome.Failed) {
                    written.forEach { done ->
                        outcomeOf { indexApi.updateRelated(indexId, done.relatedId, done.previous, done.comment, token) }
                    }
                    return@authenticatedOutcome result
                }
                written += change
            }
            Outcome.Success(Unit)
        }.counted()

    /**
     * 我创建的全部目录（含私密的），每个带上 [target] 在不在里面（[IndexMembership.relatedId]）：收录面板的勾选初值。
     *
     * 服务端没有「这样东西在我哪些目录里」的反查（`/p1/subjects/{id}/indexes` 列的是所有人的目录），
     * 所以逐个目录按 [target] 的类别取全它收录的这一类（每页 [INDEX_ENTRIES_PAGE_LIMIT] 条），找同一个 id。
     * 目录列表与各目录的查询固定在同一个会话上。
     */
    suspend fun membershipsOf(target: IndexTarget): Outcome<List<IndexMembership>> =
        tokenStore.authenticatedOutcome { session, token ->
            outcomeOf {
                val username = requireNotNull(session.username)
                val permits = Semaphore(MEMBERSHIP_PARALLEL_REQUESTS)
                val indexes = fetchAllPages(USER_INDEXES_PAGE_LIMIT, permits = permits) { offset, limit ->
                    userApi.getUserIndexes(username, limit = limit, offset = offset, accessToken = token).toSlice()
                }.map { it.toBo() }
                coroutineScope {
                    indexes.map { index ->
                        async {
                            val rows = fetchAllPages(INDEX_ENTRIES_PAGE_LIMIT, permits = permits) { offset, limit ->
                                indexApi.getIndexRelated(index.id, cat = target.category.code, limit = limit, offset = offset, accessToken = token)
                                    .toSlice()
                            }
                            IndexMembership(index, rows.firstOrNull { it.sid == target.id }?.id)
                        }
                    }.awaitAll()
                }
            }
        }
}
