package io.github.touhougo.chogumi.core.data.util

import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.toLoad
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * 缓存优先 + 按需网络刷新的 SWR（stale-while-revalidate）状态机。
 *
 * 核心设计目标：
 * 1. 秒开与省流量：有缓存立即回显，新鲜期内（age < ttlMillis）不发网络请求，减少冗余开销与服务器压力；
 * 2. 过期静默刷新：缓存过期后后台发起网络静默刷新，成功后回写缓存并发射新数据；
 * 3. 失败反馈：过期刷新失败原样发出 Failed；页面分区收集器保留已呈现内容，并通过统一失败通知告知用户；
 * 4. 年龄未知自愈：timestamp <= 0L（没给 [cachedAt]，或缓存是空列表取不到时间戳）视为已过期，
 *    自动触发一次后台刷新并在成功后自愈为真实时间戳；
 * 5. 强刷兜底（refresh=true）：绕过缓存读取直打网络，失败发 Failed，但原有缓存完好不被破坏。
 *    HTTP 层不做缓存（见 `createHttpClient`），网络拍一定打到服务端。
 *
 * 读写与映射要离开主线程时传 [workContext]（各步在里面跑），不要在返回的流上接 `flowOn`：`flowOn` 在上下游之间加缓冲，
 * 缓存拍一交出去上游就接着发网络请求，只要一个答案的 `settled()` 随后取消它，过期缓存的每次读取都白发一个请求。
 * 这里发射留在收集方，`settled()` 拿到缓存拍就停，不发网络。门禁：`tools/guards/check_cached_network.py`。
 */
fun <BO, CACHED> cachedNetworkCall(
    readCache: suspend () -> CACHED?,
    mapCache: suspend (CACHED) -> BO,
    fetchNetwork: suspend () -> BO,
    writeCache: suspend (BO) -> Unit,
    refresh: Boolean = false,
    ttlMillis: Long = CacheTtl.INFINITE,
    cachedAt: ((CACHED) -> Long)? = null,
    clock: AppClock,
    workContext: CoroutineContext = EmptyCoroutineContext,
): Flow<Load<BO>> = flow {
    // refresh=true（下拉刷新）：不读不发缓存，直连网络；读缓存异常降级走网络
    val cached = if (refresh) null else outcomeOf { withContext(workContext) { readCache() } }.valueOrNull()

    if (cached != null) {
        // 先回显缓存保秒开
        emit(Load.Ready(withContext(workContext) { mapCache(cached) }, fromCache = true))

        if (!isCacheStale(cachedAt?.invoke(cached) ?: 0L, ttlMillis, clock.nowMillis())) {
            return@flow
        }

        // 原样交出刷新结果；保留已显示内容与失败反馈由页面唯一分区收集器裁决。
        emit(outcomeOf { withContext(workContext) { fetchNetwork().also { writeCache(it) } } }.toLoad())
    } else {
        // 无缓存（或 refresh 强刷）：先发 Pending，再打网络
        emit(Load.Pending)
        val network = outcomeOf { withContext(workContext) { fetchNetwork().also { writeCache(it) } } }
        emit(network.toLoad())
    }
}

/**
 * 缓存是否过了新鲜期（全项目唯一的判定）：[ttlMillis] 为 [CacheTtl.INFINITE] 时永不过期；
 * 时间戳未知（`<= 0`，存量数据或取不到时间戳）算过期，走一次刷新以自愈；其余按年龄 `>= ttlMillis`。
 */
internal fun isCacheStale(cachedAt: Long, ttlMillis: Long, nowMillis: Long): Boolean = when {
    ttlMillis >= CacheTtl.INFINITE -> false
    cachedAt <= 0L -> true
    else -> nowMillis - cachedAt >= ttlMillis
}
