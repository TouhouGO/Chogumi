package io.github.touhougo.chogumi.core.model

/**
 * 稳定顺序（纯函数）：数据更新时**只更新内容、不改变位置**。
 *
 * 用户在列表里点了 EP 按钮，条目的 `updatedAt` 立刻变新；若每次数据变化都重新排序，
 * 条目会瞬间跳到顶部。因此列表在「下拉刷新 / 切换筛选或排序 / 重新进入页面」时才
 * 重新排序（调用方传 [previous] = null），其余数据变化都经本函数保持原位。
 *
 * - [previous] 为 null：直接返回 [sorted]（重新排序）；
 * - 已在 [previous] 中的条目按 [previous] 的次序排列；
 * - [previous] 中已不存在的条目被移除；
 * - 新出现的条目按它们在 [sorted] 中的次序放在最前，或在 [newItemsFirst] 为 false 时放在末尾。
 */
fun <T, K> stableOrder(
    previous: List<K>?,
    sorted: List<T>,
    newItemsFirst: Boolean = true,
    key: (T) -> K,
): List<T> {
    if (previous == null) return sorted
    val byKey = sorted.associateBy(key)
    val known = previous.toSet()
    val added = sorted.filter { key(it) !in known }
    val kept = previous.mapNotNull { byKey[it] }
    return if (newItemsFirst) added + kept else kept + added
}
