package io.github.touhougo.chogumi.core.model

/**
 * 条目讨论版里的一个话题（列表项，不含楼层）。
 *
 * @property createdAt / [updatedAt] epoch 毫秒；0 表示未知。
 */
data class SubjectTopicBo(
    val id: Int,
    val title: String,
    val creator: UserBo?,
    val replyCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 话题 / 日志眉题里的作品：小封面 + 作品名，点了回作品。
 */
data class TopicSubjectBo(
    val id: Int,
    val type: SubjectType,
    override val name: String,
    override val nameCn: String,
    val cover: String?,
) : NamedEntity

/** 单集讨论页的头部：这一集，与它属于的作品（眉题，点了回作品）；接口没带作品时为 null，不画眉题。 */
data class EpisodeThreadBo(val episode: EpisodeBo, val subject: TopicSubjectBo?)

/** 话题挂在哪：作品讨论版或小组。路由、楼层所在的评论区、网页地址与接口都按它分。 */
enum class TopicType { SUBJECT, GROUP }

/** 话题的上级：作品或小组，帖子页眉题画它。 */
sealed interface TopicParent {
    data class Subject(val subject: TopicSubjectBo) : TopicParent
    data class Group(val group: GroupSummaryBo) : TopicParent
}

/**
 * 话题详情（作品讨论版与小组同一形状）：服务端一次返回全部楼层（实测 7822 楼也不分页，2026-10-05 实测）。
 *
 * [threads] 是两层楼：第一串的主楼就是 #1 楼（楼主的正文）。楼主徽标由 `creatorId == anchor.creatorId` 派生，
 * 不另存字段。
 */
data class TopicDetailBo(
    val id: Int,
    val title: String,
    val parent: TopicParent,
    val creatorId: Int,
    val replyCount: Int,
    val createdAt: Long,
    val threads: List<CommentThreadBo>,
)

/**
 * 「谁在看」里的一个收藏者。[updatedAt] epoch 毫秒。
 */
data class SubjectCollectorBo(
    val user: UserBo,
    val type: CollectionType,
    val rate: Int,
    val updatedAt: Long,
)
