package io.github.touhougo.chogumi.feature.explore

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.touhougo.chogumi.core.model.PinnedShelf
import io.github.touhougo.chogumi.core.model.SearchAirtime
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SearchDimensionOption
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagCatalogEntry
import io.github.touhougo.chogumi.core.model.TagFilter
import io.github.touhougo.chogumi.core.model.WordPick

/**
 * 仪器测试用 [SearchActions]：每个事件都走生产 [reduce]，把结果写回可观察的 [form]。
 * 这样 UI 测试验证的是「真实组件 → 真实归约 → 重新渲染」整条链，而不是只记回调参数；
 * 网络与分页仍由 JVM 的 SearchViewModelTest 覆盖。
 */
class ReducingSearchActions(
    initial: SearchForm = SearchForm(),
    /** 面板搜索框的输入与目录结果：与生产一样由上层状态持有，面板不自记一份。 */
    panelQuery: String = "",
    catalogSuggestions: List<TagCatalogEntry> = emptyList(),
) : SearchActions {
    var form by mutableStateOf(initial)
    var panelQuery by mutableStateOf(panelQuery)
        private set
    var catalogSuggestions by mutableStateOf(catalogSuggestions)
        private set

    /** 图钉对着的货架：真实实现里由探索页布局驱动，这里由测试直接摆。 */
    var pinned by mutableStateOf<PinnedShelf?>(null)
        private set
    var renaming by mutableStateOf(false)
        private set

    /** 记录最后一次固定 / 改名请求，供测试断言界面递上来的名字。 */
    var lastPinTitle: String? = null
    var lastRenameTitle: String? = null

    val calls = mutableListOf<String>()

    private fun dispatch(name: String, intent: SearchIntent) {
        calls += name
        // 与 SearchViewModel.dispatch 一致：出错时也采用 reducer 给出的表单（已提交条件不变）。
        form = reduce(form, intent).form
    }

    override fun onQueryChange(value: String) = dispatch("onQueryChange", SearchIntent.QueryChanged(value))
    override fun onQueryClear() = dispatch("onQueryClear", SearchIntent.QueryCleared)
    override fun onSearch() = dispatch("onSearch", SearchIntent.SearchSubmitted)
    override fun onFilterTypeChange(type: SubjectType?) = dispatch("onFilterTypeChange", SearchIntent.TypeChanged(type))
    override fun onSortChange(sort: SearchSort) = dispatch("onSortChange", SearchIntent.SortChanged(sort))
    override fun onFiltersOpen() = dispatch("onFiltersOpen", SearchIntent.FiltersOpened)
    override fun onFiltersDismiss() = dispatch("onFiltersDismiss", SearchIntent.FiltersDismissed)
    override fun onDraftTagRemove(tag: TagFilter) = dispatch("onDraftTagRemove", SearchIntent.DraftTagRemoved(tag))

    override fun onPanelQueryChange(value: String) {
        calls += "onPanelQueryChange"
        panelQuery = value
    }
    override fun onDraftWordPick(word: String, pick: WordPick) =
        dispatch("onDraftWordPick", SearchIntent.DraftWordPicked(word, pick))
    override fun onDraftDimensionToggle(dimension: SearchDimension, option: SearchDimensionOption) =
        dispatch("onDraftDimensionToggle", SearchIntent.DraftDimensionToggled(dimension, option))
    override fun onDraftAirtimeChange(airtime: SearchAirtime?) =
        dispatch("onDraftAirtimeChange", SearchIntent.DraftAirtimeChanged(airtime))
    override fun onDraftClear() = dispatch("onDraftClear", SearchIntent.DraftCleared)
    override fun onFiltersSave() = dispatch("onFiltersSave", SearchIntent.FiltersSaved)
    override fun onHistoryClick(keyword: String) = dispatch("onHistoryClick", SearchIntent.HistoryClicked(keyword))
    override fun onPinToggle(defaultPresetName: String) {
        calls += "onPinToggle"
        lastPinTitle = defaultPresetName
        if (pinned != null) {
            pinned = null
        } else {
            // 放上去后货架自己的名字就是递上来的默认名（真实实现里由布局仓库存下）。
            pinned = PinnedShelf(id = "custom:1", name = defaultPresetName)
        }
    }

    override fun onRenameStart() {
        calls += "onRenameStart"
        renaming = true
    }

    override fun onRenameCancel() {
        calls += "onRenameCancel"
        renaming = false
    }

    override fun onRenameSubmit(title: String) {
        calls += "onRenameSubmit"
        lastRenameTitle = title
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) pinned = pinned?.copy(name = trimmed)
        renaming = false
    }

    override fun onRetry() { calls += "onRetry" }
    override fun loadMore() { calls += "loadMore" }
    override fun onClearHistory() { calls += "onClearHistory" }
}
