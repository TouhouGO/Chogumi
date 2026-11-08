package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.ExploreTile
import io.github.touhougo.chogumi.core.model.ExploreTitle
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.pressedCorner

/**
 * 方块的三种规格。几何来自原型：
 * 维度块第 182/185/186 行、年代块第 192–194 行、公司块第 195–197 行。
 * 公司方块与其他方块同样点开即按条件搜索，外观取原型第 195–197 行的 Studio 规格。
 */
enum class ExploreTileStyle { Dimension, Decade, Studio }

/**
 * 方块 → 规格的**唯一**判定（绝对红线一）。规格一律由所在货架的标题决定，
 * 界面层只许从这里派生，不许再写第二个 `when`。
 */
fun exploreTileStyle(shelfTitle: ExploreTitle): ExploreTileStyle = when (shelfTitle) {
    is ExploreTitle.DecadeBrowse -> ExploreTileStyle.Decade
    is ExploreTitle.CompanyBrowse -> ExploreTileStyle.Studio
    else -> ExploreTileStyle.Dimension
}

/**
 * 一档规格的几何。排版不在这里——字号走 M3 字阶角色，见
 * [exploreTileBigStyle] 与 [exploreTileLabelStyle]。
 */
internal data class ExploreTileSpec(
    val width: Dp,
    val height: Dp,
    /** 大字：装饰水印，被容器裁掉一角，不读给无障碍。 */
    val bigAlpha: Float,
    val bigAlignment: Alignment,
    val bigOffsetX: Dp,
    val bigOffsetY: Dp,
    /** 标签：真正要读的那行。 */
    val labelAlpha: Float,
    val labelAlignment: Alignment,
    val labelOffsetY: Dp,
)

internal fun exploreTileSpec(style: ExploreTileStyle): ExploreTileSpec = when (style) {
    // 原型第 182、185、186 行
    ExploreTileStyle.Dimension -> ExploreTileSpec(
        width = 148.dp, height = 88.dp,
        bigAlpha = 0.28f, bigAlignment = Alignment.BottomEnd,
        bigOffsetX = 6.dp, bigOffsetY = 12.dp,
        labelAlpha = 1f, labelAlignment = Alignment.TopStart, labelOffsetY = 10.dp,
    )
    // 原型第 192–194 行：大字翻到左下、不透明、缩一档；标签退成副标
    ExploreTileStyle.Decade -> ExploreTileSpec(
        width = 128.dp, height = 88.dp,
        bigAlpha = 1f, bigAlignment = Alignment.BottomStart,
        bigOffsetX = (-2).dp, bigOffsetY = 4.dp,
        labelAlpha = 0.8f, labelAlignment = Alignment.TopStart, labelOffsetY = 10.dp,
    )
    // 原型第 195–197 行：矮一档，标签沉到左下
    ExploreTileStyle.Studio -> ExploreTileSpec(
        width = 120.dp, height = 64.dp,
        bigAlpha = 0.28f, bigAlignment = Alignment.BottomEnd,
        bigOffsetX = 4.dp, bigOffsetY = (-6).dp,
        labelAlpha = 1f, labelAlignment = Alignment.BottomStart, labelOffsetY = (-10).dp,
    )
}

/**
 * 水印的字体角色。原型的 45/52 与 36/44 正好是 M3 字阶的 Display medium 与
 * Display small（`.agent-skills/m3-expressive/references/tokens.md:61-62`），
 * 所以走字阶角色而不是写死 sp——用户调大系统字号时水印跟着走。
 * 强调态用字阶自己的 emphasized 版本（Medium、字距 0）：原型第 185 行加粗到 Bold、字距 −0.5，不另造字重与字距。
 */
@Composable
internal fun exploreTileBigStyle(style: ExploreTileStyle): TextStyle {
    return when (style) {
        ExploreTileStyle.Dimension -> MaterialTheme.typography.displayMediumEmphasized
        ExploreTileStyle.Decade, ExploreTileStyle.Studio -> MaterialTheme.typography.displaySmallEmphasized
    }
}

/**
 * 标签的字体角色。原型的 16/24/500、14/20/500、12/16/500 分别是 M3 的
 * Title medium、Title small、Label medium（同上 tokens.md:67、68、73）。
 */
@Composable
internal fun exploreTileLabelStyle(style: ExploreTileStyle): TextStyle = when (style) {
    ExploreTileStyle.Dimension -> MaterialTheme.typography.titleMedium
    ExploreTileStyle.Studio -> MaterialTheme.typography.titleSmall
    ExploreTileStyle.Decade -> MaterialTheme.typography.labelMedium
}

/** 一档配色：背景与前景成对，不许拆开用。原型第 188–191 行，按序号 `% 4` 轮转。 */
internal data class ExploreTileColors(val container: Color, val content: Color)

@Composable
internal fun exploreTileColors(index: Int): ExploreTileColors {
    val scheme = MaterialTheme.colorScheme
    return when (index % 4) {
        0 -> ExploreTileColors(scheme.primaryContainer, scheme.onPrimaryContainer)
        1 -> ExploreTileColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
        2 -> ExploreTileColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
        else -> ExploreTileColors(scheme.surfaceContainerHighest, scheme.onSurface)
    }
}

/**
 * 维度方块。
 *
 * 大字 [ExploreTile.big] 是**装饰水印**——压在角上、半透明、被容器裁掉一截，
 * 给纯文字的方块一点份量；真正要读的是 [ExploreTile.label]。两者常常是同一个词
 * （短题材词不需要缩写），所以水印对无障碍隐藏，否则读屏会把它念两遍。
 *
 * 按下时圆角 12 → 20（原型第 184 行），形变见 [pressedCorner]。
 */
@Composable
internal fun ExploreTileCard(
    big: String,
    label: String,
    style: ExploreTileStyle,
    colorIndex: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = exploreTileSpec(style)
    val colors = exploreTileColors(colorIndex)

    val interactionSource = remember { MutableInteractionSource() }
    val corner by pressedCorner(interactionSource, resting = TILE_CORNER_RESTING, pressed = TILE_CORNER_PRESSED)

    PressArea(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = colors.container,
        contentColor = colors.content,
        interactionSource = interactionSource,
        modifier = modifier.size(width = spec.width, height = spec.height),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(corner))) {
                Text(
                    text = big,
                    style = exploreTileBigStyle(style),
                    color = colors.content.copy(alpha = spec.bigAlpha),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .align(spec.bigAlignment)
                        .offset(x = spec.bigOffsetX, y = spec.bigOffsetY)
                        // 水印是装饰：读屏跳过，免得和下面的标签念重
                        .clearAndSetSemantics { },
                )
            }
            Text(
                text = label,
                style = exploreTileLabelStyle(style),
                color = colors.content.copy(alpha = spec.labelAlpha),
                maxLines = 1,
                // 「Nintendo Switch」这种长名字在 148dp 里刚好顶到边，再长就截字形
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(spec.labelAlignment)
                    .offset(y = spec.labelOffsetY)
                    .padding(horizontal = 12.dp),
            )
        }
    }
}

/** 方块静息圆角（原型第 182 行）。 */
internal val TILE_CORNER_RESTING = 12.dp

/** 方块按下时的圆角（原型第 184 行）。 */
internal val TILE_CORNER_PRESSED = 20.dp
