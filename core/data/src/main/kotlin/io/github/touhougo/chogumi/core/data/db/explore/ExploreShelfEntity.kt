package io.github.touhougo.chogumi.core.data.db.explore

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 探索页布局的一行：一个分类里的一条货架（只在本机，不是缓存：缓存清理不碰它）。
 *
 * 一个分类的全部行按 [position] 排就是那一页的布局；这个分类一行都没有 = 没动过，用默认布局（`defaultShelfLayout`）。
 * 第一次改动时整份写进去，之后每次改动都整份重写这个分类的行。
 *
 * [entry] 是整条货架（`ShelfEntry`，含 id、来源、样式、隐藏）的 kotlinx JSON，不拆列：货架的形状变了不用改表，
 * 读不懂的行（比当前版本新的写法）跳过，不让一行拖垮整页。
 */
@Entity(tableName = "explore_shelves", primaryKeys = ["subject_type", "position"])
data class ExploreShelfEntity(
    /** 分类（`SubjectType.value`）。 */
    @ColumnInfo(name = "subject_type") val subjectType: Int,
    /** 在这一页里的位置，从 0 起，即近期热门下面的显示顺序。 */
    val position: Int,
    val entry: String,
)
