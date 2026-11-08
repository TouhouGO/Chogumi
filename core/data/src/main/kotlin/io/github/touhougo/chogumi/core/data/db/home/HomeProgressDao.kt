package io.github.touhougo.chogumi.core.data.db.home

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 首页进度区块缓存访问（真源见 [HomeProgressEntity]）。 */
@Dao
interface HomeProgressDao {

    @Query("select * from home_progress where accountId = :accountId order by updatedAt desc")
    suspend fun getByAccount(accountId: Int): List<HomeProgressEntity>

    /** 同 [getByAccount]，表一变就发新的一份。 */
    @Query("select * from home_progress where accountId = :accountId order by updatedAt desc")
    fun observeByAccount(accountId: Int): Flow<List<HomeProgressEntity>>

    @Query("select * from home_progress_meta where accountId = :accountId")
    suspend fun getMeta(accountId: Int): HomeProgressMetaEntity?

    @Upsert
    suspend fun upsertAll(rows: List<HomeProgressEntity>)

    @Upsert
    suspend fun upsertMeta(meta: HomeProgressMetaEntity)

    @Query("delete from home_progress where accountId = :accountId")
    suspend fun deleteByAccount(accountId: Int)

    /**
     * 原子替换本账户的进度区块：**仅在成功取回非空数据时调用**。
     *
     * 注意调用方不得在 `progress` 为空时走入这里——服务端单块失败也是空数组，
     * 清空会把用户上一条真实缓存抹掉（见 [HomeProgressEntity] 的说明）。
     */
    @Transaction
    suspend fun replaceForAccount(accountId: Int, rows: List<HomeProgressEntity>, syncedAt: Long) {
        deleteByAccount(accountId)
        upsertAll(rows)
        upsertMeta(HomeProgressMetaEntity(accountId, syncedAt))
    }
}
