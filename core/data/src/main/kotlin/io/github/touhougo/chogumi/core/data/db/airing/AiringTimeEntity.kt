package io.github.touhougo.chogumi.core.data.db.airing

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * bangumi-data 给的每周首播时刻，按 bgm.tv 条目 id：[broadcastStart] 是 epoch 毫秒（每日放送页按它算放送日里的时刻）。
 * 只存每周播一次的作品；整份一起替换（[AiringTimeDao.replaceAll]），不逐条更新。
 */
@Entity(tableName = "airing_times")
data class AiringTimeEntity(
    @PrimaryKey val subjectId: Int,
    val broadcastStart: Long,
)

/**
 * 放送时刻表的刷新记录，只有一行（[id] 恒为 0）：[refreshedAt] 是上次拿到可用数据（整份或 304）的时刻，[attemptedAt] 是上次
 * 发请求的时刻（失败后按它退避），[etag] 是上次整份响应的 ETag，下次带上让服务端在没变时回 304。
 */
@Entity(tableName = "airing_times_meta")
data class AiringTimeMetaEntity(
    @PrimaryKey val id: Int = 0,
    val refreshedAt: Long = 0L,
    val attemptedAt: Long = 0L,
    val etag: String? = null,
)
