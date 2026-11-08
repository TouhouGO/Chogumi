package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.model.SearchAirtime
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchSource
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.TagSource
import io.github.touhougo.chogumi.core.model.concreteOrNull
import io.github.touhougo.chogumi.core.model.dimensionMetaTags
import io.github.touhougo.chogumi.core.model.effectiveSort
import io.github.touhougo.chogumi.core.model.isTagBrowse
import io.github.touhougo.chogumi.core.model.source
import io.github.touhougo.chogumi.core.network.model.p1.SearchSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectSearchFilterDto
import io.github.touhougo.chogumi.core.network.model.v0.SearchFilterDto
import io.github.touhougo.chogumi.core.network.model.v0.SearchRequestDto
import java.time.YearMonth

/**
 * 唯一条件 → 请求映射（两个接口都在这里）。调用方必须先 normalize 通过，并按
 * [io.github.touhougo.chogumi.core.model.searchSource] 选定接口；本文件不做业务校验，排序只取 [effectiveSort]。
 * 依据 2026-09-27、2026-09-28 的实测（浏览按排序分流的依据）。
 */

/** 分类与标签浏览（p1 /p1/subjects）的查询参数。页码由仓库按偏移换算，不在这里。 */
internal data class BrowseQuery(
    val type: Int,
    val sort: String,
    val tags: List<String>,
    val year: Int?,
    val month: Int?,
    val tagsCat: String? = null,
)

/**
 * 条件 → 浏览查询（[SearchSource.BROWSE]）。只有热度、日期、名称三种排序走到这里；排名与标注数走搜索接口
 * （不带时间条件走 p1 搜索 [toP1SearchRequestDto]，带时间条件走 v0 搜索 [toSearchRequestDto]）。
 *
 * - 标签浏览（[isTagBrowse]）：tagsCat="subject"，只发这一个用户标签；
 * - 分类浏览：tagsCat=null，meta 标签（维度 + 公共标签）原样发出。
 *
 * 不需要「恰好一个 meta 标签且按收藏排时同一标签发两次」这类服务端缺陷规避：
 * 按收藏排（COLLECTS）一律走搜索接口，p1 浏览走不到这个组合；搜索的 heat 排序无此缺陷
 * （2026-09-28 实测，两个搜索接口读同一个索引）。
 *
 * 排序经 [effectiveSort] 取，与界面选中态同源（同一判定函数，界面不另做筛除）。
 */
internal fun SearchCriteria.toBrowseQuery(): BrowseQuery {
    val type = checkNotNull(subjectType.concreteOrNull()) { "分类浏览必须有具体类型" }
    val sort = effectiveSort()
    val airtime = filters.airtime
    return if (isTagBrowse()) {
        val userTag = filters.tags.single { it.source() == TagSource.USER }.value
        BrowseQuery(
            type = type.value,
            sort = sort.browseValue(),
            tags = listOf(userTag),
            year = airtime?.startYear,
            month = airtime?.month,
            tagsCat = "subject",
        )
    } else {
        BrowseQuery(
            type = type.value,
            sort = sort.browseValue(),
            tags = metaTags(),
            year = airtime?.startYear,
            month = airtime?.month,
            tagsCat = null,
        )
    }
}

/**
 * 条件 → v0 搜索请求体（带时间条件的搜索，[SearchSource.DATED_SEARCH]）。条件由 [searchFilter] 给出，这里只换字段名。
 */
internal fun SearchCriteria.toSearchRequestDto(nsfwEnabled: Boolean): SearchRequestDto = SearchRequestDto(
    keyword = query,
    sort = effectiveSort().searchValue(),
    filter = searchFilter(nsfwEnabled)?.let {
        SearchFilterDto(type = it.type, tag = it.userTags, airDate = it.airDate, metaTags = it.metaTags, rank = it.rank, nsfw = it.nsfw)
    },
)

/**
 * 条件 → p1 搜索请求体（[SearchSource.SEARCH]）。条件由 [searchFilter] 给出，这里只换字段名：
 * 两个搜索接口读同一个索引，条件语义相同（`tags` 即 v0 的 `tag`、`metaTags` 即 `meta_tags`）。
 */
internal fun SearchCriteria.toP1SearchRequestDto(nsfwEnabled: Boolean): SearchSubjectDto = SearchSubjectDto(
    keyword = query,
    sort = effectiveSort().searchValue(),
    filter = searchFilter(nsfwEnabled)?.let {
        SubjectSearchFilterDto(type = it.type, tags = it.userTags, metaTags = it.metaTags, date = it.airDate, rank = it.rank, nsfw = it.nsfw)
    },
)

