package io.github.touhougo.chogumi.feature.home.widget

import android.content.Context
import android.graphics.Bitmap
import android.text.TextPaint
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentWidth
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import io.github.touhougo.chogumi.core.model.CoverQuality
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.bookProgressLabel
import io.github.touhougo.chogumi.core.ui.composables.collectionProgressLabel
import io.github.touhougo.chogumi.core.ui.composables.dayOffsetLabel
import io.github.touhougo.chogumi.core.ui.composables.episodeProgressLabel
import io.github.touhougo.chogumi.core.ui.image.coverBitmapOf
import kotlin.math.min

/** 进度条的比例：总数已知时是看过的占比，否则 null（不画进度条）。 */
internal fun progressFractionOf(progress: ProgressCount): Float? =
    (progress as? ProgressCount.Known)?.takeIf { it.total > 0 }?.let { (it.current.toFloat() / it.total).coerceIn(0f, 1f) }

/** 一张要取的封面：作品 id 与封面地址（没有封面是 null）。 */
internal data class CoverRef(val subjectId: Int, val url: String?)

internal val WidgetWork.coverRef: CoverRef get() = CoverRef(subjectId, coverUrl)

/**
 * 小组件上的封面：按 [width] × [height] 取图并裁好圆角（RemoteViews 只收位图）。画的时候按版面缩小，同一张图在各个尺寸里共用，
 * 所以按用到的最大尺寸取。一次更新的位图都在同一个跨进程包里，所以像素密度最多按 2 倍取，最多取 [limit] 张。
 * 圆角 [corner] 烤进位图，默认是系统给小组件内部元素的那个值；很小的封面（本周追番）传小一档的，免得四角连成药丸。
 * 取不到的那张画成空的封面框。
 */
internal suspend fun coverBitmapsOf(
    context: Context,
    covers: List<CoverRef>,
    limit: Int,
    width: Dp,
    height: Dp,
    corner: Dp = widgetInnerRadius(context),
): Map<Int, Bitmap> {
    val scale = min(context.resources.displayMetrics.density, COVER_MAX_SCALE)
    val widthPx = (width.value * scale).toInt()
    val heightPx = (height.value * scale).toInt()
    val cornerPx = corner.value * scale
    return covers.distinctBy { it.subjectId }.take(limit).mapNotNull { cover ->
        val url = cover.url ?: return@mapNotNull null
        coverBitmapOf(context, url, widthPx, heightPx, cornerPx, CoverQuality.AUTO)?.let { cover.subjectId to it }
    }.toMap()
}

/** 封面的宽：3:4，与 App 的封面同比例。 */
internal fun coverWidthOf(height: Dp): Dp = height * 3 / 4

/**
 * 一张 [height] 高的封面（3:4），点它打开作品页；没有图时是一块空的封面框，圆角与取图时烤进位图的一样（[corner]，null 是系统的内部圆角）。
 */
@Composable
internal fun WidgetCover(subjectId: Int, cover: Bitmap?, height: Dp, corner: Dp? = null) {
    val context = LocalContext.current
    val modifier = GlanceModifier.size(coverWidthOf(height), height).clickable(openSubject(context, subjectId))
    if (cover != null) {
        Image(ImageProvider(cover), contentDescription = null, modifier = modifier, contentScale = ContentScale.FillBounds)
    } else {
        val frame = modifier.background(LocalWidgetPalette.current.track)
        Box(if (corner != null) frame.cornerRadius(corner) else frame.innerCorner()) {}
    }
}

/** 一行（[WorkRow]）的内容要的高：标题、进度句、进度条叠起来，也装得下 [ButtonHeight] 的按钮。 */
internal val WorkRowHeight = 56.dp

/**
 * 小组件只画一行（[WorkRow]）时 [height] 高的框上下各留多少空：平常是 [padding]，框矮到装不下一行加两头的空时让出来，
 * 先保住一行的 [WorkRowHeight]。
 */
internal fun rowFramePaddingOf(height: Dp, padding: Dp): Dp = ((height - WorkRowHeight) / 2).coerceIn(0.dp, padding)

/** 一张作品列表（在看、今日放送）在一个高度下的排法：框上下各留多少空，有没有标题行，放几行（[rows]），每行多高，封面多高。 */
internal data class ListLayout(
    val verticalPadding: Dp,
    val header: Boolean,
    val rows: Int,
    val rowHeight: Dp,
    val coverHeight: Dp,
)

/**
 * [height] 高的作品列表（[count] 行可画）怎么排：只画放得下的整行，不露半行。一行至少 [WorkRowHeight]，放得下几行就分几行、
 * 把高度均分给它们，封面随行高变大（到 [ListCoverMaxHeight] 为止）；连一行都放不下标题行时不画标题行，只画一行，框上下的空
 * 让给这一行（[rowFramePaddingOf]）。可画的比放得下的少时只画那几行，行高不变。
 */
