package io.github.touhougo.chogumi.core.ui.common

import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull

/**
 * 详情页一个内容区块此刻该呈现什么。只有 [EMPTY]（加载完成、没出错、确实没有内容）整块隐藏：
 * 加载中隐藏会让页面先空后跳，失败隐藏会吞掉错误、用户无从重试。
 */
enum class SectionState { LOADING, ERROR, EMPTY, CONTENT }

/**
 * 区块状态的唯一判定。有内容优先（后台刷新或重试失败时保留已有内容）。
 * 只给下面几个重载用：页面的区块是一个 [Load] 或 [PagedList]，不拼「数据 + 加载中 + 错误」三件套。
 */
@PublishedApi
internal fun sectionStateOf(
    isEmpty: Boolean,
    isLoading: Boolean = false,
    error: Failure? = null,
): SectionState = when {
    !isEmpty -> SectionState.CONTENT
    isLoading -> SectionState.LOADING
    error != null -> SectionState.ERROR
    else -> SectionState.EMPTY
}

/** 一拍 [Load] 的区块：就绪但没有可显示的内容（[isEmpty]）才是 [SectionState.EMPTY]。判定仍是上面那一个。 */
inline fun <T> sectionStateOf(load: Load<T>, isEmpty: (T) -> Boolean): SectionState = sectionStateOf(
    isEmpty = load.valueOrNull()?.let(isEmpty) ?: true,
    isLoading = load is Load.Pending,
    error = load.failureOrNull(),
)

/** 列表区块：就绪但列表为空才是 [SectionState.EMPTY]。 */
fun sectionStateOf(load: Load<List<*>>): SectionState = sectionStateOf(load) { it.isEmpty() }

/**
 * 分页列表区块：空态只在 [PagedList.isConfirmedEmpty] 时成立。过滤让一页没有可见条目、但还有下一页时
 * 是 [SectionState.CONTENT]（零行），列表照常往下画、页脚继续续取，不先说「没有」再冒出条目。
 */
fun sectionStateOf(list: PagedList<*>): SectionState =
    sectionStateOf(isEmpty = list.items.isEmpty() && !list.hasMore, isLoading = list.isLoading, error = list.error)
