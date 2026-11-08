package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.draft.ComposerDraftOwner
import io.github.touhougo.chogumi.core.common.draft.ComposerDrafts
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Outcome
import kotlinx.coroutines.CoroutineScope

/** 编辑器测试只替换读写边界；账号绑定、待存与成功移除仍走真实 DraftRepository，不复制队列实现。 */
abstract class TestComposerDrafts<T>(scope: CoroutineScope) : ComposerDrafts<T> {
    private val store = testDraftRepository(scope).composerDrafts(
        kind = "test",
        keyFor = { target: T -> keyOf(target) },
        load = { owner, target -> read(owner, target).requiredValue() },
        save = { owner, target, draft -> write(owner, target, draft).requiredValue() },
        discard = { owner, target -> remove(owner, target).requiredValue() },
    )

    final override val saveScope = store.saveScope
    final override val owners = store.owners
    final override suspend fun bind() = store.bind()
    final override fun stage(owner: ComposerDraftOwner, target: T, draft: ComposerDraft) = store.stage(owner, target, draft)
    final override suspend fun load(owner: ComposerDraftOwner, target: T) = store.load(owner, target)
    final override suspend fun flush() = store.flush()
    final override suspend fun discard(owner: ComposerDraftOwner, target: T) = store.discard(owner, target)

    abstract suspend fun read(owner: ComposerDraftOwner, target: T): Outcome<ComposerDraft?>
    abstract suspend fun write(owner: ComposerDraftOwner, target: T, draft: ComposerDraft): Outcome<Unit>
    abstract suspend fun remove(owner: ComposerDraftOwner, target: T): Outcome<Unit>
}

private fun <T> Outcome<T>.requiredValue(): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failed -> throw FailureException(failure)
}
