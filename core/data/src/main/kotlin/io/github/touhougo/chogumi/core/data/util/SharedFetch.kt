package io.github.touhougo.chogumi.core.data.util

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 同一个键同时只发一次请求：在途时后来的调用方等同一次的结果，结束（成功、失败或取消）后下一次重新发。
 * 两个页面同时要同一份数据（他人主页与它的生涯卡都取这个人的资料）时共用一次网络往返，不在 ViewModel 之间搬数据。
 *
 * 发起的那一方被取消时，等着的一方不跟着结束，自己重新发起。
 */
internal class SharedFetch<K : Any, V> {
    private val running = ConcurrentHashMap<K, CompletableDeferred<V>>()

    suspend fun fetch(key: K, block: suspend () -> V): V {
        while (true) {
            val mine = CompletableDeferred<V>()
            val shared = running.putIfAbsent(key, mine)
            if (shared == null) {
                // 先摘掉再交出结果：等着的一方醒来重试时，看到的已是空位
                val value = try {
                    block()
                } catch (t: Throwable) {
                    running.remove(key, mine)
                    mine.completeExceptionally(t)
                    throw t
                }
                running.remove(key, mine)
                mine.complete(value)
                return value
            }
            try {
                return shared.await()
            } catch (e: CancellationException) {
                // 自己被取消就照常结束；只是发起方被取消，就换自己发起
                currentCoroutineContext().ensureActive()
            }
        }
    }
}
