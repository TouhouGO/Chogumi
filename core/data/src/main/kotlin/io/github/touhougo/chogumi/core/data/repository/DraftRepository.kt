package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.draft.ComposerDraftOwner
import io.github.touhougo.chogumi.core.common.draft.ComposerDrafts
import io.github.touhougo.chogumi.core.data.db.community.PostDraftDao
import io.github.touhougo.chogumi.core.data.db.community.PostDraftEntity
import io.github.touhougo.chogumi.core.data.db.community.TopicReplyDraftDao
import io.github.touhougo.chogumi.core.data.db.community.TopicReplyDraftEntity
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.util.failuresAsLoad
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.PostDraft
import io.github.touhougo.chogumi.core.model.PostDraftKind
import io.github.touhougo.chogumi.core.model.TopicReplyDraft
import io.github.touhougo.chogumi.core.model.TopicType
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 编辑器的草稿（只在本机，按账号分）：话题回复、发动态、小组新帖、动态回复边写边存，发出成功或内容清空就删；
 * 「我的帖子」的草稿页列出话题回复的全部。换号后看不到上一个账号写的。
 */
class DraftRepository(
    private val replyDao: TopicReplyDraftDao,
    private val postDao: PostDraftDao,
    private val tokens: TokenStore,
    val saveScope: CoroutineScope,
) {
    /** 与面板绑定完全同源的会话变化，不保存第二份当前账号。 */
    val owners: Flow<Load<Any>> = tokens.sessionFlow.map { Load.Ready<Any>(it) }.failuresAsLoad()

    /** 在任何等待或延迟之前捕获；草稿操作只接受本仓库发出的绑定。 */
    suspend fun bind(): Outcome<ComposerDraftOwner> = outcomeOf { Owner(tokens.session()) }

    private inner class Owner(val session: AccountSession) : ComposerDraftOwner {
        val repository: DraftRepository get() = this@DraftRepository
        override val accountId: Int get() = session.viewerId
        override val sessionKey: Any get() = session
        override suspend fun checkCurrent() = outcomeOf { tokens.requireCurrent(session) }
    }

    private fun accountOf(owner: ComposerDraftOwner): Int {
        require(owner is Owner && owner.repository === this) { "草稿绑定必须来自同一个仓库" }
        return owner.accountId
    }

    private data class DraftKey(val accountId: Int, val kind: String, val key: Any)
    private class PendingDraft(val owner: ComposerDraftOwner, val draft: ComposerDraft, val write: suspend (ComposerDraftOwner) -> Unit)
    private val pending = ConcurrentHashMap<DraftKey, PendingDraft>()
    private val draftMutex = Mutex()

    /** 所有面板共用本仓库的一份待存记录；页面消失后存储故障仍可在重开原账号草稿时恢复。 */
    internal fun <T> composerDrafts(
        kind: String,
        keyFor: (T) -> Any,
        load: suspend (ComposerDraftOwner, T) -> ComposerDraft?,
        save: suspend (ComposerDraftOwner, T, ComposerDraft) -> Unit,
        discard: suspend (ComposerDraftOwner, T) -> Unit,
    ): ComposerDrafts<T> {
        val repository = this
        return object : ComposerDrafts<T> {
            override val saveScope = repository.saveScope
            override val owners = repository.owners
            override suspend fun bind() = repository.bind()
            override fun keyOf(target: T): Any = keyFor(target)
            private fun key(owner: ComposerDraftOwner, target: T) = DraftKey(accountOf(owner), kind, keyFor(target))

            override suspend fun load(owner: ComposerDraftOwner, target: T) = draftMutex.withLock {
                pending[key(owner, target)]?.let { Outcome.Success(it.draft) } ?: outcomeOf { load(owner, target) }
            }

            override fun stage(owner: ComposerDraftOwner, target: T, draft: ComposerDraft) {
                pending[key(owner, target)] = PendingDraft(owner, draft) { bound -> save(bound, target, draft) }
            }

            override suspend fun flush(): Outcome<Unit> = draftMutex.withLock {
                var result: Outcome<Unit> = Outcome.Success(Unit)
                for ((key, staged) in pending.toMap()) {
                    when (val saved = outcomeOf { staged.write(staged.owner) }) {
                        is Outcome.Success -> if (pending[key] === staged) pending.remove(key, staged)
                        is Outcome.Failed -> result = saved
                    }
                }
                result
            }

            override suspend fun discard(owner: ComposerDraftOwner, target: T) = draftMutex.withLock {
                val key = key(owner, target)
                val staged = pending[key]
                outcomeOf { discard(owner, target) }.also { result ->
                    if (result is Outcome.Success && staged != null && pending[key] === staged) pending.remove(key, staged)
                }
            }
        }
    }

    /** 当前账号的全部话题回复草稿，最近改过的在前。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAll(): Flow<Load<List<TopicReplyDraft>>> =
        tokens.viewerIds.flatMapLatest { accountId ->
            replyDao.observeAll(accountId).map { rows -> Load.Ready(rows.mapNotNull { it.toDraft() }) }.failuresAsLoad()
        }

    /** 回复 [topicId] 话题里 [postId] 那层楼（0 = 话题本身）的草稿；没有为 null。 */
    suspend fun get(owner: ComposerDraftOwner, type: TopicType, topicId: Int, postId: Int): TopicReplyDraft? =
        replyDao.get(accountOf(owner), type.name, topicId, postId)?.toDraft()

    /** 原账号的输入只存回原账号；换号不使未落盘输入丢失，也不读取新账号。 */
    suspend fun save(owner: ComposerDraftOwner, draft: TopicReplyDraft, now: Long) {
        if (draft.text.isBlank()) return delete(owner, draft.topicType, draft.topicId, draft.postId)
        replyDao.upsert(draft.copy(updatedAt = now).toEntity(accountOf(owner)))
    }

    suspend fun delete(owner: ComposerDraftOwner, type: TopicType, topicId: Int, postId: Int) =
        replyDao.delete(accountOf(owner), type.name, topicId, postId)

    /** [kind] 下 [key] 的草稿（新帖、动态回复）；没有为 null。 */
    suspend fun getPost(owner: ComposerDraftOwner, kind: PostDraftKind, key: String): PostDraft? =
        postDao.get(accountOf(owner), kind.name, key)?.let { PostDraft(kind, key, it.title, it.text, it.updatedAt) }

    /** 存回捕获的账号（时刻记成调用方捕获的 [now]）；标题与正文都是空白时删掉这份。 */
    suspend fun savePost(owner: ComposerDraftOwner, kind: PostDraftKind, key: String, title: String, text: String, now: Long) {
        if (title.isBlank() && text.isBlank()) return deletePost(owner, kind, key)
        postDao.upsert(PostDraftEntity(accountOf(owner), kind.name, key, title, text, now))
    }

    suspend fun deletePost(owner: ComposerDraftOwner, kind: PostDraftKind, key: String) = postDao.delete(accountOf(owner), kind.name, key)
}

private fun TopicReplyDraft.toEntity(accountId: Int) = TopicReplyDraftEntity(
    accountId = accountId,
    topicType = topicType.name,
    topicId = topicId,
    postId = postId,
    topicTitle = topicTitle,
    replyTo = replyTo,
    floor = floor,
    quoteAuthor = quoteAuthor,
    quoteExcerpt = quoteExcerpt,
    quote = quote,
    text = text,
    updatedAt = updatedAt,
)

/** 认不出的话题类型（以后的版本写的行）不显示，也不删：装回新版本时还在。 */
private fun TopicReplyDraftEntity.toDraft(): TopicReplyDraft? {
    val type = TopicType.entries.firstOrNull { it.name == topicType } ?: return null
    return TopicReplyDraft(
        topicType = type,
        topicId = topicId,
        topicTitle = topicTitle,
        postId = postId,
        replyTo = replyTo,
        floor = floor,
        quoteAuthor = quoteAuthor,
        quoteExcerpt = quoteExcerpt,
        quote = quote,
        text = text,
        updatedAt = updatedAt,
    )
}
