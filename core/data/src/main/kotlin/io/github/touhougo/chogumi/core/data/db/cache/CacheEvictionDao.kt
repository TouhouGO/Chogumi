package io.github.touhougo.chogumi.core.data.db.cache

import androidx.room.Dao
import androidx.room.Query

/** 条目行的清理候选：id 与最近被写入的时刻。 */
data class SubjectAgeRow(val id: Int, val touchedAt: Long)

/** 条目子模块行的清理候选：主键与落库时刻。 */
data class SubjectSectionAgeRow(val subjectId: Int, val section: String, val viewerId: Int, val updatedAt: Long)

/** 用户资料行的清理候选：id 与落库时刻。 */
data class UserAgeRow(val id: Int, val updatedAt: Long)

/** 一行收藏的账号与条目：清理按它保护该账号视角下的阵容模块。 */
data class CollectedSubjectRow(val accountId: Int, val subjectId: Int)

/** 一个账号的首页进度上次落库的时刻。 */
data class AccountTouchRow(val accountId: Int, val updatedAt: Long)

/** 搜索第一页行的清理候选：键与取回的时刻。 */
data class SearchFirstPageAgeRow(val cacheKey: String, val updatedAt: Long)

/**
 * 本地缓存清理用的读写：只读清理判断需要的列，删除按主键分批。
 * 「删谁」不在这里决定，见 [cacheEvictionVictims]。
 */
@Dao
interface CacheEvictionDao {

    @Query("select id, touchedAt from subjects")
    suspend fun subjectAges(): List<SubjectAgeRow>

    /**
     * 本地任何账号的收藏、放送表、首页进度、逐集观看状态引用到的条目：这些页面直接读条目表，
     * 条目行被删就会缺封面、缺名字或缺 NSFW 判定，故一律受保护。
     */
    @Query(
        "select subjectId from user_collections union select subjectId from calendar " +
            "union select subjectId from home_progress union select subjectId from episode_watch_states",
    )
    suspend fun referencedSubjectIds(): List<Int>

    @Query("select accountId, subjectId from user_collections")
    suspend fun collectedSubjects(): List<CollectedSubjectRow>

    /** 本地留着按账号分份数据（收藏、逐集状态、首页进度）的账号。 */
    @Query(
        "select accountId from user_collections union select accountId from episode_watch_states " +
            "union select accountId from home_progress union select accountId from home_progress_meta",
    )
    suspend fun accountsWithData(): List<Int>

    @Query("select accountId, updatedAt from home_progress_meta")
    suspend fun homeProgressTouches(): List<AccountTouchRow>

    @Query("delete from user_collections where accountId in (:accountIds)")
    suspend fun deleteCollectionsOf(accountIds: List<Int>)

    @Query("delete from episode_watch_states where accountId in (:accountIds)")
    suspend fun deleteWatchStatesOf(accountIds: List<Int>)

    @Query("delete from home_progress where accountId in (:accountIds)")
    suspend fun deleteHomeProgressOf(accountIds: List<Int>)

    @Query("delete from home_progress_meta where accountId in (:accountIds)")
    suspend fun deleteHomeProgressMetaOf(accountIds: List<Int>)

    @Query("select id, updated_at as updatedAt from users")
    suspend fun userAges(): List<UserAgeRow>

    @Query("delete from users where id in (:ids)")
    suspend fun deleteUsers(ids: List<Int>)

    /** 子模块行与它们上次取回的时刻（没有取回记录的退回正文写入时刻）：清理按「多久没取过」判断，不按正文多久没变。 */
    @Query(
        "select s.subjectId, s.section, s.viewerId, coalesce(f.fetchedAt, s.updatedAt) as updatedAt " +
            "from subject_sections s left join fetch_stamps f " +
            "on f.subjectId = s.subjectId and f.section = s.section and f.viewerId = s.viewerId",
    )
    suspend fun sectionAges(): List<SubjectSectionAgeRow>

    @Query("delete from subjects where id in (:ids)")
    suspend fun deleteSubjects(ids: List<Int>)

    @Query("delete from episodes where subjectId in (:subjectIds)")
    suspend fun deleteEpisodesOf(subjectIds: List<Int>)

    @Query("delete from subject_sections where subjectId in (:subjectIds)")
    suspend fun deleteSectionsOf(subjectIds: List<Int>)

    /** 章节与子模块的取回时刻跟着条目走。 */
    @Query("delete from fetch_stamps where subjectId in (:subjectIds)")
    suspend fun deleteFetchStampsOf(subjectIds: List<Int>)

    @Query("delete from subject_sections where subjectId = :subjectId and section = :section and viewerId = :viewerId")
    suspend fun deleteSection(subjectId: Int, section: String, viewerId: Int)

    @Query("delete from fetch_stamps where subjectId = :subjectId and section = :section and viewerId = :viewerId")
    suspend fun deleteFetchStamp(subjectId: Int, section: String, viewerId: Int)

    @Query("select cacheKey, updatedAt from search_first_pages")
    suspend fun searchFirstPageAges(): List<SearchFirstPageAgeRow>

    @Query("delete from search_first_pages where cacheKey in (:keys)")
    suspend fun deleteSearchFirstPages(keys: List<String>)
}
