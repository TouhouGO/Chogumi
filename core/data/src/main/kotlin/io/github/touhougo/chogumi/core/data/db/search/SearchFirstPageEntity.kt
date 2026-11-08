package io.github.touhougo.chogumi.core.data.db.search

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一组搜索条件第一页的可见条目，供探索页货架与搜索页先出上次的结果。只存条目 id（按服务端顺序），
 * 条目本身在 `subjects` 表。[cacheKey] 由 `SearchRepository` 按条件、NSFW 开关、账号与每页条数算出；
 * [total] 与 [nextOffset] 是这一页取回时服务端给的总数与下一页游标（搜索页从它接着往下翻）；
 * [updatedAt] 是这一页取回的时刻（新鲜期见 `CacheTtl.SEARCH_FIRST_PAGE`）。
 */
@Entity(tableName = "search_first_pages")
data class SearchFirstPageEntity(
    @PrimaryKey val cacheKey: String,
    val subjectIdsJson: String,
    val total: Int,
    val nextOffset: Int?,
    val updatedAt: Long,
)
