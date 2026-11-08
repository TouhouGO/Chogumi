package io.github.touhougo.chogumi.feature.explore

import io.github.touhougo.chogumi.core.model.SearchAirtime
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SearchDimensionOption
import io.github.touhougo.chogumi.core.model.SearchFilters
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.SearchValidation
import io.github.touhougo.chogumi.core.model.SearchValidationError
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagFilter
import io.github.touhougo.chogumi.core.model.WordPick
import io.github.touhougo.chogumi.core.model.normalizeSearchCriteria
import io.github.touhougo.chogumi.core.model.pickWord
import io.github.touhougo.chogumi.core.model.toggleDimension
import io.github.touhougo.chogumi.core.model.withSubjectType

/**
 * 搜索表单：未提交的输入草稿 + 已提交的完整条件。
 * 草稿只在本类持有；已提交条件只有 core/model 的 SearchCriteria 一份真源。
 *
 * [filterDraft] 是筛选面板的编辑草稿：非 null 即面板打开（面板开关不另存状态）。
 * 打开时从已提交条件复制，点「保存」才写回已提交条件并重搜；关闭（遮罩/下滑/返回）直接丢弃。
 * 请求只读 [applied]，草稿永远不进请求。
 *
 * [title] 是路由带进来的名字（探索页的「近期热门」「排行榜」、或货架上某个组合的名字）。
 * 它不是持久状态，只是「这组条件当初叫什么」——条件一变就作废，判定只在 [reduce] 一处。
 * 已固定的组合有自己的名字（存在本地表里），由界面按 [SearchCriteria.presetKey] 查回来。
 */
data class SearchForm(
    val draftQuery: String = "",
    val applied: SearchCriteria = SearchCriteria(),
    val filterDraft: SearchFilters? = null,
    val title: String? = null,
)

/** 搜索事件。UI 只发事件，不自己算下一态。 */
sealed interface SearchIntent {
    data class QueryChanged(val value: String) : SearchIntent
    data object SearchSubmitted : SearchIntent
    data object QueryCleared : SearchIntent
    data class TypeChanged(val type: SubjectType?) : SearchIntent
    data class SortChanged(val sort: SearchSort) : SearchIntent
    /** 筛选面板：只改 [SearchForm.filterDraft]，不发请求，直到 [FiltersSaved]。 */
    data object FiltersOpened : SearchIntent
    data object FiltersDismissed : SearchIntent

    /** 筛选面板内移除标签：只改草稿。 */
    data class DraftTagRemoved(val tag: TagFilter) : SearchIntent

    /** 筛选面板内选一个词（题材区 chip 为切换，搜索框回车 / 点建议为只加不减，见 [WordPick]）：只改草稿。 */
    data class DraftWordPicked(val word: String, val pick: WordPick) : SearchIntent

    /** 点选某维度的一个取值（单选替换 / 多选叠加与取消由 core:model 的唯一更新点决定）。 */
    data class DraftDimensionToggled(val dimension: SearchDimension, val option: SearchDimensionOption) : SearchIntent

    /** [airtime] 为 null 表示时间「不限」。 */
    data class DraftAirtimeChanged(val airtime: SearchAirtime?) : SearchIntent
    data object DraftCleared : SearchIntent
    data object FiltersSaved : SearchIntent

    data class HistoryClicked(val keyword: String) : SearchIntent
}

/**
 * 归约结果。
 * - [submit] 表示需要重置/重搜，**不等于一定发 HTTP**：重置到空态也要取消旧请求并清结果。
 * - [writeHistory] 仅在显式提交且关键词非空时为真。
 * - [errors] 非空时已提交条件与输入草稿保持旧值；唯一例外是保存失败也关闭面板（界面已先收起面板再提交）。
 */
data class SearchReduction(
    val form: SearchForm,
    val submit: Boolean = false,
    val writeHistory: Boolean = false,
    val errors: List<SearchValidationError> = emptyList(),
)

