package io.github.touhougo.chogumi.core.model

/** 草稿属于哪种编辑器：发动态、在小组里发新帖、回复一条动态、在对象的评论区（日志、目录、角色、人物、单集）发一条或回复、写短信。 */
enum class PostDraftKind {
    TIMELINE,
    GROUP_TOPIC,
    TIMELINE_REPLY,
    COMMENT_REPLY,
    PM,
}

/**
 * 一份新帖草稿。[key] 是种类内的对象：动态固定为空串，小组新帖是小组的英文键，
 * 动态回复是「动态 id/回复的那一条」，评论区回复是「对象/id/回复的那一层楼」，短信是「收件人 uid/回复的那条线」（新信的线是 0）。
 * [title] 只有小组新帖与短信（可选的主题）有，别的为空串。
 */
data class PostDraft(
    val kind: PostDraftKind,
    val key: String,
    val title: String,
    val text: String,
    val updatedAt: Long,
)
