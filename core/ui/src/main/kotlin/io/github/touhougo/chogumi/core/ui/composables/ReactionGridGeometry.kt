package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 贴贴表情格子的几何（设计决定，not in M3）：表情排 [ROWS] 行，12 个一行 6 格，格子 44 加左右 8 正好是
 * M3 菜单的最大宽度 280dp（feedback.md Menus），一次点击就能贴，不用横滑或二级菜单。
 * core:reader 的贴贴选择器与手势引导里长按菜单的示意都读这里。
 */
object ReactionGridGeometry {
    const val ROWS = 2
    val Cell: Dp = 44.dp
    val Smiley: Dp = 24.dp
    val Padding: Dp = 8.dp

    /** [count] 个表情一行几格：排满 [ROWS] 行（12 个是 6×2，作品吐槽的 8 个是 4×2，不会有半行）。 */
    fun columnsOf(count: Int): Int = ((count + ROWS - 1) / ROWS).coerceAtLeast(1)
}
