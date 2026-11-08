package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow

/**
 * 一行会变的字，变化时**只有变了的那几个字**在原位翻一格：「看过 168 / 1155 集」→「看过 169 / 1155 集」只有 8 翻成 9，
 * 「第 9 集」→「第 10 集」只有 9 翻成 10，其余的字不动。
 *
 * 整行始终是**一个** [Text]：同一条基线、同样的字距与省略，读屏读到的就是整句。翻动的那几个字画在它上面，
 * 位置取自这一行自己的排版结果，与两旁的字同一条基线；翻动期间这一段从底下那一行里挖掉，翻完就只剩那一行本身。
 *
 * [order] 决定翻的方向：变大往上翻（新字从下方进来），变小往下翻。[order] 不变而字变了（总集数到了、
 * 放不下改写短文案）直接换，不翻——只有「数」变了才动。
 *
 * 翻动用默认（不是快速）空间弹簧：快速弹簧两三帧就翻完，看不出来。
 *
 * 用在**离散的一步一步**变的数（点一下、步进按钮、点格子、一格一格的评分）。手指连续拖动时跟着手走的读数
 * （进度尺上的数）每帧都在变，翻动追不上手：这时 [animate] 传 false，直接换字。是否翻只在这里判定。
 */
@Composable
fun RollingText(
    text: String,
    order: Int,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    animate: Boolean = true,
) {
    val resolvedStyle = style.merge(color = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } })
    // 上一次画定的字与序：只在本次组合提交后更新，所以算翻动时读到的一定是变化之前的那一份
    val settled = remember { RollingSnapshot(text, order) }
    val roll = remember(text, order, animate) { if (animate) rollBetween(settled, text, order) else null }
    SideEffect {
        settled.text = text
        settled.order = order
    }
    // 跟着这一次翻动新建：变化的第一帧就从 0 画起，不会先闪一帧新字
    val progress = remember(roll) { Animatable(if (roll == null) 1f else 0f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(progress) { progress.animateTo(1f, spec) }

    val measurer = rememberTextMeasurer()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = resolvedStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = modifier.drawWithContent {
            val line = layout
            val fraction = progress.value
            // 翻完、没在翻，或要翻的那几个字被省略号吃掉了：就是这一行本身
            if (roll == null || fraction >= 1f || line == null || line.layoutInput.text.text != text ||
                roll.toEnd > line.getLineEnd(0, visibleEnd = true)
            ) {
                drawContent()
                return@drawWithContent
            }
            val top = line.getLineTop(0)
            val bottom = line.getLineBottom(0)
            val left = line.getHorizontalPosition(roll.start, usePrimaryDirection = true)
            val right = line.getHorizontalPosition(roll.toEnd, usePrimaryDirection = true)
            clipRect(left, top, right, bottom, ClipOp.Difference) { this@drawWithContent.drawContent() }
            val baseline = line.getLineBaseline(0)
            val shift = (bottom - top) * if (roll.up) 1f else -1f
            clipRect(top = top, bottom = bottom) {
                drawRun(measurer, roll.from.substring(roll.start, roll.fromEnd), resolvedStyle, left, baseline - shift * fraction, 1f - fraction)
                drawRun(measurer, text.substring(roll.start, roll.toEnd), resolvedStyle, left, baseline + shift * (1f - fraction), fraction)
            }
        },
    )
}

/** 一次翻动：从 [from] 翻成新字；两者只在 `[start, fromEnd)` 与 `[start, toEnd)` 这一段不同。 */
private class Roll(val from: String, val up: Boolean, val start: Int, val fromEnd: Int, val toEnd: Int)

private class RollingSnapshot(var text: String, var order: Int)

/** 只有序变了才翻；要翻的是两段字去掉相同的头尾之后剩下的那一段。 */
private fun rollBetween(before: RollingSnapshot, text: String, order: Int): Roll? {
    if (before.text == text || before.order == order) return null
    val prefix = before.text.commonPrefixWith(text).length
    val suffix = before.text.substring(prefix).commonSuffixWith(text.substring(prefix)).length
    return Roll(
        from = before.text,
        up = order > before.order,
        start = prefix,
        fromEnd = before.text.length - suffix,
        toEnd = text.length - suffix,
    )
}

/** 把一小段字画在 [x] 起、基线落在 [baseline] 的位置。 */
private fun DrawScope.drawRun(measurer: TextMeasurer, run: String, style: TextStyle, x: Float, baseline: Float, alpha: Float) {
    if (run.isEmpty()) return
    val measured = measurer.measure(run, style, maxLines = 1, softWrap = false)
    drawText(measured, topLeft = Offset(x, baseline - measured.firstBaseline), alpha = alpha.coerceIn(0f, 1f))
}
