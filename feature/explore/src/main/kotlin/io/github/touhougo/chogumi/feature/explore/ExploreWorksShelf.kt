package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.map
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.ExploreShelf
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.WorkShelfContent
import io.github.touhougo.chogumi.core.model.WorkShelfStyle
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.subjectMetaParts
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.posterCoverShape
import io.github.touhougo.chogumi.core.ui.collection.posterMarkGesture
import io.github.touhougo.chogumi.core.ui.collection.rememberPosterMark
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.CarouselRow
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.SubjectCardVertical
import io.github.touhougo.chogumi.core.ui.composables.SubjectCardVerticalSkeleton
import io.github.touhougo.chogumi.core.ui.composables.SubjectMetaText
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.SubjectPosterSkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.formatScore
import io.github.touhougo.chogumi.core.ui.composables.shelfRankNumeralText
import io.github.touhougo.chogumi.core.ui.composables.starScoreText
import io.github.touhougo.chogumi.core.ui.composables.subjectRatingText
import io.github.touhougo.chogumi.core.ui.image.rememberCoverScheme
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import java.time.LocalDate

/**
 * 一条作品货架：标题 + 按样式画的一行作品。今日放送的作品来自放送表（副标题是星期与部数），其余来自一次搜索。
 *
 * **已经加载完但一条内容都没有的货架整条不画**——留个光杆标题加箭头，点进去还是空的，只会让页面看起来没做完；
 * 取失败的也不画（失败由一轮加载结束时的提示条统一说明）。还在加载的画这个样式的骨架，位置先占住。
 */
@Composable
internal fun WorksShelf(
    shelf: ExploreShelf.Works,
    typeContent: ExploreTypeContent,
    today: LocalDate,
    onSubjectClick: (Int) -> Unit,
    onOpenSearch: (criteria: SearchCriteria, title: String?, openFilters: Boolean) -> Unit,
    onOpenCalendar: () -> Unit,
    onArrange: () -> Unit,
) {
    val titleText = exploreTitleText(shelf.title)
    val content = typeContent.worksOf(shelf)
    val subtitle: List<String>?
    val onArrowClick: () -> Unit
    when (val source = shelf.content) {
        WorkShelfContent.TodayOnAir -> {
            subtitle = todayOnAirSubtitle(today, typeContent.todayOnAir.valueOrNull().orEmpty().size)
            onArrowClick = onOpenCalendar
        }
        is WorkShelfContent.Search -> {
            subtitle = null
            onArrowClick = { onOpenSearch(source.criteria, titleText, false) }
        }
    }
    if (!content.shelfShown()) return
    Column(Modifier.testTag("explore_shelf_${shelf.id}")) {
        ShelfHeader(title = titleText, onArrange = onArrange, subtitle = subtitle, onArrowClick = onArrowClick)
        val subjects = if (sectionStateOf(content) == SectionState.LOADING) null else content.valueOrNull().orEmpty()
        WorksShelfBody(shelf.style, subjects, onSubjectClick)
    }
}

/** 一条作品货架的作品（唯一来源）：今日放送来自放送表里今天那一天，其余是这条货架的搜索结果。 */
internal fun ExploreTypeContent.worksOf(shelf: ExploreShelf.Works): Load<List<SubjectBo>> = when (shelf.content) {
    WorkShelfContent.TodayOnAir -> todayOnAir.map { day -> day.map { it.subject } }
    is WorkShelfContent.Search -> shelf(shelf.id)
}

/**
 * 作品货架按样式画——**样式的唯一分派**（红线一：别处不再判断样式；整理里样式的缩略图也是它）。
 * [subjects] 为 null 时画这个样式的骨架。
 */
@Composable
internal fun WorksShelfBody(style: WorkShelfStyle, subjects: List<SubjectBo>?, onSubjectClick: (Int) -> Unit) {
    when (style) {
        WorkShelfStyle.POSTER_ROW -> PosterRow(subjects, onSubjectClick)
        WorkShelfStyle.FOCUS -> FocusRow(subjects, onSubjectClick)
        WorkShelfStyle.CHART -> ChartPages(subjects, onSubjectClick)
        WorkShelfStyle.BENTO -> BentoPages(subjects, onSubjectClick)
        WorkShelfStyle.COVER_FLOW -> CoverFlow(subjects, onSubjectClick)
        WorkShelfStyle.DECK -> DeckShelf(subjects, onSubjectClick)
        WorkShelfStyle.SPINE -> SpineRow(subjects, onSubjectClick)
        WorkShelfStyle.TIMELINE -> TimelineRow(subjects, onSubjectClick)
    }
}

/**
 * 货架画不画的唯一判定：还在取的画骨架占位，有内容的画内容；取到了没内容、取失败的整条不画
 * （失败由一轮加载结束时的提示条统一说明）。
 */
