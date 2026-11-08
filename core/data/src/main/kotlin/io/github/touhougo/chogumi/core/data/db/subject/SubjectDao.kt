package io.github.touhougo.chogumi.core.data.db.subject

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.touhougo.chogumi.core.data.db.SQLITE_IN_CHUNK
import io.github.touhougo.chogumi.core.data.db.mapper.SubjectSnapshot
import kotlinx.coroutines.flow.Flow

@Dao
interface SubjectDao {

    @Query("select * from subjects where id = :id")
    suspend fun get(id: Int): SubjectEntity?

    /** 按 id 一次批量查询（收藏同步落库后回读用，避免逐条 get 的 N 次往返）。 */
    @Query("select * from subjects where id in (:ids)")
    suspend fun getByIds(ids: List<Int>): List<SubjectEntity>
    @Upsert
    suspend fun upsert(subject: SubjectEntity)

    @Upsert
    suspend fun upsertAll(subjects: List<SubjectEntity>)

    /**
     * 只查给定 id 的条目（统计只关心被收藏引用到的条目，不读整张表）。
     *
     * Room 按表失效：`subjects` 里**任何**一行的写入都会让这条查询重跑并再发一拍，哪怕给定的 id 一行没变。
     * 订阅方要在映射前丢掉没变的那几拍（见 `CollectionObserver.observeCollections`）。
     */
    @Query("select * from subjects where id in (:ids)")
    fun observeByIds(ids: List<Int>): Flow<List<SubjectEntity>>

    /**
     * 读、合并、写在同一 Room 事务内，避免详情和摘要并发回包互相覆盖。
     * 合并结果与库里那一行相等时不写：没有写入就没有表失效，订阅 `subjects` 的查询不必重跑。
     */
    @Transaction
    suspend fun merge(snapshot: SubjectSnapshot): SubjectEntity {
        val previous = get(snapshot.value.id)
        val merged = snapshot.mergeInto(previous)
        if (merged != previous) upsert(merged)
        return merged
    }

    /**
     * 批量读-合并-写，**一个 Room 事务**内完成，且旧行**按 id 一次批量取回**——
     * 不逐条 `get`（N 部收藏同步会 N 次读）。分块见 [SQLITE_IN_CHUNK]。
     *
     * **同一批里出现重复 id 时按出现顺序依次 fold 到同一条行上**（后一条合并进前一条的结果），
     * 而不是各自从库里的旧行起算、后者覆盖前者——这是逐条 `merge` 的既有语义，批量实现必须保持。
     * 合并结果整批一次 `upsertAll`，杜绝半批写坏的中间态；与库里那一行相等的不写（同 [merge]），整批都没变就一条也不写。
     */
    @Transaction
    suspend fun mergeAll(snapshots: List<SubjectSnapshot>): List<SubjectEntity> {
        if (snapshots.isEmpty()) return emptyList()
        // 旧行按 id 分块一次取回，作为同 id 合并的起点；批内重复 id 会继续 fold 到 running 里。
        val stored = getByIdsChunked(snapshots.map { it.value.id }).associateBy { it.id }
        val running = stored.toMutableMap()
        val merged = snapshots.map { snapshot ->
            val result = snapshot.mergeInto(running[snapshot.value.id])
            running[snapshot.value.id] = result
            result
        }
        val changed = running.values.filter { it != stored[it.id] }
        if (changed.isNotEmpty()) upsertAll(changed)
        return merged
    }
}

/** 按 [SQLITE_IN_CHUNK] 分块批量查条目，规避 SQLite 宿主参数上限。 */
internal suspend fun SubjectDao.getByIdsChunked(ids: List<Int>): List<SubjectEntity> =
    ids.distinct().chunked(SQLITE_IN_CHUNK).flatMap { getByIds(it) }
