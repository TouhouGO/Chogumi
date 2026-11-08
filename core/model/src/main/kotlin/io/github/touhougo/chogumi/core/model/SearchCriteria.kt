package io.github.touhougo.chogumi.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray

/**
 * 排序：与 Bangumi 网页「分类浏览」一致的五种，顺序同网页排序条（排名 / 热度 / 收藏 / 日期 / 名称）。
 * 哪几种当前可用由 [availableSorts] 决定；依据 2026-09-27 实测。
 */
@Serializable
enum class SearchSort { RANK, TRENDS, COLLECTS, DATE, TITLE }

/** 结果来自哪个接口。 */
enum class SearchSource {
    /** 网页「分类浏览」同源接口（p1 /p1/subjects）：五种排序齐全，只能按分类（meta 标签）、年月筛。 */
    BROWSE,

    /**
     * 搜索接口（p1 /p1/search/subjects）：支持关键词与用户标签，排序只有排名、收藏。
     * 与 v0 搜索读同一个索引——同条件下结果、顺序、总数、NSFW 处理逐条一致（2026-10-16 实测 35 组条件），
     * 只回短行（20 条约 14 KB，v0 是 46–188 KB），一页最多 100 条。
     */
    SEARCH,

    /**
     * 带时间条件的搜索（v0 /v0/search/subjects）：p1 搜索的日期条件在服务端恒为 0 条
     * （2026-10-16 实测 `>=`、`<=`、单边、双边都一样），只有这一路能按日期区间筛。
     */
    DATED_SEARCH,
}

/**
 * 完整搜索条件。查询/类型/排序/附加筛选可任意组合。
 * [sort] 是用户选的排序，始终保留；当前接口不支持时按 [effectiveSort] 临时退回「收藏」，选择本身不丢。
 *
 * @Serializable：带条件进搜索页的路由参数，也随自建作品货架（[WorkShelfSource.Custom]）存进探索页布局。
 */
@Serializable
data class SearchCriteria(
    val query: String = "",
    val subjectType: SubjectType? = null,
    val sort: SearchSort = SearchSort.COLLECTS,
    val filters: SearchFilters = SearchFilters(),
)

/**
 * 一组条件的唯一判等依据：同一组条件在探索页上只有一条货架，图钉的选中态、对着的货架都靠它查（[pinnedShelfOf]）。
 *
 * 组成：类型 + 各维度取值（维度按名、取值按 metaTag 排序）+ 标签（按 [stableKey] 排序）+ 时间 +
 * [effectiveSort]。**不含关键词**——关键词是即时的输入，不是组合的一部分；因此这里先在
 * `query = ""` 的副本上取生效排序，让同一个组合不因搜索框里打了什么字而变成两个 key。
 *
 * 用 JSON 数组编码而不是手拼分隔符：标签值来自用户输入，含分隔符的输入会让两条不同的条件
 * 编成同一个 key（JSON 转义天然排除这种情况）。
 */
fun SearchCriteria.presetKey(): String {
    val canonical = copy(query = "")
    return buildJsonArray {
        add(canonical.subjectType?.name ?: ALL_TYPES_KEY)
        addJsonArray {
            canonical.filters.dimensions.entries.sortedBy { it.key.name }.forEach { (dimension, options) ->
                addJsonArray {
                    add(dimension.name)
                    options.map { it.metaTag }.sorted().forEach { add(it) }
                }
            }
        }
        addJsonArray {
            canonical.filters.tags.map { it.stableKey() }.sorted().forEach { add(it) }
        }
        add(
            canonical.filters.airtime?.let { airtime ->
                buildJsonArray {
                    add(airtime.startYear)
                    add(airtime.endYear)
                    add(airtime.month)
                }.toString()
            } ?: "",
        )
        add(canonical.effectiveSort().name)
    }.toString()
}

/** 「全部」类型在组合键里的写法（null 不是 JSON 原始值，用中性标记占位）。 */
private const val ALL_TYPES_KEY = "ALL"

/** 组合名的连接符：一组条件念成「A × B」。 */
private const val COMBINATION_SEPARATOR = " × "

