package io.github.touhougo.chogumi.core.data.db.airing

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 放送时刻表（[AiringTimeEntity]）与它的刷新记录（[AiringTimeMetaEntity]）。 */
@Dao
interface AiringTimeDao {

    @Query("select * from airing_times")
    fun observeAll(): Flow<List<AiringTimeEntity>>

    @Query("select * from airing_times_meta where id = 0")
    suspend fun getMeta(): AiringTimeMetaEntity?

    @Upsert
    suspend fun upsertMeta(meta: AiringTimeMetaEntity)

    @Upsert
    suspend fun upsertAll(rows: List<AiringTimeEntity>)

    @Query("delete from airing_times")
    suspend fun deleteAll()

    /** 整份替换并记下这次刷新，同一个事务：中途被打断不会留下半张表或对不上的 ETag。 */
    @Transaction
    suspend fun replaceAll(rows: List<AiringTimeEntity>, meta: AiringTimeMetaEntity) {
        deleteAll()
        upsertAll(rows)
        upsertMeta(meta)
    }
}
