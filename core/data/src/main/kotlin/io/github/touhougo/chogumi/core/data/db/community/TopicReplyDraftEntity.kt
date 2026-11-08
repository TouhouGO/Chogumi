package io.github.touhougo.chogumi.core.data.db.community

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 话题回复的草稿（`TopicReplyDraft` 的一行）。同一个账号在同一个话题里回复同一层楼只有一行：
 * 主键是账号、话题类型、话题 id 与回复的楼 id。[accountId] 是写它的查看者（游客为 0，见 `AccountSession.viewerId`），
 * 换号后看不到上一个账号的草稿。[topicType] 存 `TopicType` 的名字。只在本机，不是缓存：缓存清理不碰它。
 */
@Entity(tableName = "topic_reply_drafts", primaryKeys = ["account_id", "topic_type", "topic_id", "post_id"])
data class TopicReplyDraftEntity(
    @ColumnInfo(name = "account_id") val accountId: Int,
    @ColumnInfo(name = "topic_type") val topicType: String,
    @ColumnInfo(name = "topic_id") val topicId: Int,
    @ColumnInfo(name = "post_id") val postId: Int,
    @ColumnInfo(name = "topic_title") val topicTitle: String,
    @ColumnInfo(name = "reply_to") val replyTo: Int,
    val floor: String?,
    @ColumnInfo(name = "quote_author") val quoteAuthor: String?,
    @ColumnInfo(name = "quote_excerpt") val quoteExcerpt: String?,
    val quote: String?,
    val text: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
