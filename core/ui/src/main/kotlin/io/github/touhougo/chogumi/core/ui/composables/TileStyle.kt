package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.touhougo.chogumi.core.model.EpisodeCellKind
import io.github.touhougo.chogumi.core.model.WatchChange

/*
 * 「格子」这套样子（章节方格与收藏面板的进度尺共用）：看过连成一段 primary container，看到这里 primary 实色，
 * 已播未看是描边空位，未播出是「还没有」的灰。两处只经这里取颜色、形状与字，不各写一份。
 */

/** 一格和左右相邻的格是否连成一段：连着的那一侧角收成 extraSmall。 */
internal data class TileJoin(val start: Boolean, val end: Boolean)

internal val NoJoin = TileJoin(start = false, end = false)

/** 格子静止时的形状：medium 圆角，连段内相邻的角取 extraSmall（shape.md：紧密成组的元素用内角）。 */
internal fun Shapes.tileShape(join: TileJoin): CornerBasedShape = medium.copy(
    topStart = if (join.start) extraSmall.topStart else medium.topStart,
    bottomStart = if (join.start) extraSmall.bottomStart else medium.bottomStart,
    topEnd = if (join.end) extraSmall.topEnd else medium.topEnd,
    bottomEnd = if (join.end) extraSmall.bottomEnd else medium.bottomEnd,
)

/** 格子的形状：按住收成 small，下一集为圆，其余按连段取角。 */
internal fun Shapes.episodeTileShape(kind: EpisodeCellKind, join: TileJoin, pressed: Boolean): CornerBasedShape = when {
    pressed -> small
    kind == EpisodeCellKind.NEXT -> CircleShape
    else -> tileShape(join)
}

/** 看到这里与下一集的集号强调；真实格子、演示与进度尺共用。 */
internal val EpisodeCellKind.numberEmphasized: Boolean
    get() = this == EpisodeCellKind.CURRENT || this == EpisodeCellKind.NEXT

/** 一格的描边宽度与颜色。 */
internal data class TileBorder(val width: Dp, val color: Color)

/** 静止描边与待提交预览只在这里定；点中的看到这里用 onPrimary，与实心底区分。 */
internal fun ColorScheme.tileBorder(kind: EpisodeCellKind, preview: WatchChange?, target: Boolean): TileBorder = when {
    target -> TileBorder(TargetStroke, if (kind == EpisodeCellKind.CURRENT) onPrimary else primary)
    kind == EpisodeCellKind.NEXT -> TileBorder(NextStroke, primary)
    preview == WatchChange.FILL -> TileBorder(TileStroke, primary.copy(alpha = PREVIEW_FILL_STROKE_ALPHA))
    kind.outlined -> TileBorder(TileStroke, outlineVariant)
    else -> TileBorder(TileStroke, outlineVariant.copy(alpha = 0f))
}

/**
 * 格子的底色。「不填色」是看过色的透明版：从空位填成看过时颜色从无到有，不经过深灰（透明黑插值会发灰）。
 * 网页上单独标的想看用 tertiaryFixedDim：深浅色主题下色调不变，一眼看得出是另一种颜色。
 */
internal fun ColorScheme.tileContainer(kind: EpisodeCellKind): Color = when (kind) {
    EpisodeCellKind.WATCHED -> primaryContainer
    EpisodeCellKind.CURRENT -> primary
    EpisodeCellKind.WISH -> tertiaryFixedDim
    EpisodeCellKind.UNAIRED -> onSurface.copy(alpha = UNAIRED_CONTAINER_ALPHA)
    EpisodeCellKind.NEXT, EpisodeCellKind.AIRED, EpisodeCellKind.DROPPED -> primaryContainer.copy(alpha = 0f)
}

/** 格子里的字色。 */
internal fun ColorScheme.tileContent(kind: EpisodeCellKind): Color = when (kind) {
    EpisodeCellKind.WATCHED -> onPrimaryContainer
    EpisodeCellKind.CURRENT -> onPrimary
    EpisodeCellKind.NEXT -> primary
    EpisodeCellKind.WISH -> onTertiaryFixed
    EpisodeCellKind.DROPPED -> onSurfaceVariant
    EpisodeCellKind.UNAIRED -> onSurface.copy(alpha = UNAIRED_CONTENT_ALPHA)
    EpisodeCellKind.AIRED -> onSurface
}

/** 有描边的格子：已播未看、抛弃（描 [TileStroke] 的 outlineVariant）。 */
internal val EpisodeCellKind.outlined: Boolean
    get() = this == EpisodeCellKind.AIRED || this == EpisodeCellKind.DROPPED

