package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.draft.ComposerDraftOwner
import io.github.touhougo.chogumi.core.common.draft.ComposerDrafts
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CommentSurface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope

/**
 * 评论区写入的测试替身（各 feature 模块的单测共用）：记下每次发出的一条（[sent]），回 [result]；
 * 草稿存在内存里，按评论区与回复的那层楼分开。
 */
class FakeCommentRepository(private val scope: CoroutineScope, var result: Outcome<Int> = Outcome.Success(1)) : CommentRepository {

    data class Sent(val box: CommentSurface.Box, val content: String, val replyTo: Int, val turnstileToken: String)

    val sent = CopyOnWriteArrayList<Sent>()
    private val drafts = ConcurrentHashMap<Pair<CommentSurface.Box, Int>, ComposerDraft>()

    override suspend fun reply(box: CommentSurface.Box, content: String, replyTo: Int, turnstileToken: String): Outcome<Int> {
        sent += Sent(box, content, replyTo, turnstileToken)
        return result
    }

    override fun <T> replyDrafts(box: CommentSurface.Box, postIdOf: (T) -> Int): ComposerDrafts<T> = object : TestComposerDrafts<T>(scope) {
        override fun keyOf(target: T): Any = box to postIdOf(target)

        override suspend fun read(owner: ComposerDraftOwner, target: T): Outcome<ComposerDraft?> = Outcome.Success(drafts[box to postIdOf(target)])

        override suspend fun write(owner: ComposerDraftOwner, target: T, draft: ComposerDraft): Outcome<Unit> {
            drafts[box to postIdOf(target)] = draft
            return Outcome.Success(Unit)
        }

        override suspend fun remove(owner: ComposerDraftOwner, target: T): Outcome<Unit> {
            drafts.remove(box to postIdOf(target))
            return Outcome.Success(Unit)
        }
    }
}