internal fun Load<List<SubjectBo>>.shelfShown(): Boolean =
    sectionStateOf(this).let { it == SectionState.LOADING || it == SectionState.CONTENT }

// —— 海报行

/** 海报行：横滑的海报卡（封面 + 两行标题），右下角按评分开关压分数。 */
@Composable
internal fun PosterRow(subjects: List<SubjectBo>?, onSubjectClick: (Int) -> Unit) {
    if (subjects == null) {
        PosterSkeletonRow()
        return
    }
    CarouselRow {
        items(subjects, key = { it.id }) { subject ->
            SubjectCardVertical(subject = subject, onClick = onSubjectClick, modifier = Modifier.width(ChogumiDimens.CoverWidth))
        }
    }
}

/** 今日放送的副标题各段：「周四」「16 部」（SectionHeader 用 MetaText 连成一行）。星期与列表同一个 [today]。 */
@Composable
private fun todayOnAirSubtitle(today: LocalDate, count: Int): List<String> {
    val weekday = stringArrayResource(R.array.weekday_names)[today.dayOfWeek.value - 1]
    return listOf(weekday, pluralStringResource(R.plurals.work_count, count, count))
}

/** 海报行的骨架：同一种海报卡、同宽，宽屏上也铺满一行。 */
@Composable
internal fun PosterSkeletonRow(modifier: Modifier = Modifier) {
    CarouselRow(modifier = modifier, userScrollEnabled = false) {
        items(POSTER_SKELETON_COUNT) {
            SubjectCardVerticalSkeleton(Modifier.width(ChogumiDimens.CoverWidth))
        }
    }
}

// —— 焦点

/**
 * 焦点：一张作品一张大卡，横滑、停在整张卡上（原型 `.focus`）。卡宽是行宽减去露出下一张的 [FocusPeek]，宽屏上不超过 [FocusMaxWidth]。
 * 卡底是那张封面的模糊铺底，上面压一层从封面取的颜色（M3 内容取色，[rememberCoverScheme]），字色是同一套配色里配对的 on 色。
 * 只放前 [FOCUS_COUNT] 部：焦点是「挑几部细看」，更多的进列表看。
 */
@Composable
private fun FocusRow(subjects: List<SubjectBo>?, onSubjectClick: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = min(maxWidth - FocusPeek, FocusMaxWidth)
        val state = rememberLazyListState()
        CarouselRow(state = state, userScrollEnabled = subjects != null, snapToItems = true) {
            if (subjects == null) {
                items(FOCUS_SKELETON_COUNT) { FocusCardSkeleton(cardWidth) }
            } else {
                items(subjects.take(FOCUS_COUNT), key = { it.id }) { subject -> FocusCard(subject, cardWidth, onSubjectClick) }
            }
        }
    }
}

@Composable
private fun FocusCard(subject: SubjectBo, width: Dp, onSubjectClick: (Int) -> Unit) {
    val page = MaterialTheme.colorScheme
    // 封面取色没到时用页面的容器色
    val (container, onContainer) = rememberCoverColors(subject.coverUrl, CoverCardColors(page.surfaceContainerHigh, page.onSurface))
    // 长按卡上任何地方都是标记这部作品，与海报卡同一个入口；按住时封面的圆角作预示
    val mark = rememberPosterMark(subject.id, subject.type)
    val interaction = remember { MutableInteractionSource() }
    val coverShape = posterCoverShape(mark, interaction, FocusCoverCorner)
    PressArea(
        onClick = { onSubjectClick(subject.id) },
        shape = MaterialTheme.shapes.extraLarge,
        interactionSource = interaction,
        modifier = Modifier.width(width).posterMarkGesture(mark),
    ) {
        FocusFrame(container = container, onContainer = onContainer, washUrl = subject.coverUrl) {
            SubjectPoster(
                url = subject.coverUrl,
                description = displayNameOf(subject),
                modifier = Modifier.width(FocusCoverWidth),
                shape = coverShape,
                mark = mark,
            )
            FocusInfo(Modifier.weight(1f)) {
                Text(
                    text = displayNameOf(subject),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = FOCUS_TITLE_LINES,
                    overflow = TextOverflow.Ellipsis,
                    // 字放大时标题先让：元信息、分数、标签各保一行
                    modifier = Modifier.weight(1f, fill = false),
                )
                val meta = subjectMetaParts(subject)
                if (meta.isNotEmpty()) SubjectMetaText(meta, MaterialTheme.typography.bodyMedium, LocalContentColor.current)
                FocusScore(subject)
                FocusTags(subject.metaTags)
            }
        }
    }
}

