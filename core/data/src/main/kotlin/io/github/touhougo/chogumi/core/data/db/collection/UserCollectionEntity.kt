package io.github.touhougo.chogumi.core.data.db.collection

import androidx.room.Entity
import androidx.room.Index

/** 我的收藏按账户隔离；游客或旧表未知归属使用 accountId=0，不向登录用户展示。 */
@Entity(
    tableName = "user_collections",
    primaryKeys = ["accountId", "subjectId"],
    indices = [Index("accountId", "subjectType", "type")],
)
data class UserCollectionEntity(
    val subjectId: Int,
    val subjectType: Int,
    val type: Int,
    val rate: Int = 0,
    val tagsJson: String = "[]",
    val epStatus: Int = 0,
    val volStatus: Int = 0,
    val comment: String = "",
    val isPrivate: Boolean = false,
    val updatedAt: Long,
    val accountId: Int = 0,
    /** p1 slim interest 不含进度/私密性，不能冒充完整收藏供 +1 使用。 */
    val detailsLoaded: Boolean = true,
)