internal fun listLayoutOf(height: Dp, count: Int): ListLayout {
    val withHeader = height - ListFramePadding * 2 - HeaderHeight
    val header = withHeader >= WorkRowHeight
    val padding = if (header) ListFramePadding else rowFramePaddingOf(height, ListFramePadding)
    val space = if (header) withHeader else height - padding * 2
    val fit = (space / WorkRowHeight).toInt().coerceIn(1, LIST_MAX_ROWS)
    val rowHeight = space / fit
    return ListLayout(
        verticalPadding = padding,
        header = header,
        rows = min(fit, count),
        rowHeight = rowHeight,
        coverHeight = (rowHeight - LIST_ROW_GAP).coerceIn(LIST_COVER_MIN_HEIGHT, ListCoverMaxHeight),
    )
}

/** 列表最多几行（Glance 一层最多 10 个子项；封面位图都在一个跨进程包里，见 [coverBitmapsOf]）。 */
internal const val LIST_MAX_ROWS = 6

/** 列表的框上下各留的空。 */
internal val ListFramePadding = 12.dp

/** 列表里封面最高多高（取图按这个取）。 */
internal val ListCoverMaxHeight = 64.dp

/** 封面上下各留的空：两行的封面之间隔开这么多。 */
private val LIST_ROW_GAP = 8.dp
private val LIST_COVER_MIN_HEIGHT = 32.dp

/**
 * 在看列表的一行，与 4×1 的单部作品同一个排法（[WidgetRow]）：标题下是进度句（写失败时换成「没同步上」）与进度条，
 * 右边是进度按钮（[button]）；[leading] 是今日放送写在最左边的播出时刻。
 */
@Composable
internal fun WorkRow(
    work: WidgetWork,
    failed: Boolean,
    cover: Bitmap?,
    coverHeight: Dp,
    width: Dp,
    button: ButtonShape,
    modifier: GlanceModifier = GlanceModifier,
    leading: String? = null,
) {
    val context = LocalContext.current
    val paint = TextPaint().apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, WidgetType.label.fontSize.value, context.resources.displayMetrics)
        isFakeBoldText = true
    }
    fun textWidth(text: String) = (paint.measureText(text) / context.resources.displayMetrics.density).dp
    var shape = if (width < PlusOneWidth + RowTextMinWidth) ButtonShape.ROUND else button
    var trailingWidth = when (shape) {
        ButtonShape.ROUND -> ButtonHeight
        ButtonShape.PLUS_ONE -> PlusOneWidth
        ButtonShape.LABELED -> textWidth(nextLabel(context, work.button)) + 32.dp
    }
    if (trailingWidth + RowTextMinWidth + CompactRowGap > width) {
        shape = ButtonShape.ROUND
        trailingWidth = ButtonHeight
    }
    trailingWidth = when (val state = work.button) {
        EpButtonState.Empty, EpButtonState.Loading -> 0.dp
        EpButtonState.Finished -> textWidth(context.getString(R.string.widget_finished))
        is EpButtonState.Waiting -> textWidth(dayOffsetLabel(context.resources, state.daysUntil))
        is EpButtonState.Next, is EpButtonState.BookNext -> trailingWidth
    }.coerceAtMost((width - RowTextMinWidth - CompactRowGap).coerceAtLeast(0.dp))
    WidgetRow(
        subjectId = work.subjectId,
        title = work.title,
        cover = cover,
        coverHeight = coverHeight,
        width = width,
        trailingWidth = trailingWidth,
        modifier = modifier,
        leading = leading,
        details = { ProgressLine(work, failed) },
        trailing = { ProgressButton(work, shape, GlanceModifier.width(trailingWidth)) },
    )
}

/**
 * 小组件上一部作品的一行：[leading]（有时）、[coverHeight] 高的封面、标题与下面的 [details]、右边的 [trailing]。
 * 行高由调用方按版面给（[modifier]），内容竖直居中。点封面和文字打开作品页。
 */
