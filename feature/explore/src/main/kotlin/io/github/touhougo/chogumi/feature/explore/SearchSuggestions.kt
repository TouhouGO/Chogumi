package io.github.touhougo.chogumi.feature.explore

import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.data.util.storageOutcomeOf
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

import io.github.touhougo.chogumi.core.data.repository.TagCatalogRepository
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagCatalogEntry
import io.github.touhougo.chogumi.core.model.TagInputResolution
import io.github.touhougo.chogumi.core.model.resolveTagInput
import io.github.touhougo.chogumi.core.model.searchDimensionsFor
import io.github.touhougo.chogumi.core.model.concreteOrNull
import io.github.touhougo.chogumi.core.model.themeWordsFor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest

internal fun CoroutineScope.refreshCatalog(
    catalog: TagCatalogRepository,
    type: SubjectType,
    failures: FailureNotices,
    isCurrent: () -> Boolean,
) {
    if (!isCurrent()) return
    val owner = this
    launch {
        val failure = catalog.refreshIfNeeded(type).failureOrNull()
        if (failure != null && isCurrent()) {
            failures.post(failure, retry = { owner.refreshCatalog(catalog, type, failures, isCurrent) })
        }
    }
}

/**
 * 筛选面板搜索框的一条建议。
 *
 * [Catalog] 来自标签目录（使用次数由 [io.github.touhougo.chogumi.core.model.formatAbbreviatedCount] 渲染），点它和点别的行一样走
 * `pickWord(…, WordPick.ADD)`——落成维度还是标签由 `resolveTagInput` 决定，界面不另判。
 */
internal sealed interface FilterSuggestion {
    val label: String

    data class Dimension(val dimension: SearchDimension, override val label: String) : FilterSuggestion
    data class Word(override val label: String) : FilterSuggestion
    data class Catalog(override val label: String, val count: Int) : FilterSuggestion
}

/** 建议条数上限（末行「按『输入的词』筛选」另计）。 */
internal const val SUGGESTION_LIMIT = 8

/**
 * 唯一合成点：面板搜索框的一次输入 → 建议列表。输入为空返回 null（不列建议）。
 *
 * 顺序与取舍：
 * 1. 本地的维度取值与题材词（`SearchDimensionDictionary` / 题材词表）；
 * 2. 目录结果（[catalog]，已在仓库层按当前 NSFW 开关过滤过）——**跳过 `resolveTagInput`
 *    会落到维度上的词**：那种词上一步已经以维度取值的身份出现过，再列一遍是同一条建议两种
 *    去处；也跳过与前面同名的词（同一 [label] 只留先出现的那条）。
 * 3. 总数上限 [SUGGESTION_LIMIT]，末行的自由词不计入上限。
 *
 * 本地部分按词典顺序，目录部分按仓库给的顺序（使用次数倒序），不重排。
 */
internal fun filterSuggestions(
    subjectType: SubjectType?,
    raw: String,
    catalog: List<TagCatalogEntry> = emptyList(),
): List<FilterSuggestion>? {
    val text = raw.trim()
    if (text.isEmpty()) return null

    val rows = mutableListOf<FilterSuggestion>()
    for (group in searchDimensionsFor(subjectType)) {
        group.options.filter { it.label.contains(text, ignoreCase = true) }
            .forEach { rows += FilterSuggestion.Dimension(group.dimension, it.label) }
    }
    themeWordsFor(subjectType).filter { it.contains(text, ignoreCase = true) }.forEach { word ->
        // 落在维度上的题材词已由上面收过（同一 label），这里只补自由词。
        if (resolveTagInput(subjectType, word) is TagInputResolution.UserTag) {
            rows += FilterSuggestion.Word(word)
        }
    }
    catalog.forEach { entry ->
        val alreadyListed = rows.any { it.label == entry.name }
        val landsOnDimension = resolveTagInput(subjectType, entry.name) is TagInputResolution.Dimension
        if (!alreadyListed && !landsOnDimension) {
            rows += FilterSuggestion.Catalog(entry.name, entry.count)
        }
    }

    val exact = rows.any { it.label.equals(text, ignoreCase = true) }
    val out = rows.take(SUGGESTION_LIMIT).toMutableList()
    if (!exact) out += FilterSuggestion.Word(text)
    return out
}

/** 面板建议里目录结果的取数上限：建议总共才 [SUGGESTION_LIMIT] 条，多取的都被合成函数丢掉。 */
private const val CATALOG_SUGGESTION_LIMIT = SUGGESTION_LIMIT

/**
 * 面板搜索框 → 标签目录的建议（唯一实现：搜索页与探索页货架编辑的筛选面板共用）。
 * [inputs] 是（输入，分类），任一变化都重查，`mapLatest` 让新输入一到就取消上一次查询；输入为空或没有具体分类时是空列表。
 * 结果连同发起查询的输入一起给出：调用方写回前核对输入还是不是它（查询被取消后仍可能送来旧结果）。
 * 目录表读不出时这一次也是空列表、流照常走到下一次输入：用户对本机目录表无能为力，不提示，输入框照常可用。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TagCatalogRepository.suggestionsFor(inputs: Flow<Pair<String, SubjectType?>>): Flow<Pair<String, List<TagCatalogEntry>>> =
    inputs.distinctUntilChanged().mapLatest { (query, type) ->
        val concrete = type?.concreteOrNull()
        val entries = if (query.isBlank() || concrete == null) emptyList()
            else storageOutcomeOf { search(concrete, query, CATALOG_SUGGESTION_LIMIT) }.valueOrNull().orEmpty()
        query to entries
    }
