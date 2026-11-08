package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectRelationBo
import io.github.touhougo.chogumi.core.model.SubjectSeries
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.rememberPosterMark
import io.github.touhougo.chogumi.core.ui.composables.CAROUSEL_SKELETON_COUNT
import io.github.touhougo.chogumi.core.ui.composables.CarouselItemSpacing
import io.github.touhougo.chogumi.core.ui.composables.CarouselRow
import io.github.touhougo.chogumi.core.ui.composables.PosterCard
import io.github.touhougo.chogumi.core.ui.composables.PosterCardSkeleton
import io.github.touhougo.chogumi.core.ui.composables.PosterCardStyle
import io.github.touhougo.chogumi.core.ui.composables.PosterCardWidth
import io.github.touhougo.chogumi.core.ui.composables.formatScore

/** 系列里的一部：本作或沿前传、续集走到的作品。[score] 为 0 是还没有评分，折线上不画它的点。 */
private class SeriesEntry(
    val id: Int,
    val type: SubjectType,
    val name: String,
    val posterUrl: String?,
    val score: Double,
    val current: Boolean,
)

/** 本作在中间，前面的在左、后面的在右；每部写它自己的名字（[displayNameOf]）。 */
private fun seriesEntriesOf(subject: SubjectBo, series: SubjectSeries): List<SeriesEntry> {
    fun entryOf(relation: SubjectRelationBo) =
        SeriesEntry(relation.id, relation.type, displayNameOf(relation), relation.images?.posterUrl(), relation.score, current = false)
    val current = SeriesEntry(subject.id, subject.type, displayNameOf(subject), subject.coverUrl, subject.ratingScore, current = true)
    return series.before.map(::entryOf) + current + series.after.map(::entryOf)
}

/** 折线区的高度：分数字一行之下留出 [SLOPE_PLOT] 画折线。我的设计决定（not in M3）。 */
@Composable
private fun slopeHeight(): Dp = with(LocalDensity.current) { MaterialTheme.typography.labelMedium.lineHeight.toDp() } + SLOPE_PLOT

private val SLOPE_PLOT = 36.dp

/**
 * 系列：与关联作品同一个横排（[CarouselRow]，从最早的一部排起），一部一张 [PosterCardStyle.CAROUSEL] 卡（点进那一部，长按标记）。
 * 概览 tab 与统计 tab 共用；统计 tab 给 [scores]：每张卡上方空出折线区，各部的全站分在那里连成一条折线，跟着横排一起滚
 * （[seriesSlope]）。
 */
@Composable
internal fun SeriesRow(
    subject: SubjectBo,
    series: SubjectSeries,
    scores: Boolean,
    onSubjectClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = remember(subject, series) { seriesEntriesOf(subject, series) }
    val state = rememberLazyListState()
    val slope = if (scores) seriesSlope(entries, state) else Modifier
    CarouselRow(modifier.then(slope), state = state) {
        items(entries, key = { it.id }) { entry ->
            Column(Modifier.width(PosterCardWidth)) {
                if (scores) Spacer(Modifier.height(slopeHeight()))
                PosterCard(
                    imageUrl = entry.posterUrl,
                    title = entry.name,
                    style = PosterCardStyle.CAROUSEL,
                    onClick = { onSubjectClick(entry.id) }.takeUnless { entry.current },
                    mark = if (entry.current) null else rememberPosterMark(entry.id, entry.type),
                    modifier = Modifier.width(PosterCardWidth),
                )
            }
        }
    }
}

/**
 * 横排上方的分数折线：各部的点在它那张卡的正上方，位置从横排的布局信息读（卡宽一样，看不见的那几部按间距推出来），
 * 所以折线跟着横排一起滚。本作的点实心 primary、分数加粗；没有评分的那一部不画点，折线跳过它。
 */
@Composable
private fun seriesSlope(entries: List<SeriesEntry>, state: LazyListState): Modifier {
    val measurer = rememberTextMeasurer()
    val description = stringResource(R.string.stats_series_description)
    val plotHeight = slopeHeight()
    val lineColor = MaterialTheme.colorScheme.outline
    val currentColor = MaterialTheme.colorScheme.primary
    val otherColor = MaterialTheme.colorScheme.onSurfaceVariant
    val background = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val currentStyle = MaterialTheme.typography.labelMediumEmphasized
    val otherStyle = MaterialTheme.typography.labelMedium
    return Modifier
        .semantics { contentDescription = description }
        .clipToBounds()
        .drawBehind {
            val info = state.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull() ?: return@drawBehind
            val pitch = (PosterCardWidth + CarouselItemSpacing).toPx()
            val origin = first.offset - info.viewportStartOffset + PosterCardWidth.toPx() / 2 - first.index * pitch
            val scored = entries.filter { it.score > 0 }
            val lo = scored.minOfOrNull { it.score } ?: 0.0
            val hi = scored.maxOfOrNull { it.score } ?: 0.0
            // 分差太小时上下各留出一点，几部差 0.1 分不画成陡坡
            val mid = (lo + hi) / 2
            val span = maxOf(hi - lo, MIN_SCORE_SPAN)
            val top = otherStyle.lineHeight.toPx() + 6.dp.toPx()
            val bottom = plotHeight.toPx() - 6.dp.toPx()
            fun yOf(score: Double) = (top + (mid + span / 2 - score) / span * (bottom - top)).toFloat()
            val points = entries.mapIndexedNotNull { i, entry ->
                if (entry.score > 0) entry to Offset(origin + i * pitch, yOf(entry.score)) else null
            }
            if (points.size >= 2) {
                val path = Path().apply {
                    moveTo(points.first().second.x, points.first().second.y)
                    points.drop(1).forEach { (_, point) -> lineTo(point.x, point.y) }
                }
                drawPath(path, lineColor, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
            }
            points.forEach { (entry, point) ->
                if (entry.current) {
                    drawCircle(currentColor, 6.dp.toPx(), point)
                } else {
                    drawCircle(background, 4.5.dp.toPx(), point)
                    drawCircle(otherColor, 4.5.dp.toPx(), point, style = Stroke(2.dp.toPx()))
                }
                val laid = measurer.measure(formatScore(entry.score), if (entry.current) currentStyle else otherStyle)
                val baseline = point.y - 10.dp.toPx()
                drawText(laid, if (entry.current) ink else otherColor, Offset(point.x - laid.size.width / 2, baseline - laid.firstBaseline))
            }
        }
}

/** 系列没到时的占位：与 [SeriesRow] 同一个横排、同宽的卡骨架，[scores] 时每张上面空出同高的折线区。 */
@Composable
internal fun SeriesRowSkeleton(scores: Boolean, modifier: Modifier = Modifier) {
    CarouselRow(modifier, userScrollEnabled = false) {
        items(CAROUSEL_SKELETON_COUNT) {
            Column(Modifier.width(PosterCardWidth)) {
                if (scores) Spacer(Modifier.height(slopeHeight()))
                PosterCardSkeleton(PosterCardStyle.CAROUSEL, Modifier.width(PosterCardWidth))
            }
        }
    }
}

/** 折线纵向至少按这么大的分差铺开（分）。我的设计决定（not in M3）。 */
private const val MIN_SCORE_SPAN = 1.0
