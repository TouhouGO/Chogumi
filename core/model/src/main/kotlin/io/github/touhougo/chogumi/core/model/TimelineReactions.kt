package io.github.touhougo.chogumi.core.model

/**
 * 一条动态的贴贴挂在哪：服务端按动态类别分两处存，贴贴走各自的端点，能贴的表情也按那一处。
 * 数据层映射动态时定一次（[TimelineItemBo.reactionTarget]），仓库发请求与面板画选择器都读它。
 */
sealed interface TimelineReactionTarget {
    /** 这里能贴的表情，顺序即选择器的顺序。 */
    val palette: List<Int>

    /** 说说（吐槽、改签名、改昵称）：贴在动态本身（`/p1/timeline/{id}/like`）。 */
    data object Status : TimelineReactionTarget {
        override val palette: List<Int> get() = COMMON_REACTIONS
    }

    /** 单条收藏：贴在那条收藏上（`/p1/subjects/-/collects/{collectId}/like`），与作品吐槽箱里的同一楼、同一组表情。 */
    data class Collect(val subjectId: Int, val collectId: Int) : TimelineReactionTarget {
        override val palette: List<Int> get() = reactionsOf(CommentSurface.SubjectComments(subjectId))
    }
}

/** 卡片上画的贴贴：各种按数从多到少（卡片只画得下前几种，先画多的）。 */
fun reactionsShownOf(item: TimelineItemBo): List<CommentReactionBo> =
    item.reactions.filter { it.count > 0 }.sortedByDescending { it.count }
