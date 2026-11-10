package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 贴贴 chip 的几何（原型 `.react`，设计决定，not in M3）：高 28、左右 10、全圆角、表情 16，表情与计数隔 4；
 * 一排 chip 横竖都隔 6。core:reader 的贴贴一排与手势引导里贴贴的示意都读这里。
 */
object ReactionChipGeometry {
    val Height: Dp = 28.dp
    val Smiley: Dp = 16.dp
    val Padding: Dp = 10.dp
    val Gap: Dp = 4.dp
    val Spacing: Dp = 6.dp
}

/** 贴贴 chip 的底色：我贴过的 `primaryContainer`，别的 `surfaceContainer`（比页面高一档）。 */
@Composable
fun reactionChipContainer(selected: Boolean): Color =
    if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer

/** 贴贴 chip 的字色，与 [reactionChipContainer] 成对。 */
@Composable
fun reactionChipContent(selected: Boolean): Color =
    if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

/**
 * 一颗贴贴 chip 的样子：全圆角的一块，里面表情在前、计数在后（[ReactionChipGeometry]）。颜色、手势与动画由调用方给：
 * [modifier] 落在外面这块上（缩放），[rowModifier] 落在里面一排上（手势、尺寸过渡）。
 */
@Composable
fun ReactionChipFace(
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    rowModifier: Modifier = Modifier,
    face: @Composable RowScope.() -> Unit,
) {
    Surface(shape = CircleShape, color = container, contentColor = content, modifier = modifier) {
        Row(
            modifier = rowModifier
                .heightIn(min = ReactionChipGeometry.Height)
                .padding(horizontal = ReactionChipGeometry.Padding),
            horizontalArrangement = Arrangement.spacedBy(ReactionChipGeometry.Gap),
            verticalAlignment = Alignment.CenterVertically,
            content = face,
        )
    }
}
