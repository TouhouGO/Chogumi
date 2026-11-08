package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.map
import io.github.touhougo.chogumi.core.data.db.calendar.CalendarDao
import io.github.touhougo.chogumi.core.data.db.calendar.CalendarEntity
import io.github.touhougo.chogumi.core.data.db.mapper.toBo
import io.github.touhougo.chogumi.core.data.db.mapper.toSnapshot
import io.github.touhougo.chogumi.core.data.db.subject.SubjectDao
import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import io.github.touhougo.chogumi.core.data.db.subject.getByIdsChunked
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.data.util.CacheTtl
import io.github.touhougo.chogumi.core.data.util.cachedNetworkCall
import io.github.touhougo.chogumi.core.model.CalendarItemBo
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.NextApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** 每日放送；日期顺序独立缓存，条目摘要共用 SubjectDao 合并，不覆盖已有详情。 */
class CalendarRepository(
    private val nextApi: NextApi,
    private val calendarDao: CalendarDao,
    private val subjectDao: SubjectDao,
    private val nsfwFilter: NsfwFilter,
    private val tokenStore: TokenStore,
    private val collectionStore: CollectionStore,
    private val clock: AppClock,
    /** 读缓存、取网络、落库与映射（一周上百条）都在这里跑，不占收集方（主线程）。 */
    private val computationDispatcher: CoroutineDispatcher,
) {
    suspend fun getCalendar(refresh: Boolean = false): Flow<Load<Map<Int, List<CalendarItemBo>>>> =
        cachedNetworkCall(
            readCache = { calendarDao.getAll().ifEmpty { null } },
            // 缓存拍与网络拍都是**全量**（过滤在下方 `.map` 边界统一做），
            // 缓存存全量是切换 NSFW 开关后命中缓存依然正确的前提。
            mapCache = { entities ->
                // 条目按 id 一次批量取回，不逐行 get
                val subjects = subjectDao.getByIdsChunked(entities.map { it.subjectId }).associateBy { it.id }
                entities.groupBy({ it.weekday }) { it }.mapValues { (_, rows) -> rows.map { it.toBo(subjects[it.subjectId]) } }
            },
            fetchNetwork = {
                tokenStore.withSessionToken { session, token ->
                    val calendar = nextApi.getCalendar(token)
                    collectionStore.mergeP1Interests(calendar.values.flatten().map { it.subject }, session)
                    // 无效星期不能悄悄伪造为周一：整份当作读不懂的响应。
                    val days = calendar.map { (day, entries) ->
                        val weekday = day.toIntOrNull()?.takeIf { it in 1..7 } ?: throw FailureException(Failure.BadResponse)
                        weekday to entries
                    }
                    // 一周的条目整批一个事务落库：逐条写会让订阅 subjects 表的每条查询（收藏、统计）跟着重跑上百次。
                    val now = clock.nowMillis()
                    val entries = days.flatMap { (_, dayEntries) -> dayEntries }
                    val merged = subjectDao.mergeAll(entries.map { it.subject.toSnapshot(now) }).iterator()
                    days.associate { (weekday, dayEntries) ->
                        weekday to dayEntries.map { CalendarItemBo(merged.next().toBo(), it.watchers) }
                    }
                }
            },
            writeCache = { calendar ->
                val now = clock.nowMillis()
                calendarDao.replaceAll(calendar.flatMap { (weekday, items) -> items.map { item ->
                    CalendarEntity(
                        weekday = weekday,
                        subjectId = item.subject.id,
                        name = item.subject.name,
                        nameCn = item.subject.nameCn,
                        imageLarge = item.subject.imageLarge,
                        watchers = item.watchers,
                        updatedAt = now,
                        nsfw = item.subject.nsfw,
                    )
                } })
            },
            refresh = refresh,
            ttlMillis = CacheTtl.CALENDAR,
            cachedAt = { it.firstOrNull()?.updatedAt ?: 0L },
            clock = clock,
            workContext = computationDispatcher,
        )
            // NSFW 过滤的**唯一边界**：缓存与网络两路都经此处。
            // 过滤发生在 cachedNetworkCall 之外 —— 它的 writeCache 写的是 fetchNetwork 的
            // 原始返回（全量），所以「缓存/落库存全量」靠这层包装保证；
            // 缓存存全量，下次加载（含命中缓存）按当时的开关过滤。
            // 注意：这**不是**即时生效 —— 已显示的页面在切换开关后需重新加载才会变化，
            // 与 [NsfwFilter] 的说明一致。
            // 过滤在计算调度器上做，发射留在收集方（同 cachedNetworkCall 的 workContext，不接 flowOn）。
            .map { load ->
                withContext(computationDispatcher) {
                    load.map { calendar -> calendar.mapValues { (_, items) -> nsfwFilter.apply(items) { it.subject.nsfw } } }
                }
            }

    /**
     * 缓存行 → 领域项。
     *
     * 优先取 `subjects` 表的完整摘要（NSFW 真源，[subject] 由调用方按 id 批量取回）。查不到时**伪造**一个 SubjectBo ——
     * 此时绝不能把 nsfw 丢掉：该行自己的 [CalendarEntity.nsfw] 是唯一可用的兜底证据，
     * 由上层过滤边界统一剔除。**不要**给伪造值写死 nsfw = false。
     */
    private fun CalendarEntity.toBo(subject: SubjectEntity?) = CalendarItemBo(
        subject = subject?.toBo()
            ?: SubjectBo(subjectId, SubjectType.ANIME, name, nameCn, imageLarge = imageLarge, nsfw = nsfw),
        watchers = watchers,
    )
}