/** 一组条件的名字：前 3 个条件文字连成的 [joined]；条件超过 3 个时 [more] 为真，界面在后面接「等」。 */
data class CombinationTitle(val joined: String, val more: Boolean)

/**
 * **全 App 唯一**的「一组条件叫什么」规则：把各条件文字用 [COMBINATION_SEPARATOR] 连起来，
 * 超过 3 个只取前 3 个（[CombinationTitle.more]，「等」字由界面按语言加）。
 *
 * 使用方：搜索页图钉新建的货架没起名字时的默认名（界面用 `conditionText` 取已解析好的条件文字，
 * 与筛选面板里显示的字一模一样）。
 *
 * 本函数只做连接规则，不认识任何资源。空列表返回空串是死分支（图钉只在至少一个条件时可用），不做兜底。
 */
fun combinationTitle(parts: List<String>): CombinationTitle =
    CombinationTitle(joined = parts.take(3).joinToString(COMBINATION_SEPARATOR), more = parts.size > 3)


/**
 * 规范化 + 校验唯一判定点。trim query，消费 normalizeSearchFilters；
 * UNKNOWN 类型返回 Invalid，不生成 type=0 请求；
 * 维度选择不在当前类型词典内（含「全部」类型下有任何维度选择）返回 Invalid，不静默丢弃。
 */
fun normalizeSearchCriteria(criteria: SearchCriteria): SearchValidation<SearchCriteria> {
    if (criteria.subjectType == SubjectType.UNKNOWN) {
        return SearchValidation.Invalid(listOf(SearchValidationError(SearchInputError.UNKNOWN_TYPE)))
    }

    if (!dimensionSelectionsValid(criteria.subjectType, criteria.filters.dimensions)) {
        return SearchValidation.Invalid(listOf(SearchValidationError(SearchInputError.UNKNOWN_DIMENSION_VALUE)))
    }

    val normalizedQuery = criteria.query.trim()
    return when (val filters = normalizeSearchFilters(criteria.filters)) {
        is SearchValidation.Invalid -> SearchValidation.Invalid(filters.errors)
        is SearchValidation.Valid -> SearchValidation.Valid(
            criteria.copy(query = normalizedQuery, filters = filters.value),
        )
    }
}

/** 唯一判定点：具体条目类型。「全部」（null）与无效类型（UNKNOWN）都视为没有类型。 */
fun SubjectType?.concreteOrNull(): SubjectType? = this?.takeIf { it != SubjectType.UNKNOWN }

/** 是否有可执行搜索：关键词、有效具体类型、附加筛选任一成立。排序本身不构成搜索。 */
fun SearchCriteria.isActive(): Boolean =
    query.isNotBlank() ||
        subjectType.concreteOrNull() != null ||
        filters.hasAdditionalFilters()

/**
 * 唯一判定点：这组条件能不能用图钉放上探索页（成一条作品货架，见 [pinned]）。
 *
 * 关键词为空、有具体类型、且至少有一个附加筛选（维度取值 / 标签 / 时间）。
 * 类型必须具体：货架在某个分类的探索页上，「全部」没有探索页。类型与排序本身不算条件——
 * 只按类型固定出来的货架就是一张「全部动画」，等于把探索页变成重复的分类页。
 */
fun SearchCriteria.isPinnable(): Boolean =
    query.isBlank() && subjectType.concreteOrNull() != null && filters.hasAdditionalFilters()

/**
 * 是否走单标签浏览：无关键词 + 具体类型 + 恰好 1 个用户标签 + 无维度且无 PUBLIC 标签（时间不限或单独一年）。
 * 年份范围排除在外——分类浏览接口只收 year/month，范围只有搜索接口能查。
 * 依据 2026-09-27 实测，对齐主站标签页数据源。
 */
fun SearchCriteria.isTagBrowse(): Boolean {
    val typed = subjectType.concreteOrNull() != null
    val userTagsCount = filters.tags.count { it.source() == TagSource.USER }
    val hasPublicTag = filters.tags.any { it.source() == TagSource.PUBLIC }
    val hasDimensions = filters.dimensions.isNotEmpty()
    return query.isBlank() && typed && userTagsCount == 1 && !hasDimensions && !hasPublicTag && !filters.airtime.isRange()
}

