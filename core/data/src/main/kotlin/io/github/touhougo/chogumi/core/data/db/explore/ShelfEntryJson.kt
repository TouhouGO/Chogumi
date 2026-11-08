package io.github.touhougo.chogumi.core.data.db.explore

import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.ShelfEntry
import io.github.touhougo.chogumi.core.model.SubjectType
import kotlinx.serialization.json.Json

// 探索页布局的行（[ExploreShelfEntity.entry]）怎么编、怎么解：只在这一处，仓库与迁移都用它。
// 读不懂的行按 null 处理（调用方跳过），不让一行坏数据拖垮整页。

private val json = Json { ignoreUnknownKeys = true }

/** 一份布局 → 一个分类的全部行，位置按列表顺序。 */
internal fun List<ShelfEntry>.toShelfRows(type: SubjectType): List<ExploreShelfEntity> = mapIndexed { position, entry ->
    ExploreShelfEntity(subjectType = type.value, position = position, entry = json.encodeToString(ShelfEntry.serializer(), entry))
}

/** 一行 → 一条货架；读不懂是 null。 */
internal fun ExploreShelfEntity.toShelfEntryOrNull(): ShelfEntry? =
    runCatching { json.decodeFromString(ShelfEntry.serializer(), entry) }.getOrNull()

/** 旧的「固定的一组条件」表里存的条件 JSON（迁移用）；读不懂是 null。 */
internal fun String.toSearchCriteriaOrNull(): SearchCriteria? =
    runCatching { json.decodeFromString(SearchCriteria.serializer(), this) }.getOrNull()
