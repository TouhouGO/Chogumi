package io.github.touhougo.chogumi.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 唯一判定点：标签来源 → 请求筛选条件。按点击所在列表分流，不按标签名。
 * 两个子类的 [SerialName] 是落库的 JSON（探索页布局里自建货架的条件）里的类型标记，写死成稳定名字，改类名、挪包不会让存下来的条件读不出来。
 */
@Serializable
sealed interface TagFilter {
    val value: String

    @SerialName("public")
    @Serializable
    data class Public(override val value: String) : TagFilter   // → filter.meta_tags

    @SerialName("user")
    @Serializable
    data class User(override val value: String) : TagFilter     // → filter.tag
}

/** 唯一判定点：TagFilter → 来源。UI 分组、排序、请求分流都消费它。 */
fun TagFilter.source(): TagSource = when (this) {
    is TagFilter.Public -> TagSource.PUBLIC
    is TagFilter.User -> TagSource.USER
}

/** 唯一判定点：TagFilter → 稳定 key。来源 + 值，跨来源同名不冲突。 */
fun TagFilter.stableKey(): String = "${source().name}:$value"

/** 标签来源。@Serializable（要进 Route 的搜索条件）。 */
@Serializable
enum class TagSource { PUBLIC, USER }

/**
 * 唯一判定点：详情页点一个标签 → 初始搜索条件。空白值返回 null（视为没点中任何标签）。
 * - 带上条目类型（UNKNOWN 视为无类型）；
 * - 分类（PUBLIC）的落位与切换类型同一规则（[withCategoriesPlaced]）：该类型词典里有就落成维度，
 *   筛选面板里能看到同一个勾选；词典里没有的分类与标签作为标签条件原样透传。
 *
 * 调用点是详情页标签行——那里是**唯一**知道「标签来自哪个条目类型」的地方，
 * 所以转换也在那里一次做完，路由只收成品条件。
 */
fun tagSearchCriteria(type: SubjectType?, value: String, source: TagSource): SearchCriteria? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val tag = when (source) {
        TagSource.PUBLIC -> TagFilter.Public(trimmed)
        TagSource.USER -> TagFilter.User(trimmed)
    }
    return SearchCriteria(
        subjectType = type.concreteOrNull(),
        filters = SearchFilters(tags = listOf(tag)),
    ).withCategoriesPlaced()
}
