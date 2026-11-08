package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.model.v0.PagedDto
import io.github.touhougo.chogumi.core.network.model.v0.UserCollectionDto
import kotlinx.coroutines.sync.Semaphore

/**
 * 某用户某内容类型（可再按收藏状态）的全部收藏：本人、全量同步与他人收藏都从这里取，分页规则只在 [fetchAllPages]。
 *
 * - [session] 为 null 时匿名请求（看他人）；不为 null 时带它的令牌，且每取回一页都确认它仍是当前会话，换号即停。
 * - [permits] 为 null 时本次自建一个 [SYNC_CONCURRENCY] 的限流；同步五类时由调用方传入**共用的**一个，
 *   把五类合计并发压在同一个上限内（第一页也拿许可，否则五类第一页会各发一个、绕过上限）。
 * - [progress] 为页进度（[PageCounter]）。
 * - 服务端分页之间可能有重复（翻页途中收藏有变动），按条目去重，先出现的留下。
 */
internal suspend fun CollectionApi.fetchAllUserCollections(
    tokenStore: TokenStore,
    username: String,
    subjectType: SubjectType,
    type: CollectionType?,
    session: AccountSession?,
    permits: Semaphore? = null,
    progress: PageCounter? = null,
): List<UserCollectionDto> = fetchAllPages(
    limit = COLLECTION_PAGE_SIZE,
    permits = permits ?: Semaphore(SYNC_CONCURRENCY),
    progress = progress,
) { offset, limit ->
    fetchUserCollectionPage(tokenStore, username, subjectType, type, session, limit, offset).toSlice()
}.distinctBy { it.subjectId }

/** 收藏的一页：带 [session] 的令牌（null 为匿名），取回后确认它仍是当前会话。全量分页与只取一页都走这里。 */
internal suspend fun CollectionApi.fetchUserCollectionPage(
    tokenStore: TokenStore,
    username: String,
    subjectType: SubjectType,
    type: CollectionType?,
    session: AccountSession?,
    limit: Int = COLLECTION_PAGE_SIZE,
    offset: Int = 0,
): PagedDto<UserCollectionDto> {
    val page = getUserCollections(
        username = username,
        subjectType = subjectType.value,
        type = type?.value,
        limit = limit,
        offset = offset,
        accessToken = session?.let { tokenStore.tokenFor(it) },
    )
    session?.let { tokenStore.requireCurrent(it) }
    return page
}

/** 收藏分页请求的跨度；服务端实际给的跨度以响应里的 `limit` 为准（[nextCursor]）。 */
internal const val COLLECTION_PAGE_SIZE = 50

/**
 * 收藏分页的最大并发数。**5 类共用同一个上限 4**（设计决定，非 M3）——
 * 不是每类各 4、合计 20。对服务器与流量都友好，往返次数从 ≥6 轮降到约 2 轮。
 */
internal const val SYNC_CONCURRENCY = 4
