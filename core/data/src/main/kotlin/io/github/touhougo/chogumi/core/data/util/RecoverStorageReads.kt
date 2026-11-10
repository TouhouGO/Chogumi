package io.github.touhougo.chogumi.core.data.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/** 本机持续订阅读失败后退避重建，取消照常结束；等待最多三十秒，不让一拍故障永久断开订阅。 */
fun <T> Flow<T>.recoverStorageReads(): Flow<T> = retryWhen { cause, attempt ->
    if (cause !is Exception) throw cause
    storageFailureOf(cause)
    delay((1_000L shl attempt.coerceAtMost(5).toInt()).coerceAtMost(30_000L))
    true
}
