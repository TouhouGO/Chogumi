package io.github.touhougo.chogumi.core.data.db.community

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PostDraftDao {

    @Query("SELECT * FROM post_drafts WHERE account_id = :accountId AND kind = :kind AND `key` = :key")
    suspend fun get(accountId: Int, kind: String, key: String): PostDraftEntity?

    @Upsert
    suspend fun upsert(draft: PostDraftEntity)

    @Query("DELETE FROM post_drafts WHERE account_id = :accountId AND kind = :kind AND `key` = :key")
    suspend fun delete(accountId: Int, kind: String, key: String)
}
