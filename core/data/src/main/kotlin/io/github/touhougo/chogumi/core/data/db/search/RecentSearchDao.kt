package io.github.touhougo.chogumi.core.data.db.search

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentSearchDao {

    /** 最近搜过的 [limit] 个词，最新的在前。 */
    @Query("SELECT query FROM recent_searches ORDER BY searched_at DESC LIMIT :limit")
    fun latest(limit: Int): Flow<List<String>>

    @Upsert
    suspend fun upsert(search: RecentSearchEntity)

    /** 只留最近搜过的 [keep] 个词。 */
    @Query("DELETE FROM recent_searches WHERE query NOT IN (SELECT query FROM recent_searches ORDER BY searched_at DESC LIMIT :keep)")
    suspend fun keepLatest(keep: Int)

    /** 记下一个词，并只留最近 [keep] 个：列表只显示这么多，多存的永远用不到，只会让表只增不减。 */
    @Transaction
    suspend fun record(search: RecentSearchEntity, keep: Int) {
        upsert(search)
        keepLatest(keep)
    }

    @Query("DELETE FROM recent_searches")
    suspend fun deleteAll()
}
