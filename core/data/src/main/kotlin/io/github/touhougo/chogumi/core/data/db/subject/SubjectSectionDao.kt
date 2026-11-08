package io.github.touhougo.chogumi.core.data.db.subject

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SubjectSectionDao : FetchStamps {

    @Query("select * from subject_sections where subjectId = :subjectId and section = :section and viewerId = :viewerId")
    suspend fun get(subjectId: Int, section: String, viewerId: Int): SubjectSectionEntity?

    @Upsert
    suspend fun upsert(entity: SubjectSectionEntity)

    @Query("select json from subject_sections where subjectId = :subjectId and section = :section and viewerId = :viewerId")
    suspend fun storedJson(subjectId: Int, section: String, viewerId: Int): String?

    /**
     * 存下一个模块的正文，并记下这次取回的时刻 [fetchedAt]。正文与库里的相同时不写：订阅本表的查询
     * （生涯阵容）不会因此重跑，新鲜期仍然往前走。
     */
    @Transaction
    suspend fun write(entity: SubjectSectionEntity, fetchedAt: Long) {
        if (storedJson(entity.subjectId, entity.section, entity.viewerId) != entity.json) upsert(entity)
        putStamp(FetchStampEntity(entity.subjectId, entity.section, entity.viewerId, fetchedAt))
    }

    /** 这个模块上次取回的时刻；从没取回过为 null。 */
    suspend fun fetchedAt(subjectId: Int, section: SubjectSection, viewerId: Int): Long? =
        fetchedAt(subjectId, section.name, viewerId)

    /** 一批条目几个模块的正文写入时刻（不读正文）：调用方比过时刻，变了的才去读正文。 */
    @Query(
        "select subjectId, section, updatedAt from subject_sections " +
            "where viewerId = :viewerId and section in (:sections) and subjectId in (:subjectIds)",
    )
    suspend fun stamps(subjectIds: List<Int>, sections: List<String>, viewerId: Int): List<SubjectSectionStamp>

    /** 表里有写入或删除就发一拍（值是最近一次落库时刻，表空时为 null）。 */
    @Query("select max(updatedAt) from subject_sections")
    fun observeLastWrite(): Flow<Long?>
}

/** 一行子模块缓存的主键与落库时刻。 */
data class SubjectSectionStamp(val subjectId: Int, val section: String, val updatedAt: Long)
