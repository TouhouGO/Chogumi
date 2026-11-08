package io.github.touhougo.chogumi.core.foundation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

/**
 * 「先缓存、后网络」加载流里的一拍。一条流的样子：有缓存时 `Ready(fromCache = true)` →（过期才有）`Ready(fromCache = false)`；
 * 没缓存时 `Pending` → `Ready(fromCache = false)` 或 `Failed`。一次性操作的结果见 [Outcome]。
 */
sealed interface Load<out T> {
    /** 没有可显示的数据，网络还没回。 */
    data object Pending : Load<Nothing>

    /** 有数据。[fromCache] 为真时只是先回显的缓存，网络那一拍可能还在后面。 */
    data class Ready<out T>(val value: T, val fromCache: Boolean = false) : Load<T>

    /** 取不到（且没有缓存可回显）。 */
    data class Failed(val failure: Failure) : Load<Nothing>
}

/** 已就绪的值；等待或取不到时为 null。 */
fun <T> Load<T>.valueOrNull(): T? = (this as? Load.Ready)?.value

/** 已就绪的列表；等待或取不到时为空列表。 */
fun <T> Load<List<T>>.orEmpty(): List<T> = valueOrNull().orEmpty()

/** 取不到的原因；等待或就绪时为 null。 */
fun Load<*>.failureOrNull(): Failure? = (this as? Load.Failed)?.failure

/**
 * 页面区块往前走一拍：新的「就绪」直接替换；「等待」「取不到」不抹掉已显示的内容，
 * 刷新途中与刷新失败时已显示的内容留在屏上。
 */
fun <T> Load<T>.advance(next: Load<T>): Load<T> = if (this is Load.Ready && next !is Load.Ready) this else next

/**
 * 这一拍的失败会不会被已显示的内容盖住：[advance] 在已有内容时留下内容，失败在界面上就看不见了，要另外告诉用户。
 * 盖住时给出那个失败，否则 null（「有内容时失败要提示」的唯一判定）。
 */
fun <T> Load<T>.hiddenFailureOf(next: Load<T>): Failure? = (next as? Load.Failed)?.failure?.takeIf { this is Load.Ready }

/** 一次性结果当作区块的一拍。 */
fun <T> Outcome<T>.toLoad(): Load<T> = when (this) {
    is Outcome.Success -> Load.Ready(value)
    is Outcome.Failed -> Load.Failed(failure)
}

/** 成功时换算值，其余原样传下去。 */
inline fun <T, R> Load<T>.map(transform: (T) -> R): Load<R> = when (this) {
    is Load.Ready -> Load.Ready(transform(value), fromCache)
    is Load.Failed -> this
    Load.Pending -> Load.Pending
}

/**
 * 等这条流落定，当作一次性结果用：第一拍「就绪」（缓存拍也算）→ 成功，「取不到」→ 失败；「等待」跳过。
 * 只要一个答案、不关心后续刷新的调用方（写入前取章节、补全、首屏搜索）都走这里。
 *
 * 来源必须以「就绪」或「取不到」结束（`cachedNetworkCall`、`loadOnce` 都是）：只发「等待」就结束的流在这里抛
 * [NoSuchElementException]。
 */
suspend fun <T> Flow<Load<T>>.settled(): Outcome<T> = mapNotNull { load ->
    when (load) {
        is Load.Ready -> Outcome.Success(load.value)
        is Load.Failed -> Outcome.Failed(load.failure)
        Load.Pending -> null
    }
}.first()
