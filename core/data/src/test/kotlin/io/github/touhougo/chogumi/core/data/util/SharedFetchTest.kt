package io.github.touhougo.chogumi.core.data.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** 同一个键同时只发一次（他人主页与生涯同时取这个人的资料）。测试调度器单线程推进，谁先到哪一步是确定的。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedFetchTest {

    @Test
    fun `在途时同一个键的第二次等第一次的结果_不另发`() = runTest {
        val shared = SharedFetch<String, Int>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val fetch: suspend () -> Int = { calls++; release.await(); 7 }

        val first = async { shared.fetch("fixture_user", fetch) }
        val second = async { shared.fetch("fixture_user", fetch) }
        runCurrent()
        release.complete(Unit)

        assertEquals(listOf(7, 7), listOf(first.await(), second.await()))
        assertEquals(1, calls)
    }

    @Test
    fun `上一次结束后再取_重新发`() = runTest {
        val shared = SharedFetch<String, Int>()
        var calls = 0
        repeat(2) { shared.fetch("fixture_user") { ++calls } }
        assertEquals(2, calls)
    }

    @Test
    fun `不同的键各发各的`() = runTest {
        val shared = SharedFetch<String, String>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val a = async { shared.fetch("a") { calls += "a"; release.await(); "A" } }
        val b = async { shared.fetch("b") { calls += "b"; release.await(); "B" } }
        runCurrent()
        release.complete(Unit)
        assertEquals(listOf("A", "B"), listOf(a.await(), b.await()))
        assertEquals(listOf("a", "b"), calls)
    }

    @Test
    fun `发起的一方被取消_等着的一方自己重新发起_拿到结果`() = runTest {
        val shared = SharedFetch<String, Int>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val fetch: suspend () -> Int = { calls++; release.await(); calls }

        val owner = async { shared.fetch("fixture_user", fetch) }
        val waiter = async { shared.fetch("fixture_user", fetch) }
        runCurrent()
        owner.cancel()
        runCurrent()
        release.complete(Unit)

        assertEquals(2, waiter.await(), "等着的一方换自己发起，是第二次请求的结果")
        assertTrue(owner.isCancelled)
    }

    @Test
    fun `请求失败_等着的一方也拿到同一个失败`() = runTest {
        val shared = SharedFetch<String, Int>()
        val release = CompletableDeferred<Unit>()
        val fetch: suspend () -> Int = { release.await(); error("boom") }
        val first = async { runCatching { shared.fetch("fixture_user", fetch) } }
        val second = async { runCatching { shared.fetch("fixture_user", fetch) } }
        runCurrent()
        release.complete(Unit)
        assertEquals(listOf("boom", "boom"), listOf(first.await(), second.await()).map { it.exceptionOrNull()?.message })
    }
}
