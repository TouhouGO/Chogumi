package io.github.touhougo.chogumi.core.data.db.collection

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.touhougo.chogumi.core.data.db.SQLITE_IN_CHUNK
import kotlinx.coroutines.flow.Flow

@Dao
interface UserCollectionDao {
    @Query("select * from user_collections where accountId = :accountId and subjectType = :subjectType and (:type is null or type = :type) and detailsLoaded = 1 order by updatedAt desc")
    suspend fun get(subjectType: Int, type: Int?, accountId: Int): List<UserCollectionEntity>

    @Query("select * from user_collections where accountId = :accountId and subjectId = :subjectId")
    suspend fun getBySubjectId(subjectId: Int, accountId: Int): UserCollectionEntity?

    @Query("select * from user_collections where accountId = :accountId and detailsLoaded = 1 order by updatedAt desc")
    fun observeCurrent(accountId: Int): Flow<List<UserCollectionEntity>>

    /** 本地有没有这个账号的收藏行（不论详情是否加载）。 */
    @Query("select exists(select 1 from user_collections where accountId = :accountId)")
    suspend fun hasRows(accountId: Int): Boolean

    @Upsert
    suspend fun upsertAll(collections: List<UserCollectionEntity>)

    @Query("select * from user_collections where accountId = :accountId and subjectId in (:subjectIds)")
    suspend fun getBySubjectIds(subjectIds: List<Int>, accountId: Int): List<UserCollectionEntity>

    /**
     * 只写与库里不同的行（按主键 accountId + subjectId 比）；一行没变就一条也不写。
     * upsert 即使内容没变也让整张表失效，订阅收藏的查询（首页、进度页、放送表、统计）就都要重跑一遍。
     */
    @Transaction
    suspend fun upsertChanged(collections: List<UserCollectionEntity>) {
        val stored = collections.groupBy { it.accountId }.flatMap { (accountId, rows) ->
            getBySubjectIdsChunked(rows.map { it.subjectId }, accountId)
        }.associateBy { it.accountId to it.subjectId }
        val changed = collections.filter { stored[it.accountId to it.subjectId] != it }
        if (changed.isNotEmpty()) upsertAll(changed)
    }
    @Upsert
    suspend fun upsert(collection: UserCollectionEntity)

    /** 一个分区（账号、条目类型、收藏类型；[type] 为 null 是这一条目类型的全部收藏）现有的条目 id。 */
    @Query("select subjectId from user_collections where accountId = :accountId and subjectType = :subjectType and (:type is null or type = :type)")
    suspend fun subjectIdsIn(subjectType: Int, type: Int?, accountId: Int): List<Int>

    @Query("delete from user_collections where accountId = :accountId and subjectId in (:subjectIds)")
    suspend fun deleteBySubjectIds(subjectIds: List<Int>, accountId: Int)

    /**
     * 仅全量同步成功才替换当前分区；聚合/分页失败/摘要种子不得调用此入口。
     * 要删的行在这里算出（分区现有的减去 [rows]），按 [SQLITE_IN_CHUNK] 分块删：保留名单是这一类的全部收藏，
     * 写成一条 `not in (...)` 会超过 API < 32 的宿主参数上限。
     */
    @Transaction
    suspend fun replaceSnapshot(accountId: Int, subjectType: Int, type: Int?, rows: List<UserCollectionEntity>) {
        upsertChanged(rows)
        val keep = rows.mapTo(HashSet()) { it.subjectId }
        subjectIdsIn(subjectType, type, accountId).filterNot { it in keep }
            .chunked(SQLITE_IN_CHUNK).forEach { deleteBySubjectIds(it, accountId) }
    }
}

/** 按 [SQLITE_IN_CHUNK] 分块批量查收藏，规避 SQLite 宿主参数上限。 */
internal suspend fun UserCollectionDao.getBySubjectIdsChunked(subjectIds: List<Int>, accountId: Int): List<UserCollectionEntity> =
    subjectIds.distinct().chunked(SQLITE_IN_CHUNK).flatMap { getBySubjectIds(it, accountId) }
