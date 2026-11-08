package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.LineupCount
import io.github.touhougo.chogumi.core.model.PopularityPoint
import io.github.touhougo.chogumi.core.model.RatedWork
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.chart.DotStrip
import io.github.touhougo.chogumi.core.ui.chart.StripDot
import io.github.touhougo.chogumi.core.ui.chart.StripTick
import io.github.touhougo.chogumi.core.ui.chart.ordinalTones
import io.github.touhougo.chogumi.core.ui.composables.CircleAvatar
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SkeletonStandIn
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.StatsCard
import io.github.touhougo.chogumi.core.ui.composables.colors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/*
 * 完整统计页的区块外框，与把统计页的数据接到 core:ui 图表上的几处（分差、收藏人数、题材深浅、领奖台）。
 * 数怎么算在 core:data，画什么在 CareerPresentation；图本身在 core:ui 的 chart 包。
 */

// ---- 区块 ----

/**
 * 统计页的一块：短标题（titleSmall，淡色）+ 一句答案（headlineSmall，关键的数用 primary）+ 图。
 * 答案里要强调的部分由 [highlighted] 标出；不写问句、不写说明。
 *
 * 在 [StatPlaceholders] 里画成骨架：标题、答案按同一句话排成占位条（[SkeletonText]），图按同一份数据画出来但不可见，
 * 盖一层色块（[SkeletonStandIn]）——骨架的每一块与真块同大，放大字号、换屏宽时一起变。
 */
