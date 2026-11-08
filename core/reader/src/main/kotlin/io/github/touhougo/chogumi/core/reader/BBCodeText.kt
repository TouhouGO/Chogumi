package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeAlign
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeAst
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeNode
import io.github.touhougo.chogumi.core.common.bbcode.SectionLayout
import io.github.touhougo.chogumi.core.common.bbcode.SmileyCatalog
import io.github.touhougo.chogumi.core.common.bbcode.plainText
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.LocalInAppLinkHandler
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import io.github.touhougo.chogumi.core.ui.common.navigation.inAppRoute
import io.github.touhougo.chogumi.core.ui.composables.SkeletonTextSample
import io.github.touhougo.chogumi.core.ui.composables.keyedListSaver
import io.github.touhougo.chogumi.core.ui.image.ImageViewer
import io.github.touhougo.chogumi.core.ui.theme.isDarkThemeActive
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * 把 BBCode 语法树渲染成 Compose。
 *
 * ## 为什么拆成"行内"与"块级"两条路径
 *
 * Compose 里把图片/引用块塞进 `AnnotatedString` 是做不到的，
 * 而一张 `Text` 里又要能混排粗体、链接、表情。
 * 所以：
 * - **行内**（粗体/斜体/颜色/链接/表情）→ [AnnotatedString]，一次 Text 绘制；
 * - **块级**（图片/引用/代码块）→ 独立 Composable，与行内片段按顺序排列。
 *
 * 这样既保住了富文本的排版连续性，又不需要自绘文本。
 */
@Composable
fun BBCodeText(
    ast: BBCodeAst,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = ReaderTypography.compact,
    onUserClick: ((String) -> Unit)? = null,
    /** 剧透默认折叠，与 bgm.tv 网页版一致。 */
    revealSpoilers: Boolean = false,
    /**
     * 折叠探针（[FoldableText] 用）：测量时把第 [FoldProbe.lines] 个视觉行的底边写进 [FoldProbe.line]，
     * 折叠线因此总落在行边界上、不切字。null 时不计算。
     */
    foldProbe: FoldProbe? = null,
) {
    BBCodeColumn(ast, modifier, style, onUserClick, revealSpoilers, foldProbe, align = BBCodeAlign.Start, fill = false)
}

/**
 * 一栏正文：行自上而下排，每行按 [align] 摆（段落容器 `[center]` 一类在里面再开一栏）。
 * [fill] 为 true 时占满给的栏宽（对齐要有一个宽度可对），否则宽度随最宽的一行。
 */
