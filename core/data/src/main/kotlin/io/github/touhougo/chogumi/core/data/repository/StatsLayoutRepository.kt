package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.stats.StatsModuleDao
import io.github.touhougo.chogumi.core.data.db.stats.StatsModuleEntity
import io.github.touhougo.chogumi.core.data.db.stats.toStatsModuleOrNull
import io.github.touhougo.chogumi.core.data.db.stats.toStatsRows
import io.github.touhougo.chogumi.core.model.StatsModule
import io.github.touhougo.chogumi.core.model.defaultStatsLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 本机全局布局，读改写串行；未知行跳过，缺数据只影响页面显示，不删除用户布局。 */
class StatsLayoutRepository(private val dao: StatsModuleDao) {
    private val writes = Mutex()
    fun observe(): Flow<List<StatsModule>> = dao.observe().map { it.toLayout() }.distinctUntilChanged()

    suspend fun edit(change: (List<StatsModule>) -> List<StatsModule>) = writes.withLock {
        val current = dao.get().toLayout()
        val next = change(current)
        if (next != current) dao.replace(next.toStatsRows())
    }

    private fun List<StatsModuleEntity>.toLayout(): List<StatsModule> =
        if (isEmpty()) defaultStatsLayout() else mapNotNull { it.toStatsModuleOrNull() }.distinctBy { it.id }
}
