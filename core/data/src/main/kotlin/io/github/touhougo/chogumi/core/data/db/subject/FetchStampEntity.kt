package io.github.touhougo.chogumi.core.data.db.subject

import androidx.room.Entity
import androidx.room.Upsert
import androidx.room.Query

/**
 * 一次成功取回的时刻：章节（[section] 是 [EPISODES_STAMP]，[viewerId] 为 0）与条目子模块（[section] 是 [SubjectSection] 的名字）
 * 共用，供 SWR 判断新鲜度。
 *
 * 与正文分开存：取回的内容没变时正文表一行也不写（订阅它们的查询不重跑），可新鲜度仍要往前走。
 * 本表没有订阅者，写它不会让任何查询重跑。
 */
@Entity(tableName = "fetch_stamps", primaryKeys = ["subjectId", "section", "viewerId"])
data class FetchStampEntity(
    val subjectId: Int,
    val section: String,
    val viewerId: Int,
    val fetchedAt: Long,
)

/** 章节在 [FetchStampEntity.section] 里的名字：与 [SubjectSection] 的名字不重合。 */
const val EPISODES_STAMP = "EPISODES"

/** 读写 [FetchStampEntity] 的两个原语，章节与子模块的 DAO 共用。 */
interface FetchStamps {

    @Query("select fetchedAt from fetch_stamps where subjectId = :subjectId and section = :section and viewerId = :viewerId")
    suspend fun fetchedAt(subjectId: Int, section: String, viewerId: Int): Long?

    @Upsert
    suspend fun putStamp(stamp: FetchStampEntity)
}
