package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * EP 按钮的容器形状：圆角矩形，圆角在「全圆」（高的一半）与 [corner] 之间按 [squareness] 插值（0 = 全圆，1 = [corner]）。
 * 全圆按实际高度算，字号放大、容器变高时依然是全圆；弹簧过冲到区间外时圆角夹在 0 到全圆之间。
 * 数据类：值不变时是同一个形状，静止时不触发重绘。
 */
internal data class EpContainerShape(val corner: Dp, val squareness: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val full = size.minDimension / 2f
        val target = with(density) { corner.toPx() }
        val radius = (full + (target - full) * squareness).coerceIn(0f, full)
        return Outline.Rounded(RoundRect(size.toRect(), CornerRadius(radius)))
    }
}

/** 官方按钮形状 token（圆角以 dp 给出的 [CornerBasedShape]，如 ButtonSmall 的按下 / 方形容器）的圆角半径。 */
@Composable
internal fun Shape.cornerRadius(): Dp {
    val density = LocalDensity.current
    return with(density) { (this@cornerRadius as CornerBasedShape).topStart.toPx(Size.Zero, density).toDp() }
}
