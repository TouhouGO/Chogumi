package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.community.MutedGroupDao
import io.github.touhougo.chogumi.core.data.db.community.MutedGroupEntity
import io.github.touhougo.chogumi.core.data.db.community.TopicReplyDraftDao
import io.github.touhougo.chogumi.core.data.db.community.TopicReplyDraftEntity
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.model.TopicReplyDraft
import io.github.touhougo.chogumi.core.model.TopicType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/** 持久层用调用方捕获的操作时刻，草稿内容携带的旧时刻不决定排序。 */
class CommunityLocalTimestampTest {
    @Test
    fun `草稿保存使用本次操作时刻`() = runTest {
        var saved: TopicReplyDraftEntity? = null
        val dao = object : TopicReplyDraftDao {
            override fun observeAll(accountId: Int): Flow<List<TopicReplyDraftEntity>> = flowOf(emptyList())
            override suspend fun get(accountId: Int, topicType: String, topicId: Int, postId: Int): TopicReplyDraftEntity? = saved
            override suspend fun upsert(draft: TopicReplyDraftEntity) { saved = draft }
            override suspend fun delete(accountId: Int, topicType: String, topicId: Int, postId: Int) = Unit
        }
        val repository = testDraftRepository(backgroundScope, replyDao = dao)
        val owner = repository.boundDraftOwner()
        repository.save(owner, TopicReplyDraft(TopicType.GROUP, 3, "title", 0, 0, null, null, null, null, "text", 7), now = 42)
        assertEquals(42L, repository.get(owner, TopicType.GROUP, 3, 0)?.updatedAt)
    }

    @Test
    fun `屏蔽保存使用本次操作时刻`() = runBlocking {
        var saved: MutedGroupEntity? = null
        val dao = object : MutedGroupDao {
            override fun observeAll(accountId: Int): Flow<List<MutedGroupEntity>> = flowOf(emptyList())
            override suspend fun upsert(group: MutedGroupEntity) { saved = group }
            override suspend fun delete(accountId: Int, name: String) = Unit
        }
        GroupMuteRepository(dao, fixedViewer()).mute(GroupSummaryBo("a", "A", null, 1), now = 84)
        assertEquals(84L, saved?.mutedAt)
    }
}
