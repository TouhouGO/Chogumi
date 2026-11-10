package io.github.touhougo.chogumi.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 网页评分拆分的实际组编号与顺序；映射与计算共读。 */
val TOTAL_COLLECT_GROUPS = listOf(1, 10, 50, 100, 200, 500, 1000, 1001)
val REGDATE_GROUPS = listOf(10, 30, 180, 365, 730, 1095, 1825)

/** 数据与画法的目录，兼容关系只写在这里；页面、添加与编辑共读。枚举常量名是存档契约。 */
enum class StatsData(val drawings: List<StatsDrawing>) {
    RATING(listOf(StatsDrawing.RATING_BARS, StatsDrawing.RATING_ROWS, StatsDrawing.RATING_DOTS)),
    STATUS_RATING(listOf(StatsDrawing.STATUS_RULER, StatsDrawing.STATUS_DELTA)),
    COLLECTION(listOf(StatsDrawing.COLLECTION_FLOW, StatsDrawing.COLLECTION_BAR, StatsDrawing.COLLECTION_RING)),
    LIFELINE(listOf(StatsDrawing.LIFELINE)),
    EPISODE_DISCUSSION(listOf(StatsDrawing.EPISODE_GRID, StatsDrawing.EPISODE_BARS, StatsDrawing.EPISODE_LINE)),
    SERIES(listOf(StatsDrawing.SERIES)),
    RECOMMENDATIONS(listOf(StatsDrawing.RECOMMENDATIONS)),
    CONTROVERSY(listOf(StatsDrawing.CONTROVERSY_SPECTRUM, StatsDrawing.CONTROVERSY_CAMPS, StatsDrawing.CONTROVERSY_RIDGE, StatsDrawing.CONTROVERSY_DIAL)),
    AIR_PHASE(listOf(StatsDrawing.PHASE_SLOPE, StatsDrawing.PHASE_DISTRIBUTIONS)),
    TOTAL_COLLECTS(listOf(StatsDrawing.COLLECT_LINE, StatsDrawing.COLLECT_STRIP)),
    REGDATE(listOf(StatsDrawing.REGDATE_LINE, StatsDrawing.REGDATE_DELTA)),
    TAGS(listOf(StatsDrawing.TAG_CLOUD, StatsDrawing.TAG_RANK)),
}

/** 每个常量代表一种数据的一种画法；不按中文显示名存储。 */
enum class StatsDrawing {
    RATING_BARS, RATING_ROWS, RATING_DOTS,
    STATUS_RULER, STATUS_DELTA,
    COLLECTION_FLOW, COLLECTION_BAR, COLLECTION_RING,
    LIFELINE, EPISODE_GRID, EPISODE_BARS, EPISODE_LINE, SERIES, RECOMMENDATIONS,
    CONTROVERSY_SPECTRUM, CONTROVERSY_CAMPS, CONTROVERSY_RIDGE, CONTROVERSY_DIAL,
    PHASE_SLOPE, PHASE_DISTRIBUTIONS, COLLECT_LINE, COLLECT_STRIP, REGDATE_LINE, REGDATE_DELTA, TAG_CLOUD, TAG_RANK,
}

/** 布局按行编码；类型标记稳定，未知类型或枚举行由存储层跳过。 */
@Serializable
sealed interface StatsLayoutEntry

/** 全部作品共用的模块：数据可以重复，名字为空时用数据名。隐藏行保留身份，使空布局仍区别于未定制。 */
@Serializable
@SerialName("module")
data class StatsModule(
    val id: String,
    val data: StatsData,
    val drawing: StatsDrawing,
    val name: String = "",
    val hidden: Boolean = false,
) : StatsLayoutEntry

/** 未定制时的七个模块，与统计现有内容顺序一致；争议度在添加目录。 */
fun defaultStatsLayout(): List<StatsModule> = StatsData.entries.take(7).map { data ->
    StatsModule("default:${data.name}", data, data.drawings.first())
}

/** 创建身份由当前整份布局推出，不按可见切片；重复数据各有自己的身份。 */
fun List<StatsModule>.newStatsModule(data: StatsData): StatsModule {
    val ids = map { it.id }.toSet()
    val next = generateSequence(1) { it + 1 }.first { "module:$it" !in ids }
    return StatsModule("module:$next", data, data.drawings.first())
}

/** 整理的可见序列重排，未出现在这部作品上的模块保留原先相对顺序。 */
fun List<StatsModule>.reorderedStats(ids: List<String>): List<StatsModule> {
    val order = ids.withIndex().associate { it.value to it.index }
    val original = withIndex().associate { it.value.id to it.index }
    return sortedBy { order[it.id] ?: (ids.size + requireNotNull(original[it.id])) }
}

fun List<StatsModule>.withoutStatsModule(id: String): List<StatsModule> = map { if (it.id == id) it.copy(hidden = true) else it }

/** 新模块在保存时从当前布局分配身份，排队期间别的保存不会令身份重复。 */
fun List<StatsModule>.withStatsModule(module: StatsModule, isNew: Boolean): List<StatsModule> =
    if (isNew) this + newStatsModule(module.data).copy(drawing = module.drawing, name = module.name)
    else map { if (it.id == module.id) module else it }
