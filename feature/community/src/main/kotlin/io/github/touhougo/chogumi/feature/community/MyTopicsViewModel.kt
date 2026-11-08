package io.github.touhougo.chogumi.feature.community

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.draft.ComposerDraftOwner
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.CommunityRepository
import io.github.touhougo.chogumi.core.data.repository.DraftRepository
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CommunityTopicBo
import io.github.touhougo.chogumi.core.model.GroupTopicScope
import io.github.touhougo.chogumi.core.model.TopicReplyDraft
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.common.paging.Pager
import kotlinx.coroutines.launch

/**
 * 我的帖子的三页：我发起的、我回复的（服务端的小组帖子，[scope] 是接口的范围）、草稿（本机，[scope] 为 null）。
 * [emptyRes] 是这一页空着时说的话。
 */
enum class MyTopicsPage(val labelRes: Int, val emptyRes: Int, val scope: GroupTopicScope?) {
    CREATED(R.string.my_topics_created, R.string.my_topics_created_empty, GroupTopicScope.CREATED),
    REPLIED(R.string.my_topics_replied, R.string.my_topics_replied_empty, GroupTopicScope.REPLIED),
    DRAFTS(R.string.my_topics_drafts, R.string.my_topics_drafts_empty, null),
}

/**
 * 我的帖子页的状态：停在哪一页、两页服务端帖子各一份分页列表（左右翻页时都在，切走再切回不重取）、本机草稿。
 *
 * @property drafts 全部草稿，最近改过的在前；跟着本机草稿表走，别处存了、删了这里马上变。
 */
data class MyTopicsUiState(
    val page: MyTopicsPage = MyTopicsPage.CREATED,
    val lists: Map<GroupTopicScope, PagedList<CommunityTopicBo>> = remoteScopes.associateWith { PagedList() },
    val drafts: Load<List<TopicReplyDraft>> = Load.Pending,
) {
    fun listFor(scope: GroupTopicScope): PagedList<CommunityTopicBo> = lists.getValue(scope)
}

/** 服务端的那几页的范围（我发起的、我回复的）。 */
private val remoteScopes = MyTopicsPage.entries.mapNotNull { it.scope }

class MyTopicsViewModel(
    private val repository: CommunityRepository,
    private val drafts: DraftRepository,
) : StateViewModel<MyTopicsUiState>(MyTopicsUiState()) {

    private val pagers = remoteScopes.associateWith { scope ->
        Pager(
            scope = viewModelScope,
            read = { currentState.listFor(scope) },
            write = { f -> updateState { it.copy(lists = it.lists + (scope to f(it.listFor(scope)))) } },
            // 按最后回复排，翻页途中有人回复会让同一帖再出现一次：只留先到的那一份
            present = { topics -> topics.distinctBy { it.key } },
        ) { offset -> outcomeOf { repository.getMyTopics(scope, offset) } }
    }

    /** 已经取过第一页的范围：重复出现在屏幕上不重取。 */
    private val opened = mutableSetOf<GroupTopicScope>()

    val failures = FailureNotices()

    init {
        retryDrafts()
    }

    fun retryDrafts() = launchSection("drafts", failures, { drafts.observeAll() }, { it.drafts }) { copy(drafts = it) }

    /** 这一页出现在屏幕上（打开时停的那页、点过去或翻过去）：服务端的页没取过就取第一页。 */
    fun open(page: MyTopicsPage) {
        val scope = page.scope ?: return
        if (opened.add(scope)) pagers.getValue(scope).refresh()
    }

    fun selectPage(page: MyTopicsPage) {
        if (currentState.page != page) updateState { it.copy(page = page) }
    }

    fun retry(scope: GroupTopicScope) = pagers.getValue(scope).refresh()

    fun loadMore(scope: GroupTopicScope) = pagers.getValue(scope).loadMore()

    /** 删掉一份草稿（长按确认之后）。 */
    fun deleteDraft(draft: TopicReplyDraft) {
        viewModelScope.launch {
            when (val bound = drafts.bind()) {
                is Outcome.Success -> deleteDraft(bound.value, draft)
                is Outcome.Failed -> failures.post(bound.failure)
            }
        }
    }

    private suspend fun deleteDraft(owner: ComposerDraftOwner, draft: TopicReplyDraft) {
        val outcome = outcomeOf { drafts.delete(owner, draft.topicType, draft.topicId, draft.postId) }
        if (outcome is Outcome.Failed) failures.post(outcome.failure, retry = { viewModelScope.launch { deleteDraft(owner, draft) } })
    }
}
