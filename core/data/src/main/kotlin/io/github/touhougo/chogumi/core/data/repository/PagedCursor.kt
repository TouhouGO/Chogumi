package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.network.model.p1.PagedDto
import io.github.touhougo.chogumi.core.network.model.v0.EpisodeCollectionPageDto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import io.github.touhougo.chogumi.core.network.model.v0.PagedDto as V0PagedDto

/**
 * 下一页的 `offset`；`null` 表示已到末页。
 *
 * ## 为什么必须按请求跨度推进，不能用返回条数
 *
 * 服务端先按 `limit/offset` 切**数据库结果集**，**再**跳过查不到的条目或用户
 * （发布者已注销、内容不可见、nsfw 过滤）。所以一页回不满、甚至整页为空，
 * 都**不代表这是最后一页**。按返回条数推进（`offset + data.size`）会让被跳过的
 * 那一段被再扫一次，表现为重复条目；整页被跳过时游标原地不动，
 * 会把最多一个页大小的可见数据**静默漏掉**（v0 收藏实测过：中间整段不可见时
 * 只取回 50 条，丢了一半）。
 *
 * ## 跨度取响应里的 `limit`
 *
 * 服务端可能不按请求值给（请求 50、回应 10）。[responseLimit] 不是正数
 * （键被省略成 0，或该 host 的响应里压根没有这个字段）时退回 [requestedLimit]。
 *
 * ## 末页判据用 `total`
 *
 * `total` 是服务端数出来的结果集行数，**是过滤前的**，因此 `offset + 跨度 >= total`
 * 才是真正的末页。服务端未给出 `total`（<= 0）时只能退回"空页即结束"——
 * 这种降级会漏数据，但不会死循环。
 *
 * p1 公开分页与 [fetchAllPages]（v0 收藏也走它）共用这一份。
 */
internal fun nextCursor(
    offset: Int,
    requestedLimit: Int,
    total: Int,
    isEmpty: Boolean,
    responseLimit: Int = 0,
): Int? {
    val span = if (responseLimit > 0) responseLimit else requestedLimit
    val next = offset + span
    return when {
        // 正常情形：服务端给了行数，够没够一目了然
        total > 0 -> next.takeIf { it < total }
        // 无 total 时只能按"空页即结束"，会漏数据，但不死循环
        isEmpty -> null
        else -> next
    }
}

/**
 * p1 的一页 → [PagedResult]。[items] 是这一页映射 / 过滤后的条目；游标按服务端**原始**页推进（[nextCursor]），
 * 过滤后整页为空也照样能往后翻。
 */
internal fun <R> PagedDto<*>.toPagedResult(offset: Int, limit: Int, items: List<R>): PagedResult<R> = PagedResult(
    items = items,
    total = total,
    nextOffset = nextCursor(offset = offset, requestedLimit = limit, total = total, isEmpty = data.isEmpty()),
)

/** 逐项映射的 [toPagedResult]。 */
internal inline fun <T, R> PagedDto<T>.toPagedResult(offset: Int, limit: Int, transform: (T) -> R): PagedResult<R> =
    toPagedResult(offset, limit, data.map(transform))

/** 取全一个分页列表时，一页里要用到的：这一页的条目、服务端给的 `total`、服务端实际给的跨度（没有该字段时 0）。 */
internal class PageSlice<T>(val data: List<T>, val total: Int, val responseLimit: Int = 0)

internal fun <T> PagedDto<T>.toSlice() = PageSlice(data, total)

internal fun <T> V0PagedDto<T>.toSlice() = PageSlice(data, total, limit)

internal fun EpisodeCollectionPageDto.toSlice() = PageSlice(data, total, limit)

/**
 * 一次取全的页进度：拿到 `total` 时登记要取的页数（[pagesPlanned]），每取完一页报一次（[pageDone]）。
 * 几次取全可以共用一个（全量同步五类合起来报一条进度），所以登记是累加。
 * 线程安全：每次变化把（已完成页, 已登记页）交给 [onChange]；还没登记过时已登记页是 0。
 */
