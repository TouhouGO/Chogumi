package io.github.touhougo.chogumi.core.model

import io.github.touhougo.chogumi.core.foundation.Outcome

/** 报告的对象类型：服务端 `ReportType`（契约 `CreateReport.type`），只列 App 里有报告入口的几类。 */
enum class ReportType(val value: Int) {
    USER(6),
    GROUP_TOPIC(7),
    GROUP_REPLY(8),
    SUBJECT_TOPIC(9),
    SUBJECT_REPLY(10),
    EPISODE_REPLY(11),
    CHARACTER_REPLY(12),
    PERSON_REPLY(13),
    BLOG(14),
    BLOG_REPLY(15),
    TIMELINE_REPLY(17),
    INDEX_REPLY(19),
}

/** 报告什么：类型 + 对象 id（用户 id、话题 id、楼 id……）。 */
data class ReportTarget(val type: ReportType, val id: Int)

/** 报告一个用户（他人主页 ⋮）。 */
fun userReportTargetOf(userId: Int) = ReportTarget(ReportType.USER, userId)

/**
 * 报告 [surface] 里的一层楼（唯一判定）：帖子正文（[isPost]）报告的是话题、日志本身（id 取话题 / 日志的），其余是这层楼（楼 id）。
 * null 表示这里不能报告：作品吐槽挂在收藏上，服务端没有对应的报告类型。
 */
fun floorReportTargetOf(surface: CommentSurface, postId: Int, isPost: Boolean): ReportTarget? = when (surface) {
    is CommentSurface.Topic -> when (surface.type) {
        TopicType.GROUP -> if (isPost) ReportTarget(ReportType.GROUP_TOPIC, surface.topicId) else ReportTarget(ReportType.GROUP_REPLY, postId)
        TopicType.SUBJECT -> if (isPost) ReportTarget(ReportType.SUBJECT_TOPIC, surface.topicId) else ReportTarget(ReportType.SUBJECT_REPLY, postId)
    }
    is CommentSurface.Blog -> if (isPost) ReportTarget(ReportType.BLOG, surface.blogId) else ReportTarget(ReportType.BLOG_REPLY, postId)
    is CommentSurface.Episode -> ReportTarget(ReportType.EPISODE_REPLY, postId)
    is CommentSurface.Mono -> ReportTarget(monoTypeSpec(surface.type).replyReportType, postId)
    is CommentSurface.Index -> ReportTarget(ReportType.INDEX_REPLY, postId)
    is CommentSurface.Timeline -> ReportTarget(ReportType.TIMELINE_REPLY, postId)
    is CommentSurface.SubjectComments -> null
}

/**
 * 对别人的写操作（报告、加好友、解除好友、绝交），楼层菜单、用户卡片与他人主页共用的数据通路；实现在数据层（`UserRepository`）。
 * 都要已登录，未登录时返回失败而不是发裸请求。
 */
interface UserModeration {
    /** 报告 [target]；[reason] 是服务端 `ReportReason`。同一对象重复报告服务端回 409。成功带服务端致谢文案。 */
    suspend fun report(target: ReportTarget, reason: Int, comment: String?): Outcome<String>

    /** 加好友。服务端幂等：已是好友时再加不报错。 */
    suspend fun addFriend(username: String): Outcome<Unit>

    /** 解除好友（界面先弹确认）。做成后本机的好友名单跟着变，名字旁的「好友」小牌与关注状态按钮当场更新。 */
    suspend fun removeFriend(username: String): Outcome<Unit>

    /** 绝交；做成后本机名单换成服务端给的新名单，已显示的楼层当场不显示这个人的楼，之后取到的帖子、动态也按它过滤。 */
    suspend fun addUserToBlocklist(username: String): Outcome<Unit>
}
