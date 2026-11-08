package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.CollectionStatistics
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.chart.ProportionBar
import io.github.touhougo.chogumi.core.ui.chart.ProportionSegment
import io.github.touhougo.chogumi.core.ui.composables.ColorSwatch
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.SkeletonTextDigits
import io.github.touhougo.chogumi.core.ui.composables.colors
import io.github.touhougo.chogumi.core.ui.composables.labelRes
import io.github.touhougo.chogumi.core.ui.composables.labelResFor
import io.github.touhougo.chogumi.core.ui.theme.raisedContainer

/**
 * 完整统计页骨架与真实内容共用的几何锚点 testTag：同一个 tag 挂在两态的同名块上，几何测试据此逐边比对
 * （块内不含文字，语义清空，tag 只作测试用，不改布局）。
 */
internal const val CareerKpiRowTag = "career_kpi_row"
internal const val CareerKpiTileTag = "career_kpi_tile"
internal const val CareerLadderTag = "career_ladder"

/**
 * 三格大数字的骨架：外框就是 [KpiTile] 的外框，排法与真实一排走同一个判定（[KpiRowLayout]）。
 * 骨架不知道数：名称与单位用这一类真实的，数留空，量宽时按 [KPI_VALUE_FLOOR] 算。
 */
@Composable
internal fun CareerKpiRowSkeleton(type: SubjectType, isSelf: Boolean, modifier: Modifier = Modifier) {
    val texts = careerKpisOf(type, CollectionStatistics(), isSelf).map { kpi ->
        KpiText("", stringResource(kpi.unitRes), stringResource(kpi.labelRes), kpi.approximate)
    }
    val styles = kpiStyles()
    KpiRowLayout(texts, modifier.testTag(CareerKpiRowTag).clearAndSetSemantics {}) { _, narrow, tileModifier ->
        KpiTileFrame(narrow, tileModifier) {
            // 大数字是数字而非汉字，样例字取数字，占位行高才与真实值同高（见 SkeletonText）。
            SkeletonText(styles.value, Modifier.fillMaxWidth(0.6f), sample = SkeletonTextDigits)
            SkeletonText(styles.label, Modifier.fillMaxWidth(0.8f))
        }
    }
}

/**
 * 他人汇总中的进度一句话（原型 `.calc` 的文案）；页数拿到后改写成确定进度。
 * 写在骨架的按钮位上（[CareerCardSkeleton] / 完整统计页骨架），不另占一块。
 *
 * **不配加载指示器**：它只出现在骨架里，骨架本身就是加载态。骨架 + 指示器是把两套加载表达叠在一起
 * （骨架优先，只留一个）。这句话保留，是因为它交代「为什么要等、还要等多久」。
 */
