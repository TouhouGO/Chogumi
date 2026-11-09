package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** 叠几张封面。 */
const val COVER_STACK_COUNT = 3

/** 叠封面占的那一格（探索页整理列表一行打头的图；方块货架的图标也放在同样大的格里）。 */
val CoverStackSize = DpSize(44.dp, 56.dp)

private val CoverStackCoverWidth = 30.dp
private val CoverStackStep = 7.dp
private const val COVER_STACK_TILT = 5f

/**
 * 叠在一起的 [COVER_STACK_COUNT] 张封面（探索页整理列表一行打头的图，手势引导里整理列表的示意也是它）：
 * 从左往右每张错开 7dp、各转 −10°、−5°、0°，第一张（position 0）在最上面——设计决定（not in M3）。
 * 放在 [CoverStackSize] 大、左对齐的格里。每张画什么由 [cover] 定（封面、骨架，或不画），它拿到的 modifier
 * 已经定好宽、错开与转角，高由封面自己的比例给。
 */
@Composable
fun CoverStack(cover: @Composable (position: Int, modifier: Modifier) -> Unit) {
    // 第一张画在最后（最上面）
    (0 until COVER_STACK_COUNT).reversed().forEach { position ->
        val layer = COVER_STACK_COUNT - 1 - position
        cover(
            position,
            Modifier
                .width(CoverStackCoverWidth)
                .offset(x = CoverStackStep * layer)
                .graphicsLayer { rotationZ = COVER_STACK_TILT * (layer - (COVER_STACK_COUNT - 1)) },
        )
    }
}
