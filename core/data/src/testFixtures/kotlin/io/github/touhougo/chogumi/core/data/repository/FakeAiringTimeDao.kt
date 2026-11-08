package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.airing.AiringTimeDao
import io.github.touhougo.chogumi.core.data.db.airing.AiringTimeEntity
import io.github.touhougo.chogumi.core.data.db.airing.AiringTimeMetaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** 放送时刻表 Fake：表是一个 StateFlow（订阅方跟着每次写入变），刷新记录一行。 */
class FakeAiringTimeDao : AiringTimeDao {
    private val rows = MutableStateFlow<List<AiringTimeEntity>>(emptyList())

    @Volatile
    var meta: AiringTimeMetaEntity? = null

    val store: List<AiringTimeEntity> get() = rows.value

    override fun observeAll(): Flow<List<AiringTimeEntity>> = rows

    override suspend fun getMeta(): AiringTimeMetaEntity? = meta

    override suspend fun upsertMeta(meta: AiringTimeMetaEntity) {
        this.meta = meta
    }

    override suspend fun upsertAll(rows: List<AiringTimeEntity>) {
        this.rows.value = this.rows.value.filter { old -> rows.none { it.subjectId == old.subjectId } } + rows
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}