/** 纯转换：(草稿, 已提交) × 事件 → 下一态。唯一判定点，无副作用、可单测。 */
fun reduce(form: SearchForm, intent: SearchIntent): SearchReduction {
    // 1. 只改草稿的事件：不发请求、不校验筛选。
    reduceDraft(form, intent)?.let { return SearchReduction(it) }

    // 2. 得到候选已提交条件；草稿按事件规则决定。
    val old = form.applied
    val candidate: SearchCriteria
    var draft = form.draftQuery

    when (intent) {
        is SearchIntent.QueryChanged,
        is SearchIntent.FiltersOpened,
        is SearchIntent.FiltersDismissed,
        is SearchIntent.DraftTagRemoved,
        is SearchIntent.DraftWordPicked,
        is SearchIntent.DraftDimensionToggled,
        is SearchIntent.DraftAirtimeChanged,
        is SearchIntent.DraftCleared,
        -> error("草稿事件已在 reduceDraft 处理")

        is SearchIntent.SearchSubmitted -> {
            candidate = old.copy(query = form.draftQuery.trim())
        }

        is SearchIntent.QueryCleared -> {
            candidate = old.copy(query = "")
            draft = ""
        }

        is SearchIntent.TypeChanged -> {
            candidate = old.withSubjectType(intent.type)
        }

        is SearchIntent.SortChanged -> {
            candidate = old.copy(sort = intent.sort)
        }

        is SearchIntent.FiltersSaved -> {
            val draft = form.filterDraft ?: return SearchReduction(form)
            candidate = old.copy(filters = draft)
        }

        is SearchIntent.HistoryClicked -> {
            candidate = old.copy(query = intent.keyword.trim())
            draft = intent.keyword
        }
    }

    // 3. 统一规范化/校验；非法候选保留旧 form，只回错误。
    val normalized = when (val result = normalizeSearchCriteria(candidate)) {
        is SearchValidation.Invalid -> {
            val kept = if (intent is SearchIntent.FiltersSaved) form.copy(filterDraft = null) else form
            return SearchReduction(form = kept, errors = result.errors)
        }
        is SearchValidation.Valid -> result.value
    }

    val changed = normalized != old
    val submit = when (intent) {
        is SearchIntent.SearchSubmitted, is SearchIntent.HistoryClicked -> true
        else -> changed
    }
    val writeHistory = intent is SearchIntent.SearchSubmitted && normalized.query.isNotBlank()

    return SearchReduction(
        form = SearchForm(
            draftQuery = draft,
            applied = normalized,
            filterDraft = if (intent is SearchIntent.FiltersSaved) null else form.filterDraft,
            // 名字仍然有效当且仅当条件一个字节都没变（含关键词、类型、排序、筛选）。
            // 探索页进来的「近期热门」「排行榜」因此一点就被改掉，不会挂在别的条件上冒充。
            // 这一处是唯一判定点；已固定组合的名字不走这里，由界面按 presetKey 查回。
            title = form.title.takeIf { normalized == old },
        ),
        submit = submit,
        writeHistory = writeHistory,
    )
}

/** 只改草稿（输入框 / 筛选面板）的事件；其他事件返回 null。 */
private fun reduceDraft(form: SearchForm, intent: SearchIntent): SearchForm? {
    val draft = form.filterDraft
    return when (intent) {
        is SearchIntent.QueryChanged -> form.copy(draftQuery = intent.value)
        is SearchIntent.FiltersOpened -> form.copy(filterDraft = form.applied.filters)
        is SearchIntent.FiltersDismissed -> form.copy(filterDraft = null)
        is SearchIntent.DraftTagRemoved ->
            draft?.let { form.copy(filterDraft = it.copy(tags = it.tags - intent.tag)) } ?: form
        is SearchIntent.DraftWordPicked ->
            draft?.let {
                // 词落到哪里、是否已选、切换还是只加，全由 core:model 的 pickWord 决定；这里不另判重。
                form.copy(filterDraft = form.applied.copy(filters = it).pickWord(intent.word, intent.pick).filters)
            } ?: form

        is SearchIntent.DraftDimensionToggled ->
            draft?.let {
                form.copy(filterDraft = form.applied.copy(filters = it).toggleDimension(intent.dimension, intent.option).filters)
            } ?: form

        is SearchIntent.DraftAirtimeChanged ->
            draft?.let { form.copy(filterDraft = it.copy(airtime = intent.airtime)) } ?: form
        is SearchIntent.DraftCleared -> draft?.let { form.copy(filterDraft = SearchFilters()) } ?: form
        else -> null
    }
}