@Composable
internal fun SummarizingText(summarizing: CareerContent.Summarizing, type: SubjectType, who: String, modifier: Modifier = Modifier) {
    val spec = careerSpecOf(type)
    val unit = stringResource(spec.unitRes)
    val label = stringResource(type.labelRes())
    val total = summarizing.ladder.total
    val progress = summarizing.progress
    val pages = progress?.totalPages
    Text(
        text = if (pages != null) {
            stringResource(R.string.career_summarizing_progress, who, total, unit, label, progress.completedPages, pages)
        } else {
            stringResource(R.string.career_summarizing, who, total, unit, label)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun SummarizeButton(count: Int, type: SubjectType, who: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        FilledTonalButton(onClick = onClick) {
            Text(stringResource(R.string.career_summarize_action, who, count, stringResource(careerSpecOf(type).unitRes)))
        }
    }
}

// ---- 三格大数字 ----

/** 一格大数字的文字；[approximate] 时数前加「≈」。真实格与骨架格（数换成样例）共用，排法按它量。 */
internal data class KpiText(val value: String, val unit: String?, val label: String, val approximate: Boolean = false)

/**
 * 一排大数字怎么排，按顺序取第一个放得下的（与连通按钮组同一思路，见 `ConnectedChoiceGroup`）：
 * 等宽 → 按内容分宽 → 左右留白收到 [KPI_NARROW_PADDING] → 竖排（每格占满一行）。
 * 任何屏宽、任何字号下，数、单位与名称都不被截掉。
 */
internal sealed interface KpiRowPlan {
    data object Equal : KpiRowPlan

    /** 宽度按 [weights] 分；[narrow] 时左右留白收窄。 */
    data class Flexible(val narrow: Boolean, val weights: List<Float>) : KpiRowPlan

    data object Stacked : KpiRowPlan
}

/**
 * [KpiRowPlan] 的唯一判定。[contentWidths] 是每格不含左右留白的内容宽（数那一行与名称取较宽者），单位都是 px。
 */
internal fun planKpiRow(contentWidths: List<Int>, available: Int, gap: Int, widePadding: Int, narrowPadding: Int): KpiRowPlan {
    val gaps = gap * (contentWidths.size - 1)
    val wide = contentWidths.map { it + 2 * widePadding }
    if (wide.max() <= (available - gaps) / contentWidths.size) return KpiRowPlan.Equal
    if (wide.sum() + gaps <= available) return KpiRowPlan.Flexible(narrow = false, weights = wide.map(Int::toFloat))
    val narrow = contentWidths.map { it + 2 * narrowPadding }
    if (narrow.sum() + gaps <= available) return KpiRowPlan.Flexible(narrow = true, weights = narrow.map(Int::toFloat))
    return KpiRowPlan.Stacked
}

/** 大数字格里三种字：数（headlineMedium 强调）、「≈」与单位（原型 13sp，取 labelLarge）、名称。量宽与真实画字共用。 */
private class KpiStyles(val value: TextStyle, val small: TextStyle, val label: TextStyle)

@Composable
private fun kpiStyles(): KpiStyles = MaterialTheme.typography.let { KpiStyles(it.headlineMediumEmphasized, it.labelLarge, it.labelMedium) }

/** 量出每格的内容宽后交给 [planKpiRow]。 */
@Composable
private fun rememberKpiRowPlan(texts: List<KpiText>, maxWidth: Int): KpiRowPlan {
    val measurer = rememberTextMeasurer()
    val styles = kpiStyles()
    val density = LocalDensity.current
    return remember(texts, maxWidth, styles.value, styles.small, styles.label, density) {
        with(density) {
            fun widthOf(text: String, style: TextStyle) = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
            val valueGap = KPI_VALUE_GAP.roundToPx()
            val valueFloor = widthOf(KPI_VALUE_FLOOR, styles.value)
            planKpiRow(
                contentWidths = texts.map { text ->
                    val parts = listOfNotNull(
                        if (text.approximate) widthOf(APPROXIMATE_MARK, styles.small) else null,
                        maxOf(widthOf(text.value, styles.value), valueFloor),
                        text.unit?.let { widthOf(it, styles.small) },
                    )
                    maxOf(parts.sum() + valueGap * (parts.size - 1), widthOf(text.label, styles.label))
                },
                available = maxWidth,
                gap = KPI_GAP.roundToPx(),
                widePadding = KPI_PADDING.roundToPx(),
                narrowPadding = KPI_NARROW_PADDING.roundToPx(),
            )
        }
    }
}

/**
 * 一排大数字的排法（完整统计页的三格、填坑率与它们的骨架共用）：按 [texts] 量出 [KpiRowPlan]，
 * [tile] 画第 index 格，`modifier` 带着这一格的宽度，`narrow` 时左右留白收窄。
 */
@Composable
private fun KpiRowLayout(
    texts: List<KpiText>,
    modifier: Modifier,
    tile: @Composable (index: Int, narrow: Boolean, modifier: Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        when (val plan = rememberKpiRowPlan(texts, constraints.maxWidth)) {
            KpiRowPlan.Stacked -> Column(verticalArrangement = Arrangement.spacedBy(KPI_GAP)) {
                texts.indices.forEach { tile(it, false, Modifier.fillMaxWidth()) }
            }
            KpiRowPlan.Equal -> Row(horizontalArrangement = Arrangement.spacedBy(KPI_GAP)) {
                texts.indices.forEach { tile(it, false, Modifier.weight(1f)) }
            }
            is KpiRowPlan.Flexible -> Row(horizontalArrangement = Arrangement.spacedBy(KPI_GAP)) {
                texts.indices.forEach { tile(it, plan.narrow, Modifier.weight(plan.weights[it])) }
            }
        }
    }
}

/** 一排大数字（完整统计页的三格、填坑率）。 */
@Composable
internal fun KpiRow(texts: List<KpiText>, modifier: Modifier = Modifier) {
    KpiRowLayout(texts, modifier) { index, narrow, tileModifier -> KpiTile(texts[index], narrow, tileModifier) }
}

@Composable
internal fun CareerKpiRow(kpis: List<CareerKpi>, modifier: Modifier = Modifier) {
    val texts = kpis.map { kpi ->
        KpiText(formatCount(kpi.value), stringResource(kpi.unitRes), stringResource(kpi.labelRes), kpi.approximate)
    }
    KpiRow(texts, modifier.testTag(CareerKpiRowTag))
}

/** 一格大数字的外框（原型 `.kpi`）：底色 [raisedContainer]（比统计卡亮一档）、圆角 16、内边距 12/12/10。真实格与骨架格共用。 */
@Composable
private fun KpiTileFrame(narrow: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val horizontal = if (narrow) KPI_NARROW_PADDING else KPI_PADDING
    Surface(
        color = MaterialTheme.colorScheme.raisedContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.testTag(CareerKpiTileTag),
    ) {
        Column(Modifier.padding(start = horizontal, end = horizontal, top = 12.dp, bottom = 10.dp)) { content() }
    }
}

/** 一格大数字：数、「≈」与单位一行，名称一行（原型 `.kpi`）。宽度由 [KpiRowLayout] 保证放得下。 */
@Composable
private fun KpiTile(text: KpiText, narrow: Boolean, modifier: Modifier = Modifier) {
    val styles = kpiStyles()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    KpiTileFrame(narrow, modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(KPI_VALUE_GAP)) {
            if (text.approximate) Text(APPROXIMATE_MARK, style = styles.small, color = muted, modifier = Modifier.alignByBaseline())
            Text(text.value, style = styles.value, maxLines = 1, modifier = Modifier.alignByBaseline())
            if (text.unit != null) Text(text.unit, style = styles.small, color = muted, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
        Text(text.label, style = styles.label, color = muted, maxLines = 1)
    }
}

/** 三格大数字之间的间距。 */
private val KPI_GAP = 8.dp

/** 一格的左右留白：原型 12；一排放不下时先收到 8（同连通按钮组收窄的那一档，我的设计决定，not in M3）。 */
private val KPI_PADDING = 12.dp
private val KPI_NARROW_PADDING = 8.dp

/** 「≈」、数、单位之间的间距。 */
private val KPI_VALUE_GAP = 2.dp

private const val APPROXIMATE_MARK = "≈"

/**
 * 量宽时数至少按三位算：骨架不知道数，与三位以内的真实数（数字等宽）量出同一个排法，数到了以后原位替换。
 * 四位以上的数可能比骨架多要一档宽（极少数情况下从一排换成竖排）。
 */
private const val KPI_VALUE_FLOOR = "000"

// ---- 进度阶梯 ----

/** 进度阶梯条的高度与图例上沿（原型 `.ladder`）；骨架共用。 */
private val LADDER_BAR_HEIGHT = 16.dp
private val LADDER_LEGEND_TOP = 10.dp

/** 进度阶梯：想 / 在 / 过 三段条 + 图例，图例末尾「另有 搁置 N、抛弃 N」（搁置与抛弃不进条里，只在数字上交代）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LadderSummary(ladder: StatusLadder, type: SubjectType, modifier: Modifier = Modifier) {
    val unit = stringResource(careerSpecOf(type).unitRes)
    val shown = listOf(
        CollectionType.WISH to ladder.wish,
        CollectionType.DOING to ladder.doing,
        CollectionType.DONE to ladder.done,
    )
    Column(modifier.testTag(CareerLadderTag)) {
        ProportionBar(
            segments = shown.map { (status, count) ->
                ProportionSegment(
                    weight = count,
                    color = status.colors().color,
                    tip = stringResource(R.string.career_tip_count, stringResource(status.labelResFor(type)), count, unit),
                )
            },
            description = stringResource(R.string.career_ladder_description),
            height = LADDER_BAR_HEIGHT,
        )
        FlowRow(
            Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = LADDER_LEGEND_TOP),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            shown.forEach { (status, count) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ColorSwatch(status.colors().color)
                    Text(stringResource(R.string.label_with_count, stringResource(status.labelResFor(type)), count), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                stringResource(
                    R.string.career_ladder_others,
                    stringResource(CollectionType.ON_HOLD.labelResFor(type)), ladder.onHold,
                    stringResource(CollectionType.DROPPED.labelResFor(type)), ladder.dropped,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
