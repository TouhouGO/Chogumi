package io.github.touhougo.chogumi.core.data.db.episode

import androidx.room.Entity
import androidx.room.Index

/**
 * 逐集观看状态缓存（按账户隔离；accountId=0 为旧表未知归属，不向登录用户展示）。
 *
 * **存在理由**：条目级 `epStatus` 是「看过的记录条数」而非「看到第几集」，
 * 记录稀疏时无法推出下一集（见 `EpisodeTracking`）。
 * 本表存服务端逐集记录，是「某集是否已看」在本地的**唯一真源**，
 * 详情页章节勾选与首页 EP 按钮都从它读。
 *
 * 数据来源是 v0 的 `GET /v0/users/-/collections/{subjectId}/episodes`：该端点
 * **返回条目的全部章节并逐集给出 type**（未标过的集是 `type = 0`），
 * 因此每集状态都是显式的，不需要「不在响应里就算未看」的推断。
 * 这一点是它胜过 p1 `/p1/home` 的 `eps[]` 的关键——后者在无记录时**整个不出现
 * `collection` 键**，且超过 72 集会被截断（2026-09-29 实测）。
 */
@Entity(
    tableName = "episode_watch_states",
    primaryKeys = ["accountId", "episodeId"],
    indices = [Index("accountId", "subjectId")],
)
data class EpisodeWatchStateEntity(
    val episodeId: Int,
    /** 冗余存 subjectId：批量按条目读取与替换时避免 join。 */
    val subjectId: Int,
    val accountId: Int = 0,
    /** [io.github.touhougo.chogumi.core.model.EpisodeStatus] 的原始值（0 无记录 1 想看 2 看过 3 抛弃）。 */
    val status: Int = 0,
    /** 该集被标成当前状态的时刻（服务端 `updated_at`，epoch 毫秒）。 */
    val updatedAt: Long = 0L,
)
