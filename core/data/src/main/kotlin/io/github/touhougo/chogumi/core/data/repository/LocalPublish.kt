package io.github.touhougo.chogumi.core.data.repository

/**
 * 本地发布闸门：一次网络写入成功后，「把结果写进本地真源」这一步交给调用方裁决。
 *
 * 写入方在要发布本地状态的那一刻调用 [publish]，把真正的本地写入作为 [write] 交出；
 * 调用方决定执行或丢弃，并保证「判定 + 写入」是一个原子步骤。
 * 进度提交用它让更旧的一轮提交不能覆盖比它新的本地意图（判定的唯一实现见 `EpTapCoordinator`）。
 */
fun interface LocalPublish {
    suspend fun publish(write: suspend () -> Unit)

    companion object {
        /** 无人抢占：直接写入。非进度协调器的调用方（如详情页保存）用它。 */
        val Immediate = LocalPublish { write -> write() }
    }
}
