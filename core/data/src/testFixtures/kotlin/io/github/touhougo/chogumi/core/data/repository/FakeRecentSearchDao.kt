package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.search.RecentSearchDao
import io.github.touhougo.chogumi.core.data.db.search.RecentSearchEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** 最近搜索 DAO 的唯一测试替身：内存存储，按 searchedAt 倒序给出前 limit 个词（同生产查询口径）。 */
class FakeRecentSearchDao : RecentSearchDao {
    val store = mutableMapOf<String, RecentSearchEntity>()
    private val rows = MutableStateFlow<List<RecentSearchEntity>>(emptyList())

    private fun publish() {
        rows.value = store.values.sortedByDescending { it.searchedAt }
    }

    override fun latest(limit: Int): Flow<List<String>> = rows.map { list -> list.take(limit).map { it.query } }

    override suspend fun upsert(search: RecentSearchEntity) {
        store[search.query] = search
        publish()
    }

    override suspend fun keepLatest(keep: Int) {
        store.values.sortedByDescending { it.searchedAt }.drop(keep).forEach { store.remove(it.query) }
        publish()
    }

    override suspend fun deleteAll() {
        store.clear()
        publish()
    }
}
