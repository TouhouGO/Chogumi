package io.github.touhougo.chogumi.core.model

/**
 * 搜索页右下角 FAB 的搜索范围：作品（含「全部」与五种具体作品类型）、角色、人物。
 *
 * 「全部」为 `Subjects(null)`，只搜五种作品，不含角色与人物：三者对应不同的服务端索引与卡片形态。
 * 角色与人物当前只支持纯关键词搜索，不进入 [SearchCriteria]。
 */
sealed interface SearchScope {
    data class Subjects(val type: SubjectType?) : SearchScope
    data object Character : SearchScope
    data object Person : SearchScope
}

/** 是否处于角色 / 人物范围（纯关键词搜索，无排序条、无筛选面板、无图钉）。 */
val SearchScope.isMono: Boolean get() = this !is SearchScope.Subjects

/** 搜索页 FAB 菜单项顺序：全部 + [ContentDomains]（单一真源）+ 角色 + 人物。 */
val searchScopeOptions: List<SearchScope> =
    listOf(SearchScope.Subjects(null)) +
        ContentDomains.map(SearchScope::Subjects) +
        listOf(SearchScope.Character, SearchScope.Person)
