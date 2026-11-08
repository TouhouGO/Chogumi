package io.github.touhougo.chogumi.core.data.db.tag

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 标签目录缓存（每个条目类型的前 [io.github.touhougo.chogumi.core.data.repository.TagCatalogRepository.CATALOG_SIZE] 个标签）。
 *
 * 数据源 `GET /p1/channels/{type}/tags`，按使用次数倒序。一期只存一份、
 * **与 NSFW 开关无关**：开关切换不重拉，过滤只发生在查找结果这一层
 * （见 [io.github.touhougo.chogumi.core.model.isExplicitAdultTag] 与 `NsfwFilter`）。
 *
 * 主键 `(type, name)` 同时承担跨页去重（服务端分页之间理论上可能重复，
 * 2026-09-30 实测前 5000 无重复，但主键保证万一重复也不会写进两行）。
 */
@Entity(
    tableName = "tag_catalog",
    primaryKeys = ["type", "name"],
    indices = [Index("type", "count")],
)
data class TagCatalogEntity(
    val type: Int,
    val name: String,
    /** 服务端返回的使用次数，查找结果按它倒序。 */
    val count: Int,
    /** 在目录中的名次（1 起），仅作留档与稳定排序的次级键。 */
    val rank: Int,
)

/**
 * 每个类型「最后一次完整拉全」的时间。
 *
 * 只在**整份目录全部拉完并整体替换成功后**写入；中途失败保留旧数据、不更新本时间，
 * 于是下次仍会判定为需要刷新（见 `TagCatalogRepository`）。
 */
@Entity(tableName = "tag_catalog_meta")
data class TagCatalogMetaEntity(
    @PrimaryKey val type: Int,
    val refreshedAt: Long = 0L,
)
