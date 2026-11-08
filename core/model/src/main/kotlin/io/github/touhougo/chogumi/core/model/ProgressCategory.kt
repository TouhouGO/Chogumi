package io.github.touhougo.chogumi.core.model

/**
 * 进度页「分类」筛选的已选项：**维度 + 取值**。
 *
 * 用二元组而不是单独的 meta_tag 字符串：不同维度可能出现同名取值
 * （2026-09-27 对各维度取值做过歧义实测，
 * 目前实测无重名，但把维度带上是**语义正确**的表示，也让「同维度取并集、跨维度取交集」
 * 有了判定的依据——只看 meta_tag 串无法区分「用户选了同一维度的两项」还是「选了不同维度的两项」，
 * 前者应取并集、后者应取交集）。
 */
data class ProgressCategory(
    val dimension: SearchDimension,
    val option: SearchDimensionOption,
)

/** 「分类」的一个维度与这一批里出现过的取值（按部数从多到少，同部数按词典顺序）。 */
data class ProgressCategoryGroup(val dimension: SearchDimension, val options: List<ProgressFilterOption<ProgressCategory>>)

/**
 * 进度页「分类」组的**可选项**——**唯一判定点**：把搜索结果页的分类词典
 * （[searchDimensionsFor]，唯一真源，不得复制一份清单）收窄到**当前这一批条目里实际出现过**的取值，
 * 每个取值带部数与最近动过的几部（[filterOptionsOf]，与标签同一种格子）。
 *
 * 与「标签」同口径：只列真出现过的取值，某个维度一个取值都没出现就不列这个维度，
 * 没有任何可选项时返回空列表（界面据此整组不渲染）。
 *
 * - [type] 为 null（「全部」页签）或 UNKNOWN 时词典本身为空，返回空列表——各类型词典互不通用，
 *   不存在跨类型的维度（见 [searchDimensionsFor] 的 KDoc）；
 * - 维度顺序沿用词典；维度里的取值按部数从多到少，同部数按词典顺序；
 * - 取值匹配用公共标签原值（[SearchDimensionOption.metaTag]），与条目上的 [SubjectBo.metaTags] 同源
 *   （如地区「中国香港」的 metaTag 是 `香港`）。
 *
 * 传入的应当是**类型 tab + 收藏状态筛过、但未经本面板筛过**的那一批：
 * 若把「分类」「标签」自己也纳入计算，选中一个取值就会让其余取值消失，用户再也取消不掉。
 */
fun availableProgressCategories(
    type: SubjectType?,
    items: List<UserCollectionBo>,
    latestWatchedAt: Map<Int, Long>,
): List<ProgressCategoryGroup> = searchDimensionsFor(type).mapNotNull { group ->
    val options = filterOptionsOf(items, latestWatchedAt) { item ->
        val tags = item.subject?.metaTags.orEmpty()
        group.options.filter { it.metaTag in tags }.map { ProgressCategory(group.dimension, it) }
    }.sortedWith(compareByDescending<ProgressFilterOption<ProgressCategory>> { it.count }.thenBy { group.options.indexOf(it.key.option) })
    if (options.isEmpty()) null else ProgressCategoryGroup(group.dimension, options)
}

/**
 * 筛选面板打开时的样子：先摊开哪个维度（[dimension]，没有分类为 null），我的标签是不是收着、可以收起（[tagsCollapsible]）。
 */
data class ProgressFilterOpening(val dimension: SearchDimension?, val tagsCollapsible: Boolean)

/**
 * 筛选面板打开时的样子——**唯一判定点**：
 * - 先摊开题材类维度（动画、游戏、音乐的 [SearchDimension.GENRE]，书籍、三次元的 [SearchDimension.THEME]），最常用；
 *   都没有就摊开第一个维度。
 * - 有分类时我的标签收着（分类在上面占地方，标签点开再看）；没有分类（「全部」页签，或这一批作品一个词典取值都没有）时
 *   标签就是面板的全部内容，直接摊开、不可收起——收着的话面板里只剩一个标题行。
 */
fun progressFilterOpening(groups: List<ProgressCategoryGroup>): ProgressFilterOpening {
    val dimensions = groups.map { it.dimension }
    val dimension = listOf(SearchDimension.GENRE, SearchDimension.THEME).firstOrNull { it in dimensions } ?: dimensions.firstOrNull()
    return ProgressFilterOpening(dimension, tagsCollapsible = groups.isNotEmpty())
}

/**
 * 进度页「分类」筛选的**唯一判定点**：[selected] 为空表示没有筛选，恒通过。
 *
 * - **同一维度内取并集**：选了某维度的多个取值，命中任一即该维度满足；
 * - **不同维度之间取交集**：所有出现过的维度都必须满足；
 * - 返回 false 的两种情形：某维度一个取值都没命中，或某维度在条目上根本没有对应标签
 *   （等同于「满足不了」）。
 *
 * 判定按维度分组进行，**不是**「条目标签命中任一已选取值就通过」——否则跨维度会退化成并集，
 * 选中「科幻」+「日本」时，只满足其中一个的作品也会通过。
 */
fun matchesCategoryFilter(metaTags: List<String>, selected: Set<ProgressCategory>): Boolean {
    if (selected.isEmpty()) return true
    val tags = metaTags.toSet()
    return selected.groupBy { it.dimension }.values.all { dimensionSelection ->
        dimensionSelection.any { it.option.metaTag in tags }
    }
}
