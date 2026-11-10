package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.common.bbcode.copyTextOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.ReaderTypography
import io.github.touhougo.chogumi.core.ui.common.copyOnLongPress
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText

/** 简介区块的 testTag：真实简介与骨架共用。 */
const val SynopsisTag = "synopsis"

/** 简介折叠时的行数（设计稿：作品详情与角色 / 人物页都是 3 行）。真实简介与骨架共用。 */
private const val SYNOPSIS_COLLAPSED_LINES = 3

/** 简介「展开 ∨」贴左对齐（原型 `.morebtn`）；真实按钮与骨架占位共用。 */
private val SynopsisTogglePadding = PaddingValues(horizontal = 0.dp)

/**
 * 简介（原型 `.summary.fold3`）：折叠 3 行 + 「展开 ∨ / 收起 ∧」文字按钮，左右 16、上 12。全仓的简介只此一处。
 *
 * 文案经 [BBCodeText] 渲染（目录简介里也带 BBCode），**只解析一次**（[remember]）；
 * 「有没有超出 3 行」和「要不要画按钮」交给楼层正文同一份 [FoldableText]，不无条件画按钮。
 * 纯文本简介的排版：`bodyMedium` + `onSurfaceVariant`。点正文与按钮一样切换折叠；长按正文复制整段简介
 * （[copyOnLongPress]，去掉标记的纯文字 [copyTextOf]，折叠着也复制全文）。
 *
 * [text] 为 null 时画骨架：同样行数的 [SkeletonText]，下面放 [FoldTogglePlaceholder] 占住按钮的高度。
 * 骨架按最大高度预留，简介不足 3 行时按钮不画，下面的内容只会上收、不会被顶下去。
 */
@Composable
fun CollapsibleSynopsis(
    text: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyle = ReaderTypography.compact
    Column(
        modifier = modifier
            .testTag(SynopsisTag)
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
    ) {
        if (text != null) {
            val ast = remember(text) { BBCodeParser.parse(text) }
            val copyText = remember(text) { copyTextOf(text) }
            FoldableText(
                ast = ast,
                expanded = expanded,
                onToggle = onToggle,
                collapsedLines = SYNOPSIS_COLLAPSED_LINES,
                expandLabel = R.string.detail_expand,
                collapseLabel = R.string.detail_collapse,
                style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                toggleContentPadding = SynopsisTogglePadding,
                contentGestures = Modifier.copyOnLongPress(copyText, onClick = onToggle),
            )
        } else {
            SkeletonText(textStyle, Modifier.fillMaxWidth(), lines = SYNOPSIS_COLLAPSED_LINES)
            FoldTogglePlaceholder(expandLabel = R.string.detail_expand, contentPadding = SynopsisTogglePadding)
        }
    }
}
