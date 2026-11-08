package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.draft.ComposerDraftOwner
import io.github.touhougo.chogumi.core.common.draft.ComposerDrafts
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.mayHaveReachedServer
import io.github.touhougo.chogumi.core.res.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 编辑器面板的状态。
 *
 * @property target 在写给谁（回复的楼、改的楼、或发动态时的 [Unit]）；null = 面板没开。
 * @property title 面板打开时标题框里的内容（只有发帖的面板有标题框）：接着写的草稿；新写的为空。
 * @property text 面板打开时框里的内容：接着写的草稿、要改的楼的正文；新写的为空。
 * @property sending 正在提交：发送按钮转圈，不再接受第二次提交。
 * @property sent 已发出：面板据此收起（`PageModalSheet` 的 hideRequested），滑完再 [ComposerActions.close]。
 */
data class ComposerState<T>(
    val target: T? = null,
    val title: String = "",
    val text: String = "",
    val sending: Boolean = false,
    val sent: Boolean = false,
)

/**
 * 编辑器面板的流程（话题回复、改自己的楼、发动态、小组发帖共用，只此一份）：开面板、提交、成功后收起，失败时面板与正文都留着、提示原因。
 * 提示走自己的 [failures]：面板盖在页面上，提示条画在面板里（页面的提示条在遮罩下面看不见）。
 * 人机验证在界面上做完才到这里，[send] 带着令牌（不需要验证的面板令牌为空串，[submit] 不用它）。
 *
 * 给了 [drafts] 的面板先绑定会话才接受输入：读取、停笔保存、关闭保存与提交后删除都属于原账号。
 * 换号时关闭旧面板，未落盘输入只存回原账号；存储失败留在待存队列，重新打开同一账号的草稿时接着写。
 * 待存记录由草稿仓库唯一持有，保存的收尾在草稿存储的作用域里，页面取消不取消最后一笔。
 *
 * @param state 当前面板状态（读）；[update] 以变换函数改它（写，原子地落进调用方的状态）。
 * @param submit 提交：对象、写好的内容、验证令牌 → 结果（新内容的 id 等）。
 * @param afterSend 提交成功后、面板收起前要做的事（重新拉楼层，好让新回复已经在列表里）。
 * @param afterUnsure 提交超时或断连、不知道有没有发出去时要做的事（重取列表一次，发出去了的话新内容已经在里面）；面板与正文都留着。
 */
