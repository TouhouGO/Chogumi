package io.github.touhougo.chogumi.feature.community

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
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
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.model.GroupTopicScope
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.RakuenFilter
import io.github.touhougo.chogumi.core.model.TopicReplyDraft
import io.github.touhougo.chogumi.core.model.TopicType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** 删除本机记录失败时列表保留，失败通过页面的提示通路送达。 */
class CommunityLocalDeletionTest {
    private val main = TestMain()
    private val scopes = mutableListOf<CoroutineScope>()

    @BeforeTest
    fun setUp() = main.install()

    @AfterTest
    fun tearDown() = main.uninstall(scopes)

    @Test
    fun `设置里取消屏蔽失败保留名单并提示`() = runBlocking {
        val group = GroupSummaryBo("a", "A", null, 1)
        val dao = object : MutedGroupDao {
            override fun observeAll(accountId: Int): Flow<List<MutedGroupEntity>> = flowOf(listOf(MutedGroupEntity(0, "a", "A", null, 1, 42)))
            override suspend fun upsert(group: MutedGroupEntity) = Unit
            override suspend fun delete(accountId: Int, name: String): Unit = throw FailureException(Failure.Offline)
        }
        val vm = withContext(main.dispatcher) { MutedGroupsViewModel(GroupMuteRepository(dao, fixedViewer())) }
        scopes += vm.viewModelScope
        awaitUntil("屏蔽名单已到") { vm.uiState.value.groups is Load.Ready }
        withContext(main.dispatcher) { vm.unmute(group) }
        awaitUntil("取消屏蔽失败提示已到") { vm.failures.current.value != null }
        assertEquals(NoticeText.Reason(Failure.Offline), vm.failures.current.value?.text)
        assertEquals(Load.Ready(listOf(group)), vm.uiState.value.groups)
    }

    @Test
    fun `删除草稿失败保留草稿并提示`() = runBlocking {
        val draft = TopicReplyDraft(TopicType.GROUP, 3, "title", 0, 0, null, null, null, null, "text", 42)
        val dao = object : TopicReplyDraftDao {
            override fun observeAll(accountId: Int): Flow<List<TopicReplyDraftEntity>> = flowOf(listOf(
                TopicReplyDraftEntity(0, "GROUP", 3, 0, "title", 0, null, null, null, null, "text", 42),
            ))
            override suspend fun get(accountId: Int, topicType: String, topicId: Int, postId: Int): TopicReplyDraftEntity? = null
            override suspend fun upsert(draft: TopicReplyDraftEntity) = Unit
            override suspend fun delete(accountId: Int, topicType: String, topicId: Int, postId: Int): Unit = throw FailureException(Failure.Offline)
        }
        val repository = object : CommunityRepository {
            override suspend fun getGroupFeed(scope: GroupTopicScope?, cursor: GroupFeedCursor): GroupFeedPage = error("unused")
            override suspend fun getRakuen(filter: RakuenFilter, offset: Int): PagedResult<CommunityTopicBo> = error("unused")
            override suspend fun getMyTopics(scope: GroupTopicScope, offset: Int): PagedResult<CommunityTopicBo> = error("unused")
        }
        val draftScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val vm = withContext(main.dispatcher) { MyTopicsViewModel(repository, testDraftRepository(draftScope, replyDao = dao)) }
        scopes += vm.viewModelScope
        awaitUntil("草稿已到") { vm.uiState.value.drafts is Load.Ready }
        withContext(main.dispatcher) { vm.deleteDraft(draft) }
        awaitUntil("删除草稿失败提示已到") { vm.failures.current.value != null }
        assertEquals(NoticeText.Reason(Failure.Offline), vm.failures.current.value?.text)
        assertEquals(Load.Ready(listOf(draft)), vm.uiState.value.drafts)
    }
}
