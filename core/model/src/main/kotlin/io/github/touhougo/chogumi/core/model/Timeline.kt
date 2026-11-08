package io.github.touhougo.chogumi.core.model

/** 时间线所有 createdAt 均为 epoch 毫秒；传输层的秒只在 mapper 转换一次。 */
enum class TimelineMode {
    FRIENDS, ALL, USER;

    /** 这条时间线里只有当前账号关注的人（好友动态）：人人都是好友，作者名旁不画「好友」小牌。 */
    val onlyFriends: Boolean get() = this == FRIENDS
}

data class TimelineQuery(
    val mode: TimelineMode = TimelineMode.FRIENDS,
    val username: String? = null,
)

/**
 * 服务端给时间线动态分的大类（`cat`），编号只在 [timelineCategoryOf] 里认。数据层、渲染层、合并都读这个枚举，
 * 不在别处比裸数字。数据库与界面状态里 `cat` 仍存 Int，认不出的编号（含将来新增的）是 null。
 */
enum class TimelineCategory(val code: Int) {
    DAILY(1),
    WIKI(2),
    COLLECT(3),
    PROGRESS(4),
    STATUS(5),
    BLOG(6),
    INDEX(7),
    MONO(8),

    /** 「天窗」：服务端不给内容。 */
    SKYLIGHT(9),
}

/** `cat` 编号 → 大类；认不出的编号是 null。 */
fun timelineCategoryOf(cat: Int): TimelineCategory? = TimelineCategory.entries.firstOrNull { it.code == cat }

/** 一条吐槽最多几个字（服务端在 HTML 转义后检查）。编辑器的计数 / 禁用与仓库的护栏都读它。 */
const val TIMELINE_TEXT_LIMIT = 380

/**
 * 条目/事件在时间线里的展示目标。
 *
 * `title` 与 [description] 一律是**数据**（服务端给的名字、用户填的评论/签名/简介），
 * 不是界面文案——界面文案由 `core:ui` 依 [cat]/[type] 决定。数据层不得在此产出措辞，
 * 否则版式一改就要改数据层。
 */
data class TimelineTargetBo(
    val title: String,
    val url: String,
    val subject: SubjectBo? = null,
    val description: String = "",
    val rating: Double? = null,
    /**
     * 描述片段里**属于服务端统计**的那些，已带数值、待渲染层套模板。
     *
     * 与 [description] 分列而非拼成一句：服务端统计（成员数、章节数、关联数）
     * 的措辞随界面语言变，用户内容（签名、评论）不变，混在一起就无法分别处理。
     */
    val segments: List<TimelineSegmentBo> = emptyList(),
    /** 关联图像（条目封面、人物立绘、用户头像、小组图标等）。 */
    val imageUrl: String? = null,
)

/**
 * 一条待渲染的描述片段。
 *
 * 只带**值**，不带措辞——`%1$d` 这样的模板在 `core:ui` 的资源里。
 */
sealed interface TimelineSegmentBo {
    /** 群组成员数。 */
    data class GroupMembers(val count: Int) : TimelineSegmentBo

    /** 目录关联内容数。 */
    data class IndexTotal(val count: Int) : TimelineSegmentBo

    /**
     * 批量收视进度。四段各自可空：章节总数、看到了 N 话、卷数总数、看到了 N 卷。
     *
     * `epsTotal`/`volsTotal` 是**字符串**：旧数据（PHP 时代）缺字段时服务端填 `??`，
     * 客户端不能当数字解析。
     */
    data class ProgressBatch(
        val epsTotal: String,
        val epsUpdate: Int?,
        val volsTotal: String,
        val volsUpdate: Int?,
    ) : TimelineSegmentBo {
        /** 话数、卷数都没有大于 0 的进度（如进度被改回 0）：这条记录没有可展示的内容。 */
        val hasNoProgress: Boolean get() = (epsUpdate ?: 0) <= 0 && (volsUpdate ?: 0) <= 0
    }

    /**
     * 单集收视进度：集号 + 集名。
     *
     * `sort` 是 Double，与 [name] 之间只隔一个空格——见 `core:ui` 的说明。
     */
    data class EpisodeProgress(val sort: Double, val name: String) : TimelineSegmentBo
}

data class TimelineItemBo(
    val id: Long,
    val uid: Int,
    val user: UserBo?,
    val cat: Int,
    val type: Int,
    val createdAt: Long,
    val text: String,
    val targets: List<TimelineTargetBo> = emptyList(),
    val reactions: List<CommentReactionBo> = emptyList(),
    val replies: Int = 0,
    /** 贴贴挂在哪；null 表示这条动态不能贴（批量收藏、进度、日志……），已有的贴贴只读。 */
    val reactionTarget: TimelineReactionTarget? = null,
    val batch: Boolean = false,
    /** 观看者本人发的：只有这种能删（取数时按当前会话的账号判定，界面与删除校验都读它）。 */
    val isOwn: Boolean = false,
)
