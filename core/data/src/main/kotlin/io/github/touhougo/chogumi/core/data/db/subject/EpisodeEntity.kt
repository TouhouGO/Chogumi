package io.github.touhougo.chogumi.core.data.db.subject

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 章节缓存表（按条目归组，随条目写入） */
@Entity(
    tableName = "episodes",
    indices = [Index("subjectId")],
)
data class EpisodeEntity(
    @PrimaryKey val id: Int,
    val subjectId: Int,
    val type: Int,
    val name: String,
    val nameCn: String,
    val sort: Double,
    val ep: Double? = null,
    val airdate: String = "",
    val comment: Int = 0,
    val desc: String = "",
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
    /** 碟号（音乐曲目按碟分组）；0 表示未知或不分碟。 */
    @ColumnInfo(defaultValue = "0") val disc: Int = 0,
)
