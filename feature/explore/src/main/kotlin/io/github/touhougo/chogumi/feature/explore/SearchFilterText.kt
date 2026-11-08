package io.github.touhougo.chogumi.feature.explore

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.model.SearchAirtime
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SearchDimensionOption
import io.github.touhougo.chogumi.core.model.SearchInputError
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.TagFilter
import io.github.touhougo.chogumi.core.model.concreteOrNull
import io.github.touhougo.chogumi.core.model.isSingleYear
import io.github.touhougo.chogumi.core.model.isThemeWord
import io.github.touhougo.chogumi.core.model.searchDimensionsFor
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.labelRes
import io.github.touhougo.chogumi.core.ui.composables.searchDimensionTitleRes

/**
 * 枚举与条件 → 资源的**唯一**映射点。其余 UI 不得再写第二份 when。
 *
 * 维度标题的映射在 `core:ui` 的 `io.github.touhougo.chogumi.core.ui.composables.searchDimensionTitleRes`
 * （进度页与搜索页共用那一份，这里不重复导出）。
 */

/** 排序标签与网页「分类浏览」排序条原文一致。在标签浏览下 COLLECTS 显示为「标注数」。 */
fun SearchSort.labelRes(isTagBrowse: Boolean = false): Int = when (this) {
    SearchSort.RANK -> R.string.search_sort_rank
    SearchSort.TRENDS -> R.string.search_sort_trends
    SearchSort.COLLECTS -> if (isTagBrowse) R.string.search_sort_tagged else R.string.search_sort_collects
    SearchSort.DATE -> R.string.search_sort_date
    SearchSort.TITLE -> R.string.search_sort_title
}

fun SearchInputError.messageRes(): Int = when (this) {
    SearchInputError.OUT_OF_RANGE -> R.string.search_out_of_range
    SearchInputError.EMPTY_TAG -> R.string.search_empty_tag
    SearchInputError.EXCLUDED_PUBLIC_TAG -> R.string.search_excluded_tag
    SearchInputError.UNKNOWN_TYPE -> R.string.search_unknown_type
    SearchInputError.UNKNOWN_DIMENSION_VALUE -> R.string.search_unknown_dimension_value
}

/** 已生效条件的一条（图钉新建货架的默认名逐条取它的文字 [conditionText]，界面不自己拼）。 */
sealed interface ActiveCondition {
    /** 一个维度取值。 */
    data class Dimension(val dimension: SearchDimension, val option: SearchDimensionOption) : ActiveCondition

    /** 一个标签条件。 */
    data class Tag(val tag: TagFilter) : ActiveCondition

    /** 时间条件（读数由 [airtimeReadout] 给出）。 */
    data object Time : ActiveCondition
}

/**
 * 唯一判定点：已提交条件 → 逐条条件。顺序：先维度（按词典维度顺序、组内取值顺序），
 * 再标签（按草稿顺序），最后时间。
 */
fun SearchCriteria.activeConditions(): List<ActiveCondition> {
    val dimensions = searchDimensionsFor(subjectType).flatMap { group ->
        val chosen = filters.dimensions[group.dimension].orEmpty().toSet()
        group.options.filter { it in chosen }.map { ActiveCondition.Dimension(group.dimension, it) }
    }
    return dimensions +
        filters.tags.map { ActiveCondition.Tag(it) } +
        listOfNotNull(filters.airtime?.let { ActiveCondition.Time })
}

/**
 * 已选标签的叫法（经 [tagSummary]：条件文字与面板「其他已选」的朗读共用）：题材区里的词标「题材」，其余一律标「标签」。
 * 落不进维度的「分类」标签（单选维度的第二个值、词典之外的 meta_tag）也叫「标签」——
 * 它恰恰没能放进任何维度，不能冒用维度的叫法；用户也不需要区分公共标签与用户标签。
 */
fun SearchCriteria.conditionTitleRes(tag: TagFilter): Int =
    if (tag.isThemeWord(subjectType)) R.string.search_condition_theme else R.string.search_dim_tag

