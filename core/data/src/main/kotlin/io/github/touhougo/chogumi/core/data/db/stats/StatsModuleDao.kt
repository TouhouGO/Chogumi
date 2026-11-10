package io.github.touhougo.chogumi.core.data.db.stats

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface StatsModuleDao {
    @Query("SELECT * FROM stats_modules ORDER BY position")
    fun observe(): Flow<List<StatsModuleEntity>>

    @Query("SELECT * FROM stats_modules ORDER BY position")
    suspend fun get(): List<StatsModuleEntity>

    @Query("DELETE FROM stats_modules")
    suspend fun clear()

    @Insert
    suspend fun insertAll(rows: List<StatsModuleEntity>)

    @Transaction
    suspend fun replace(rows: List<StatsModuleEntity>) {
        clear()
        insertAll(rows)
    }
}