internal class PageCounter(private val onChange: (completed: Int, planned: Int) -> Unit) {
    private var completed = 0
    private var planned = 0

    fun pagesPlanned(count: Int) = report { planned += count }

    fun pageDone() = report { completed++ }

    private inline fun report(change: () -> Unit) {
        val (done, total) = synchronized(this) {
            change()
            completed to planned
        }
        onChange(done, total)
    }
}

/**
 * 按 offset 分页的列表取全，所有「取全一个分页列表」都走这里。游标规则见 [nextCursor]。
 *
 * - 有 `total`：第一页拿到 `total` 和实际跨度后，其余各页的 offset 一次算全、一起发，所以取全只比取一页多一轮往返，
 *   不随页数线性变长（柯南、海贼王的章节都是十几页）。
 * - 没有 `total`（<= 0）：预先不知道末页，逐页往后取，直到空页（[nextCursor] 的降级规则）。
 * - 相邻两页内容完全相同（且不空）：服务端翻页没有前进，读到的不是一份能用的分页，按 [Failure.BadResponse] 失败——
 *   否则没有 `total` 时会一直取下去，有 `total` 时调用方去重后只剩一页，再拿它整体替换本地就删掉了其余的行。
 * - 任一页失败整体失败，不拼出缺页的列表。
 *
 * 参数：
 * - [maxRows]：只取结果集的前 [maxRows] 行，超出的页不请求，结果也不超过 [maxRows] 条。
 * - [permits]：每一页（第一页也算）发出前要拿到的许可，为 null 时不限。用户在等结果时不限；后台取几十页时要限，
 *   否则这些页会占满网络层对同一 host 的并发名额，页面自己的请求得排在它们后面。几次取全共用一个，就把合计在途页数压在同一个上限内。
 * - [progress]：页进度（见 [PageCounter]）。
 */
internal suspend fun <T> fetchAllPages(
    limit: Int,
    maxRows: Int = Int.MAX_VALUE,
    permits: Semaphore? = null,
    progress: PageCounter? = null,
    page: suspend (offset: Int, limit: Int) -> PageSlice<T>,
): List<T> = coroutineScope {
    suspend fun fetch(offset: Int): PageSlice<T> {
        val slice = if (permits == null) page(offset, limit) else permits.withPermit { page(offset, limit) }
        progress?.pageDone()
        return slice
    }
    val first = fetch(0)
    val pages = mutableListOf(first)
    if (first.total > 0) {
        val total = minOf(first.total, maxRows)
        val offsets = generateSequence(0) { offset ->
            nextCursor(offset = offset, requestedLimit = limit, total = total, isEmpty = true, responseLimit = first.responseLimit)
        }.drop(1).toList()
        progress?.pagesPlanned(offsets.size + 1)
        pages += offsets.map { offset -> async { fetch(offset) } }.awaitAll()
        pages.zipWithNext().forEach { (previous, next) -> requireAdvanced(previous, next) }
    } else {
        var offset = 0
        var last = first
        var rows = first.data.size
        while (rows < maxRows) {
            offset = nextCursor(
                offset = offset, requestedLimit = limit, total = last.total, isEmpty = last.data.isEmpty(), responseLimit = last.responseLimit,
            ) ?: break
            last = fetch(offset)
            requireAdvanced(pages.last(), last)
            pages += last
            rows += last.data.size
        }
    }
    pages.flatMap { it.data }.take(maxRows)
}

/** 翻页有没有前进：相邻两页内容完全相同（且不空）就是没有，见 [fetchAllPages]。 */
private fun requireAdvanced(previous: PageSlice<*>, next: PageSlice<*>) {
    if (next.data.isNotEmpty() && next.data == previous.data) throw FailureException(Failure.BadResponse)
}