/** 两个搜索接口共用的一份筛选条件（空列表已省略为 null）。 */
private class SearchFilterParts(
    val type: List<Int>?,
    val metaTags: List<String>?,
    val userTags: List<String>?,
    val airDate: List<String>?,
    val rank: List<String>?,
    val nsfw: Boolean?,
)

/** 维度选择经唯一翻译点得到 meta 标签，再并入路由种子等自由公共标签；同值只发一次。 */
private fun SearchCriteria.metaTags(): List<String> = (
    dimensionMetaTags(subjectType, filters.dimensions) +
        filters.tags.filter { it.source() == TagSource.PUBLIC }.map { it.value }
    ).distinct()

/**
 * 条件 → 搜索的筛选条件（两个搜索接口的唯一映射）。空列表省略为 null；无任何条件时为 null（请求里整个 filter 省略）；
 * offset/limit 不进 body。
 *
 * 分类/标签浏览在排名、标注数排序下也走搜索（此时 keyword 为空串，条件全落在 filter）：
 * meta 维度与公共标签 → meta 标签、用户标签 → 用户标签、年月 → 日期区间、RANK → rank 下限、COLLECTS → heat。
 *
 * 按排名排时附带 `rank >= 1`：否则未上榜条目（rank=0）排在第 1 名之前
 * （2026-09-27 实测）。
 *
 * [nsfwEnabled] 由仓库从 [NsfwFilter] 传入（纯函数，不在此处读状态）：为 false 时显式发 `nsfw:false`，
 * 让服务端直接只返回非 NSFW（结果因此是密集的）；为 true 时不传 nsfw，服务端放行 NSFW。
 */
private fun SearchCriteria.searchFilter(nsfwEnabled: Boolean): SearchFilterParts? {
    val type = subjectType.concreteOrNull()?.let { listOf(it.value) }
    val metaTags = metaTags().ifEmpty { null }
    val userTags = filters.tags
        .filter { it.source() == TagSource.USER }
        .map { it.value }
        .ifEmpty { null }
    val airDate = filters.airtime?.dateRange()
    val rank = if (effectiveSort() == SearchSort.RANK) listOf(">=1") else null
    // 关闭时显式 false（服务端据此剔除 NSFW）；开启时不传，服务端放行。
    val nsfw = if (nsfwEnabled) null else false

    if (type == null && metaTags == null && userTags == null && airDate == null && rank == null && nsfw == null) return null

    return SearchFilterParts(type = type, metaTags = metaTags, userTags = userTags, airDate = airDate, rank = rank, nsfw = nsfw)
}

/**
 * 时间条件 → 搜索接口的日期区间（含首尾两天）。
 * 单独一年：照旧给全年或整月；年份范围：只写有值的那一端（`>=起-01-01`、`<=止-12-31`），
 * 两端都给就是闭区间。范围带不了月份（校验已拦）。
 */
private fun SearchAirtime.dateRange(): List<String> {
    val start = startYear
    val end = endYear
    if (start != null && start == end) {
        val y = start.toString().padStart(4, '0')
        val m = month ?: return listOf(">=$y-01-01", "<=$y-12-31")
        val mm = m.toString().padStart(2, '0')
        val lastDay = YearMonth.of(start, m).lengthOfMonth()
        return listOf(">=$y-$mm-01", "<=$y-$mm-$lastDay")
    }
    return listOfNotNull(
        start?.let { ">=${it.toString().padStart(4, '0')}-01-01" },
        end?.let { "<=${it.toString().padStart(4, '0')}-12-31" },
    )
}

/** 唯一映射点：排序 → 分类浏览接口的 sort 值。 */
private fun SearchSort.browseValue(): String = when (this) {
    SearchSort.RANK -> "rank"
    SearchSort.TRENDS -> "trends"
    SearchSort.COLLECTS -> "collects"
    SearchSort.DATE -> "date"
    SearchSort.TITLE -> "title"
}

/**
 * 唯一映射点：排序 → 搜索接口的 sort 值。搜索接口只有排名与收藏（heat 即收藏人数）；
 * 其余三种由 [io.github.touhougo.chogumi.core.model.availableSorts] 排除，走到这里说明判定点被绕过。
 */
private fun SearchSort.searchValue(): String = when (this) {
    SearchSort.RANK -> "rank"
    SearchSort.COLLECTS -> "heat"
    SearchSort.TRENDS, SearchSort.DATE, SearchSort.TITLE -> error("搜索接口不支持排序 $this")
}
