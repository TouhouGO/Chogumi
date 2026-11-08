package io.github.touhougo.chogumi.core.data.db

import androidx.room.InvalidationTracker
import io.github.touhougo.chogumi.core.data.db.search.RecentSearchEntity
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 记下真实 Room 里哪些表收到过失效通知（订阅这些表的查询会因此重跑）。只增不减，等它含某张表是单调的条件。
 * 「某次写入没让表失效」用栅栏判：之后写一张别的表（[fence]），它的通知到了，之前的写入（若有）也已通知过。
 */
class TableInvalidations(private val db: ChogumiDatabase, vararg tables: String) {
    private val seen = CopyOnWriteArraySet<String>()

    init {
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(arrayOf(*tables, FENCE_TABLE)) {
            override fun onInvalidated(tables: Set<String>) { seen += tables }
        })
    }

    operator fun contains(table: String): Boolean = table in seen

    /** 写栅栏表并等它的失效通知到达。 */
    suspend fun fence() {
        db.recentSearchDao().upsert(RecentSearchEntity(query = "fence-${System.nanoTime()}", searchedAt = 0))
        awaitUntil("栅栏写入的失效通知到达") { FENCE_TABLE in seen }
        seen -= FENCE_TABLE
    }

    /** 等 [table] 收到失效通知（对照用：内容变了的写入照常失效）。 */
    suspend fun awaitInvalidated(table: String) = awaitUntil("$table 表失效") { table in seen }

    private companion object {
        const val FENCE_TABLE = "recent_searches"
    }
}
