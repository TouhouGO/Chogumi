package io.github.touhougo.chogumi.core.data.progress

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * 进度提交停手窗口的闸门：交给 [EpTapCoordinator] 当 `awaitCommit`，代替生产按时间等到提交时刻。
 *
 * 关着时窗口不结束——乐观写入与 pending 一直在，用例可以等它们出现再断言「窗口里还没发写入」，
 * 等的是单调为真的状态，不赌轮询落在窗口里；[open] 之后，正在等的窗口与之后的窗口都直接结束。
 * 需要两轮的用例在两轮之间 [close]。
 */
class CommitGate(open: Boolean = false) {
    private val isOpen = MutableStateFlow(open)

    private val deadlines = CopyOnWriteArrayList<Long>()

    /** 协调器每次开始等待时交来的提交时刻，按发生顺序；只增不减。 */
    val awaited: List<Long> get() = deadlines

    suspend fun await(commitAtNanos: Long) {
        deadlines += commitAtNanos
        isOpen.first { it }
    }

    fun open() {
        isOpen.value = true
    }

    fun close() {
        isOpen.value = false
    }
}
