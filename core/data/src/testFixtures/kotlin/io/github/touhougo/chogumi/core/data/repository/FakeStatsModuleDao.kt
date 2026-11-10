package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.stats.StatsModuleDao
import io.github.touhougo.chogumi.core.data.db.stats.StatsModuleEntity
import kotlinx.coroutines.flow.MutableStateFlow

/** 本地模块布局的内存存储，生产仓库的读改写规则仍实际运行。 */
class FakeStatsModuleDao : StatsModuleDao {
    val rows = MutableStateFlow<List<StatsModuleEntity>>(emptyList())
    override fun observe() = rows
    override suspend fun get() = rows.value
    override suspend fun clear() { rows.value = emptyList() }
    override suspend fun insertAll(rows: List<StatsModuleEntity>) { this.rows.value += rows }
    override suspend fun replace(rows: List<StatsModuleEntity>) { this.rows.value = rows }
}
