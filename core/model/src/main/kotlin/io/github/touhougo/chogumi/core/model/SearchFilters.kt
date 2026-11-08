package io.github.touhougo.chogumi.core.model

import kotlinx.serialization.Serializable

/**
 * 放送/发售时间条件：一个年份范围 [startYear, endYear]（两端都可空 = 该端不限），
 * 以及只在起止为同一年时才有意义的 [month]。
 *
 * - 单独一年 = 起止相同（可带月份），分类浏览接口（p1）收 year/month；
 * - 范围（一端不限或起止不同）= 只有搜索接口（v0）能查，换算成 air_date 日期区间；
 * - 两端都空不是合法状态（那是「不限」，用 filters 里的 null 表示），见 [validateSearchAirtime]。
 */
@Serializable
data class SearchAirtime(val startYear: Int? = null, val endYear: Int? = null, val month: Int? = null)

/** 时间条件的取值边界（唯一真源）。 */
internal const val SEARCH_YEAR_MIN = 1
internal const val SEARCH_YEAR_MAX = 9999

/**
 * 唯一判定点：时间条件是不是单独一年（可带月份）。null（不限）与年份范围都返回 false。
 * [isTagBrowse] / [isCategoryBrowse] 经它排除范围——范围只有搜索接口能查。
 */
fun SearchAirtime?.isSingleYear(): Boolean = this != null && startYear != null && startYear == endYear

/** 唯一判定点：是不是年份范围。null（不限）不算范围。 */
fun SearchAirtime?.isRange(): Boolean = this != null && !isSingleYear()

/**
 * 唯一判定点：滑块两端 → 时间条件。两端都空即「不限」（返回 null）；
 * 新的起止仍是同一年、且与原来的年份相同时保留原月份，其余一律清掉月份（范围不能带月份）。
 */
fun SearchAirtime?.withYears(startYear: Int?, endYear: Int?): SearchAirtime? {
    if (startYear == null && endYear == null) return null
    val month = this?.month?.takeIf { startYear == endYear && this.startYear == startYear }
    return SearchAirtime(startYear, endYear, month)
}

/**
 * 搜索附加条件。标签带来源；[airtime] 为 null 表示不限时间。
 * [dimensions] 是分类浏览维度的唯一选择状态（每维一组值，单维可多可少由 [dimensionSelectionMode] 决定）；
 * 请求用的 meta_tags 只由 [dimensionMetaTags] 从它派生，不在 [tags] 里另存一份。
 *
 * @Serializable：随 [SearchCriteria] 进路由、进探索页布局的本地表（条件按 kotlinx JSON 整存）。
 */
@Serializable
data class SearchFilters(
    val dimensions: Map<SearchDimension, List<SearchDimensionOption>> = emptyMap(),
    val tags: List<TagFilter> = emptyList(),
    val airtime: SearchAirtime? = null,
)

/**
 * 时间条件校验的唯一判定点：
 * 年份在 [SEARCH_YEAR_MIN, SEARCH_YEAR_MAX]；两端不能都空；起 ≤ 止；
 * 月份只在起止为同一年时合法（类型上不强制，故这里必须拦）。不夹取。
 */
internal fun validateSearchAirtime(airtime: SearchAirtime?): SearchValidation<SearchAirtime?> {
    if (airtime == null) return SearchValidation.Valid(null)
    val start = airtime.startYear
    val end = airtime.endYear
    val yearOk = listOfNotNull(start, end).all { it in SEARCH_YEAR_MIN..SEARCH_YEAR_MAX }
    val notBothNull = start != null || end != null
    val ordered = start == null || end == null || start <= end
    val monthOk = airtime.month?.let { it in 1..12 && start != null && start == end } ?: true
    return if (yearOk && notBothNull && ordered && monthOk) {
        SearchValidation.Valid(airtime)
    } else {
        SearchValidation.Invalid(listOf(SearchValidationError(SearchInputError.OUT_OF_RANGE)))
    }
}

/**
 * 标签规范化的唯一判定点：trim 后校验，按来源（PUBLIC 在 USER 前）、再按值排序，
 * 同来源同值去重。返回新列表，不回连外部可变集合；不 locale 排序、不小写、不按逗号拆。
 */
internal fun normalizeSearchTags(tags: List<TagFilter>): SearchValidation<List<TagFilter>> {
    val errors = mutableListOf<SearchValidationError>()
    val normalized = mutableListOf<TagFilter>()

    for (tag in tags) {
        val trimmed = when (tag) {
            is TagFilter.Public -> TagFilter.Public(tag.value.trim())
            is TagFilter.User -> TagFilter.User(tag.value.trim())
        }
        when {
            trimmed.value.isEmpty() ->
                errors += SearchValidationError(SearchInputError.EMPTY_TAG)

            trimmed is TagFilter.Public && trimmed.value.startsWith("-") ->
                errors += SearchValidationError(SearchInputError.EXCLUDED_PUBLIC_TAG)

            else -> normalized += trimmed
        }
    }

    if (errors.isNotEmpty()) return SearchValidation.Invalid(errors)

    val deduped = normalized
        .distinct()
        .sortedWith(compareBy({ it.source().ordinal }, { it.value }))
    return SearchValidation.Valid(deduped)
}

/** 汇总校验：先规范化标签，再校验时间；不得自动夹取非法值。 */
fun normalizeSearchFilters(filters: SearchFilters): SearchValidation<SearchFilters> {
    val errors = mutableListOf<SearchValidationError>()
    var tags = filters.tags

    when (val tagResult = normalizeSearchTags(filters.tags)) {
        is SearchValidation.Valid -> tags = tagResult.value
        is SearchValidation.Invalid -> errors += tagResult.errors
    }

    when (val airtimeResult = validateSearchAirtime(filters.airtime)) {
        is SearchValidation.Valid -> Unit
        is SearchValidation.Invalid -> errors += airtimeResult.errors
    }

    return if (errors.isEmpty()) {
        SearchValidation.Valid(filters.copy(tags = tags))
    } else {
        SearchValidation.Invalid(errors)
    }
}

/** 是否有附加筛选。 */
fun SearchFilters.hasAdditionalFilters(): Boolean =
    dimensions.isNotEmpty() || tags.isNotEmpty() || airtime != null
