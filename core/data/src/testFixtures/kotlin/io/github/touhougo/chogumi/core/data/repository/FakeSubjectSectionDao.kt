package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.subject.FetchStampEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSectionDao
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSectionEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSectionStamp
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 测试用条目子模块缓存：按主键（条目、模块、查看者）存一行，[store] 供测试直接断言，[gets] 数读正文的次数。
 * [observeLastWrite] 每次写入发一个新值（写入次数），测试时钟不走时同一时刻的两次写入也各发一拍。
 */
class FakeSubjectSectionDao : SubjectSectionDao {
    val store = ConcurrentHashMap<Triple<Int, String, Int>, SubjectSectionEntity>()
    val gets = AtomicInteger()
    private val fetched = ConcurrentHashMap<Triple<Int, String, Int>, Long>()
    private val writes = MutableStateFlow<Long?>(null)

    override suspend fun get(subjectId: Int, section: String, viewerId: Int): SubjectSectionEntity? {
        gets.incrementAndGet()
        return store[Triple(subjectId, section, viewerId)]
    }

    override suspend fun upsert(entity: SubjectSectionEntity) {
        store[Triple(entity.subjectId, entity.section, entity.viewerId)] = entity
        writes.value = (writes.value ?: 0) + 1
    }

    override suspend fun storedJson(subjectId: Int, section: String, viewerId: Int): String? =
        store[Triple(subjectId, section, viewerId)]?.json

    override suspend fun fetchedAt(subjectId: Int, section: String, viewerId: Int): Long? = fetched[Triple(subjectId, section, viewerId)]

    override suspend fun putStamp(stamp: FetchStampEntity) {
        fetched[Triple(stamp.subjectId, stamp.section, stamp.viewerId)] = stamp.fetchedAt
    }

    override suspend fun stamps(subjectIds: List<Int>, sections: List<String>, viewerId: Int): List<SubjectSectionStamp> =
        store.values
            .filter { it.viewerId == viewerId && it.section in sections && it.subjectId in subjectIds }
            .map { SubjectSectionStamp(it.subjectId, it.section, it.updatedAt) }

    override fun observeLastWrite(): Flow<Long?> = writes
}

/** 预置一行缓存，取回时刻取它的 `updatedAt`（像刚取回的一样）。 */
suspend fun SubjectSectionDao.seed(entity: SubjectSectionEntity) = write(entity, fetchedAt = entity.updatedAt)
