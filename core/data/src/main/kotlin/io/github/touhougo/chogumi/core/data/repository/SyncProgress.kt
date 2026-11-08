package io.github.touhougo.chogumi.core.data.repository

/**
 * 收藏同步进度。
 *
 * [totalPages] 为 null 表示**总页数未知**（首屏还没拿到 `total`）——界面此时画不定进度；
 * 拿到后转成确定进度 [completedPages]/[totalPages]。**本人同步与「他人收藏现拉」共用这一个模型。**
 *
 * [active] 为 false 表示当前没有在跑的取数（界面据此隐藏进度条）。
 */
data class SyncProgress(
    val active: Boolean = false,
    val completedPages: Int = 0,
    val totalPages: Int? = null,
)

/**
 * 收藏同步「上次成功时刻」的存储（判断收藏要不要重新同步用）。
 *
 * **为什么单开一层**：自动同步的 30 分钟新鲜期需要持久化「上次成功时间」，
 * 而 DataStore 在 JVM 单测里不可用。生产用 [androidx.datastore.core.DataStore] 实现（按账号隔离），
 * 单测用内存实现。
 */
interface SyncPreferenceStore {
    /** 上次成功同步的时刻（epoch 毫秒）；从未同步过返回 null。 */
    suspend fun lastSyncedAt(accountId: Int): Long?

    /** 记录一次成功同步。 */
    suspend fun markSynced(accountId: Int, at: Long)
}
