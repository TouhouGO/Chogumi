package io.github.touhougo.chogumi.core.reader

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeAst
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion

/**
 * 「超出才折叠 + 展开 / 收起」的**唯一实现**：楼层正文（6 行）与简介（3 行）都走这里。
 *
 * ## 为什么是 [SubcomposeLayout] 而不是 `Modifier.layout`
 *
 * 「要不要画按钮」必须在**同一次测量里**定下来，条目第一帧的高度就得是最终高度。若在
 * `Modifier.layout` 里量一次、把「有没有超出」写回 `MutableState`、下一帧再决定画不画按钮，有两个后果：
 *
 * 1. 首帧没有按钮、次帧才有，条目高度凭空变一次（外加 `animateContentSize` 就是肉眼可见的抽搐）；
 * 2. 更糟的是，泛型 layout modifier 的**默认固有尺寸实现**会把测量回调当成固有尺寸查询来调，
 *    此时 Compose 给不可知的那一轴填的是 `LargeDimension`（32767）。同一段很短、只占一行的正文，
 *    在固有宽度探测里会被量成「高 32767」，于是「超出」被误判、状态被写反；外层只要有一次固有尺寸查询，
 *    每帧就是：固有探测写 true → 真实测量写 false → 固有探测写 true……无限翻转，按钮时有时无、列表高度每帧在跳。
 *
 * 这里在同一次测量里把「量内容 → 判定 → 定高 → 摆放按钮」做完：**内容只量一次**，
 * 判据只有这一遍的真实排版，不写状态、不跨帧。折叠线取 [BBCodeText] 经 [FoldProbe] 报出的（第 N 个视觉行的底边，
 * 跨段落累计），所以总落在行边界上、不切字；行几何只在 [BBCodeText] 一处。折叠靠正文自己的图层裁剪：正文按自然高度排版，
 * 图层只保留折叠线以上（[FoldClip]），因此折叠线以下既不绘制、也命不中指针，按钮那一行不会透出后面的字。
 *
 * 代价：[SubcomposeLayout] 不支持固有尺寸查询，因此**不能在它外层使用 `IntrinsicSize`**（docs/dev/guide.md §2.3，
 * 门禁 check_intrinsic_size.py）；要和内容等高的线在内容列上用 `drawBehind` 画。
 *
 * @param ast 已解析的 BBCode；由调用方 `remember` 一次，避免每次重组重新解析。
 * @param expanded 当前是否展开，由调用方持有（楼层是 `rememberSaveable`，简介来自页面状态）。
 * @param collapsedLines 折叠时保留的视觉行数（楼层 6、简介 3）。超过这么多行才出现按钮。
 *
 * 展开 / 收起的高度变化走 standard 方案的默认空间弹簧过渡：`animateContentSize` 按动画途中的高度裁剪，会回弹的弹簧
 * 收起时缩过头，把底下的「展开」按钮裁掉一截。
 * @param expandLabel / collapseLabel 复用调用方已有的字符串键，不新增文案。
 * @param onContentClick 点正文也切换折叠（简介保留该行为）；楼层不传，让正文里的链接自己响应。
 */
@Composable
fun FoldableText(
    ast: BBCodeAst,
    expanded: Boolean,
    onToggle: () -> Unit,
    collapsedLines: Int,
    @StringRes expandLabel: Int,
    @StringRes collapseLabel: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = ReaderTypography.compact,
    toggleContentPadding: PaddingValues = PaddingValues(vertical = 6.dp),
    onContentClick: (() -> Unit)? = null,
    onUserClick: ((String) -> Unit)? = null,
) {
    require(collapsedLines >= 1) { "collapsedLines 至少为 1：$collapsedLines" }
    val contentModifier = if (onContentClick != null) Modifier.clickable(onClick = onContentClick) else Modifier

    val probe = remember(ast, collapsedLines) { FoldProbe(collapsedLines) }
    // 正文按自然高度排版、只露出前 N 行：裁剪挂在正文的图层上、切在折叠线，不需要二次测量，也不依赖任何状态回写。
    SubcomposeLayout(
        modifier = modifier.animateContentSize(animationSpec = LocalAppMotion.current.scheme.defaultSpatialSpec()),
    ) { constraints ->
        val natural = subcompose(FoldSlot.Content) {
            BBCodeText(
                ast = ast,
                modifier = contentModifier,
                style = style,
                onUserClick = onUserClick,
                foldProbe = probe,
            )
        }.first().measure(constraints.copy(maxHeight = Constraints.Infinity))
        // 量完正文，探针里就是这一遍排版的折叠线；null = 不超出折叠行数
        val foldLine = probe.line
        val overflows = foldLine != null
        val foldedHeight = foldLine?.takeUnless { expanded }
        val folded = foldedHeight != null
        val visibleHeight = foldedHeight ?: natural.height
        val toggle = if (overflows) {
            subcompose(FoldSlot.Toggle) {
                FoldToggle(
                    expanded = expanded,
                    onToggle = onToggle,
                    expandLabel = expandLabel,
                    collapseLabel = collapseLabel,
                    contentPadding = toggleContentPadding,
                )
            }.first().measure(constraints.copy(maxHeight = Constraints.Infinity))
        } else {
            null
        }

        layout(
            width = constraints.constrainWidth(natural.width),
            height = visibleHeight + (toggle?.height ?: 0),
        ) {
            natural.placeWithLayer(0, 0) {
                clip = folded
                shape = if (folded) FoldClip(visibleHeight.toFloat()) else RectangleShape
            }
            toggle?.place(0, visibleHeight)
        }
    }
}

/** 只保留图层顶部 [heightPx] 高的矩形：折叠态的正文按自然高度排版，裁到折叠线为止。 */
private data class FoldClip(val heightPx: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, 0f, size.width, heightPx))
}

/** [FoldableText] 的两个子组合槽位。 */
private enum class FoldSlot { Content, Toggle }

/**
 * 骨架里给「展开」按钮占位：同一个 [FoldToggle]，透明、禁用、清掉语义。
 * 骨架按「最多会有多高」预留（简介骨架本来就画满折叠行数），内容到了只会上收、不会把下面顶下去。
 */
@Composable
internal fun FoldTogglePlaceholder(@StringRes expandLabel: Int, contentPadding: PaddingValues) {
    FoldToggle(
        expanded = false,
        onToggle = {},
        expandLabel = expandLabel,
        collapseLabel = expandLabel,
        contentPadding = contentPadding,
        enabled = false,
        modifier = Modifier.alpha(0f).clearAndSetSemantics {},
    )
}

/** 「展开 / 收起」按钮：图标与文案都由 [FoldableText] 统一决定，调用方只给字符串键。 */
@Composable
private fun FoldToggle(
    expanded: Boolean,
    onToggle: () -> Unit,
    @StringRes expandLabel: Int,
    @StringRes collapseLabel: Int,
    contentPadding: PaddingValues,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onToggle, enabled = enabled, modifier = modifier, contentPadding = contentPadding) {
        Text(stringResource(if (expanded) collapseLabel else expandLabel))
        Icon(
            painter = painterResource(if (expanded) R.drawable.expand_less_24 else R.drawable.expand_more_24),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
    }
}
