package io.github.touhougo.chogumi.core.model

/**
 * 楼层所在的评论区。能贴哪些表情、回复怎么写，只在这里按类型决定一次（[reactionsOf] / [replyStyleOf]）。
 */
sealed interface CommentSurface {
    /** 作品讨论版或小组的一个话题（楼层是 subject post / group post）。 */
    data class Topic(val type: TopicType, val topicId: Int) : CommentSurface

    /** 作品的吐槽（收藏评论）。 */
    data class SubjectComments(val subjectId: Int) : CommentSurface

    /**
     * 挂在一个对象上的评论区（服务端 `/p1/{对象}/{id}/comments`）：日志、目录的留言，角色、人物的吐槽箱，单集的评论。
     * 都是两层楼，发一条与回复一层楼都走同一个接口（`replyTo`），按对象分接口只在数据层一处。
     */
    sealed interface Box : CommentSurface

    data class Blog(val blogId: Int) : Box

    data class Index(val indexId: Int) : Box

    /** 角色 / 人物的吐槽箱。 */
    data class Mono(val type: MonoType, val id: Int) : Box

    /** 单集的评论（章节长按面板、单集讨论页）。 */
    data class Episode(val episodeId: Int) : Box

    /** 一条动态的回复（动态详情面板）。 */
    data class Timeline(val timelineId: Long) : CommentSurface
}

/**
 * 这里能贴的表情（服务端实测有贴贴接口的才有）；空表示没有贴贴：
 * 话题回复、单集评论 → [COMMON_REACTIONS]（服务端 `like.ts`：GroupReply、SubjectReply 与 EpisodeReply 同用通用组）；
 * 作品吐槽 → [SUBJECT_COLLECT_REACTIONS]；日志、目录、角色、人物的留言与动态的回复没有贴贴接口
 * （动态只有整条的贴贴 `/p1/timeline/{id}/like`）。
 */
fun reactionsOf(surface: CommentSurface): List<Int> = when (surface) {
    is CommentSurface.Topic -> topicReactions()
    is CommentSurface.Episode -> COMMON_REACTIONS
    is CommentSurface.SubjectComments -> SUBJECT_COLLECT_REACTIONS
    is CommentSurface.Blog, is CommentSurface.Index, is CommentSurface.Mono, is CommentSurface.Timeline -> emptyList()
}

/** 话题的楼层能贴的表情（主楼与回复都一样）：发新帖时还没有话题，编辑器从这里问（小组发帖）。 */
fun topicReactions(): List<Int> = COMMON_REACTIONS

/** 回复一层楼的写法：都在 App 里的编辑器写（发送前人机验证）。 */
enum class ReplyStyle {
    /** 评论区分两层，回复挂在被回复的主楼下（话题、对象的评论区）。 */
    THREADED,

    /** 平铺的评论区：回复总是新的一条，开头 @ 被回复的人（动态的回复：服务端存下的回复全是一层，网页也这样写）。 */
    FLAT,
}

/**
 * 这里的楼层怎么回复；null 表示不能回复：作品吐槽挂在收藏上，服务端与网页都没有回复吐槽（只能贴贴）。
 * 话题、对象的评论区（[CommentSurface.Box]）与动态的回复都有回复接口（`replyTo` 指向主楼），全在 App 里写。
 */
fun replyStyleOf(surface: CommentSurface): ReplyStyle? = when (surface) {
    is CommentSurface.Topic, is CommentSurface.Box -> ReplyStyle.THREADED
    is CommentSurface.Timeline -> ReplyStyle.FLAT
    is CommentSurface.SubjectComments -> null
}

/** 自己的一层楼在楼层菜单里能做什么。 */
data class OwnFloorActions(val edit: Boolean, val delete: Boolean) {
    companion object {
        val None = OwnFloorActions(edit = false, delete = false)
    }
}

/**
 * 楼层菜单给不给「编辑」「删除」（唯一判定）。只有话题（作品讨论版、小组）接了编辑、删除自己楼层的接口；
 * 规则照服务端（`lib/topic/post.ts` 的 update / delete）：
 * - 只能动自己的、没删除的楼（[CommentItemBo.isOwn]）；
 * - 编辑：这层楼下面还没有楼中楼（服务端查挂在它下面的回复，所以楼中楼本身总能改）；话题关闭时服务端拒绝，失败时说明；
 * - 删除：帖子正文（话题 #1 楼，[isPost]）不删：删了是一个没有正文的话题，网页上也只删回复。
 *
 * @param repliesBelow 挂在这层楼下的楼中楼条数（楼中楼自己传 0）。
 */
fun ownFloorActionsOf(surface: CommentSurface, item: CommentItemBo, repliesBelow: Int, isPost: Boolean): OwnFloorActions {
    val editable = when (surface) {
        is CommentSurface.Topic -> true
        is CommentSurface.SubjectComments, is CommentSurface.Blog, is CommentSurface.Index,
        is CommentSurface.Mono, is CommentSurface.Episode, is CommentSurface.Timeline -> false
    }
    if (!editable || !item.isOwn || item.state == CommentState.DELETED) return OwnFloorActions.None
    return OwnFloorActions(edit = repliesBelow == 0, delete = !isPost)
}
