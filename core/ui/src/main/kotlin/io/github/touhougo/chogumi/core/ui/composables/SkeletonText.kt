package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp

/**
 * 文字占位：骨架里**唯一**画「一行（或几行）字」的组件。
 *
 * ## 用法
 *
 * 真实内容里这行字是 `Text(text, style = X, maxLines = n)`，骨架里就写
 * `SkeletonText(style = X, lines = n)`——**同一个 [style]、同一个行数**，别的什么都不用算：
 * 占位的高度自己跟着真实文字走。默认撑满可用宽度；行内文字传 [fillWidth] = false，就按 [sample] 的实际文字宽测量，
 * 与真实行共用断行框架。真实文字还设了单行或行数上限时同步传 [maxLines]；[widthFraction] 只缩短色条，不影响布局宽度。
 *
 * 真实文字的行数由内容和屏宽决定（一句可能折成两行的答案）时，把一句同样长短的样例句当 [sample] 传进来：
 * 它照真实文字的方式折行，占位就有几行、每行一条色条。
 *
 * ## 为什么不许给文字占位写死 dp 高度
 *
 * 文字的行高取自 typography，会随系统字体大小缩放；写死的 `height(16.dp)` 不会。
 * 默认字号下写死的数碰巧对上，字体放大到 2 倍就错位翻倍，内容一出现整页就往下跳。
 * 这里不量也不猜，而是让一个**透明的真 [Text]** 去占位（同样的 [style]、每行一个 [sample] 共 [lines] 行）：
 * 它走的是与真实文字完全相同的排版路径，行高、字体回退、字号缩放都一并带上。色条只是画在它上面。
 *
 * ## 色条画多高（**本项目设计决定，不在 M3**）
 *
 * 排出来的每一行一条色条，高 = [style] 的字号（sp 随字体缩放），在该行的行高里垂直居中。
 * 理由：字号就是一个字符方框的高，色条与将来出现的字等高，行间自然留出行距（行高 − 字号）；
 * 色条比行高矮，相邻两行不会糊成一块。圆角取 `Corner extra small`（4dp，`tokens.md`），颜色取 [skeletonColors] 的字条色（平时与 [SkeletonBox] 同色）。
 * 多行时最后一行缩短到 [widthFraction] 的 60%，读起来像一段文字。
 *
 * 整个占位对读屏隐藏（透明文字不能被念出来）。
 */
@Composable
fun SkeletonText(
    style: TextStyle,
    modifier: Modifier = Modifier,
    lines: Int = 1,
    widthFraction: Float = 1f,
    sample: String = SkeletonTextSample,
    fillWidth: Boolean = true,
    maxLines: Int? = null,
) {
    require(lines >= 1) { "lines 至少为 1：$lines" }
    require(maxLines == null || maxLines >= 1) { "maxLines 至少为 1：$maxLines" }
    val barColor = skeletonColors.bar
    val barShape = MaterialTheme.shapes.extraSmall
    // 布局载体：透明的真 Text，每行一个样例、用换行撑出 lines 行（不用 minLines：它按拉丁字母算行高）；样例是一句话时照常折行。
    // 样例字默认取一个汉字——应用以中文为主，汉字的行高因字体回退略高于拉丁字母，
    // 取汉字才与中文内容严格同高（每行相差 0.3dp 左右，拉丁内容在两行内不超过 0.5dp 容差）。
    // 占位的是数字时（如生涯卡的大数字）显式传 [SkeletonTextDigits]：数字行高比汉字矮，用汉字占位会高出几 dp。
    // 色条画在这个 Text 背后，整个占位**只有一个布局节点**：可以放进列表项副行这类测量时读基线的槽位，
    // 不会像多层子树那样触发 RectList 闪退（见 SegmentedItem）。
    // 排版结果只在绘制时读：折成几行、每行在哪，不触发重组。
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val layoutModifier = if (fillWidth) modifier.fillMaxWidth() else modifier
    Text(
        text = List(lines) { sample }.joinToString("\n"),
        style = style,
        color = Color.Transparent,
        maxLines = maxLines ?: Int.MAX_VALUE,
        overflow = if (maxLines == null) TextOverflow.Clip else TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = layoutModifier
            .clearAndSetSemantics {}
            .drawBehind {
                val result = layout ?: return@drawBehind
                val barHeight = style.fontSize.toPx()
                val count = result.lineCount
                repeat(count) { line ->
                    val shortened = count > 1 && line == count - 1
                    val width = size.width * if (shortened) widthFraction * LastLineFraction else widthFraction
                    val outline = barShape.createOutline(Size(width, barHeight), layoutDirection, this)
                    val center = (result.getLineTop(line) + result.getLineBottom(line)) / 2
                    translate(top = center - barHeight / 2) { drawOutline(outline, barColor) }
                }
            },
    )
}

/**
 * 按 [style] 排满 [lines] 行时文字的高度，与 [SkeletonText] 用同一个样例字、同一条排版路径量出来。
 *
 * 真实文字要固定占几行时用它：`Text(text, Modifier.heightIn(min = rememberTextLinesHeight(style, n)), maxLines = n)`，
 * 不用 `minLines`。`minLines` 按拉丁字母的行高补空出的行，汉字因字体回退每行略高：一行的中文比 [SkeletonText] 的两行矮一截
 * （`labelMedium` 两行约 1.3dp，字号越大差得越多），骨架换成真内容时下面的东西会上跳，同一排里一行与两行的卡片也不齐。
 */
@Composable
fun rememberTextLinesHeight(style: TextStyle, lines: Int, sample: String = SkeletonTextSample): Dp {
    require(lines >= 1) { "lines 至少为 1：$lines" }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density, style, lines, sample) {
        with(density) { measurer.measure(List(lines) { sample }.joinToString("\n"), style).size.height.toDp() }
    }
}

/** 布局载体里的样例字（见 [SkeletonText] 的说明）。 */
const val SkeletonTextSample = "国"

/** 占位数字时的样例字：数字的行高比汉字矮，用汉字占位会高出几 dp（见 [SkeletonText]）。 */
const val SkeletonTextDigits = "0"

/** 多行占位里最后一行相对其余行的宽度。 */
private const val LastLineFraction = 0.6f