@Composable
internal fun WidgetRow(
    subjectId: Int,
    title: String,
    cover: Bitmap?,
    coverHeight: Dp,
    width: Dp,
    trailingWidth: Dp = 0.dp,
    modifier: GlanceModifier = GlanceModifier,
    leading: String? = null,
    details: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val palette = LocalWidgetPalette.current
    val layout = widgetRowLayout(width, coverHeight, trailingWidth, leading != null)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null && layout.cover) {
            Text(leading, style = widgetText(WidgetType.meta, palette.onSurfaceVariant), maxLines = 1, modifier = GlanceModifier.width(LeadingWidth))
        }
        if (layout.cover) WidgetCover(subjectId, cover, coverHeight)
        Column(GlanceModifier.defaultWeight().padding(start = layout.start, end = layout.end).clickable(openSubject(context, subjectId))) {
            val caption = if (!layout.cover && !leading.isNullOrBlank()) "$leading  $title" else title
            Text(caption, style = widgetText(WidgetType.title, palette.onSurface), maxLines = 1)
            details()
        }
        trailing()
    }
}

/** 行内先保证作品文字和操作，窄时将时刻并入标题、不画封面；不缩字号。数值是排版预算（not in M3）。 */
internal data class WidgetRowLayout(val cover: Boolean, val start: Dp, val end: Dp)

internal fun widgetRowLayout(width: Dp, coverHeight: Dp, trailingWidth: Dp, leading: Boolean): WidgetRowLayout {
    val fixed = coverWidthOf(coverHeight) + (if (leading) LeadingWidth else 0.dp) + trailingWidth
    val showCover = width - fixed - RowTextPadding * 2 >= RowTextMinWidth
    return if (showCover) WidgetRowLayout(true, RowTextPadding, RowTextPadding)
    else WidgetRowLayout(false, 0.dp, CompactRowGap)
}

private val RowTextPadding = 12.dp
private val RowTextMinWidth = 40.dp
private val CompactRowGap = 4.dp

/** 一行一句次要的话（今日放送里没在追的那几部写在看人数）。 */
@Composable
internal fun DetailLine(text: String) {
    Text(
        text,
        style = widgetText(WidgetType.meta, LocalWidgetPalette.current.onSurfaceVariant),
        maxLines = 1,
        modifier = GlanceModifier.padding(top = 2.dp),
    )
}

/** 行首播出时刻那一列的宽：放得下「24:30」。 */
private val LeadingWidth = 40.dp

/**
 * 进度句与进度条；[failed] 时进度句换成「没同步上，点一下重试」（错误色），再点 +1 就是重试。
 * [sentence] 为 false 时不写进度句（单部作品的大数字已经写了），只在失败时写那句。
 */
@Composable
internal fun ProgressLine(work: WidgetWork, failed: Boolean, sentence: Boolean = true) {
    val context = LocalContext.current
    val palette = LocalWidgetPalette.current
    val text = when {
        failed -> context.getString(R.string.widget_write_failed)
        sentence -> collectionProgressLabel(context.resources, work.progress)
        else -> null
    }
    if (text != null) {
        Text(
            text,
            style = widgetText(WidgetType.meta, if (failed) palette.error else palette.onSurfaceVariant),
            maxLines = 1,
            modifier = GlanceModifier.padding(top = 2.dp),
        )
    }
    progressFractionOf(work.progress)?.let { fraction ->
        // 间距单独一个 Spacer：Glance 的内边距算在高度里，写在 4dp 高的条上会把条挤没
        Spacer(GlanceModifier.height(6.dp))
        LinearProgressIndicator(
            progress = fraction,
            modifier = GlanceModifier.fillMaxWidth().height(4.dp),
            color = palette.primary,
            backgroundColor = palette.track,
        )
    }
}

/**
 * 进度按钮的样子：列表里的「+1」胶囊、压在封面上的圆形「+1」（主色，压在图上要醒目）、写全的「看完第 6 集」
 * （[LABELED] 的宽由调用方给）。胶囊与写全的是次要色调（secondaryContainer）。
 */
internal enum class ButtonShape { PLUS_ONE, ROUND, LABELED }

/**
 * 进度按钮，按首页同一个 [EpButtonState]：能前进时是按钮（[shape]），等下一集时写还有几天，全看完时写「都看完了」
 * （这两句用 [statusInk]，压在封面上时是浅色），其余（章节还在路上、没有进度的类型）不占位置。
 */
