package io.github.touhougo.chogumi.feature.explore

import io.github.touhougo.chogumi.core.model.ExploreEntry
import io.github.touhougo.chogumi.core.model.SearchCriteria

/**
 * [ExploreEntry] → 页面导航语义的唯一映射。
 *
 * 契约规则：
 * - [ExploreEntry.Calendar] 交给 [onOpenCalendar]
 * - [ExploreEntry.OpenFilters] 带上该分类的条件并请求展开筛选
 * - [ExploreEntry.Search] 带上条件，标题是入口上显示的 [title]
 */
internal fun openExploreEntry(
    entry: ExploreEntry,
    title: String,
    onOpenSearch: (criteria: SearchCriteria, title: String?, openFilters: Boolean) -> Unit,
    onOpenCalendar: () -> Unit,
) {
    when (entry) {
        is ExploreEntry.Calendar -> onOpenCalendar()
        is ExploreEntry.OpenFilters -> onOpenSearch(entry.criteria, null, true)
        is ExploreEntry.Search -> onOpenSearch(entry.criteria, title, false)
    }
}
