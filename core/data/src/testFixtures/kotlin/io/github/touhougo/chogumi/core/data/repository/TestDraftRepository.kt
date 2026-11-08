package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.common.draft.ComposerDraftOwner
import io.github.touhougo.chogumi.core.data.db.community.PostDraftDao
import io.github.touhougo.chogumi.core.data.db.community.TopicReplyDraftDao
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Outcome
import kotlinx.coroutines.CoroutineScope

/** 用例显式拥有 DataStore 与保存作用域；不关心换号的用例默认游客身份。 */
fun testDraftRepository(
    scope: CoroutineScope,
    replyDao: TopicReplyDraftDao = FakeTopicReplyDraftDao(),
    postDao: PostDraftDao = FakePostDraftDao(),
    tokens: TokenStore = testTokenStore(scope),
): DraftRepository = DraftRepository(replyDao, postDao, tokens, scope)

/** 测试准备阶段捕获一份真实仓库的绑定，失败不能当作空草稿。 */
suspend fun DraftRepository.boundDraftOwner(): ComposerDraftOwner = when (val bound = bind()) {
    is Outcome.Success -> bound.value
    is Outcome.Failed -> throw FailureException(bound.failure)
}
