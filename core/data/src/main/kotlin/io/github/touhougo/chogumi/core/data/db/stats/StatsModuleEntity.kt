package io.github.touhougo.chogumi.core.data.db.stats

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 全部作品共用的布局，一行一模块，位置即排序；隐藏行使空布局区别于从未定制。 */
@Entity(tableName = "stats_modules")
data class StatsModuleEntity(@PrimaryKey val position: Int, val entry: String)
