package io.github.touhougo.chogumi.core.data.db.user

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 用户缓存表（updated_at 用于 TTL 判断） */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: Int,
    val username: String,
    val nickname: String,
    @ColumnInfo(name = "avatar_large") val avatarLarge: String = "",
    @ColumnInfo(name = "avatar_medium") val avatarMedium: String = "",
    @ColumnInfo(name = "avatar_small") val avatarSmall: String = "",
    val sign: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
