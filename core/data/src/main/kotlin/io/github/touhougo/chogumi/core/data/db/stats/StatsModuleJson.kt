package io.github.touhougo.chogumi.core.data.db.stats

import io.github.touhougo.chogumi.core.model.StatsLayoutEntry
import io.github.touhougo.chogumi.core.model.StatsModule
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/** 各行独立编码，稳定类型标记和枚举常量名不随包或类改名改变。 */
internal fun List<StatsModule>.toStatsRows(): List<StatsModuleEntity> = mapIndexed { index, module ->
    StatsModuleEntity(index, json.encodeToString(StatsLayoutEntry.serializer(), module))
}

/** 未知行跳过，合法数据与画法也必须兼容。 */
internal fun StatsModuleEntity.toStatsModuleOrNull(): StatsModule? =
    runCatching { json.decodeFromString(StatsLayoutEntry.serializer(), entry) as? StatsModule }.getOrNull()
        ?.takeIf { it.drawing in it.data.drawings }
