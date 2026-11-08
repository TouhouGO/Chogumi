package io.github.touhougo.chogumi.core.data.db.community

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MutedGroupDao {

    /** [accountId] 屏蔽的全部小组，最近屏蔽的在前。 */
    @Query("SELECT * FROM muted_groups WHERE account_id = :accountId ORDER BY muted_at DESC")
    fun observeAll(accountId: Int): Flow<List<MutedGroupEntity>>

    @Upsert
    suspend fun upsert(group: MutedGroupEntity)

    @Query("DELETE FROM muted_groups WHERE account_id = :accountId AND name = :name")
    suspend fun delete(accountId: Int, name: String)
}
