package io.github.touhougo.chogumi.core.data.db.community

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 新帖与动态回复的草稿：发动态、在小组里发新帖、回复一条动态。主键是账号、种类（[kind]，`PostDraftKind` 的名字）与种类内的
 * 对象（[key]：动态固定为空串，小组新帖是小组的英文键，动态回复是「动态 id/回复的那一条」）。[title] 只有小组新帖有，别的为空串。只在本机，不是缓存：缓存清理不碰它。
 */
@Entity(tableName = "post_drafts", primaryKeys = ["account_id", "kind", "key"])
data class PostDraftEntity(
    @ColumnInfo(name = "account_id") val accountId: Int,
    val kind: String,
    val key: String,
    val title: String,
    val text: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