/**
 * 格子里的数：title medium，「看到这里」与下一集用 emphasized；等宽数字，同样位数的数缩到同一个字号。
 * 格子边长随屏宽变、字随系统字号变：放不下时整体缩小，数不截断、不折行（不折行才不会在小数点后断成「1202.」）。
 * 最小到 label small 在 1 倍字号下的大小：系统字号调大时下限不跟着放大，最窄的格子也放得下「1202.5」（设计决定，not in M3）。
 * 行高与字距按字号的比例写成 em（[proportional]），随 autoSize 缩小的字一起缩。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TileNumber(text: String, emphasized: Boolean, color: Color) {
    val style = if (emphasized) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.titleMedium
    Text(
        text = text,
        style = style.proportional().copy(fontFeatureSettings = TABULAR_FIGURES),
        color = color,
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = unscaled(MaterialTheme.typography.labelSmall.fontSize), maxFontSize = style.fontSize),
    )
}

/**
 * 行高与字距换成相对字号的 em，比例与原样式相同。sp 的行高与字距不跟 autoSize 缩：2 倍字号下一行比最窄的格子还高，
 * 字距也放大一倍，缩到最小字号仍放不下。
 */
private fun TextStyle.proportional(): TextStyle = copy(
    lineHeight = (lineHeight.value / fontSize.value).em,
    letterSpacing = (letterSpacing.value / fontSize.value).em,
)

/** 系统字号为 1 倍时的 [size]：换算成当前字号下的 sp，画出来与 1 倍时一样大。 */
@Composable
private fun unscaled(size: TextUnit): TextUnit = with(LocalDensity.current) { size.value.dp.toSp() }

/**
 * 格子里的字：数居中，下面可能有一行小字（播出日）；两行放不下（大字号）时只留数。
 * 字左右各让出 [TileLabelInset]，不顶到格边。圆角只在格子上下 12dp 内收窄，居中的一行数碰不到；
 * 下一集的圆格里只有一行数（播出日只写在未播出的格子上），这一行在圆的直径上，让出的边距也够。
 */
@Composable
internal fun TileLabel(number: @Composable () -> Unit, below: (@Composable () -> Unit)? = null) {
    Layout(
        content = {
            number()
            below?.invoke()
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (constraints.maxWidth - TileLabelInset.roundToPx() * 2).coerceAtLeast(0))
        val numberPlaceable = measurables[0].measure(loose)
        val belowPlaceable = measurables.getOrNull(1)?.measure(loose)
            ?.takeIf { numberPlaceable.height + it.height <= constraints.maxHeight }
        val height = numberPlaceable.height + (belowPlaceable?.height ?: 0)
        layout(constraints.maxWidth, constraints.maxHeight) {
            val top = (constraints.maxHeight - height) / 2
            numberPlaceable.place((constraints.maxWidth - numberPlaceable.width) / 2, top)
            belowPlaceable?.place((constraints.maxWidth - belowPlaceable.width) / 2, top + numberPlaceable.height)
        }
    }
}

/** 抛弃：空位上一道从左下到右上的斜线，线宽同描边。 */
internal fun DrawScope.drawDroppedSlash(color: Color) {
    drawLine(color, Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = TileStroke.toPx())
}

/**
 * 一种格子静止时的面（底色、描边、抛弃的斜线；下一集是 2dp 的 primary 描边）：图例的色块与手势引导的演示格子照它画，
 * 与方格里的格子是同一份样子。方格里的格子颜色要过渡，各项分开取（[tileContainer] / [tileContent] / [outlined] / [drawDroppedSlash]）。
 */
@Composable
internal fun Modifier.tileFace(kind: EpisodeCellKind, shape: Shape): Modifier {
    val colors = MaterialTheme.colorScheme
    val border = colors.tileBorder(kind, preview = null, target = false)
    return background(colors.tileContainer(kind), shape)
        .border(border.width, border.color, shape)
        .then(if (kind == EpisodeCellKind.DROPPED) Modifier.drawBehind { drawDroppedSlash(colors.outlineVariant) } else Modifier)
}

/** 格子描边：已播未看、抛弃 1dp（设计稿，not in M3）。 */
internal val TileStroke = 1.dp

/** 未播出格子的底与字：M3 Expressive 按钮的不可用令牌（component-tokens.md BaselineButton：容器 10%、文字 38%）。 */
private const val UNAIRED_CONTAINER_ALPHA = 0.1f
private const val UNAIRED_CONTENT_ALPHA = 0.38f

/** 格子里的字离左右格边至少多远（设计决定，not in M3）。 */
private val TileLabelInset = 4.dp

/** OpenType 等宽数字。 */
private const val TABULAR_FIGURES = "tnum"

/** 格子描边：下一集 2dp，等确认时点的那一格 3dp（设计决定，not in M3）。 */
private val NextStroke = 2.dp
private val TargetStroke = 3.dp

/** 待标上的格子描 35% 的 primary 边（设计决定，not in M3）。 */
private const val PREVIEW_FILL_STROKE_ALPHA = 0.35f