@Composable
internal fun ProgressButton(
    work: WidgetWork,
    shape: ButtonShape,
    modifier: GlanceModifier = GlanceModifier,
    statusInk: ColorProvider = LocalWidgetPalette.current.onSurfaceVariant,
) {
    val context = LocalContext.current
    val palette = LocalWidgetPalette.current
    // 每次都写一遍宽：桌面换尺寸时把新的画法套在已有的视图上，没写宽的会留着上一个尺寸的宽（「+1」胶囊的宽把「看完第 6 集」截成「看…」）。
    // 调用方给的宽（[modifier]）写在后面，盖过这个默认的
    val base = GlanceModifier.wrapContentWidth().then(modifier)
    when (val state = work.button) {
        is EpButtonState.Next, is EpButtonState.BookNext -> {
            val label = if (shape == ButtonShape.LABELED) nextLabel(context, state) else context.getString(R.string.widget_plus_one)
            val description = context.getString(R.string.widget_plus_one_description)
            val size = when (shape) {
                ButtonShape.PLUS_ONE -> GlanceModifier.width(PlusOneWidth).height(ButtonHeight)
                ButtonShape.ROUND -> GlanceModifier.size(ButtonHeight)
                ButtonShape.LABELED -> GlanceModifier.height(ButtonHeight).padding(horizontal = 16.dp)
            }
            Box(
                base
                    .then(size)
                    .then(modifier)
                    .background(if (shape == ButtonShape.ROUND) palette.primary else palette.tonal)
                    .cornerRadius(ButtonHeight / 2)
                    .semantics { contentDescription = description }
                    .clickable(actionRunCallback<AdvanceEpisodeAction>(actionParametersOf(AdvanceEpisodeAction.SubjectIdKey to work.subjectId))),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = widgetText(WidgetType.label, if (shape == ButtonShape.ROUND) palette.onPrimary else palette.onTonal), maxLines = 1)
            }
        }
        is EpButtonState.Waiting -> Box(base.height(ButtonHeight), contentAlignment = Alignment.Center) {
            Text(dayOffsetLabel(context.resources, state.daysUntil), style = widgetText(WidgetType.meta, statusInk), maxLines = 1)
        }
        EpButtonState.Finished -> Box(base.height(ButtonHeight), contentAlignment = Alignment.Center) {
            Text(context.getString(R.string.widget_finished), style = widgetText(WidgetType.meta, statusInk), maxLines = 1)
        }
        EpButtonState.Loading, EpButtonState.Empty -> Spacer(base)
    }
}

/** 单部作品按钮上的字：下一集「看完第 6 集」，书「读完第 4 卷」（进度文案只来自 core:ui 的进度文案）。 */
private fun nextLabel(context: Context, state: EpButtonState): String = when (state) {
    is EpButtonState.Next -> context.getString(
        R.string.widget_mark_next,
        episodeProgressLabel(context.resources, state.episode.sort),
    )
    is EpButtonState.BookNext -> context.getString(
        R.string.widget_read_next,
        bookProgressLabel(context.resources, state.unit, state.current + 1),
    )
    else -> context.getString(R.string.widget_plus_one)
}

/** 没登录时整块：一句话加一个打开 App 的按钮（App 的首页在未登录时就是登录入口）。 */
@Composable
internal fun SignInContent() {
    val context = LocalContext.current
    val palette = LocalWidgetPalette.current
    Column(GlanceModifier.fillMaxWidth()) {
        Text(context.getString(R.string.widget_sign_in_prompt), style = widgetText(WidgetType.body, palette.onSurfaceVariant))
        Spacer(GlanceModifier.height(12.dp))
        Box(
            GlanceModifier.fillMaxWidth().height(ButtonHeight).background(palette.tonal).cornerRadius(ButtonHeight / 2).clickable(openApp(context)),
            contentAlignment = Alignment.Center,
        ) {
            Text(context.getString(R.string.widget_open_app), style = widgetText(WidgetType.label, palette.onTonal), maxLines = 1)
        }
    }
}

/** 一句说明（空、读不出来、要重新选），点它做 [onClick]，默认打开 App。 */
@Composable
internal fun MessageContent(text: String, onClick: Action = openApp(LocalContext.current)) {
    Text(
        text,
        style = widgetText(WidgetType.body, LocalWidgetPalette.current.onSurfaceVariant),
        modifier = GlanceModifier.fillMaxWidth().clickable(onClick),
    )
}

/** 小组件的标题行：左边标题，右边一个数；点它做 [onClick]，默认打开 App。 */
@Composable
internal fun WidgetHeader(title: String, trailing: String?, onClick: Action = openApp(LocalContext.current)) {
    val palette = LocalWidgetPalette.current
    Row(
        GlanceModifier.fillMaxWidth().height(HeaderHeight).clickable(onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = widgetText(WidgetType.title, palette.onSurface), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        if (trailing != null) Text(trailing, style = widgetText(WidgetType.meta, palette.onSurfaceVariant), maxLines = 1)
    }
}

/** 按钮高 40dp（M3 小号按钮；圆形 +1 是 40dp 见方）；列表里的 +1 宽 56dp。 */
internal val ButtonHeight = 40.dp
private val PlusOneWidth = 56.dp
internal val HeaderHeight = 28.dp

/** 封面位图最多按几倍像素密度取。 */
private const val COVER_MAX_SCALE = 2f