@Composable
internal fun StatSection(title: String, answer: AnnotatedString, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (LocalStatPlaceholder.current) {
        StatSectionFrame(
            title = { SkeletonText(it, sample = title, widthFraction = TITLE_SKELETON_FRACTION) },
            answer = { style, answerModifier -> SkeletonText(style, answerModifier, sample = answer.text, widthFraction = ANSWER_SKELETON_FRACTION) },
            modifier = modifier.testTag(StatSectionTag),
        ) {
            SkeletonStandIn(content = content)
        }
        return
    }
    StatSectionFrame(
        title = { Text(title, style = it, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        answer = { style, answerModifier -> Text(answer, style = style, modifier = answerModifier) },
        modifier = modifier.testTag(StatSectionTag),
        content = content,
    )
}

/** 区块的测试标签：骨架与真块同一个，几何测试按顺序逐块比对。 */
internal const val StatSectionTag = "career_stat_section"

/** 为 true 时 [StatSection] 画成骨架；只由 [StatPlaceholders] 打开。 */
private val LocalStatPlaceholder = staticCompositionLocalOf { false }

/** 里面的 [StatSection] 都画成骨架（完整统计页骨架用样例数据摆出真实的区块，见 [CareerStatsSkeleton]）。 */
@Composable
internal fun StatPlaceholders(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatPlaceholder provides true, content = content)
}

/** 骨架里标题、答案占位条的长短（相对可用宽度；我的设计决定，not in M3）。 */
private const val TITLE_SKELETON_FRACTION = 0.25f
private const val ANSWER_SKELETON_FRACTION = 0.6f

/** 区块外框：真块与骨架共用，版面只此一处。 */
@Composable
private fun StatSectionFrame(
    title: @Composable (TextStyle) -> Unit,
    answer: @Composable (TextStyle, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    StatsCard(modifier) {
        title(MaterialTheme.typography.titleSmall)
        answer(MaterialTheme.typography.headlineSmall, Modifier.padding(top = 2.dp, bottom = 12.dp))
        content()
    }
}

/** 区块里的小标题（「我 − 大众」「最冷门的三部」）：与区块标题同一样式。 */
@Composable
internal fun StatSubtitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/** 把 [text] 里第一处 [part] 标成 primary：答案句里要一眼看到的那个数。[part] 不在句中时原样返回。 */
@Composable
internal fun highlighted(text: String, part: String): AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    val at = text.indexOf(part)
    return buildAnnotatedString {
        if (at < 0 || part.isEmpty()) {
            append(text)
        } else {
            append(text.substring(0, at))
            withStyle(SpanStyle(color = accent)) { append(part) }
            append(text.substring(at + part.length))
        }
    }
}

// ---- 点条 ----

/** 点条的字：几部合成一点时写「几部、哪几部」，比例写「一点代表几部」；[unit] 是作品的量词。 */
@Composable
private fun UnitDotStrip(
    bins: List<List<StripDot>>,
    ticks: List<StripTick>,
    marker: Float?,
    centered: Boolean,
    unit: String,
    description: String,
    modifier: Modifier,
) {
    DotStrip(
        bins = bins,
        ticks = ticks,
        marker = marker,
        centered = centered,
        groupTip = { count, titles -> stringResource(R.string.career_tip_dots, count, unit, namesOf(titles)) },
        scaleLabel = { scale -> stringResource(R.string.career_dot_scale, scale, unit) },
        description = description,
        modifier = modifier,
    )
}

/** 分差点条：我 − 大众，±[GAP_AXIS_LIMIT] 分；比大众高的点深一档。 */
@Composable
internal fun GapStrip(bins: List<GapBin>, tip: @Composable (RatedWork) -> String, unit: String, description: String, modifier: Modifier = Modifier) {
    val (lower, higher) = ordinalTones(2)
    fun at(gap: Double) = ((gap + GAP_AXIS_LIMIT) / (2 * GAP_AXIS_LIMIT)).toFloat()
    UnitDotStrip(
        bins = bins.map { bin -> bin.works.map { StripDot(at(bin.gap), if (it.gap < 0) lower else higher, it.title, tip(it)) } },
        ticks = GAP_TICKS.map { StripTick(at(it), if (it > 0) "+${it.toInt()}" else "${it.toInt()}") },
        marker = at(0.0),
        centered = true,
        unit = unit,
        description = description,
        modifier = modifier,
    )
}

private val GAP_TICKS = listOf(-4.0, -2.0, 0.0, 2.0, 4.0)

/**
 * 收藏人数点条：对数轴，人越少越靠左；点色是我的收藏状态，竖线是中位数，刻度见 [popularityTicks]。
 * [membersLabel] 写一个人数（「1,000」「1.2万」）。
 */
@Composable
internal fun PopularityStrip(
    points: List<PopularityPoint>,
    median: Int,
    tip: @Composable (PopularityPoint) -> String,
    membersLabel: (Int) -> String,
    unit: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val lo = ln(points.minOf { it.members } * POPULARITY_PAD_LO)
    val hi = ln(points.maxOf { it.members } * POPULARITY_PAD_HI)
    fun at(members: Int) = ((ln(members.toDouble()) - lo) / (hi - lo).coerceAtLeast(1e-6)).toFloat()
    val colors = points.associate { it.subjectId to it.status.colors().color }
    // 每 [POPULARITY_BINS] 分之一的轴长一箱，同一箱的点叠起来
    val bins = points.groupBy { (at(it.members) * POPULARITY_BINS).toInt() }.values.map { bin ->
        bin.map { StripDot(at(it.members), colors.getValue(it.subjectId), it.title, tip(it)) }
    }
    val ticks = popularityTicks(points.minOf { it.members }, points.maxOf { it.members }).map { StripTick(at(it), membersLabel(it)) }
    UnitDotStrip(bins, ticks, marker = at(median), centered = false, unit = unit, description = description, modifier = modifier)
}

/** 收藏人数轴两端各留一点空（我的设计决定，not in M3）。 */
private const val POPULARITY_PAD_LO = 0.8
private const val POPULARITY_PAD_HI = 1.25
private const val POPULARITY_BINS = 36

/** 收藏人数轴的刻度：轴上的 10 的整数次幂，至多取最大的三个；只有一个时再加它的 5 倍（轴上放得下的话）。 */
internal fun popularityTicks(least: Int, most: Int): List<Int> {
    val from = ceil(kotlin.math.log10(least * POPULARITY_PAD_LO)).toInt()
    val to = floor(kotlin.math.log10(most * POPULARITY_PAD_HI)).toInt()
    val powers = (from..to).map { 10.0.pow(it).toInt() }
    return when {
        powers.size >= 2 -> powers.takeLast(3)
        powers.size == 1 -> listOf(powers[0], powers[0] * 5).filter { it <= most * POPULARITY_PAD_HI }
        else -> emptyList()
    }
}

// ---- 题材版图的深浅 ----

/** 题材均分落在哪一档：6 分及以下最浅，8.5 分及以上最深。 */
internal fun treemapToneOf(average: Double): Int =
    (((average - 6.0) / 2.5).coerceIn(0.0, 1.0) * (GENRE_TONES - 1)).let { Math.round(it).toInt() }

/** 题材版图的深浅分几档。 */
internal const val GENRE_TONES = 5

// ---- 声优领奖台 ----


/** 领奖台站几个人。 */
internal const val PODIUM_SIZE = 3

/**
 * 声优领奖台：出现最多的三位并排，第一在中间最大，第二在左、第三在右依次小一档，底边对齐；
 * 头像下是名字和部数（[caption]），点一位进人物页。不到三位时空出缺的位置，第一仍在中间。
 * 头像直径 96 / 80 / 72dp 是我的设计决定（not in M3）。
 */
@Composable
internal fun VoicePodium(top: List<LineupCount>, caption: @Composable (Int) -> String, onPersonClick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        PODIUM_ORDER.forEach { rank ->
            val entry = top.getOrNull(rank)
            if (entry == null) {
                Spacer(Modifier.weight(1f))
            } else {
                PressArea(
                    onClick = { onPersonClick(entry.name.id) },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircleAvatar(entry.name.avatarUrl, description = null, size = PODIUM_AVATARS[rank])
                        Text(
                            entry.name.name,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            caption(entry.works),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** 从左到右站第几名：第二、第一、第三。 */
private val PODIUM_ORDER = listOf(1, 0, 2)

/** 第一到第三名的头像直径。 */
private val PODIUM_AVATARS = listOf(96.dp, 80.dp, 72.dp)
