package io.github.touhougo.chogumi.core.data.repository

import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 测试里一切等待的预算（真实墙钟）：等的是由代码保证必然发生的结果，不是性能断言，
 * 整仓编译与各模块测试同时跑、机器满载时也要够用。等 Deferred / 流的地方写
 * `withTimeout(WAIT_BUDGET_MS) { … }`（只在 runBlocking 里），轮询最终态用 [awaitUntil]，不各写一个数。
 */
const val WAIT_BUDGET_MS: Long = 10_000L

/**
 * 轮询等待最终态成立（避免测试中写死固定 delay）。
 *
 * 预算默认 [WAIT_BUDGET_MS]。超时失败时显式报出等待的目标条件 [what]，便于排查。
 *
 * 预算按**真实墙钟**计量，休眠也落在真实调度器上：`runTest` 的虚拟时间会快进一切
 * `delay`，若用 `withTimeout` + 普通 `delay`，预算会在毫秒内烧光，对尚未赶到的
 * 真实进度（网络、落盘、其他线程）报假超时。条件 [block] 仍在调用方的线程上求值，
 * 调用方无须为此改动共享状态的类型。
 *
 * 注意：被等的条件必须是**单调**的——一旦为真就不再变假。轮询是采样，
 * 采样一个「操作中途瞬时为真」的条件迟早会落进那条缝里，调大预算也救不了。
 */
suspend fun awaitUntil(
    what: String,
    timeoutMs: Long = WAIT_BUDGET_MS,
    pollIntervalMs: Long = 10L,
    block: () -> Boolean,
) {
    val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
    while (!block()) {
        if (System.nanoTime() >= deadlineNanos) {
            val seconds = timeoutMs / 1000
            val timeoutLabel = if (timeoutMs % 1000 == 0L) "${seconds}s" else "${timeoutMs}ms"
            fail("等待「$what」超时：条件在 $timeoutLabel 内未成立")
        }
        withContext(Dispatchers.Default) { delay(pollIntervalMs) }
    }
}
