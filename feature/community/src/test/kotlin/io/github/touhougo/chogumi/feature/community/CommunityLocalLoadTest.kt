package io.github.touhougo.chogumi.feature.community

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.db.community.MutedGroupDao
import io.github.touhougo.chogumi.core.data.db.community.MutedGroupEntity
import io.github.touhougo.chogumi.core.data.db.community.TopicReplyDraftDao
import io.github.touhougo.chogumi.core.data.db.community.TopicReplyDraftEntity
import io.github.touhougo.chogumi.core.data.repository.CommunityRepository
import io.github.touhougo.chogumi.core.data.repository.GroupMuteRepository
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.fixedViewer
import io.github.touhougo.chogumi.core.data.repository.testDraftRepository
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.CommunityTopicBo
import io.github.touhougo.chogumi.core.model.GroupFeedCursor
import io.github.touhougo.chogumi.core.model.GroupFeedPage
import io.github.touhougo.chogumi.core.model.GroupTopicScope
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.RakuenFilter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** 本地名单或草稿读取失败是可见的区块失败，不能永远停在骨架。 */
class CommunityLocalLoadTest {
    private val main = TestMain()
    private val scopes = mutableListOf<CoroutineScope>()

    @BeforeTest
    fun setUp() = main.install()

    @AfterTest
    fun tearDown() = main.uninstall(scopes)

    @Test
    fun `屏蔽名单读取失败落成区块错误`() = runBlocking {
        val dao = object : MutedGroupDao {
            override fun observeAll(accountId: Int): Flow<List<MutedGroupEntity>> = flow { throw FailureException(Failure.Offline) }
            override suspend fun upsert(group: MutedGroupEntity) = Unit
            override suspend fun delete(accountId: Int, name: String) = Unit
        }
        val vm = withContext(main.dispatcher) { MutedGroupsViewModel(GroupMuteRepository(dao, fixedViewer())) }
        scopes += vm.viewModelScope
        awaitUntil("屏蔽名单失败已落定") { vm.uiState.value.groups is Load.Failed }
        assertEquals(Load.Failed(Failure.Offline), vm.uiState.value.groups)
    }

    @Test
    fun `草稿读取失败落成区块错误`() = runBlocking {
        val dao = object : TopicReplyDraftDao {
            override fun observeAll(accountId: Int): Flow<List<TopicReplyDraftEntity>> = flow { throw FailureException(Failure.Offline) }
            override suspend fun get(accountId: Int, topicType: String, topicId: Int, postId: Int): TopicReplyDraftEntity? = null
            override suspend fun upsert(draft: TopicReplyDraftEntity) = Unit
            override suspend fun delete(accountId: Int, topicType: String, topicId: Int, postId: Int) = Unit
        }
        val repository = object : CommunityRepository {
            override suspend fun getGroupFeed(scope: GroupTopicScope?, cursor: GroupFeedCursor): GroupFeedPage = error("unused")
            override suspend fun getRakuen(filter: RakuenFilter, offset: Int): PagedResult<CommunityTopicBo> = error("unused")
            override suspend fun getMyTopics(scope: GroupTopicScope, offset: Int): PagedResult<CommunityTopicBo> = error("unused")
        }
        val draftScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val vm = withContext(main.dispatcher) { MyTopicsViewModel(repository, testDraftRepository(draftScope, replyDao = dao)) }
        scopes += vm.viewModelScope
        awaitUntil("草稿失败已落定") { vm.uiState.value.drafts is Load.Failed }
        assertEquals(Load.Failed(Failure.Offline), vm.uiState.value.drafts)
    }
}
