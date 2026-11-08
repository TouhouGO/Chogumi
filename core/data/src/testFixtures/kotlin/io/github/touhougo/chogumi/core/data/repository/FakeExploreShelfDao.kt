package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.explore.ExploreShelfDao
import io.github.touhougo.chogumi.core.data.db.explore.ExploreShelfEntity
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * 测试用 Fake `ExploreShelfDao`（JVM 单测没有 Room 运行时，接口手写实现）。
 *
 * 行放在一个 [MutableStateFlow] 里，「改了布局探索页立刻能看到」在没有 Room 的测试里也观察得到；
 * [writes] 记下整份重写了几次（没变的改动不该写表）。
 */
class FakeExploreShelfDao : ExploreShelfDao {

    private val lock = Any()
    val rows = MutableStateFlow<List<ExploreShelfEntity>>(emptyList())
    var writes = 0
        private set

    override fun observe(subjectType: Int): Flow<List<ExploreShelfEntity>> = rows.map { it.ofType(subjectType) }

    override suspend fun get(subjectType: Int): List<ExploreShelfEntity> = rows.value.ofType(subjectType)

    override suspend fun insertAll(rows: List<ExploreShelfEntity>) = synchronized(lock) {
        val taken = this.rows.value.map { it.subjectType to it.position }.toSet()
        check(rows.none { (it.subjectType to it.position) in taken }) { "主键重复" }
        this.rows.value += rows
    }

    override suspend fun deleteType(subjectType: Int) = synchronized(lock) {
        rows.value = rows.value.filterNot { it.subjectType == subjectType }
    }

    override suspend fun replaceType(subjectType: Int, rows: List<ExploreShelfEntity>) = synchronized(lock) {
        writes++
        this.rows.value = this.rows.value.filterNot { it.subjectType == subjectType } + rows
    }

    private fun List<ExploreShelfEntity>.ofType(subjectType: Int) = filter { it.subjectType == subjectType }.sortedBy { it.position }
}

/**
 * 读失败可控的布局表：[failReads] 为 true 时 `observe` 的每次新订阅立刻抛 `IOException`（本机数据库读失败）；为 false 时与
 * [FakeExploreShelfDao] 一样。写入与一次性读取始终落到真实的假表，所以可以先写好布局、让观察失败、放行后观察到它。
 */
class FaultableExploreShelfDao(private val real: FakeExploreShelfDao = FakeExploreShelfDao()) : ExploreShelfDao by real {
    val failReads = AtomicBoolean(false)

    override fun observe(subjectType: Int): Flow<List<ExploreShelfEntity>> = flow {
        if (failReads.get()) throw IOException("explore_shelves unreadable")
        emitAll(real.observe(subjectType))
    }
}
