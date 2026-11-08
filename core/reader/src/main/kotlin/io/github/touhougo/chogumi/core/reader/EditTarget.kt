package io.github.touhougo.chogumi.core.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.common.bbcode.QuoteHeader
import io.github.touhougo.chogumi.core.common.bbcode.splitLeadingQuote
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.res.R

/**
 * 改自己的一层楼：编辑器里只改引用之后的正文，开头的引用块（回复楼中楼时写进去的）原样留着、画成引用条，提交时接回。
 *
 * @property postId 改的是哪层楼。
 * @property floor 楼层号（「#6」「#6-2」）；帖子正文为 null。
 * @property lead 正文开头的引用块原文（连同其后的空白）；没有引用时为空串。
 * @property quote [lead] 画成的引用条；没有引用时为 null。
 * @property body 引用之后的正文：编辑器打开时的内容。
 */
data class EditTarget(
    val postId: Int,
    val floor: String?,
    val lead: String,
    val quote: QuoteHeader?,
    val body: String,
) {
    /** 提交的正文：原样的引用块 + 改过的正文。 */
    fun contentOf(text: String): String = lead + text
}

/** 长按自己的楼选「编辑」时改的是什么（全仓唯一的构造处）。[floorNumber] 空串表示帖子正文。 */
internal fun editTargetOf(comment: CommentItemBo, floorNumber: String): EditTarget {
    val split = splitLeadingQuote(comment.content)
    return EditTarget(
        postId = comment.id,
        floor = floorNumber.ifEmpty { null },
        lead = comment.content.substring(0, comment.content.length - split.body.length),
        quote = split.quote,
        body = split.body,
    )
}

/** 编辑面板的标题：「编辑 #6」，或「编辑正文」。 */
@Composable
fun EditTarget.title(): String = floor?.let { stringResource(R.string.composer_edit_floor, it) } ?: stringResource(R.string.composer_edit_post)
