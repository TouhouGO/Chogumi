package io.github.touhougo.chogumi.core.model

import kotlinx.serialization.Serializable

/**
 * 关联内容所需图片，不复用任何 host 的 transport DTO。
 *
 * @Serializable（连同下方 [RelatedPersonBo]、[RelatedCharacterBo]）：条目详情的角色、制作人员
 * 按 kotlinx JSON 整存进本地缓存表；字段改名或删减时旧缓存解析失败，仓库按未命中处理、重新拉取。
 */
@Serializable
data class RelatedImagesBo(val large: String? = null, val common: String? = null,
    val medium: String? = null, val small: String? = null, val grid: String? = null) {

    /**
     * 角色 / 人物**圆形头像**该用的图：整张立绘（`medium` 优先），交给 core:ui 的 `CircleAvatar`（顶对齐裁圆） 取上方的方块——
     * 立绘的脸几乎总在上部。
     *
     * **不用 `grid`**（2026-10-06 实测：代表角色会被切头）：
     * - p1 接口的 `grid` 是 `/r/100x100/pic/crt/l/…`——站方图片服务把整张图**居中**裁成方块，全身立绘切到腰腿；
     * - v0 接口的 `grid` 是 `/pic/crt/g/…`，取景是顶部，但只有 75×75，放大到 72dp 头像发糊。
     * `medium` 两边都是等比缩放的整图（p1 宽 200、v0 宽 400），顶对齐裁出来的就是 `/pic/crt/g/` 的取景、清晰度够。
     *
     * 回退：medium → large → small → grid；全空返回 null（调用方画占位底色）。
     * 不回退到 `/v0/{characters|persons}/{id}/image?type=grid`：实测缺图的条目这个接口只会 302 到站方灰色占位图
     * （2026-10-05 实测）。
     */
    fun avatarUrl(): String? = medium.orNullIfBlank()
        ?: large.orNullIfBlank()
        ?: small.orNullIfBlank()
        ?: grid.orNullIfBlank()

    /** **条目海报**该用的图：`large` 就是 3:4 封面本身，不能换成方图。 */
    fun posterUrl(): String? = large.orNullIfBlank()
        ?: common.orNullIfBlank()
        ?: medium.orNullIfBlank()
        ?: grid.orNullIfBlank()
}

/** 服务端用空串而非缺键表示"没有这张图"；空串当有值会渲染成破图。 */
private fun String?.orNullIfBlank(): String? = takeIf { !it.isNullOrBlank() }

@Serializable
data class RelatedPersonBo(val id: Int, val name: String, val type: Int, val images: RelatedImagesBo? = null,
    val relation: String = "")

/**
 * 关联角色。[type] 是角色类别（1 角色 2 机体 3 舰船 4 组织），[role] 是在这部作品里的定位（主角 / 配角 / 客串，
 * 没有定位的列表为 null）。[role] 没有默认值：缓存里没有这个字段的旧行解析失败，按未命中重新拉取，不会显示成没有定位。
 */
@Serializable
data class RelatedCharacterBo(val id: Int, override val name: String, val summary: String, val type: Int,
    val role: CastRole?, val images: RelatedImagesBo? = null, val actors: List<RelatedPersonBo> = emptyList(),
    override val nameCn: String = "") : NamedEntity

/**
 * 目录（Index）列表项。
 *
 * 只保留列表展示需要的字段：`stats`/`award` 这类详情才有意义的字段不在此列，
 * 避免把服务端的完整形状带进领域层。
 */
data class IndexBo(
    val id: Int,
    val title: String,
    val total: Int,
    /** 创建者；服务端在部分端点上不返回，故可空。 */
    val creator: UserBo? = null,
    /** epoch 毫秒；0 表示未知 */
    val createdAt: Long = 0L,
    /** 私密目录（只有创建者自己看得到；列表行带锁）。 */
    val isPrivate: Boolean = false,
)

/**
 * 用户日志（Blog）列表项。
 *
 * 列表只要标题、摘要、日期与留言数；正文只在详情页取，不进列表模型。
 */
data class SlimBlogBo(
    val id: Int,
    val title: String,
    /** 服务端生成的纯文本摘要（列表两行展示） */
    val summary: String = "",
    val replies: Int = 0,
    /** epoch 毫秒；0 表示未知 */
    val createdAt: Long = 0L,
    val author: UserBo? = null,
)

/**
 * 日志详情：正文按 BBCode 渲染；留言另取（接口一次给全部楼层）。
 */
data class BlogDetailsBo(
    val id: Int,
    val title: String,
    /** BBCode 原文 */
    val content: String,
    val replies: Int = 0,
    val createdAt: Long = 0L,
    val author: UserBo? = null,
    /** 当前账号写的日志（取数据时按当前会话判定，同 [CommentItemBo.isOwn]）：正文楼的长按菜单不对自己出加好友、绝交。 */
    val isOwn: Boolean = false,
) {
    /**
     * 日志正文画成楼主楼（与讨论版话题的 #1 楼同一个楼层组件：头像、昵称、楼主、时间）。
     * 楼 id 取日志 id 的相反数：正文不是留言表里的楼，负数不会与任何留言 id 撞上。
     */
    fun openingPost(): CommentThreadBo = CommentThreadBo(
        anchor = CommentItemBo(
            id = -id,
            mainId = id,
            relatedId = 0,
            creatorId = author?.id ?: 0,
            creatorNickname = author?.nickname.orEmpty(),
            creatorUsername = author?.username.orEmpty(),
            creatorAvatar = author?.avatarUrl,
            content = content,
            createdAt = createdAt,
            state = CommentState.NORMAL,
            creatorSign = author?.sign.orEmpty(),
            isOwn = isOwn,
        ),
        replies = emptyList(),
    )
}

