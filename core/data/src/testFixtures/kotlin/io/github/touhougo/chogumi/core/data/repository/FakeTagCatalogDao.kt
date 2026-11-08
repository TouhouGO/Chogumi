package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.tag.TagCatalogDao
import io.github.touhougo.chogumi.core.data.db.tag.TagCatalogEntity
import io.github.touhougo.chogumi.core.data.db.tag.TagCatalogMetaEntity

/**
 * 测试用 Fake `TagCatalogDao`（JVM 单测无 Room 运行时，接口手写实现）。
 *
 * 所有读写在同一把锁内完成：仓库测试会并发触发刷新，无锁的 MutableMap 会丢行。
 *
 * **`like` 的通配符语义不在这里** —— SQLite 的 `escape` 行为由
 * `TagCatalogDaoTest` 走真实 Room 验证。本 Fake 的 [search] 只做朴素的
 * `contains`，并记录仓库实际传下来的 [lastSearchKeyword]，
 * 供测试断言「仓库在进 SQL 之前已把 `%`/`_` 转义」。
 */
class FakeTagCatalogDao : TagCatalogDao {

    private val lock = Any()
    private val stored = mutableMapOf<Pair<Int, String>, TagCatalogEntity>()
    private val metas = mutableMapOf<Int, TagCatalogMetaEntity>()

    /** 最近一次 [search] 收到的关键词（含转义），用于断言转义发生在仓库层。 */
    @Volatile
    var lastSearchKeyword: String? = null

    override suspend fun getMeta(type: Int): TagCatalogMetaEntity? = synchronized(lock) { metas[type] }

    override suspend fun search(type: Int, keyword: String, limit: Int): List<TagCatalogEntity> = synchronized(lock) {
        lastSearchKeyword = keyword
        val needle = keyword
            .replace("\\\\", "\u0000")
            .replace("\\%", "%")
            .replace("\\_", "_")
            .replace("\u0000", "\\")
        stored.values
            .filter { it.type == type && it.name.contains(needle, ignoreCase = true) }
            .sortedWith(compareByDescending<TagCatalogEntity> { it.count }.thenBy { it.rank })
            .take(limit)
    }

    /** 测试专用：按名次读出该类型的全部行（不属于 [TagCatalogDao] 接口）。 */
    fun rowsOf(type: Int): List<TagCatalogEntity> =
        synchronized(lock) { stored.values.filter { it.type == type }.sortedBy { it.rank } }

    override suspend fun upsertAll(rows: List<TagCatalogEntity>) = synchronized(lock) {
        rows.forEach { stored[it.type to it.name] = it }
    }

    override suspend fun upsertMeta(meta: TagCatalogMetaEntity) = synchronized(lock) {
        metas[meta.type] = meta
    }

    override suspend fun deleteByType(type: Int) {
        synchronized(lock) { stored.keys.removeAll { it.first == type } }
    }
}
