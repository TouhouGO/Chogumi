package io.github.touhougo.chogumi.core.data.db.episode

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.touhougo.chogumi.core.data.db.SQLITE_IN_CHUNK
import kotlinx.coroutines.flow.Flow

/** 逐集观看状态缓存访问（真源见 [EpisodeWatchStateEntity]）。 */
@Dao
interface EpisodeWatchStateDao {

    @Query("select * from episode_watch_states where accountId = :accountId and subjectId = :subjectId order by episodeId asc")
    suspend fun getBySubject(subjectId: Int, accountId: Int): List<EpisodeWatchStateEntity>

    @Query("select * from episode_watch_states where accountId = :accountId and subjectId = :subjectId order by episodeId asc")
    fun observeBySubject(subjectId: Int, accountId: Int): Flow<List<EpisodeWatchStateEntity>>

    @Query("select * from episode_watch_states where accountId = :accountId order by episodeId asc")
    fun observeAll(accountId: Int): Flow<List<EpisodeWatchStateEntity>>

    @Query("select * from episode_watch_states where accountId = :accountId and subjectId in (:subjectIds)")
    suspend fun getBySubjects(subjectIds: List<Int>, accountId: Int): List<EpisodeWatchStateEntity>

    /**
     * 覆盖整条目的逐集状态：仅在完整取回后调用，否则会丢掉未返回的集。
     * 与库里这一条目的逐集记录完全相同时不写：先删后插即使内容没变也让整张表失效，
     * 订阅整表的查询（首页、进度页、放送表、统计）就都要重跑一遍。
     */
    @Transaction
    suspend fun replaceForSubject(subjectId: Int, accountId: Int, rows: List<EpisodeWatchStateEntity>) {
        if (getBySubject(subjectId, accountId) == rows.sortedBy { it.episodeId }) return
        deleteBySubject(subjectId, accountId)
        upsertAll(rows)
    }

    /** 只写与库里不同的行（按主键 accountId + episodeId 比，同 [replaceForSubject] 的理由）；一行没变就一条也不写。 */
    @Transaction
    suspend fun upsertChanged(rows: List<EpisodeWatchStateEntity>) {
        val stored = rows.groupBy { it.accountId }.flatMap { (accountId, accountRows) ->
            getBySubjectsChunked(accountRows.map { it.subjectId }, accountId)
        }.associateBy { it.accountId to it.episodeId }
        val changed = rows.filter { stored[it.accountId to it.episodeId] != it }
        if (changed.isNotEmpty()) upsertAll(changed)
    }

    @Upsert
    suspend fun upsertAll(rows: List<EpisodeWatchStateEntity>)

    @Query("delete from episode_watch_states where accountId = :accountId and subjectId = :subjectId")
    suspend fun deleteBySubject(subjectId: Int, accountId: Int)
}

/** 按 [SQLITE_IN_CHUNK] 分块批量查逐集状态，规避 SQLite 宿主参数上限。 */
internal suspend fun EpisodeWatchStateDao.getBySubjectsChunked(subjectIds: List<Int>, accountId: Int): List<EpisodeWatchStateEntity> =
    subjectIds.distinct().chunked(SQLITE_IN_CHUNK).flatMap { getBySubjects(it, accountId) }