/**
 * 是否走分类浏览：无关键词 + 具体类型 + 无用户标签（时间不限或单独一年）+ 分类都在该类型的词典里。年份范围同样排除。
 * 分类浏览接口只认站点自己的分类：词典外的分类（详情页分类行透传来的「Galgame」「独立游戏」之类）无论登不登录都是 0 条
 * （2026-10-18 实测），这种条件只走搜索。
 */
fun SearchCriteria.isCategoryBrowse(): Boolean {
    val typed = subjectType.concreteOrNull() != null
    val hasUserTag = filters.tags.any { it.source() == TagSource.USER }
    val hasUnlistedCategory = filters.tags.any { it is TagFilter.Public && searchDimensionOptionFor(subjectType, it.value) == null }
    return query.isBlank() && typed && !hasUserTag && !hasUnlistedCategory && !filters.airtime.isRange()
}

/** 是否可浏览（分类浏览或单用户标签浏览）：决定排序集合，也是接口分流的必要条件之一。 */
private fun SearchCriteria.isBrowsable(): Boolean = isCategoryBrowse() || isTagBrowse()

/**
 * 只有这三种排序必须留在 p1 浏览：热度、日期、名称（两个搜索接口都没有）。
 * 排名与标注数（COLLECTS → 搜索的 heat）走搜索：一页最多 100 条，NSFW 按请求里的 nsfw 条件在服务端筛，
 * 还能与用户标签、多个分类组合。
 */
private val BROWSE_SORTS = setOf(SearchSort.TRENDS, SearchSort.DATE, SearchSort.TITLE)

/**
 * 接口选择的唯一判定点：可浏览且生效排序需要 p1 才有的排序时走 BROWSE（p1 /p1/subjects）；其余走搜索，
 * 带时间条件的走 DATED_SEARCH（v0），不带的走 SEARCH（p1 搜索）。
 * 用户标签≥2 个或与分类混用时走搜索（分类浏览接口不能同时按分类与用户标签筛、多个用户标签也不成「且」，实测）。
 *
 * 依赖链单向：searchSource → effectiveSort → availableSorts → isCategoryBrowse/isTagBrowse，不构成循环。
 * 标签浏览选「热度」时 availableSorts 无热度 → effectiveSort 回落 COLLECTS → 走搜索，属预期。
 */
fun SearchCriteria.searchSource(): SearchSource = when {
    isBrowsable() && effectiveSort() in BROWSE_SORTS -> SearchSource.BROWSE
    filters.airtime != null -> SearchSource.DATED_SEARCH
    else -> SearchSource.SEARCH
}

/**
 * 当前可点的排序（排序区的唯一依据，为空时排序区不显示）：
 * - 没有可执行搜索（空态）：空；
 * - 有关键词：空（结果固定按收藏排）；
 * - 标签浏览：排名、收藏（标注数）、日期、名称（主站标签页无热度排序）；
 * - 分类浏览：五种全部；
 * - 其余走搜索接口：排名、收藏（搜索接口没有热度、日期、名称）。
 *
 * 只依据「是否可浏览」，不看 [searchSource]（后者又要靠 [effectiveSort] 反查本函数）。
 * 用户可选集合不因分流而变：分类浏览 5 种、标签浏览 4 种、其余 2 种。
 *
 * 排行榜与按排名排序永远可用，不受模式或评分开关影响。
 */
fun SearchCriteria.availableSorts(): Set<SearchSort> = when {
    !isActive() || query.isNotBlank() -> emptySet()
    isTagBrowse() -> setOf(SearchSort.RANK, SearchSort.COLLECTS, SearchSort.DATE, SearchSort.TITLE)
    isCategoryBrowse() -> SearchSort.entries.toSet()
    else -> setOf(SearchSort.RANK, SearchSort.COLLECTS)
}

/**
 * 实际生效排序（界面选中态与请求共用）：选中的当前可用就用它，否则按收藏。
 */
fun SearchCriteria.effectiveSort(): SearchSort =
    sort.takeIf { it in availableSorts() } ?: SearchSort.COLLECTS