/** 焦点卡的骨架：同一个框（[FocusFrame]），封面与几行字换成占位，同宽就同高。 */
@Composable
private fun FocusCardSkeleton(width: Dp) {
    Box(Modifier.width(width)) {
        FocusFrame(
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            onContainer = MaterialTheme.colorScheme.onSurface,
            washUrl = null,
        ) {
            SubjectPosterSkeletonBox(Modifier.width(FocusCoverWidth))
            FocusInfo(Modifier.weight(1f)) {
                SkeletonText(MaterialTheme.typography.titleLarge, lines = 2, widthFraction = 0.9f)
                SkeletonText(MaterialTheme.typography.bodyMedium, widthFraction = 0.6f)
            }
        }
    }
}

/**
 * 焦点卡的框（真卡与骨架共用）：圆角 28（`shapes.extraLarge`），四周 16，封面在左、字在右。
 * 底是封面卡底（[CoverBackdrop]）的容器色罩，字落在 [container] 上。
 */
@Composable
private fun FocusFrame(
    container: Color,
    onContainer: Color,
    washUrl: String?,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.extraLarge
    Surface(shape = shape, color = container, contentColor = onContainer) {
        Box {
            CoverBackdrop(washUrl, CoverVeil.TINT, container, shape, Modifier.matchParentSize())
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(16.dp),
                content = content,
            )
        }
    }
}

/** 封面右边那一栏：与封面同高，各行间隔 6，顶上让出 4 与封面顶边的视觉对齐（原型 `.fx`）。 */
@Composable
private fun FocusInfo(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .height(FocusCoverWidth / ChogumiDimens.CoverRatio)
            .padding(top = 4.dp),
        content = content,
    )
}

/**
 * 焦点卡与卡堆的分数：大号数字加「分，第 N 名」，两种字号按基线对齐。公共评分关着或没有分时整行不画
 * （判定只在 [subjectRatingText]；名次是排名数字，跟着分数一起藏）。
 */
