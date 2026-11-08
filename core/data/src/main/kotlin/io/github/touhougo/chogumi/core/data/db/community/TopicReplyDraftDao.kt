package io.github.touhougo.chogumi.core.data.db.community

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TopicReplyDraftDao {

    /** [accountId] 的全部草稿，最近改过的在前。 */
    @Query("SELECT * FROM topic_reply_drafts WHERE account_id = :accountId ORDER BY updated_at DESC")
    fun observeAll(accountId: Int): Flow<List<TopicReplyDraftEntity>>

    @Query(
        "SELECT * FROM topic_reply_drafts " +
            "WHERE account_id = :accountId AND topic_type = :topicType AND topic_id = :topicId AND post_id = :postId",
    )
    suspend fun get(accountId: Int, topicType: String, topicId: Int, postId: Int): TopicReplyDraftEntity?

    @Upsert
    suspend fun upsert(draft: TopicReplyDraftEntity)

    @Query(
        "DELETE FROM topic_reply_drafts " +
            "WHERE account_id = :accountId AND topic_type = :topicType AND topic_id = :topicId AND post_id = :postId",
    )
    suspend fun delete(accountId: Int, topicType: String, topicId: Int, postId: Int)
}
