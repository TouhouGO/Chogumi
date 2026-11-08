package io.github.touhougo.chogumi.core.data.db.search

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 最近搜过的词：同一个词只留一行（再搜一次只更新时刻），永久保存，用户手动清空。 */
@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val query: String,
    @ColumnInfo(name = "searched_at") val searchedAt: Long,
)
