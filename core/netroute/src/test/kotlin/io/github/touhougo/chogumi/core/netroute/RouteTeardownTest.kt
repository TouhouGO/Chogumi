package io.github.touhougo.chogumi.core.netroute

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test

/** 线路测试的清理返回之前，后台协程必须已经结束。 */
class RouteTeardownTest {
    @Test
    fun `测试清理等待后台协程退出`() = runBlocking {
        val fixture = RouteFailureTest()
        val scopeField = RouteFailureTest::class.java.getDeclaredField("scope").apply { isAccessible = true }
        val scope = scopeField.get(fixture) as kotlinx.coroutines.CoroutineScope
        val entered = CompletableDeferred<Unit>()
        val cleaning = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val worker = scope.launch {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleaning.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
        }
        entered.await()
        val teardown = Thread {
            fixture.tearDown()
            returned.countDown()
        }.apply { start() }
        try {
            assertTrue("后台清理必须开始", cleaning.await(5, TimeUnit.SECONDS))
            assertTrue("测试清理在后台协程结束前就返回", !returned.await(200, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            teardown.join(5_000)
            worker.join()
        }
    }
}
