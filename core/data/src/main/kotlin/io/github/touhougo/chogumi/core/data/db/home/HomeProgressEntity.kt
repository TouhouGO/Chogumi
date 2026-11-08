package io.github.touhougo.chogumi.core.data.db.home

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 首页进度区块缓存（`/p1/home` 的 `progress[]`）。
 *
 * 按账户隔离：`accountId` 为主键一部分，换号不会串数据。
 * 章节（`eps`）以 JSON 列存放——它们随条目整体替换，不做按集查询，
 * 因此不值得为它单开一张表（与 `subjects.tagsJson` 的取舍一致）。
 *
 * **空 `progress` 不得导致本表被清空**：服务端各区块独立计算、单块失败静默返回空数组
 * 且无失败标志（2026-09-29 实测），
 * 因此「返回空」与「用户没有在看条目」不可区分。清空逻辑只在**成功且非空**时执行。
 */
@Entity(
    tableName = "home_progress",
    primaryKeys = ["accountId", "subjectId"],
    indices = [Index("accountId", "updatedAt")],
)
data class HomeProgressEntity(
    val subjectId: Int,
    val accountId: Int,
    val subjectType: Int,
    /** 服务端返回的章节（可能被截断，勿当全量）。 */
    val epsJson: String = "[]",
    /** 收藏更新时间（epoch 毫秒），用于「继续」区块排序。 */
    val updatedAt: Long = 0L,
)

/** 首页进度区块的缓存拍时间戳，用于判断新鲜度。 */
@Entity(tableName = "home_progress_meta")
data class HomeProgressMetaEntity(
    @PrimaryKey val accountId: Int,
    val updatedAt: Long = 0L,
)