class ComposerActions<T : Any, R>(
    private val scope: CoroutineScope,
    private val state: () -> ComposerState<T>,
    private val update: ((ComposerState<T>) -> ComposerState<T>) -> Unit,
    private val submit: suspend (target: T, draft: ComposerDraft, token: String) -> Outcome<R>,
    private val afterSend: suspend (R) -> Unit = {},
    private val afterUnsure: suspend () -> Unit = {},
    private val drafts: ComposerDrafts<T>? = null,
) {
    /** 提交失败的提示（禁言、话题已关闭、验证没过、太频繁……文案见 `Failure.text()`）。 */
    val failures = FailureNotices()

    /** 每次打开、关闭都有自己的归属；旧请求可以完成，但不得修改后来打开的面板。 */
    private var generation = 0L
    private var openJob: Job? = null
    private var saveJob: Job? = null
    private var panelOwner: ComposerDraftOwner? = null
    private val draftMutex = Mutex()
    private data class DraftKey(val accountId: Int, val target: Any)
    /** 同一账号的同一草稿后来打开的面板拥有它；旧提交不能删除后来那份草稿。 */
    private val draftOwners = mutableMapOf<DraftKey, Long>()

    init {
        drafts?.let { store ->
            scope.launch {
                store.owners.collect { loaded ->
                    when (loaded) {
                        is Load.Ready -> {
                            val bound = panelOwner
                            if (bound != null && bound.sessionKey != loaded.value && bound.checkCurrent() is Outcome.Failed) close()
                        }
                        is Load.Failed -> {
                            close()
                            failures.post(loaded.failure)
                        }
                        Load.Pending -> Unit
                    }
                }
            }
            scope.coroutineContext.job.invokeOnCompletion { savePending(store) }
        }
    }

    /** 从保存的对象恢复初始面板；对象读取与正文读取共用同一个绑定。 */
    fun openSaved(
        loadTarget: suspend (ComposerDraftOwner) -> Outcome<T?>,
        onFailure: (Failure) -> Unit,
    ) {
        val store = requireNotNull(drafts)
        val owner = beginOpen()
        openJob = scope.launch {
            val bound = bind(store, owner) ?: return@launch
            val loaded = loadTarget(bound)
            if (!ownsPanel(owner, bound)) return@launch
            when (loaded) {
                is Outcome.Success -> loaded.value?.let { openBound(store, bound, owner, it, "") }
                is Outcome.Failed -> onFailure(loaded.failure)
            }
        }
    }

    /** 开面板写给 [target]，框里是 [text]；绑定与读取完成前不发布可输入的对象。 */
    fun open(target: T, text: String = "") {
        val owner = beginOpen()
        val store = drafts ?: return update { ComposerState(target = target, text = text) }
        openJob = scope.launch {
            val bound = bind(store, owner) ?: return@launch
            openBound(store, bound, owner, target, text)
        }
    }

    private fun beginOpen(): Long {
        val owner = ++generation
        openJob?.cancel()
        saveJob?.cancel()
        panelOwner = null
        update { ComposerState() }
        return owner
    }

    private suspend fun bind(store: ComposerDrafts<T>, owner: Long): ComposerDraftOwner? {
        val result = store.bind()
        if (owner != generation) return null
        return when (result) {
            is Outcome.Success -> result.value.also { panelOwner = it }
            is Outcome.Failed -> {
                failures.post(result.failure)
                null
            }
        }
    }

    private suspend fun openBound(store: ComposerDrafts<T>, bound: ComposerDraftOwner, owner: Long, target: T, text: String) {
        if (!ownsPanel(owner, bound)) return
        val key = keyOf(store, bound, target)
        draftOwners[key] = owner
        val loaded = draftMutex.withLock {
            flush(store)
            store.load(bound, target)
        }
        if (!ownsPanel(owner, bound)) return
        val saved = when (loaded) {
            is Outcome.Success -> loaded.value
            is Outcome.Failed -> {
                failures.post(loaded.failure)
                null
            }
        }
        update { ComposerState(target = target, title = saved?.title.orEmpty(), text = saved?.text ?: text) }
    }

    /** 框里的内容变了：立即记下原账号与正文，停笔后才写库。 */
    fun edit(draft: ComposerDraft) {
        val store = drafts ?: return
        val bound = panelOwner ?: return
        val target = state().target ?: return
        store.stage(bound, target, draft)
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(DRAFT_SAVE_DELAY_MILLIS)
            draftMutex.withLock { flush(store) }
        }
    }

    /** 面板滑走或账号离开时：最后一笔存回原账号，页面立即回到没开的状态。 */
    fun close() {
        generation++
        openJob?.cancel()
        saveJob?.cancel()
        drafts?.let(::savePending)
        panelOwner = null
        update { ComposerState() }
    }

    /** 保存归草稿存储的生命周期，仓库串行处理唯一待存队列。 */
    private fun savePending(store: ComposerDrafts<T>) = store.saveScope.launch { flush(store) }

    /** 提交成功后刷新内容与删原账号草稿；只有仍属本次打开的面板才收起并回调 [onSent]。 */
    fun send(draft: ComposerDraft, token: String, onSent: (R) -> Unit = {}) {
        val current = state()
        val target = current.target ?: return
        if (current.sending || current.sent) return
        val owner = generation
        val store = drafts
        val bound = panelOwner
        if (store != null && bound == null) return
        val key = if (store != null && bound != null) keyOf(store, bound, target) else null
        update { it.copy(sending = true) }
        saveJob?.cancel()
        if (store != null && bound != null) store.stage(bound, target, draft)
        scope.launch {
            if (!ownsPanel(owner, bound)) return@launch
            store?.let { draftMutex.withLock { flush(it) } }
            if (!ownsPanel(owner, bound)) return@launch
            when (val result = submit(target, draft, token)) {
                is Outcome.Success -> {
                    afterSend(result.value)
                    if (store != null && bound != null && key != null) {
                        draftMutex.withLock {
                            if (draftOwners[key] == owner) {
                                when (val discarded = store.discard(bound, target)) {
                                    is Outcome.Success -> Unit
                                    is Outcome.Failed -> failures.post(discarded.failure)
                                }
                            }
                        }
                    }
                    if (!ownsPanel(owner, bound)) return@launch
                    saveJob?.cancel()
                    update { it.copy(sending = false, sent = true) }
                    onSent(result.value)
                }
                is Outcome.Failed -> {
                    if (!ownsPanel(owner, bound)) return@launch
                    update { it.copy(sending = false) }
                    if (result.failure.mayHaveReachedServer()) {
                        failures.post(R.string.composer_send_unsure)
                        afterUnsure()
                    } else {
                        failures.post(result.failure)
                    }
                }
            }
        }
    }

    /** 会话有效性只问捕获的绑定；关闭旧面板不改变待存内容的归属。 */
    private suspend fun ownsPanel(owner: Long, bound: ComposerDraftOwner?): Boolean {
        if (owner != generation) return false
        val valid = bound?.checkCurrent()
        if (owner != generation) return false
        if (valid is Outcome.Failed) {
            close()
            return false
        }
        return true
    }

    private fun keyOf(store: ComposerDrafts<T>, owner: ComposerDraftOwner, target: T) = DraftKey(owner.accountId, store.keyOf(target))

    /** 仓库只在写入成功后移出待存记录；失败提示可重试同一批原账号的输入。 */
    private suspend fun flush(store: ComposerDrafts<T>) {
        when (val saved = store.flush()) {
            is Outcome.Success -> Unit
            is Outcome.Failed -> failures.post(saved.failure, retry = { savePending(store) })
        }
    }
}

/** 停笔多久存一次草稿（设计决定：打字时不每个字写一次库，停下来马上就存）。 */
private const val DRAFT_SAVE_DELAY_MILLIS = 500L
