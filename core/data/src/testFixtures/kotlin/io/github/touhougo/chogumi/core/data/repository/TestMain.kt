package io.github.touhougo.chogumi.core.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.Executors

/**
 * ViewModel 单测的 `Dispatchers.Main`：一条真实的单线程（不用 runTest 的虚拟时间——仓库、DataStore、
 * 假服务端都跑在真线程上，[awaitUntil] 按真实时间等）。各测试只用这一份，不各写执行器。
 *
 * 用例前 [install]；收尾 [uninstall]：先 [cancelAndAwait] 这些作用域，再关线程、还原 Main。
 * 顺序反了（或只 `cancel()` 不等），取消收尾仍会调度回 Main，与下一条用例的 `setMain` 并发，
 * 偶发 "Dispatchers.Main is used concurrently with setting it"。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestMain {
    val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    fun install() {
        Dispatchers.setMain(dispatcher)
    }

    /** [scopes]：跑在 Main 上、要在关线程前结束的作用域（ViewModel 作用域、后台作用域）。 */
    fun uninstall(scopes: Iterable<CoroutineScope>) {
        cancelAndAwait(scopes)
        dispatcher.close()
        Dispatchers.resetMain()
    }
}
