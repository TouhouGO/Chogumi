package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em

/**
 * 段间空白：一个普通空格再加 0.75em 字距，合起来约一个字宽（Roboto 的空格约 0.25em），是段内词间空格（「CV 坂田将吾」）的
 * 四倍左右，一眼分得出段落。按字号走：放大字号时与文字一起变宽。我的设计决定（not in M3）。
 */
private val MetaGapSpacing = 0.75.em

/**
 * 单行元信息：各段之间留一段约一个字宽的空白（[MetaGapSpacing]），不画任何分隔点。**全 App 段间分隔只有这一种画法**。
 *
 * 规则（`tools/guards/check_separators.py` 守着）：
 * - **R1 单行元信息**（不会也不该折行的一行短信息，如「主角」「CV 田中真弓」）：
 *   用本组件，各段分别传入，不要自己拼字符串。
 * - **R2 可能折行的句子**（提示、说明、tooltip、标题等）：不用任何分隔点。并列用「、」，句子之间用「，」或「；」，
 *   补充说明用全角括号，信息多、一行放不下的拆成两行（两个 Text）。
 *
 * **整行是一个 [Text]**，段间空白是一个加了字距的空格（复制、读屏都是普通空格）：各段若各是一个 Text，纯拉丁的段（`TV`）
 * 与含中文的段（`2026年7月`）字体回退不同、行盒不同，按中心对齐后基线错开；同一段文字只有一条基线。
 * 固定 `maxLines = 1`；空段跳过，不留下多余的空白。
 *
 * [shrinkPart]：放不下时可以被截短的那一段的下标（按传入的 [parts]）。给了，就只把这一段截成「前缀…」，
 * 其余各段保持完整（眉题「作品名」「讨论版」截作品名、保住后缀）；null 则整行在末尾省略。
 * 规则：**前段是可变长的名字、后段是短而必须看到的信息**时传。
 *
 * [droppableFrom]：从第几段起（按传入的 [parts]）越靠后越可有可无。放不下就从末尾起整段整段去掉（连同前面的空白），
 * 直到放下，或剩下的最后一段已在它之前；末段是 [shrinkPart] 时先截它，截到不足 [SHRINK_MIN_KEEP] 个字
 * （只剩「…」毫无信息）就整段去掉。规则：**后面的段看不全不如没有**时传——条目头部的「形式、年月、话数、资料表」
 * 传 1（第一段保底），收藏面板的「更新于 1 小时前」只让最后一段可丢。
 */
@Composable
fun MetaText(
    parts: List<AnnotatedString>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    shrinkPart: Int? = null,
    droppableFrom: Int? = null,
) {
    if (shrinkPart == null && droppableFrom == null) {
        MetaTextLine(parts.filter { it.isNotEmpty() }, modifier, style, color, overflow)
        return
    }
    // 放不下时先去掉可有可无的末段，再只截第 shrinkPart 段（判定都在 FitText.kt，与搜索框提示共用）；量的就是画出来的那一行，段间空白算在里面
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxWidth = constraints.maxWidth
        val fitted = remember(parts, shrinkPart, droppableFrom, maxWidth, style) {
            fun widthOf(list: List<AnnotatedString>): Int =
                measurer.measure(metaLine(list), style, maxLines = 1, softWrap = false).size.width
            val nonEmpty = parts.withIndex().filter { it.value.isNotEmpty() }
            if (maxWidth == Constraints.Infinity) return@remember nonEmpty.map { it.value }
            val shown = if (droppableFrom == null) {
                nonEmpty
            } else {
                dropTrailingToFit(nonEmpty, droppableFrom, shrinkPart, maxWidth) { kept -> widthOf(kept.map { it.value }) }
            }
            val at = shown.indexOfFirst { it.index == shrinkPart }
            if (at < 0) return@remember shown.map { it.value }
            fitSegment(shown.map { it.value }, at, maxWidth, ::widthOf)
        }
        MetaTextLine(fitted, Modifier, style, color, overflow)
    }
}

/**
 * [MetaText] 的段间空白在 [style] 下有多宽：量的就是 [metaLine] 画出来的那一个空格，随字号一起变。
 * 自己排版一行元信息的地方（动态卡片的回复与贴贴）段间也留这么宽，全 App 只有这一个段间距。
 */
@Composable
fun metaGapWidth(style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, style, density) {
        val gap = metaLine(listOf(AnnotatedString("a"), AnnotatedString("a")))
        val tight = AnnotatedString("aa")
        fun widthOf(text: AnnotatedString) = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
        with(density) { (widthOf(gap) - widthOf(tight)).toDp() }
    }
}

/** 画一行：[parts] 已去掉空段。 */
@Composable
private fun MetaTextLine(
    parts: List<AnnotatedString>,
    modifier: Modifier,
    style: TextStyle,
    color: Color,
    overflow: TextOverflow,
) {
    val text = remember(parts) { metaLine(parts) }
    Text(text = text, modifier = modifier, style = style, color = color, maxLines = 1, overflow = overflow)
}

/** 各段连成一行，段间一段 [MetaGapSpacing] 空白：画的与量宽的是同一串。 */
internal fun metaLine(parts: List<AnnotatedString>): AnnotatedString = buildAnnotatedString {
    parts.forEachIndexed { index, part ->
        if (index > 0) withStyle(SpanStyle(letterSpacing = MetaGapSpacing)) { append(' ') }
        append(part)
    }
}

/** [MetaText] 的纯文本便捷重载（`List` 擦除后签名相同，所以改 JVM 名）。 */
@JvmName("MetaTextStrings")
@Composable
fun MetaText(
    parts: List<String>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    shrinkPart: Int? = null,
    droppableFrom: Int? = null,
) {
    MetaText(parts.map { AnnotatedString(it) }, modifier, style, color, overflow, shrinkPart, droppableFrom)
}
