package io.github.touhougo.chogumi.core.common.draft

import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** 编辑器里写好的：标题（只有发帖有，回复、发动态、改楼为空串）与正文。 */
data class ComposerDraft(val title: String, val text: String)

/** 草稿面板捕获的账号与会话；持久化归账号，面板与提交的有效性归这次会话。 */
interface ComposerDraftOwner {
    val accountId: Int
    val sessionKey: Any

    /** 面板发布读取结果或提交前，确认捕获的会话仍有效。 */
    suspend fun checkCurrent(): Outcome<Unit>
}

/**
 * 编辑器的草稿存取：面板可输入前 [bind] 一次，后面的读取、保存与删除都携带同一个 [ComposerDraftOwner]。
 * 换号后未落盘的输入只保存到原账号；存储失败时调用方保留待保存文本，不转存到新账号。
 */
interface ComposerDrafts<T> {
    /** 原账号未落盘输入的收尾作用域；页面离开或账号切换后仍能保存。 */
    val saveScope: CoroutineScope

    /** 唯一会话源的身份变化；值与 [ComposerDraftOwner.sessionKey] 同源。 */
    val owners: Flow<Load<Any>>

    suspend fun bind(): Outcome<ComposerDraftOwner>

    /** 持久化草稿的对象身份；摘要等展示信息变化时不变，调用方另加账号形成完整键。 */
    fun keyOf(target: T): Any

    suspend fun load(owner: ComposerDraftOwner, target: T): Outcome<ComposerDraft?>

    /** 在输入事件里保存唯一待存记录；延迟落盘与页面取消都不会丢掉归属。 */
    fun stage(owner: ComposerDraftOwner, target: T, draft: ComposerDraft)

    /** 每份待存记录都携带原绑定，成功才移出队列；失败保留，供关闭或重开时重试。 */
    suspend fun flush(): Outcome<Unit>

    suspend fun save(owner: ComposerDraftOwner, target: T, draft: ComposerDraft): Outcome<Unit> {
        stage(owner, target, draft)
        return flush()
    }

    suspend fun discard(owner: ComposerDraftOwner, target: T): Outcome<Unit>
}
