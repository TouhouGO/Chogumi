package io.github.touhougo.chogumi.feature.timeline

import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.reader.TimelineFilter

/**
 * 按类别往前找时，连续这么多条动态都不属于该类别就先停下，等用户点「继续往前找」（二十页，约九秒）。
 *
 * 时间线接口不能按类别查，只能一页二十条往前翻：好友动态能一直翻到最早，冷门类别（日志、目录……）
 * 可能翻几百页也没有一条，不设上限就会在后台一直取下去。我的设计决定（not in M3）。
 */
internal const val SCAN_BUDGET = 400

/**
 * 按 [filter] 筛时是否该先停下：从最后一条属于该类别的动态、与用户上次点「继续往前找」时的最后一条
 * （[resumeAfter]，id）里靠后的那个往后数，已取到的动态里连续 [budget] 条都不属于该类别。「全部」从不停。
 */
internal fun scanPaused(
    items: List<TimelineItemBo>,
    filter: TimelineFilter,
    resumeAfter: Long?,
    budget: Int = SCAN_BUDGET,
): Boolean {
    if (filter == TimelineFilter.ALL) return false
    val lastHit = items.indexOfLast(filter::accepts)
    val resumed = resumeAfter?.let { id -> items.indexOfFirst { it.id == id } } ?: -1
    return items.size - (maxOf(lastHit, resumed) + 1) >= budget
}

/**
 * 往前找一轮：一页一页续取，直到列出的动态多了一条、到底、续取失败、该停下（[paused]），
 * 或者一页没带来新动态（续取被仓库跳过：正在刷新等）。进来时页脚上还留着上次的失败，是用户点了重试：照样先取一页。列表露出页脚时会再要一轮，所以一轮多一条就够。
 *
 * [accepts] 与 [paused] 每页重读：找的途中换了类别，按新类别判断。
 */
internal suspend fun scanRound(
    feed: () -> PagedList<TimelineItemBo>,
    accepts: (TimelineItemBo) -> Boolean,
    paused: (List<TimelineItemBo>) -> Boolean,
    loadPage: suspend () -> Unit,
) {
    val listed = feed().items.count(accepts)
    while (true) {
        val before = feed()
        if (!before.hasMore || paused(before.items)) return
        loadPage()
        val after = feed()
        if (after.loadMoreError != null || after.items.count(accepts) > listed || after.items.size <= before.items.size) return
    }
}
