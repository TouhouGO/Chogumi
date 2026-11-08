package io.github.touhougo.chogumi.core.data.repository

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 并发预算的探针：假服务端的每个请求先 [enter]，停在门里直到放行，[peak] 记下同时停在门里的最多几个。
 *
 * - 测「同时在途不超过 N」：[releaseAfterBudget]。预算之内的 N 个必然进门，按 [WAIT_BUDGET_MS] 等；之后给第 N + 1 个
 *   [OVERFLOW_WINDOW_MS] 的窗——预算漏了，它几毫秒内就进门。「没有第 N + 1 个」没有信号可等，只能给窗。
 * - 测「不受这份预算限制」：[releaseAfter]。N + 1 个都必然同时进门，等到了才放行，不靠窗：满载的机器上晚到的那个
 *   不会被当成「没并发」。
 */
class ConcurrencyGate {
    private val release = CompletableDeferred<Unit>()
    private val entered = MutableStateFlow(0)
    private val active = AtomicInteger()
    private val peakActive = AtomicInteger()

    /** 进过门的请求数。 */
    val calls: Int get() = entered.value

    /** 同时停在门里的最多几个。 */
    val peak: Int get() = peakActive.get()

    suspend fun enter() {
        val current = active.incrementAndGet()
        peakActive.updateAndGet { maxOf(it, current) }
        entered.update { it + 1 }
        try {
            release.await()
        } finally {
            active.decrementAndGet()
        }
    }

    /** 等 [budget] 个请求进门，再给多出来的一个 [OVERFLOW_WINDOW_MS]，然后放行全部。 */
    suspend fun releaseAfterBudget(budget: Int) = releaseWhen("$budget 个请求进门") {
        entered.first { it >= budget }
        withTimeoutOrNull(OVERFLOW_WINDOW_MS) { entered.first { it > budget } }
    }

    /** 等 [count] 个请求同时进门，然后放行全部。 */
    suspend fun releaseAfter(count: Int) = releaseWhen("$count 个请求同时进门") { entered.first { it >= count } }

    private suspend fun releaseWhen(what: String, condition: suspend () -> Unit) {
        try {
            withTimeoutOrNull(WAIT_BUDGET_MS) { condition(); true } ?: fail("等待「$what」超时：进门的只有 $calls 个")
        } finally {
            release.complete(Unit)
        }
    }

    private companion object {
        /** 预算漏了时多出来的请求几毫秒内就到；窗只决定漏检的概率，不决定通过与否。 */
        const val OVERFLOW_WINDOW_MS = 500L
    }
}
