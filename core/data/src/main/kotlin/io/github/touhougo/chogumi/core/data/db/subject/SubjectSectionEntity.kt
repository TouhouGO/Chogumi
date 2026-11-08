package io.github.touhougo.chogumi.core.data.db.subject

import androidx.room.Entity

/**
 * 条目详情页的子模块缓存（关联条目、角色、制作人员、统计用的评分拆分等），一行一个「条目 × 模块 × 查看者」。
 *
 * - [section]：[SubjectSection] 的名字；各模块共用一张表，读写与新鲜度判断只有一套。
 * - [viewerId]：发起请求的账号 id，游客为 0。服务端对匿名请求会剔除 NSFW 条目，
 *   游客拿到的结果不能给登录后的账号复用，反之亦然。
 * - [json]：该模块的**全量**内容（kotlinx JSON）；NSFW 过滤在读取边界做，不写进缓存。
 * - [updatedAt]：正文上次变化的时刻（epoch 毫秒）；取回时刻（SWR 的新鲜度）在 [FetchStampEntity]。
 */
@Entity(tableName = "subject_sections", primaryKeys = ["subjectId", "section", "viewerId"])
data class SubjectSectionEntity(
    val subjectId: Int,
    val section: String,
    val viewerId: Int,
    val json: String,
    val updatedAt: Long,
)

/**
 * 条目详情页里各自独立请求、独立缓存的子模块。统计 tab 的三项：[RATING_SPLIT] 网页透视页的评分拆分，
 * [RECS] 推荐作品的 id（作品本身合并进条目表），[RECENT_COLLECTS] 最近一批收藏的时刻。
 */
enum class SubjectSection { RELATIONS, CHARACTERS, PERSONS, RATING_SPLIT, RECS, RECENT_COLLECTS }

/**
 * 生涯阵容读的两个模块：角色（带声优）与制作人员。条目详情页与生涯统计共用这一份缓存；
 * 本地清理据此保护收藏里作品的阵容（重拉一部要两个请求，收藏多的账号过期一次就是几百上千个）。
 */
internal val LINEUP_SECTIONS = listOf(SubjectSection.CHARACTERS, SubjectSection.PERSONS)
