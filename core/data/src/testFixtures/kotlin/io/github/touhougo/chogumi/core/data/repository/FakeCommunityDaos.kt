package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.community.MutedGroupDao
import io.github.touhougo.chogumi.core.data.db.community.MutedGroupEntity
import io.github.touhougo.chogumi.core.data.db.community.PostDraftDao
import io.github.touhougo.chogumi.core.data.db.community.PostDraftEntity
import io.github.touhougo.chogumi.core.data.db.community.TopicReplyDraftDao
import io.github.touhougo.chogumi.core.data.db.community.TopicReplyDraftEntity
import kotlinx.coroutines.flow.Flow
import io.github.touhougo.chogumi.core.data.token.ViewerSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** 一直是同一个查看者（默认游客）的 [ViewerSource]：不关心换号的测试用它。 */
fun fixedViewer(id: Int = 0): ViewerSource = object : ViewerSource {
    override val viewerIds = flowOf(id)
}

/** 草稿 DAO 的唯一测试替身：内存存储，按 updatedAt 倒序给出（同生产查询口径）。 */
class FakeTopicReplyDraftDao : TopicReplyDraftDao {
    private val store = mutableMapOf<List<Any>, TopicReplyDraftEntity>()
    private val rows = MutableStateFlow<List<TopicReplyDraftEntity>>(emptyList())

    private fun keyOf(accountId: Int, topicType: String, topicId: Int, postId: Int) =
        listOf(accountId, topicType, topicId, postId)

    private fun publish() {
        rows.value = store.values.sortedByDescending { it.updatedAt }
    }

    override fun observeAll(accountId: Int): Flow<List<TopicReplyDraftEntity>> =
        rows.map { list -> list.filter { it.accountId == accountId } }

    override suspend fun get(accountId: Int, topicType: String, topicId: Int, postId: Int): TopicReplyDraftEntity? =
        store[keyOf(accountId, topicType, topicId, postId)]

    override suspend fun upsert(draft: TopicReplyDraftEntity) {
        store[keyOf(draft.accountId, draft.topicType, draft.topicId, draft.postId)] = draft
        publish()
    }

    override suspend fun delete(accountId: Int, topicType: String, topicId: Int, postId: Int) {
        store.remove(keyOf(accountId, topicType, topicId, postId))
        publish()
    }
}

/** 屏蔽小组 DAO 的唯一测试替身：内存存储，按 mutedAt 倒序给出（同生产查询口径）。 */
class FakeMutedGroupDao : MutedGroupDao {
    private val store = mutableMapOf<Pair<Int, String>, MutedGroupEntity>()
    private val rows = MutableStateFlow<List<MutedGroupEntity>>(emptyList())

    private fun publish() {
        rows.value = store.values.sortedByDescending { it.mutedAt }
    }

    override fun observeAll(accountId: Int): Flow<List<MutedGroupEntity>> =
        rows.map { list -> list.filter { it.accountId == accountId } }

    override suspend fun upsert(group: MutedGroupEntity) {
        store[group.accountId to group.name] = group
        publish()
    }

    override suspend fun delete(accountId: Int, name: String) {
        store.remove(accountId to name)
        publish()
    }
}

/** 新帖草稿 DAO 的唯一测试替身：内存存储。 */
class FakePostDraftDao : PostDraftDao {
    private val store = mutableMapOf<List<Any>, PostDraftEntity>()

    override suspend fun get(accountId: Int, kind: String, key: String): PostDraftEntity? =
        store[listOf(accountId, kind, key)]

    override suspend fun upsert(draft: PostDraftEntity) {
        store[listOf(draft.accountId, draft.kind, draft.key)] = draft
    }

    override suspend fun delete(accountId: Int, kind: String, key: String) {
        store.remove(listOf(accountId, kind, key))
    }
}
