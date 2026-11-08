package io.github.touhougo.chogumi.core.data.db.subject

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 条目缓存表（tags/metaTags 存 JSON；完整详情的有效期见 `CacheTtl.SUBJECT_DETAIL`）。 */
@Entity(tableName = "subjects")
data class SubjectEntity(
    @PrimaryKey val id: Int,
    val type: Int,
    val name: String,
    val nameCn: String,
    val summary: String = "",
    val nsfw: Boolean = false,
    val date: String? = null,
    val imageLarge: String? = null,
    val imageCommon: String? = null,
    val ratingScore: Double = 0.0,
    val ratingRank: Int = 0,
    val ratingTotal: Int = 0,
    val collectionTotal: Int = 0,
    val eps: Int? = null,
    val volumes: Int? = null,
    val totalEpisodes: Int? = null,
    val collectionWish: Int = 0,
    val collectionCollect: Int = 0,
    val collectionDoing: Int = 0,
    val collectionOnHold: Int = 0,
    val collectionDropped: Int = 0,
    val metaTagsJson: String = "[]",
    val tagsJson: String = "[]",
    val updatedAt: Long,
    /** 完整条目的 Bangumi 平台字段。 */
    val platform: String = "",
    /** 完整条目的 infobox 以领域模型 JSON 缓存。 */
    val infoboxJson: String = "[]",
    /**
     * 最近一次被任何来源（摘要或详情）写入的时刻，epoch 毫秒。
     *
     * 与 [updatedAt] 不同：[updatedAt] 只记完整详情的落库时刻（摘要不续期，仅摘要的行恒为 0），
     * 本字段供本地缓存清理判断「多久没人用到这一行」，规则见 `cacheEvictionVictims`。
     */
    @ColumnInfo(defaultValue = "0") val touchedAt: Long = 0L,
    /** 评分 1–10 分各档人数（JSON 整数数组，下标 0 是 1 分）；只有详情接口给，空数组表示未知。 */
    @ColumnInfo(defaultValue = "[]") val ratingCountsJson: String = "[]",
)