@Composable
private fun BBCodeColumn(
    ast: BBCodeAst,
    modifier: Modifier,
    style: androidx.compose.ui.text.TextStyle,
    onUserClick: ((String) -> Unit)?,
    revealSpoilers: Boolean,
    foldProbe: FoldProbe?,
    align: BBCodeAlign,
    fill: Boolean,
) {
    // 站内链接去向：app 壳经 LocalInAppLinkHandler 接到导航。
    // null = 未接线环境（预览/测试），站内链接退回浏览器行为。
    val onInAppLink = LocalInAppLinkHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    // 具名色跟随当前主题明暗（全仓唯一判定 isDarkThemeActive）
    val darkTheme = isDarkThemeActive()
    val plugin = LocalBBCodeBlockPlugin.current
    val blocks = remember(ast, linkColor, darkTheme, onInAppLink, onUserClick, plugin) {
        ast.nodes.toBlocks(linkColor, darkTheme, onInAppLink, onUserClick, plugin)
    }
    // 连续的「行内文本 + 表情」要合成一行渲染：表情虽然必须独立成块
    // （图片塞不进 AnnotatedString），但它在句中是**行内元素**，
    // 若每个块各占一行，"文字(bgm38)文字"会断成三行。
    // 表情只有这一种画法：不管整段是不是只有表情，一律进行内流、按字号定大小，与网页一致。
    // 不设「整段只有 1–3 个表情就放大单独画」的第二条路径：那样同一页里表情时大时小。
    val rows = remember(blocks) { blocks.toRows() }
    // 全屏大图翻页的范围是这段正文里的全部图片；每个图片行记下它在其中的起始下标。
    val imageUrls = remember(rows) { rows.filterIsInstance<Row.Images>().flatMap { row -> row.images.map { it.url } } }
    val imageOffsets = remember(rows) {
        var next = 0
        rows.map { row -> if (row is Row.Images) next.also { next += row.images.size } else -1 }
    }
    var viewerIndex by rememberViewerIndex(imageUrls)
    viewerIndex?.let { ImageViewer(imageUrls, startIndex = it, onDismiss = { viewerIndex = null }) }
    // 每个文字行最近一次的排版结果：由 Text 的 onTextLayout 在**测量时同步**写入、同一次测量里读出，
    // 不是 snapshot 状态，不跨帧（折叠判定必须在同一次测量里定下来）。
    val textLayouts = remember(rows) { arrayOfNulls<TextLayoutResult>(rows.size) }
    val textMeasurer = rememberTextMeasurer()
    val rowStyle = remember(style, align) { if (align == BBCodeAlign.Start) style else style.copy(textAlign = align.textAlign) }
    val uriHandler = LocalUriHandler.current

    Layout(
        modifier = modifier,
        content = {
            rows.forEachIndexed { index, row ->
                when (row) {
                    is Row.InlineFlow -> InlineFlow(
                        items = row.items,
                        style = rowStyle,
                        onUserClick = onUserClick,
                        revealSpoilers = revealSpoilers,
                        modifier = Modifier.layoutId(index),
                        onTextLayout = { result -> textLayouts[index] = result },
                    )
                    is Row.BlankLine -> Spacer(Modifier.layoutId(index).height(BlankLineHeight))
                    is Row.Images -> BBCodeImages(
                        images = row.images,
                        onOpen = { i ->
                            // 链接里的图片点了去链接（与网页一致），其余进全屏大图
                            val link = row.images[i].link
                            if (link != null) openLink(link, onInAppLink, uriHandler) else viewerIndex = imageOffsets[index] + i
                        },
                        align = align,
                        modifier = Modifier.layoutId(index),
                    )
                    is Row.Single -> Box(Modifier.layoutId(index)) {
                        when (val block = row.block) {
                            is Block.QuoteBlock -> QuoteBlock(block, style, onUserClick)
                            is Block.Code -> CodeBlock(block.text)
                            is Block.Section -> SectionBlock(block, align, style, onUserClick, revealSpoilers)
                            is Block.Plugged -> block.content.Content()
                            // 行内块、图片与换行已在 toRows 归入结构
                            is Block.Image, is Block.Inline, is Block.Smiley, is Block.StyledSmiley, is Block.SpoilerBlock,
                            is Block.LineBreak -> Unit
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        // 行与行纵向排列、间隔 RowGap（与 Column + spacedBy 同一几何，唯一一份）。
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val gap = RowGap.roundToPx()
        val placeables = arrayOfNulls<Placeable>(rows.size)
        measurables.forEach { placeables[it.layoutId as Int] = it.measure(childConstraints) }
        val tops = IntArray(rows.size)
        var y = 0
        placeables.forEachIndexed { index, placeable ->
            if (index > 0) y += gap
            tops[index] = y
            y += placeable?.height ?: 0
        }
        foldProbe?.let { probe ->
            probe.line = foldLineOf(
                budget = probe.lines,
                rows = rows,
                tops = tops,
                heights = IntArray(rows.size) { placeables[it]?.height ?: 0 },
                textLayouts = textLayouts,
                sampleLineHeight = { textMeasurer.measure(SkeletonTextSample, style).size.height },
            )
        }
        val width = if (fill && constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            constraints.constrainWidth(placeables.maxOfOrNull { it?.width ?: 0 } ?: 0)
        }
        val height = constraints.constrainHeight(y)
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                if (placeable == null) return@forEachIndexed
                val x = rows[index].placement(align).horizontal.align(placeable.width, width, layoutDirection)
                placeable.place(x, tops[index])
            }
        }
    }
}

/** 行间距：段落之间 4dp。 */
private val RowGap = 4.dp

/** 空行占位高度。 */
private val BlankLineHeight = 8.dp

/** `[indent]` 的缩进（设计决定，not in M3：与正文左右边距同为 16dp 一档）。 */
internal val IndentWidth = 16.dp

/** 一行在栏里摆在哪一侧：浮动盒贴它自己的一侧，其余跟栏的对齐。 */
private fun Row.placement(align: BBCodeAlign): BBCodeAlign =
    ((this as? Row.Single)?.block as? Block.Section)?.let { sectionGeometryOf(it.layout).placement } ?: align

internal val BBCodeAlign.horizontal: Alignment.Horizontal
    get() = when (this) {
        BBCodeAlign.Start -> Alignment.Start
        BBCodeAlign.Center -> Alignment.CenterHorizontally
        BBCodeAlign.End -> Alignment.End
    }

private val BBCodeAlign.textAlign: TextAlign
    get() = when (this) {
        BBCodeAlign.Start -> TextAlign.Start
        BBCodeAlign.Center -> TextAlign.Center
        BBCodeAlign.End -> TextAlign.End
    }

/**
 * 段落容器在一栏里的几何，全仓只此一处由 [SectionLayout] 推出：
 * - [content]：容器里各行的对齐，null = 沿用外层（网页上 `text-align` 会继承）；
 * - [fill]：占满栏宽（`[center]` 一类、`[indent]`），否则宽度随内容（浮动盒）；
 * - [placement]：容器本身摆在哪一侧，null = 跟外层的对齐；
 * - [indent]：整段缩进；
 * - [dropsBlank]：只有空白时不画。网页上这种浮动盒是多栏排版里的横向留白，单栏里只会多出一个空行。
 */
internal data class SectionGeometry(
    val content: BBCodeAlign?,
    val fill: Boolean,
    val placement: BBCodeAlign?,
    val indent: Boolean,
    val dropsBlank: Boolean,
)

internal fun sectionGeometryOf(layout: SectionLayout): SectionGeometry = when (layout) {
    is SectionLayout.Aligned ->
        SectionGeometry(content = layout.align, fill = true, placement = null, indent = false, dropsBlank = false)
    SectionLayout.Indent ->
        SectionGeometry(content = null, fill = true, placement = null, indent = true, dropsBlank = false)
    // 单栏里放不下并排的浮动盒：每个盒子独占一段、贴自己那一侧，宽度随内容（网页上浮动盒同样不与外面的文字同行排）
    is SectionLayout.Floating ->
        SectionGeometry(content = null, fill = false, placement = layout.side, indent = false, dropsBlank = true)
}

/**
 * 链接的站内去向：站内页面（解析只许 [inAppRoute] 一处）且接了导航时返回打开它的动作；
 * null = 交给浏览器（站外、暂无路由，或预览 / 测试里没接导航）。文字链接与链接里的图片共用这一处判定。
 */
private fun inAppAction(url: String, onInAppLink: ((Route) -> Unit)?): (() -> Unit)? {
    val route = inAppRoute(url) ?: return null
    return onInAppLink?.let { navigate -> { navigate(route) } }
}

private fun openLink(url: String, onInAppLink: ((Route) -> Unit)?, uriHandler: UriHandler) {
    inAppAction(url, onInAppLink)?.invoke() ?: uriHandler.openUri(url)
}

/**
 * [BBCodeText] 的折叠探针：测量时同步写入 [line]（第 [lines] 个视觉行的底边；不超出 [lines] 行时为 null），
 * [FoldableText] 在同一次测量里、量完正文之后读出。与 `textLayouts` 一样不是 snapshot 状态，不跨帧。
 *
 * 不用自定义对齐线：读对齐线会让正文的子布局在父节点还没摆放时先摆放孙节点（带表情的段落、
 * 引用块），列表快滑复用行时 Compose 崩在 `RectManager`「LayoutNode not found in RectList」。
 * 正文没重新测量时 [line] 保留上次的值，那正是同一份排版的结果；探针按正文与行数 `remember`，换了就是新探针。
 */
class FoldProbe internal constructor(internal val lines: Int) {
    internal var line: Int? = null
}

/**
 * 折叠线落在哪：按视觉行累计 [budget] 行，返回第 [budget] 行底边的 y；不超出时返回 null。
 * - 文字行：用排版结果的逐行底边，折叠线因此总在行边界上；
 * - 图片行与插件块：按单行行高折算行数，但不可切开——放不下时整块都留在折叠线以上（图片高度有上限，见 BBCodeImages；
 *   插件块是一个整体的控件，切开一半的投票卡没法用）；
 * - 其他块（引用、代码、空行）：按单行行高折算行数，放不下时按像素裁在块内。
 */
internal fun foldLineOf(
    budget: Int,
    rows: List<Row>,
    tops: IntArray,
    heights: IntArray,
    textLayouts: Array<TextLayoutResult?>,
    sampleLineHeight: () -> Int,
): Int? {
    var remaining = budget
    rows.forEachIndexed { index, row ->
        val layout = textLayouts[index].takeIf { row is Row.InlineFlow }
        val bottoms: List<Int> = if (layout != null) {
            List(layout.lineCount) { layout.getLineBottom(it).roundToInt() }
        } else {
            val lineHeight = sampleLineHeight().coerceAtLeast(1)
            val count = ((heights[index] + lineHeight - 1) / lineHeight).coerceAtLeast(1)
            List(count) { minOf((it + 1) * lineHeight, heights[index]) }
        }
        if (bottoms.size > remaining) {
            if (row.isUncuttable()) return if (index < rows.lastIndex) tops[index] + heights[index] else null
            return tops[index] + bottoms[remaining - 1]
        }
        remaining -= bottoms.size
        if (remaining == 0) return if (index < rows.lastIndex) tops[index] + heights[index] else null
    }
    return null
}

private fun Row.isUncuttable(): Boolean = this is Row.Images || (this is Row.Single && block is Block.Plugged)

/** 渲染行：一整行行内流，或一组连续图片，或一个独占一行的块，或一个空行占位。 */
internal sealed interface Row {
    data class InlineFlow(val items: List<Block>) : Row
    data class Images(val images: List<Block.Image>) : Row
    data class Single(val block: Block) : Row
    data object BlankLine : Row
}

/**
 * 把块序列归并成行：连续的 [Block.Inline] / [Block.Smiley] / [Block.StyledSmiley] / [Block.SpoilerBlock]
 * 合成一个 [Row.InlineFlow]，遇到 [Block.LineBreak] 则立即换行，其余块各自占一行。
 * 连续的图片（之间只有空白、换行、空行）归成一个 [Row.Images]，界面排成宫格。
 *
 * **mask 必须并入行内流**：官方 `span.text_mask` 是行内元素（只有
 * `border-radius` 与 `padding:0 5px`，无任何块级属性），
 * 若把它单独成行会把句中遮罩撕裂成独立一行。
 */
internal fun List<Block>.toRows(): List<Row> {
    val rows = mutableListOf<Row>()
    val inline = mutableListOf<Block>()

    // 图片行之后的空行先挂起：下一个还是图片就并进同一组（空行丢掉），否则照常补上。
    var pendingBlankLines = 0
    fun emit(row: Row) {
        repeat(pendingBlankLines) { rows.add(Row.BlankLine) }
        pendingBlankLines = 0
        rows.add(row)
    }
    fun flush() {
        if (inline.isNotEmpty()) {
            emit(Row.InlineFlow(inline.toList()))
            inline.clear()
        }
    }
    forEach { block ->
        val afterImages = inline.isEmpty() && rows.lastOrNull() is Row.Images
        when (block) {
            is Block.Inline -> if (!(afterImages && block.text.isBlank())) inline.add(block)
            is Block.Smiley, is Block.StyledSmiley, is Block.SpoilerBlock -> inline.add(block)
            is Block.LineBreak -> {
                flush()
                if (block.isBlankLine) {
                    if (rows.lastOrNull() is Row.Images) pendingBlankLines++ else rows.add(Row.BlankLine)
                }
            }
            is Block.Image -> {
                flush()
                val last = rows.lastOrNull()
                if (last is Row.Images) {
                    pendingBlankLines = 0
                    rows[rows.lastIndex] = Row.Images(last.images + block)
                } else {
                    emit(Row.Images(listOf(block)))
                }
            }
            else -> {
                flush()
                emit(Row.Single(block))
            }
        }
    }
    flush()
    repeat(pendingBlankLines) { rows.add(Row.BlankLine) }
    // 一栏开头、结尾只有空格的一行（「…\n [/float]」这种换行后跟个空格就收尾）不画：半角空格网页上撑不起一行；
    // 不换行空格是站点编辑器替作者输入的空格，网页上靠多栏并排藏住了这一行，单栏里它只是盒子底下多出的空行
    while (rows.firstOrNull()?.isSpacesOnly() == true) rows.removeAt(0)
    while (rows.lastOrNull()?.isSpacesOnly() == true) rows.removeAt(rows.lastIndex)
    return rows
}

/** 只有半角空格、制表符、不换行空格的行内行（全角空格「　」是作者有意的留白，照常占一行）。 */
private fun Row.isSpacesOnly(): Boolean =
    this is Row.InlineFlow && items.all { it is Block.Inline && it.text.text.all { c -> c == ' ' || c == '\t' || c == '\u00A0' } }

/**
 * 一行行内内容：文字、表情与遮罩交错。
 *
 * **必须渲染成单个 `Text`**：表情用 `InlineTextContent`，遮罩用字形上的 `SpanStyle` 与行内链接，
 * 共用同一文本流的折行与基线。
 * 用并列子块排布会让多行文字占满整行，**迫使后面的表情换行**
 * （见 [toInlineAnnotated] 的根因说明）。
 */
@Composable
internal fun InlineFlow(
    items: List<Block>,
    style: androidx.compose.ui.text.TextStyle,
    onUserClick: ((String) -> Unit)?,
    revealSpoilers: Boolean,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    // 只有含遮罩的段落才有揭示状态（[rememberRevealedMasks]）；没有遮罩的段落不往存档里写任何东西。
    val revealed = if (items.any { it is Block.SpoilerBlock }) rememberRevealedMasks(items) else null
    val revealedMasks = revealed?.value.orEmpty()
    val linkColor = MaterialTheme.colorScheme.primary
    val onInAppLink = LocalInAppLinkHandler.current
    val darkTheme = isDarkThemeActive()
    val imageMarker = stringResource(R.string.bbcode_image_marker)
    val (annotated, placed) = remember(items, revealSpoilers, revealedMasks, linkColor, darkTheme, onInAppLink, onUserClick, imageMarker) {
        items.toInlineAnnotated(
            revealSpoilers = revealSpoilers,
            revealedMasks = revealedMasks,
            onMaskClick = { maskId ->
                revealed?.let { it.value = if (maskId in it.value) it.value - maskId else it.value + maskId }
            },
            linkColor = linkColor,
            darkTheme = darkTheme,
            onInAppLink = onInAppLink,
            onUserClick = onUserClick,
            imageMarker = imageMarker,
        )
    }
    if (placed.isEmpty()) {
        // 纯文字（含原生 SpanStyle 遮罩）：直接画，省掉 InlineTextContent 的登记开销
        Text(text = annotated, modifier = modifier, style = style, onTextLayout = onTextLayout)
        return
    }
    val inlineStyle = styleForInlineContent(style, placed)
    Text(
        text = annotated,
        modifier = modifier,
        style = inlineStyle,
        inlineContent = placed.associate {
            it.id to inlineContentOf(it, inlineStyle)
        },
        onTextLayout = onTextLayout,
    )
}

/**
 * 一段行内文字里遮罩的揭示状态，跟着这一段的内容走（[keyedListSaver]）：同一位置换成别的内容时不沿用旧揭示。
 * 键是这一段内容的摘要（[contentDigestOf]）：定长、跨进程不变，存档里每段只占一个摘要和揭开的序号，不随全文变长。
 */
@Composable
private fun rememberRevealedMasks(items: List<Block>): MutableState<Set<Int>> {
    val identity = remember(items) { contentDigestOf(items) }
    val saver = remember(identity) {
        keyedListSaver<MutableState<Set<Int>>>(
            identity,
            save = { it.value.toList() },
            restore = { saved -> mutableStateOf(saved.map { it as Int }.toSet()) },
        )
    }
    return rememberSaveable(identity, saver = saver) { mutableStateOf(emptySet()) }
}

/**
 * 全屏大图开在这段正文的第几张图（null = 没开），跟着这段的图片走（[keyedListSaver]）：转屏、切深浅色重建页面后
 * 大图还开着、停在原来那张；恢复时图片已经不是这些（帖子改过）就不恢复，旧下标不拿去翻新的图。
 * 键是图片地址的摘要（[digestOf]），定长。
 */
@Composable
private fun rememberViewerIndex(urls: List<String>): MutableState<Int?> {
    val identity = remember(urls) { digestOf(urls) }
    val saver = remember(identity) {
        keyedListSaver<MutableState<Int?>>(
            identity,
            // 没开大图时不存：一页上百段正文，各存一个摘要只占存档
            save = { state -> state.value?.let { listOf(it) } },
            restore = { saved -> mutableStateOf(saved.single() as Int) },
        )
    }
    return rememberSaveable(identity, saver = saver) { mutableStateOf(null) }
}

/**
 * 一段内容的摘要（[digestOf]）。文字块取纯文字（遮罩不在文字块里，样式不影响哪个遮罩是哪个），
 * 其余块（遮罩、表情、图片……）都是数据类，取其文本形式。
 */
internal fun contentDigestOf(items: List<Block>): String =
    digestOf(items.map { block -> if (block is Block.Inline) "T" + block.text.text else block.toString() })

/** 一串文字的 SHA-256 摘要（十六进制），段与段之间用 0 字节隔开：定长、跨进程不变，做存档的键。 */
private fun digestOf(parts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        digest.update(part.toByteArray())
        digest.update(0.toByte())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun styleForInlineContent(
    style: androidx.compose.ui.text.TextStyle,
    placed: List<Placed>,
): androidx.compose.ui.text.TextStyle {
    val smileyCodes = placed.mapNotNull {
        when (val item = it.item) {
            is InlineItem.Smiley -> item.code
        }
    }
    return SmileySizes.inlineTextStyleOf(style, smileyCodes)
}

/** 行内表情始终占用自身尺寸；遮罩未揭开时绘制同尺寸底色，揭开后绘制 [SmileyImage]。 */
@Composable
private fun inlineContentOf(
    placed: Placed,
    style: androidx.compose.ui.text.TextStyle,
): InlineTextContent = when (val item = placed.item) {
    is InlineItem.Smiley -> {
        // 尺寸由家族决定（见 SmileySizes）：小表情一行高、大动态表情显著更大。
        // Placeholder 只接受 em 单位 —— 恰好让我们的大小表情跟随阅读字号缩放。
        // 图片高 = 占位高（同一个 em 换算成 dp），宽按 SmileyCatalog.widthRatioOf（颜文字比方格宽），
        // 左右间隙另算在占位宽里：占位多大、图就画多大，不会被挤小。
        val em = SmileySizes.emOf(item.code)
        val side = with(LocalDensity.current) { (style.fontSize * em.value).toDp() }
        InlineTextContent(
            Placeholder(
                width = (em.value * SmileyCatalog.widthRatioOf(item.code) + 2 * SmileySizes.INLINE_GAP_EM).em,
                height = em,
                placeholderVerticalAlign = SmileySizes.verticalAlignOf(item.code),
            ),
        ) {
            if (placed.hidden) {
                Box(Modifier.fillMaxSize().background(MaskColor))
            } else {
                SmileyImage(item.code, size = side)
            }
        }
    }
}

/** 一条行内内容：占位符对应的渲染物（当前仅表情需要独立占位符，图片塞不进 AnnotatedString）。 */
internal sealed interface InlineItem {
    /** 表情，[code] 已确认可解析。 */
    data class Smiley(val code: String) : InlineItem
}

/**
 * 已登记的行内内容：**唯一 id** + 渲染物。
 *
 * id 必须唯一 —— `inlineContent` 是按 id 索引的 Map，
 * 两个表情若共用一个 id，后一个会覆盖前一个。
 */
internal data class Placed(val id: String, val item: InlineItem, val hidden: Boolean = false)

/** 登记一个行内表情；[code] 为 null（语法合法但素材不存在，如 bgm999）时退回原文 [rawCode]，与 [SmileyImage] 的降级一致。 */
private fun AnnotatedString.Builder.appendSmiley(code: String?, rawCode: String, placed: MutableList<Placed>, hidden: Boolean) {
    if (code != null) {
        val id = "smiley-${placed.size}"
        placed.add(Placed(id, InlineItem.Smiley(code), hidden))
        appendInlineContent(id)
    } else {
        append("(${rawCode})")
    }
}

/**
 * 把一行行内块合成**单一 `AnnotatedString`**，并登记其中的行内内容。
 *
 * ## 为什么必须合成一条文本流
 *
 * 官方表情是 `display:inline` + `vertical-align:baseline` 的行内元素，
 * 与文字同处一条文本流，由排版引擎统一折行。真实 DOM 实测
 * （`character/46462`）：`(bgm85)(bgm85)` 在 648px 容器下与文字**同行不换行**。
 *
 * 若把文字与表情当并列子块交给 `FlowRow`，一个多行 `Text` 会占满整行宽度，
 * 紧随其后的表情被迫换行——即使文字末行仍有空位。这正是「末尾两个表情
 * 自动换行」的根因。合成 `AnnotatedString` 后折行由文本引擎决定，
 * 与浏览器行为一致。
 *
 * 返回 `(行内文本, 行内内容清单)`；清单顺序即占位符出现顺序。
 */
internal fun List<Block>.toInlineAnnotated(
    revealSpoilers: Boolean = false,
    revealedMasks: Set<Int> = emptySet(),
    onMaskClick: ((Int) -> Unit)? = null,
    linkColor: Color = Color.Unspecified,
    darkTheme: Boolean = false,
    onInAppLink: ((Route) -> Unit)? = null,
    onUserClick: ((String) -> Unit)? = null,
    imageMarker: String,
): Pair<AnnotatedString, List<Placed>> {
    val placed = mutableListOf<Placed>()
    var maskIndex = 0
    fun leaves(hidden: Boolean) = InlineLeaves(imageMarker) { smiley ->
        appendSmiley(resolvableSmileyCode(smiley.code), smiley.code, placed, hidden)
    }
    val annotated = buildAnnotatedString {
        forEach { block ->
            when (block) {
                is Block.Inline -> append(block.text)
                is Block.Smiley -> appendSmiley(block.code, block.rawCode, placed, hidden = false)
                is Block.StyledSmiley ->
                    append(buildText(listOf(block.node), linkColor, darkTheme, onInAppLink, onUserClick, leaves(hidden = false)))
                is Block.SpoilerBlock -> {
                    val currentMaskId = maskIndex++
                    val isRevealed = revealSpoilers || revealedMasks.contains(currentMaskId)
                    val maskStyle = SpanStyle(
                        background = MaskColor,
                        color = if (isRevealed) MaskRevealedTextColor else MaskColor,
                    )
                    val link = LinkAnnotation.Clickable(
                        tag = "mask-$currentMaskId",
                        styles = TextLinkStyles(style = maskStyle),
                        linkInteractionListener = {
                            onMaskClick?.invoke(currentMaskId)
                        },
                    )
                    withLink(link) {
                        val innerText = buildText(block.children, linkColor, darkTheme, onInAppLink, onUserClick, leaves(hidden = !isRevealed))
                        val start = length
                        append(if (isRevealed) innerText else innerText.flatMapAnnotations { range ->
                            val nestedLink = range.item as? LinkAnnotation
                            if (nestedLink == null) listOf(range) else {
                                // 保留链接文字的字重等排版样式，但未揭开时只允许点击遮罩。
                                nestedLink.styles?.style?.let { listOf(AnnotatedString.Range(it, range.start, range.end)) }.orEmpty()
                            }
                        })
                        // 遮罩色最后应用，内层链接色与显式颜色都不能盖过它。
                        addStyle(maskStyle, start, length)
                    }
                }
                else -> Unit
            }
        }
    }
    return annotated to placed
}

/** 渲染单元：行内富文本，或一个需要独立 Composable 的块。 */
internal sealed interface Block {
    data class Inline(val text: AnnotatedString) : Block
    /** [link] 非 null 时这张图在链接里，点了去链接。 */
    data class Image(val url: String, val width: Double?, val height: Double?, val link: String?) : Block
    data class SpoilerBlock(val children: List<BBCodeNode>) : Block
    data class QuoteBlock(val children: List<BBCodeNode>) : Block
    data class Code(val text: String) : Block

    /** 段落容器（`[center]`、`[indent]`、`[float]` 一类），独占一行、里面再开一栏。 */
    data class Section(val layout: SectionLayout, val children: List<BBCodeNode>) : Block

    /** 块级插件认下的一块（[BBCodeBlockPlugin]），独占一行、由插件自己画。 */
    data class Plugged(val content: BBCodeBlockContent) : Block

    /**
     * 表情。**必须独立成块** —— 图片塞不进 `AnnotatedString`。
     *
     * [code] 为 null 表示该表情不可解析（越界），渲染方按 [rawCode] 显示原文。
     */
    data class Smiley(val code: String?, val rawCode: String) : Block

    /**
     * 行内样式或链接包着的一个表情：上浮后的 `[b](bgm38)[/b]`、`[user=x](bgm38)[/user]`（见 [hoistContainer]）。
     * [node] 是整条包装链，行内渲染时按它恢复样式与链接，表情照常登记成占位符。
     */
    data class StyledSmiley(val node: BBCodeNode) : Block

    /** 换行标记，[isBlankLine] 为 true 表示生成一个额外的空行。 */
    data class LineBreak(val isBlankLine: Boolean = false) : Block
}

/**
 * 把节点序列切成块。
 *
 * 连续的文本类节点合并成**一个** [Block.Inline]，避免每个词都变成独立 Text
 * （那会破坏换行与两端对齐）。
 */
internal fun List<BBCodeNode>.toBlocks(
    linkColor: Color,
    darkTheme: Boolean,
    onInAppLink: ((Route) -> Unit)?,
    onUserClick: ((String) -> Unit)?,
    plugin: BBCodeBlockPlugin? = null,
): List<Block> {
    val blocks = mutableListOf<Block>()
    val inline = mutableListOf<BBCodeNode>()

    fun flushInline() {
        if (inline.isEmpty()) return
        // 纯空白的行内片段不产生可视块
        if (inline.any { it.plainTextForRender().isNotEmpty() }) {
            val annotated = buildText(inline, linkColor, darkTheme, onInAppLink, onUserClick, leaves = null)
            blocks.addAll(annotated.splitNewlines())
        }
        inline.clear()
    }

    val normalizedNodes = hoistBlockElements(this)

    normalizedNodes.forEach { node ->
        when (node) {
            is BBCodeNode.Image -> {
                flushInline()
                val url: String? = node.url
                // 链接只认 http(s)，与文字链接同一判据
                if (url != null) blocks.add(Block.Image(url, node.width, node.height, node.link?.takeIf(::isHttp)))
            }
            is BBCodeNode.Mask -> {
                // 先落盘前序文字以保证顺序；但**不构成行边界** ——
                // 行边界由 toRows 决定，那里把 SpoilerBlock 归入行内流。
                flushInline()
                blocks.add(Block.SpoilerBlock(node.children))
            }
            is BBCodeNode.Quote -> {
                flushInline()
                blocks.add(Block.QuoteBlock(node.children))
            }
            is BBCodeNode.CodeBlock -> {
                flushInline()
                blocks.add(Block.Code(node.text))
            }
            is BBCodeNode.Section -> {
                flushInline()
                if (!(sectionGeometryOf(node.layout).dropsBlank && node.plainText().isBlank())) {
                    blocks.add(Block.Section(node.layout, node.children))
                }
            }
            // 列表：插件认得出就整块交给插件；认不出一项一行（网页的 ul 不画项目符号），项里照常是行内内容
            is BBCodeNode.ListBlock -> {
                flushInline()
                val claimed = plugin?.claim(node)
                if (claimed != null) {
                    blocks.add(Block.Plugged(claimed))
                } else {
                    node.items.forEach { item ->
                        blocks.add(Block.LineBreak())
                        blocks.addAll(item.toBlocks(linkColor, darkTheme, onInAppLink, onUserClick))
                    }
                    blocks.add(Block.LineBreak())
                }
            }
            // 表情必须切出独立块：图片塞不进 AnnotatedString。
            // 同批行内内容先 flush，保证「文字 表情 文字」的顺序不乱。
            is BBCodeNode.Smiley -> {
                flushInline()
                blocks.add(Block.Smiley(resolvableSmileyCode(node.code), node.code))
            }
            else -> if (node.containsBlockElement()) {
                // 上浮后还含块级元素的行内包装节点只包着一个表情（见 hoistContainer）
                flushInline()
                blocks.add(Block.StyledSmiley(node))
            } else {
                inline.add(node)
            }
        }
    }
    flushInline()
    return blocks
}

/**
 * 递归检查节点及其子树是否包含非普通文本的块级元素（表情、剧透遮罩、图片、引用、代码块、段落容器、列表）。
 */
private fun BBCodeNode.containsBlockElement(): Boolean = when (this) {
    is BBCodeNode.Smiley, is BBCodeNode.Mask, is BBCodeNode.Image, is BBCodeNode.Quote, is BBCodeNode.CodeBlock,
    is BBCodeNode.Section, is BBCodeNode.ListBlock -> true
    is BBCodeNode.Url -> children.any { it.containsBlockElement() }
    is BBCodeNode.User -> children.any { it.containsBlockElement() }
    is BBCodeNode.Bold -> children.any { it.containsBlockElement() }
    is BBCodeNode.Italic -> children.any { it.containsBlockElement() }
    is BBCodeNode.Underline -> children.any { it.containsBlockElement() }
    is BBCodeNode.Strike -> children.any { it.containsBlockElement() }
    is BBCodeNode.Size -> children.any { it.containsBlockElement() }
    is BBCodeNode.Color -> children.any { it.containsBlockElement() }
    is BBCodeNode.Unknown -> children.any { it.containsBlockElement() }
    else -> false
}

/**
 * 将内嵌在行内包装节点（如 Strike, Bold 等）中的块级元素（如表情、Mask）上浮，
 * 避免其在 buildText 行内拼接时被静默丢弃，同时保持格式样式向内继承。
 */
private fun hoistBlockElements(nodes: List<BBCodeNode>): List<BBCodeNode> {
    val result = mutableListOf<BBCodeNode>()
    for (node in nodes) {
        if (!node.containsBlockElement()) {
            result.add(node)
        } else {
            when (node) {
                is BBCodeNode.Bold -> hoistContainer(node.children, result) { BBCodeNode.Bold(it) }
                is BBCodeNode.Italic -> hoistContainer(node.children, result) { BBCodeNode.Italic(it) }
                is BBCodeNode.Underline -> hoistContainer(node.children, result) { BBCodeNode.Underline(it) }
                is BBCodeNode.Strike -> hoistContainer(node.children, result) { BBCodeNode.Strike(it) }
                is BBCodeNode.Color -> hoistContainer(node.children, result) { BBCodeNode.Color(node.value, it) }
                is BBCodeNode.Size -> hoistContainer(node.children, result) { BBCodeNode.Size(node.value, it) }
                // 链接里的图片带着链接地址上浮（见 BBCodeNode.Image.link），其余文字照旧是链接
                is BBCodeNode.Url -> hoistContainer(node.children, result) { BBCodeNode.Url(node.url, it) }
                is BBCodeNode.User -> hoistContainer(node.children, result) { BBCodeNode.User(node.username, it) }
                // 认不出的标签没有样式可往里带，只有两半字面方括号：前后各留一份，里面的块照常上浮。
                // 不能像样式标签那样逐段重包：每段都会重印一遍方括号（「[list]文字[/list]表情[list]文字[/list]」）
                is BBCodeNode.Unknown -> {
                    result.add(BBCodeNode.Text(node.openingLiteral))
                    result.addAll(hoistBlockElements(node.children))
                    if (node.hasClosingTag) result.add(BBCodeNode.Text(node.closingLiteral))
                }
                else -> result.add(node)
            }
        }
    }
    return result
}

private fun hoistContainer(
    children: List<BBCodeNode>,
    result: MutableList<BBCodeNode>,
    wrap: (List<BBCodeNode>) -> BBCodeNode,
) {
    val hoistedChildren = hoistBlockElements(children)
    val currentInline = mutableListOf<BBCodeNode>()
    fun flushInline() {
        if (currentInline.isNotEmpty()) {
            result.add(wrap(currentInline.toList()))
            currentInline.clear()
        }
    }
    for (child in hoistedChildren) {
        when (child) {
            is BBCodeNode.Smiley -> {
                flushInline()
                result.add(wrap(listOf(child)))
            }
            is BBCodeNode.Image, is BBCodeNode.Quote, is BBCodeNode.CodeBlock -> {
                flushInline()
                result.add(child)
            }
            is BBCodeNode.Mask -> {
                flushInline()
                result.add(BBCodeNode.Mask(listOf(wrap(child.children))))
            }
            // 段落容器上浮，外层的样式（[color]、[b]…）带进容器里面，不丢
            is BBCodeNode.Section -> {
                flushInline()
                result.add(child.copy(children = listOf(wrap(child.children))))
            }
            // 列表同样上浮，外层样式带进每一项
            is BBCodeNode.ListBlock -> {
                flushInline()
                result.add(BBCodeNode.ListBlock(child.items.map { listOf(wrap(it)) }))
            }
            else -> if (child.containsBlockElement()) {
                flushInline()
                result.add(wrap(listOf(child)))
            } else {
                currentInline.add(child)
            }
        }
    }
    flushInline()
}

/**
 * 将含换行符的富文本切分为行内块与换行块：
 * - 裸 \n、CRLF 与 \r 转换为 [Block.LineBreak]；
 * - 连续 2 个及以上的换行折叠为最多 1 个空行（[Block.LineBreak.isBlankLine] = true）；
 * - 提取的文本片段通过 subSequence 完整保留 SpanStyle 与 LinkAnnotation。
 */
private fun AnnotatedString.splitNewlines(): List<Block> {
    val raw = this.text
    if (!raw.contains('\n') && !raw.contains('\r')) {
        return if (raw.isNotEmpty()) listOf(Block.Inline(this)) else emptyList()
    }
    val result = mutableListOf<Block>()
    var cursor = 0
    val length = raw.length

    while (cursor < length) {
        val nextNewline = raw.indexOfAny(charArrayOf('\r', '\n'), cursor)
        if (nextNewline < 0) {
            if (cursor < length) {
                result.add(Block.Inline(this.subSequence(cursor, length)))
            }
            break
        }
        if (nextNewline > cursor) {
            result.add(Block.Inline(this.subSequence(cursor, nextNewline)))
        }

        // 识别当前连续换行序列：\r\n 或 \n 或 \r
        var newlineEnd = nextNewline
        var newlineCount = 0
        while (newlineEnd < length && (raw[newlineEnd] == '\r' || raw[newlineEnd] == '\n')) {
            if (raw[newlineEnd] == '\r' && newlineEnd + 1 < length && raw[newlineEnd + 1] == '\n') {
                newlineEnd += 2
            } else {
                newlineEnd += 1
            }
            newlineCount++
        }

        // 产生换行块：单个换行对应换行，2个及以上换行追加一个空行标记
        result.add(Block.LineBreak(isBlankLine = false))
        if (newlineCount >= 2) {
            result.add(Block.LineBreak(isBlankLine = true))
        }

        cursor = newlineEnd
    }
    return result
}

private fun BBCodeNode.plainTextForRender(): String = when (this) {
    is BBCodeNode.Image -> ""
    is BBCodeNode.Mask, is BBCodeNode.Quote, is BBCodeNode.CodeBlock, is BBCodeNode.Section, is BBCodeNode.ListBlock -> ""
    else -> plainText()
}

/**
 * 行内渲染里要由外层登记的叶子：表情登记成占位符（[Placed]，带遮罩的揭示状态），图片换成文字标记 [imageMarker]。
 */
private class InlineLeaves(
    val imageMarker: String,
    val smiley: AnnotatedString.Builder.(BBCodeNode.Smiley) -> Unit,
)

/**
 * 行内节点 → [AnnotatedString]。BBCode 的样式嵌套是**叠加**语义：子层在外层样式上继续追加。
 *
 * 块级节点在行内只有一条降级规则：**留下它的文字，排不进一行的结构丢掉**。引用、段落容器、嵌套遮罩展开子节点；
 * 列表一项一行；代码保留文字、换等宽字体；没有文字的图片显示 [InlineLeaves.imageMarker]。只有遮罩里会走到这些分支
 * （遮罩是行内元素，里面的块没法另起一段）；遮罩样式与揭示状态由外层统一加。
 *
 * [leaves] 为 null 只给 [toBlocks] 合并的行内片段用：那里的节点不含表情、图片与其他块级节点（已切成独立块）。
 */
private fun buildText(
    nodes: List<BBCodeNode>,
    linkColor: Color,
    darkTheme: Boolean,
    onInAppLink: ((Route) -> Unit)?,
    onUserClick: ((String) -> Unit)?,
    leaves: InlineLeaves?,
): AnnotatedString = buildAnnotatedString {
    fun styled(style: SpanStyle?, content: AnnotatedString.Builder.() -> Unit) {
        if (style != null) withStyle(style) { content() } else content()
    }

    fun emit(nodes: List<BBCodeNode>, inherited: SpanStyle?) {
        nodes.forEach { node ->
            when (node) {
                is BBCodeNode.Text -> styled(inherited) { append(node.value) }
                is BBCodeNode.Bold -> emit(node.children, inherited.merge(SpanStyle(fontWeight = FontWeight.Bold)))
                is BBCodeNode.Italic -> emit(node.children, inherited.merge(SpanStyle(fontStyle = FontStyle.Italic)))
                is BBCodeNode.Underline ->
                    emit(node.children, inherited.merge(SpanStyle(textDecoration = TextDecoration.Underline)))
                is BBCodeNode.Strike ->
                    emit(node.children, inherited.merge(SpanStyle(textDecoration = TextDecoration.LineThrough)))
                is BBCodeNode.Size -> {
                    // size 在 Bangumi 上是**磅值**；这里按相对倍率近似，
                    // 因为我们的字号来自 M3 type scale，直接套绝对磅值会与主题脱节。
                    val em = node.value?.let { (it / 16.0).coerceIn(0.75, 2.5).toFloat() } ?: 1f
                    emit(node.children, inherited.merge(SpanStyle(fontSize = em.em)))
                }
                is BBCodeNode.Color ->
                    emit(node.children, inherited.merge(SpanStyle(color = parseColor(node.value, darkTheme))))
                is BBCodeNode.Url -> {
                    val target: String? = node.url
                    // 链接文字照常带外层样式（[size]、[b]…），链接色压过外层的 [color]，链接里面的 [color] 又压过链接色（同网页）；
                    // 所以链接色作为继承样式往下传，不写进链接注解（注解样式会盖掉里面的颜色）。
                    val linkStyle = inherited.merge(SpanStyle(color = linkColor))
                    fun label() = if (node.children.isEmpty()) {
                        withStyle(linkStyle) { append(target.orEmpty()) }
                    } else {
                        emit(node.children, linkStyle)
                    }
                    if (target != null && isHttp(target)) {
                        // 站内链接（/subject/{id} 等）→ App 内打开：解析只许 [inAppRoute] 一处；
                        // 未命中（站外或暂无路由）→ 默认浏览器行为。
                        val inApp = inAppAction(target, onInAppLink)
                        val link = if (inApp != null) {
                            LinkAnnotation.Clickable(tag = "inapp-${target}", linkInteractionListener = { _ -> inApp() })
                        } else {
                            LinkAnnotation.Url(url = target)
                        }
                        withLink(link) { label() }
                    } else {
                        // 不是 http(s) 的地址不可点，文字照常画
                        if (node.children.isEmpty()) append(target.orEmpty()) else emit(node.children, inherited)
                    }
                }
                is BBCodeNode.User -> {
                    // 用户链接由调用方决定去向（站内用户页 vs 浏览器）
                    val username = node.username
                    val userStyle = SpanStyle(
                        color = linkColor,
                        fontWeight = FontWeight.Medium,
                    )
                    val labelStyle = userStyle.merge(inherited ?: SpanStyle())
                    // 标签内容什么都没画出来（`[user=x][/user]`）时显示 @用户名
                    fun label() {
                        val start = length
                        emit(node.children, labelStyle)
                        if (length == start) styled(labelStyle) { append("@$username") }
                    }
                    if (username.isNotBlank() && onUserClick != null) {
                        val link = LinkAnnotation.Clickable(
                            tag = "user-$username",
                            styles = TextLinkStyles(style = SpanStyle(color = linkColor)),
                            linkInteractionListener = {
                                onUserClick.invoke(username)
                            },
                        )
                        withLink(link) { label() }
                    } else {
                        label()
                    }
                }
                // 占位符的 em 按所在位置的字号换算，表情图按正文字号画（inlineContentOf）：外层 [size] 不带进来，
                // 占位与图同按正文字号取尺寸，和 [size] 外的表情一样大
                is BBCodeNode.Smiley -> styled(inherited?.copy(fontSize = TextUnit.Unspecified)) {
                    checkNotNull(leaves).smiley(this, node)
                }
                is BBCodeNode.Image -> styled(inherited) { append(checkNotNull(leaves).imageMarker) }
                is BBCodeNode.CodeBlock -> withStyle(inherited.merge(SpanStyle(fontFamily = FontFamily.Monospace))) { append(node.text) }
                is BBCodeNode.Mask -> emit(node.children, inherited)
                is BBCodeNode.Quote -> emit(node.children, inherited)
                is BBCodeNode.Section -> emit(node.children, inherited)
                is BBCodeNode.ListBlock -> node.items.forEachIndexed { index, item ->
                    if (index > 0) styled(inherited) { append('\n') }
                    emit(item, inherited)
                }
                is BBCodeNode.Unknown -> {
                    // 不知道怎么渲染：原样显示拼写，比静默丢弃更诚实
                    append(node.openingLiteral)
                    emit(node.children, inherited)
                    append(node.closingLiteral)
                }
            }
        }
    }
    emit(nodes, null)
}

private fun SpanStyle?.merge(other: SpanStyle): SpanStyle =
    if (this == null) other else this.merge(other)

/**
 * `[color=...]` 的值 → 颜色。
 *
 * Bangumi 上这个值**不受控**（用户能写任何字符串），所以只接受能明确解析的形式，
 * 其余回退到正文默认色——绝不把无法解析的字符串塞进渲染管线。
 */
private fun parseColor(value: String?, darkTheme: Boolean): Color {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return Color.Unspecified
    // #rgb / #rrggbb
    if (raw.startsWith("#")) {
        val hex = raw.drop(1)
        return when (hex.length) {
            3 -> runCatching {
                Color(
                    (hex[0].digitToInt(16) * 17) / 255f,
                    (hex[1].digitToInt(16) * 17) / 255f,
                    (hex[2].digitToInt(16) * 17) / 255f,
                )
            }.getOrDefault(Color.Unspecified)
            6 -> runCatching { Color(("ff$hex").toLong(16)) }.getOrDefault(Color.Unspecified)
            else -> Color.Unspecified
        }
    }
    // 具名色：只保留 Bangumi 正文里常见的几个，不做完整的 CSS 色表。
    // **跟随明暗两档**：写死的 [color=black]/[color=white] 在另一侧主题下不可见。
    // 色值是本项目设计决定（not in M3）：各档在对应 surface 上都满足 ≥3:1 对比
    // （正文以 surface/surfaceContainerHigh 为底；亮档取较深色、暗档取较亮色，
    // 中彩色的两档只在亮度上拉开）。hex 自定义色值照写的值用、不分明暗：那是作者指定的具体颜色。
    return namedBBCodeColor(raw.lowercase(), darkTheme)
}

/**
 * 具名色的明暗两档。数值为**本项目设计决定**（not in M3）：
 * 亮色档选 WCAG 对白底（#FFFBFF surface）≥3:1 的深色；暗色档选对暗底（#141218 surface）
 * ≥3:1 的亮色；black/white 这种极端色在两侧各给一个"看得清"的替身
 * （black → 亮档近黑、暗档近白；white 反之）。
 */
internal fun namedBBCodeColor(name: String, darkTheme: Boolean): Color = when (name) {
    "red" -> if (darkTheme) Color(0xFFF5797C) else Color(0xFFB3261E)
    "orange" -> if (darkTheme) Color(0xFFFFB86B) else Color(0xFF9A4A00)
    "yellow" -> if (darkTheme) Color(0xFFF4D35E) else Color(0xFF7A5A00)
    "green" -> if (darkTheme) Color(0xFF86D98B) else Color(0xFF1B6E24)
    "blue" -> if (darkTheme) Color(0xFF9FC7FF) else Color(0xFF1D5FBF)
    "purple" -> if (darkTheme) Color(0xFFD0A9F5) else Color(0xFF6B2FB3)
    "pink" -> if (darkTheme) Color(0xFFF2A9CF) else Color(0xFFA63167)
    "brown" -> if (darkTheme) Color(0xFFD3A685) else Color(0xFF6B4326)
    "black" -> if (darkTheme) Color(0xFFE5E1E6) else Color(0xFF1C1B1F)
    "gray", "grey" -> if (darkTheme) Color(0xFFC4C1C7) else Color(0xFF57535A)
    "white" -> if (darkTheme) Color(0xFFFFFFFF) else Color(0xFF57535A)
    else -> Color.Unspecified
}

private fun isHttp(url: String) = url.startsWith("http://") || url.startsWith("https://")

/**
 * 遮罩底色。**对齐官方**：bgm.tv 服务端给 `span.text_mask` 注入的内联样式是
 * `background-color:#555; color:#555; border:1px solid #555`，
 * 即「深色底 + 同色文字 + 同色边框」，文字因此不可见但**照常参与排版**。
 * 官方不随主题切换此色，深浅色下均为可见的中深灰。
 */
private val MaskColor = Color(0xFF555555)

/** 揭示后的文字色。官方 `span.text_mask:hover{color:#fff!important}`。 */
private val MaskRevealedTextColor = Color(0xFFFFFFFF)

@Composable
private fun QuoteBlock(
    block: Block.QuoteBlock,
    style: androidx.compose.ui.text.TextStyle,
    onUserClick: ((String) -> Unit)?,
) {
    val ast = remember(block.children) { BBCodeAst(block.children) }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(12.dp).quoteBar(MaterialTheme.colorScheme.outlineVariant)) {
            // 引用的是别人的话：插件（投票卡）绑的是这一楼的数据，引文里的块不交给它
            CompositionLocalProvider(LocalBBCodeBlockPlugin provides null) {
                BBCodeText(ast, style = style, onUserClick = onUserClick)
            }
        }
    }
}

@Composable
private fun CodeBlock(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 段落容器：里面再开一栏正文，几何来自 [sectionGeometryOf]；[inherited] 是外层这一栏的对齐。 */
@Composable
private fun SectionBlock(
    block: Block.Section,
    inherited: BBCodeAlign,
    style: androidx.compose.ui.text.TextStyle,
    onUserClick: ((String) -> Unit)?,
    revealSpoilers: Boolean,
) {
    val ast = remember(block.children) { BBCodeAst(block.children) }
    val geometry = sectionGeometryOf(block.layout)
    BBCodeColumn(
        ast = ast,
        modifier = if (geometry.indent) Modifier.padding(start = IndentWidth) else Modifier,
        style = style,
        onUserClick = onUserClick,
        revealSpoilers = revealSpoilers,
        foldProbe = null,
        align = geometry.content ?: inherited,
        fill = geometry.fill,
    )
}
