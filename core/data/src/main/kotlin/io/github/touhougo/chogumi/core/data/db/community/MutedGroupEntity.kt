package io.github.touhougo.chogumi.core.data.db.community

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 屏蔽的小组（本机名单，按账号分）：主键是账号加小组的英文键，社区 tab 按它过滤；名字、图标、成员数存下来给设置里的屏蔽列表画。
 * [accountId] 是屏蔽它的查看者（游客为 0，见 `AccountSession.viewerId`）。只在本机，不是缓存：缓存清理不碰它。
 */
@Entity(tableName = "muted_groups", primaryKeys = ["account_id", "name"])
data class MutedGroupEntity(
    @ColumnInfo(name = "account_id") val accountId: Int,
    val name: String,
    val title: String,
    val icon: String?,
    val members: Int,
    @ColumnInfo(name = "muted_at") val mutedAt: Long,
)