/**
 * 时间读数（唯一映射）：不限 / 2019 年 / 2019 年 3 月 / 2015–2020 年 / 2015 年及以后 / 2015 年及以前 / 1990 年代。
 * 面板时间区的标题读数与条件文字「时间：…」都从这一份取。
 */
@Composable
fun SearchAirtime?.airtimeReadout(): String = when {
    this == null -> stringResource(R.string.search_time_any)
    isSingleYear() && month != null ->
        stringResource(R.string.search_time_year_month, startYear!!, month!!)

    isSingleYear() -> stringResource(R.string.search_time_year, startYear!!)
    startYear == null -> stringResource(R.string.search_time_until, endYear!!)
    endYear == null -> stringResource(R.string.search_time_from, startYear!!)
    isDecade() -> stringResource(R.string.search_time_decade, startYear!!)
    else -> stringResource(R.string.search_time_range, startYear!!, endYear!!)
}

/** 整十年代：1990–1999 读作「1990 年代」。 */
private fun SearchAirtime.isDecade(): Boolean {
    val start = startYear ?: return false
    val end = endYear ?: return false
    return start % 10 == 0 && end == start + 9
}

/** 一条已生效条件的读法：「题材：奇幻」「形式：TV」「标签：菅野洋子」「时间：1990 年代」。 */
@Composable
fun SearchCriteria.conditionText(condition: ActiveCondition): String = when (condition) {
    is ActiveCondition.Dimension ->
        stringResource(
            R.string.search_condition_labeled,
            stringResource(searchDimensionTitleRes(subjectType, condition.dimension)),
            condition.option.label,
        )

    is ActiveCondition.Tag -> tagSummary(this, condition.tag)
    is ActiveCondition.Time -> stringResource(
        R.string.search_condition_labeled,
        stringResource(R.string.search_time),
        filters.airtime.airtimeReadout(),
    )
}

/** 标签摘要：「题材：值」/「标签：值」。叫法只由 [conditionTitleRes] 决定。 */
@Composable
fun tagSummary(criteria: SearchCriteria, tag: TagFilter): String =
    stringResource(R.string.search_condition_labeled, stringResource(criteria.conditionTitleRes(tag)), tag.value)

/** 已生效条件数：维度取值数 + 时间 + 标签数。排序不算筛选条件。筛选按钮与搜索框提示共用这一个数。 */
internal fun SearchCriteria.activeConditionCount(): Int =
    filters.dimensions.values.sumOf { it.size } + (if (filters.airtime != null) 1 else 0) + filters.tags.size

/**
 * 搜索框提示：[text] 整句；[value] 是句中可缩短的那一段（条件的取值），放不下一行时只缩它（见 ContainedSearchField）。
 */
data class SearchHint(val text: String, val value: String? = null)

/**
 * 搜索框提示文字（唯一判定点）：说明当前在什么范围内搜索。M3（input.md「Hinted search text」）：一句简短的说明，单行。
 * - 恰好一个条件（维度取值或标签）→「在「X」下搜索」：只写取值，不写维度名——「在连载状态「连载中」下搜索」念起来重复，
 *   取值本身已能看懂；
 * - 两个及以上条件 →「在 N 项条件下搜索」，N 与筛选按钮上的数量同源（[activeConditionCount]）；
 * - 其余（无条件或只有时间）→ 跟随条目类型：选了类型为「搜索动画」等，「全部」为默认写法。
 */
@Composable
fun SearchCriteria.searchHint(): SearchHint {
    val count = activeConditionCount()
    val single = filters.dimensions.entries.singleOrNull()?.value?.singleOrNull()?.label
        ?: filters.tags.singleOrNull()?.value
    return when {
        count >= 2 -> SearchHint(stringResource(R.string.search_hint_multi_tag, count))
        single != null -> SearchHint(stringResource(R.string.search_hint_single, single), value = single)
        else -> SearchHint(
            when (val type = subjectType.concreteOrNull()) {
                null -> stringResource(R.string.search_hint)
                else -> stringResource(R.string.search_hint_typed, stringResource(type.labelRes()))
            },
        )
    }
}
