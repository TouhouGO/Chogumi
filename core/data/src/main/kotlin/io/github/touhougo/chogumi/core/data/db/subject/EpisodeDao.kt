package io.github.touhougo.chogumi.core.data.db.subject

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.touhougo.chogumi.core.data.db.SQLITE_IN_CHUNK
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao : FetchStamps {

    @Query("select * from episodes where subjectId = :subjectId order by sort asc")
    suspend fun getBySubject(subjectId: Int): List<EpisodeEntity>

    /** 按条目一次批量取章节（统计沿用了进度页的逐集口径，需要正片列表）。 */
    @Query("select * from episodes where subjectId in (:subjectIds) order by subjectId asc, sort asc")
    suspend fun getBySubjectIds(subjectIds: List<Int>): List<EpisodeEntity>

    @Upsert
    suspend fun upsertAll(episodes: List<EpisodeEntity>)

    @Query("delete from episodes where subjectId = :subjectId")
    suspend fun deleteBySubject(subjectId: Int)

    /**
     * 原子替换某条目的章节缓存，并记下这次取回的时刻 [fetchedAt]。章节内容与库里的相同（不计 `updatedAt`）时一行也不写：
     * 订阅章节表的查询（生涯统计、每日放送）不会因此重跑，新鲜期仍然往前走。
     */
    @Transaction
    suspend fun replaceAll(subjectId: Int, episodes: List<EpisodeEntity>, fetchedAt: Long) {
        if (getBySubject(subjectId).ignoringUpdatedAt() != episodes.ignoringUpdatedAt()) {
            deleteBySubject(subjectId)
            upsertAll(episodes)
        }
        putStamp(FetchStampEntity(subjectId, EPISODES_STAMP, viewerId = 0, fetchedAt = fetchedAt))
    }

    /** 这部的章节上次取回的时刻；从没取回过为 null。 */
    suspend fun fetchedAt(subjectId: Int): Long? = fetchedAt(subjectId, EPISODES_STAMP, viewerId = 0)

    /** 表里有写入或删除就发一拍（值是最近一次落库时刻，表空时为 null）：统计读章节，靠它知道章节变了。 */
    @Query("select max(updatedAt) from episodes")
    fun observeLastWrite(): Flow<Long?>
}

private fun List<EpisodeEntity>.ignoringUpdatedAt(): Set<EpisodeEntity> = mapTo(HashSet()) { it.copy(updatedAt = 0L) }

/** 按 [SQLITE_IN_CHUNK] 分块批量查章节，规避 SQLite 宿主参数上限。 */
internal suspend fun EpisodeDao.getBySubjectIdsChunked(subjectIds: List<Int>): List<EpisodeEntity> =
    subjectIds.distinct().chunked(SQLITE_IN_CHUNK).flatMap { getBySubjectIds(it) }
