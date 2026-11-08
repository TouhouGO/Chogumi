package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.findItem
import io.github.touhougo.chogumi.core.model.withMyReaction
import io.github.touhougo.chogumi.core.model.withReactions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 楼层贴贴的乐观更新（讨论版话题、作品吐槽、单集评论共用，流程只此一份）：先按 [withMyReaction] 改本地，再发请求，
 * 失败退回原来的贴贴并提示。未登录根本到不了这里：页面把贴贴回调包在登录门里（`LoginGate`），未登录点了去登录页。
 * 一次只处理一个贴贴（[Mutex]）：退回只会退回自己这一次，不会盖掉之后的操作。
 * 提示走所属 ViewModel 的 [failures]（全仓唯一的失败提示通道），界面不必另接。
 *
 * @param threads 当前楼层（读）；[updateThreads] 以变换函数改楼层（写，原子地落进调用方的状态）。
 * @param send 发请求：楼 id + 表情（null = 取消）；结果收成 [Outcome]。
 */
class FloorReactions(
    private val scope: CoroutineScope,
    private val threads: () -> List<CommentThreadBo>,
    private val updateThreads: ((List<CommentThreadBo>) -> List<CommentThreadBo>) -> Unit,
    private val send: suspend (itemId: Int, value: Int?) -> Outcome<Unit>,
    private val failures: FailureNotices,
) {
    private val mutex = Mutex()

    fun react(itemId: Int, value: Int?) {
        scope.launch {
            mutex.withLock {
                val before = threads().findItem(itemId)?.reactions ?: return@withLock
                updateThreads { it.withReactions(itemId, before.withMyReaction(value)) }
                val result = send(itemId, value)
                if (result is Outcome.Failed) {
                    updateThreads { it.withReactions(itemId, before) }
                    failures.post(result.failure)
                }
            }
        }
    }
}
