package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.search.SearchFirstPageDao
import io.github.touhougo.chogumi.core.data.db.search.SearchFirstPageEntity
import java.util.concurrent.ConcurrentHashMap

/** 搜索第一页缓存 DAO 的测试替身：内存存储，按键覆盖（同生产的 upsert）。 */
class FakeSearchFirstPageDao : SearchFirstPageDao {
    val store = ConcurrentHashMap<String, SearchFirstPageEntity>()
    override suspend fun get(cacheKey: String): SearchFirstPageEntity? = store[cacheKey]
    override suspend fun upsert(page: SearchFirstPageEntity) {
        store[page.cacheKey] = page
    }
}
