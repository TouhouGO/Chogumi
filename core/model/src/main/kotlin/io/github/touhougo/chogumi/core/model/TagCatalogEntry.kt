package io.github.touhougo.chogumi.core.model

/**
 * 标签目录中的一项：标签名 + 使用次数 + 在目录中的名次。
 *
 * 只读快照，供筛选面板展示与选择；来源是本地缓存
 * （`core:data` 的 `TagCatalogRepository`），不是网络 DTO。
 */
data class TagCatalogEntry(
    val name: String,
    val count: Int,
    /** 名次，1 起；同名标签跨页去重后取首次出现的名次。 */
    val rank: Int,
)
