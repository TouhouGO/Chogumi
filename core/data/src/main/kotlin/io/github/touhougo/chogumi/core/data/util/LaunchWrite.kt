package io.github.touhougo.chogumi.core.data.util

import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 本机写入（偏好、本机名单、最近搜索、登出……）：磁盘满或文件损坏时写入会抛，异常不能冲出 `viewModelScope` 把进程带崩。
 * 失败原因见 [storageFailureOf]，发到 [failures]，[retry] 不为空时提示带重试。[block] 只能是本机写入。
 */
fun CoroutineScope.launchWrite(failures: FailureNotices, retry: (() -> Unit)? = null, block: suspend () -> Unit): Job = launch {
    storageOutcomeOf { block() }.failureOrNull()?.let { failures.post(it, retry) }
}
