package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.CareerStatistics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 本人收藏的全量同步（[CollectionSync]）：个人页统计要触发它、读它的进度与上次成功时刻。 */
interface CollectionSyncing {
    /** 全量同步进度（已完成页 / 总页数）；统计页顶部进度条读它。 */
    val syncProgress: StateFlow<SyncProgress>

    /**
     * 全量同步收藏。
     *
     * [force] = false（默认，自动路径）时受 30 分钟新鲜期约束，**一次打开 App 只自动同步一次**；
     * = true（下拉刷新 / 统计页 ⟳）时强制绕过新鲜期。
     */
    suspend fun refreshAllUserCollections(force: Boolean = false): Outcome<Unit>

    /** 上次成功同步的时刻（epoch 毫秒）；未登录或从未同步过为 null。统计页的「上次同步 N 分钟前」读它。 */
    suspend fun lastSyncedAt(): Long?
}

/**
 * 仅供个人页统计使用的小切面（生产实现是 [LocalCareerStatistics]）。
 *
 * 个人页统计只需要"观察统计"、"同步进度"、"触发全量同步"与"上次同步时刻"四个能力，
 * 测试 fake 只实现这几个方法，不必搭起整套 Api / Dao / TokenStore。
 *
 * 不含 `observeCollections`：那是**原始收藏行**，只服务首页/进度/放送的原始流，
 * 统计一律走派生好的 [observeStatistics]，接口不暴露它。
 */
interface CollectionStatsDataSource : CollectionSyncing {
    /**
     * 观察本地收藏的生涯统计：**按条目类型各算一份**（完整统计按类型切换），再把全部收藏合起来算一份（生涯卡）。
     * 五种可同步类型恒在 [CareerStatistics.byType] 里，没有收藏的类型是全零快照——「流发过一拍」即「已加载」。
     *
     * **含逐集状态**，因此标记一集也会重算（与进度页同拍）。
     * 正在计算的一轮不会被取消；`conflate` 合并期间的连续变化，`map` 随后用最新输入补算一次。
     */
    fun observeStatistics(currentYear: Int): Flow<Load<CareerStatistics>>
}
