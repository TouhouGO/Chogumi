package io.github.touhougo.chogumi.core.data.db.explore

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 探索页布局的本地表访问（真源见 [ExploreShelfEntity]）。 */
@Dao
interface ExploreShelfDao {

    /** 一个分类存下来的布局，按位置；没动过的分类是空的。 */
    @Query("SELECT * FROM explore_shelves WHERE subject_type = :subjectType ORDER BY position")
    fun observe(subjectType: Int): Flow<List<ExploreShelfEntity>>

    @Query("SELECT * FROM explore_shelves WHERE subject_type = :subjectType ORDER BY position")
    suspend fun get(subjectType: Int): List<ExploreShelfEntity>

    @Insert
    suspend fun insertAll(rows: List<ExploreShelfEntity>)

    @Query("DELETE FROM explore_shelves WHERE subject_type = :subjectType")
    suspend fun deleteType(subjectType: Int)

    /** 整份换掉一个分类的布局；[rows] 为空 = 回到默认布局。 */
    @Transaction
    suspend fun replaceType(subjectType: Int, rows: List<ExploreShelfEntity>) {
        deleteType(subjectType)
        insertAll(rows)
    }

}
