package io.github.touhougo.chogumi.core.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 用例收尾：取消这些作用域并**等它们真正结束**。
 *
 * 只 `cancel()` 不等时，协程的取消收尾仍会调度回 Dispatchers.Main，与下一条用例的
 * `Dispatchers.setMain` 并发，偶发 "Dispatchers.Main is used concurrently with setting it"。
 * 必须在关闭 Main 执行器、`resetMain()` 之前调用（收尾要在 Main 上跑完）。
 */
fun cancelAndAwait(scopes: Iterable<CoroutineScope>) = runBlocking {
    withTimeout(WAIT_BUDGET_MS) { scopes.forEach { it.coroutineContext.job.cancelAndJoin() } }
}