@Composable
internal fun FocusScore(subject: SubjectBo) {
    val score = subjectRatingText(subject.ratingScore, LocalAppFeatures.current, ::formatScore) ?: return
    Row {
        Text(text = score, style = MaterialTheme.typography.headlineMediumEmphasized, maxLines = 1, modifier = Modifier.alignByBaseline())
        Text(
            text = if (subject.ratingRank > 0) {
                stringResource(R.string.explore_focus_score_rank, subject.ratingRank)
            } else {
                stringResource(R.string.explore_focus_score_unit)
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .alignByBaseline()
                .padding(start = 4.dp),
        )
    }
}

/** 焦点卡与卡堆说明底部的几个类型标签（元标签，前 [FOCUS_TAG_COUNT] 个），只占一行，放不下的不画。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FocusTags(tags: List<String>) {
    if (tags.isEmpty()) return
    val chip = LocalContentColor.current.copy(alpha = FOCUS_TAG_ALPHA)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), maxLines = 1) {
        tags.take(FOCUS_TAG_COUNT).forEach { tag ->
            Text(
                text = tag,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier
                    .background(chip, MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

// —— 榜单

/**
 * 榜单：一页三行，横滑翻页、停在整页上（原型 `.chart`），只放前 [CHART_SIZE] 部，更多的进列表看。
 * 每行是名次、封面、标题、元信息与分数；名次是这条货架里的位次，公共评分关着时整列不画（判定只在 [shelfRankNumeralText]）。
 * 页宽是行宽减去露出下一页的 [ChartPeek]，宽屏上不超过 [ChartMaxWidth]。
 */
@Composable
private fun ChartPages(subjects: List<SubjectBo>?, onSubjectClick: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val pageWidth = min(maxWidth - ChartPeek, ChartMaxWidth)
        val state = rememberLazyListState()
        val rows: List<SubjectBo?> = subjects?.take(CHART_SIZE) ?: List(CHART_ROWS) { null }
        CarouselRow(state = state, userScrollEnabled = subjects != null, snapToItems = true) {
            itemsIndexed(rows.chunked(CHART_ROWS)) { page, pageRows ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(pageWidth)) {
                    pageRows.forEachIndexed { row, subject ->
                        val position = page * CHART_ROWS + row + 1
                        if (subject == null) ChartRowSkeleton(position) else ChartRow(subject, position, onSubjectClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartRow(subject: SubjectBo, position: Int, onSubjectClick: (Int) -> Unit) {
    val features = LocalAppFeatures.current
    val mark = rememberPosterMark(subject.id, subject.type)
    val interaction = remember { MutableInteractionSource() }
    val coverShape = posterCoverShape(mark, interaction, FocusCoverCorner)
    PressArea(
        onClick = { onSubjectClick(subject.id) },
        shape = MaterialTheme.shapes.medium,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().posterMarkGesture(mark),
    ) {
        ChartRowFrame(
            numeral = shelfRankNumeralText(position, features),
            cover = {
                SubjectPoster(
                    url = subject.coverUrl,
                    description = displayNameOf(subject),
                    modifier = Modifier.width(ChartCoverWidth),
                    shape = coverShape,
                    mark = mark,
                )
            },
        ) {
            Text(
                text = displayNameOf(subject),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 元信息与分数各占一行，没有的也留着这一行：各行一样高，骨架（[ChartRowSkeleton]）与真行同框
            val secondary = MaterialTheme.colorScheme.onSurfaceVariant
            val meta = subjectMetaParts(subject)
            if (meta.isEmpty()) {
                Text(text = "", style = MaterialTheme.typography.bodyMedium)
            } else {
                SubjectMetaText(meta, MaterialTheme.typography.bodyMedium, secondary)
            }
            Text(
                text = subjectRatingText(subject.ratingScore, features, ::starScoreText).orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = secondary,
                maxLines = 1,
            )
        }
    }
}

/** 榜单一行的骨架：同一个框（[ChartRowFrame]）、同样三行；名次是位次，不等数据就知道，照画。 */
@Composable
private fun ChartRowSkeleton(position: Int) {
    ChartRowFrame(
        numeral = shelfRankNumeralText(position, LocalAppFeatures.current),
        cover = { SubjectPosterSkeletonBox(Modifier.width(ChartCoverWidth)) },
    ) {
        SkeletonText(MaterialTheme.typography.bodyLarge, widthFraction = 0.8f)
        SkeletonText(MaterialTheme.typography.bodyMedium, widthFraction = 0.5f)
        SkeletonText(MaterialTheme.typography.labelMedium, widthFraction = 0.3f)
    }
}

/** 榜单一行的框（真行与骨架共用）：至少 88 高，名次、封面、文字各隔 12，竖向居中（原型 `.crow`）；名次为 null 时整列不画。 */
@Composable
private fun ChartRowFrame(
    numeral: String?,
    cover: @Composable () -> Unit,
    text: @Composable ColumnScope.() -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChartRowMinHeight),
    ) {
        if (numeral != null) {
            Text(
                text = numeral,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                // 位次由列表顺序本身表达，读屏不再念一遍
                modifier = Modifier
                    .widthIn(min = ChartNumeralWidth)
                    .clearAndSetSemantics { },
            )
        }
        cover()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f), content = text)
    }
}

/** 海报行骨架画几张：800dp 宽也铺得满。 */
private const val POSTER_SKELETON_COUNT = 8

/** 焦点卡与榜单行里封面的圆角：`shapes.small` 的 8dp，长按标记的按住变形（[posterCoverShape]）要 Dp 值。 */
private val FocusCoverCorner = 8.dp

/** 焦点卡露出下一张的宽度：卡宽 = 行宽 − 它（原型 `.focus` 的 `--w − 72px`）。 */
private val FocusPeek = 72.dp

/** 焦点卡在宽屏上的最宽：再宽封面与字之间就空出一大片。我的设计决定（not in M3）。 */
private val FocusMaxWidth = 420.dp

/** 焦点卡的封面宽（原型 `.focus .cv`），高按 3:4 推出，整张卡的高由它定。 */
private val FocusCoverWidth = 132.dp

/** 焦点卡标题最多几行（原型 `.ttl`）。 */
private const val FOCUS_TITLE_LINES = 3

/** 焦点放几部（原型 `vFocus` 的前 6 部）。 */
private const val FOCUS_COUNT = 6

/** 焦点骨架画几张：一屏一张多，两张就铺满。 */
private const val FOCUS_SKELETON_COUNT = 2

/** 焦点卡标签放几个。 */
private const val FOCUS_TAG_COUNT = 3


/** 标签底色：当前字色的 12%（原型 `.tags span`）。我的设计决定（not in M3）。 */
private const val FOCUS_TAG_ALPHA = 0.12f

/** 榜单页露出下一页的宽度：页宽 = 行宽 − 它（原型 `.cpage` 的 `--w − 80px`）。 */
private val ChartPeek = 80.dp

/** 榜单页在宽屏上的最宽。我的设计决定（not in M3）。 */
private val ChartMaxWidth = 420.dp

/** 榜单放几部：三页（原型 `vChart` 的前 9 部）。 */
private const val CHART_SIZE = 9

/** 榜单一页几行（原型 `.cpage`）。 */
private const val CHART_ROWS = 3

/** 榜单行的最低高度（原型 `.crow`）。 */
private val ChartRowMinHeight = 88.dp

/** 榜单行的封面宽（原型 `.crow .cv`）。 */
private val ChartCoverWidth = 60.dp

/** 名次一列的最窄宽度：一位数居中，整列对齐（原型 `.no` 的 1.6em）。 */
private val ChartNumeralWidth = 24.dp
