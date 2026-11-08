package io.github.touhougo.chogumi.core.data.db.tag

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** 标签目录缓存访问（真源见 [TagCatalogEntity]）。 */
@Dao
interface TagCatalogDao {

    @Query("select * from tag_catalog_meta where type = :type")
    suspend fun getMeta(type: Int): TagCatalogMetaEntity?

    /**
     * 按关键词查找该类型的标签，大小写不敏感（`like` 对 ASCII 默认不敏感），
     * 按使用次数倒序，[limit] 限条数。
     *
     * **[keyword] 必须已经过 SQL 通配符转义**（`\`、`%`、`_` 各自前置转义反斜杠），
     * 转义由 `TagCatalogRepository.escapeLike` 单点完成；这里的 `escape '\'`
     * 子句与它配对，缺一不可。不区分大小写只对 ASCII 成立，中文标签本就无大小写。
     */
    @Query(
        "select * from tag_catalog where type = :type and name like '%' || :keyword || '%' escape '\\' " +
            "order by count desc, rank asc limit :limit",
    )
    suspend fun search(type: Int, keyword: String, limit: Int): List<TagCatalogEntity>

    @Upsert
    suspend fun upsertAll(rows: List<TagCatalogEntity>)

    @Upsert
    suspend fun upsertMeta(meta: TagCatalogMetaEntity)

    @Query("delete from tag_catalog where type = :type")
    suspend fun deleteByType(type: Int)

    /**
     * 原子替换该类型的整份目录，并记录刷新时间。
     *
     * **只在整个类型全部页都拉取成功后才调用**——中途失败走不到这里，
     * 旧数据因此原样保留、刷新时间也不动（调用方复用旧数据继续查找）。
     * 两步写若被中断会留下半表，故必须同事务。
     */
    @Transaction
    suspend fun replaceForType(type: Int, rows: List<TagCatalogEntity>, refreshedAt: Long) {
        deleteByType(type)
        upsertAll(rows)
        upsertMeta(TagCatalogMetaEntity(type, refreshedAt))
    }
}
