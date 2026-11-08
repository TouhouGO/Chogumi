package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberRangeSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.SearchAirtime
import io.github.touhougo.chogumi.core.model.isSingleYear
import io.github.touhougo.chogumi.core.model.withYears
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.chart.keepSeparated
import io.github.touhougo.chogumi.core.ui.composables.SelectableChip
import io.github.touhougo.chogumi.core.ui.composables.SheetSectionTitle
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import kotlin.math.roundToInt

/**
 * 时间：M3 范围滑块（Range 变体就是选最小值和最大值的，input.md:258）。
 *
 * 档位：最左「更早」（不限下限）、[SLIDER_MIN_YEAR] 到今年 +1 每年一档、最右「更晚」（不限上限）；
 * 两个手柄都在头上即「不限」。读数写在标题右侧——数值已在别处显示，故不画数值气泡（input.md:264）。
 * 两个手柄落在同一年时才允许选月份（6 列 chip）；否则整排变灰并给出说明。
 *
 * From M3（input.md:262-264、component-tokens.md Slider）：轨道高 16dp、圆角全圆，手柄高 44dp、宽 4dp，
 * 按下时手柄变窄。手柄读屏文案用 compose-ui 自带的 `range_start` / `range_end`（含中文翻译），
 * 读数已在标题行显示，不另做读屏文案。
 * 我的设计决定（不在 M3）：年份从 1960 起、月份排成 6 列、**不画档位点**。
 *
 * 档位点：`steps` 必须保留（手柄吸附到整年的行为由它决定，见 [rememberRangeSliderState]），
 * 但让它一年一个点画出来太密（这一档从 1960 到今天有六十多个）。所以覆盖 [SliderDefaults.Track]：
 * `drawTick` 空实现去掉每年的点，`drawStopIndicator = null` 连轨道两端那两个点一起去掉。
 * 两者都只影响绘制，不影响手柄位置（位置由 tickFractions 算，与画不画无关）。
 */
@Composable
internal fun AirtimeSection(airtime: SearchAirtime?, currentYear: Int, onChange: (SearchAirtime?) -> Unit) {
    val lastYear = currentYear + 1
    val yearCount = lastYear - SLIDER_MIN_YEAR + 1
    val lowerIndex = airtime?.startYear?.let { yearToIndex(it) } ?: LOWER_END_INDEX
    val upperIndex = airtime?.endYear?.let { yearToIndex(it) } ?: upperEndIndex(yearCount)
    val state = rememberRangeSliderState(
        startValue = lowerIndex,
        endValue = upperIndex,
        steps = yearCount,
        trackRange = LOWER_END_INDEX..upperEndIndex(yearCount),
    )
    // 外部变化（点「不限」、切换类型把条件带过来）时把手柄同步回来。手指按着、拖着时不回写：那时的值是手柄自己报出去、
    // 晚一帧才传回来的，写回去会把手柄往回拽
    val startSource = remember { MutableInteractionSource() }
    val endSource = remember { MutableInteractionSource() }
    val startHeld by startSource.collectIsDraggedAsState()
    val endHeld by endSource.collectIsDraggedAsState()
    val startPressed by startSource.collectIsPressedAsState()
    val endPressed by endSource.collectIsPressedAsState()
    val held = startHeld || endHeld || startPressed || endPressed
    LaunchedEffect(lowerIndex, upperIndex, held) {
        if (held) return@LaunchedEffect
        state.startValue = lowerIndex
        state.endValue = upperIndex
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AirtimeHeader(airtime = airtime, onClear = { onChange(null) })
        RangeSlider(
            state = state,
            startThumbInteractionSource = startSource,
            endThumbInteractionSource = endSource,
            modifier = Modifier.fillMaxWidth().heightIn(min = SLIDER_TOUCH_HEIGHT).testTag("SearchTimeSlider"),
            onValueChange = { range ->
                onChange(
                    airtime.withYears(
                        startYear = indexToYear(range.start, yearCount),
                        endYear = indexToYear(range.endInclusive, yearCount),
                    ),
                )
            },
            track = { rangeSliderState ->
                SliderDefaults.Track(
                    rangeSliderState = rangeSliderState,
                    drawTick = { _, _ -> },
                    drawStopIndicator = null,
                )
            },
        )
        SliderScale(yearCount)
        MonthRow(airtime = airtime, onChange = onChange)
        Text(
            stringResource(airtimeNoteRes(airtime)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("SearchTimeNote"),
        )
    }
}

/** 最左档位：不限下限。 */
private const val LOWER_END_INDEX = 0f

/** 最右档位：不限上限（档位 0..年数+1 共 年数+2 个）。 */
private fun upperEndIndex(yearCount: Int): Float = (yearCount + 1).toFloat()

/** 年份 → 档位（手柄与刻度共用这一份换算）。 */
private fun yearToIndex(year: Int): Float = (year - SLIDER_MIN_YEAR + 1).toFloat()

/** 档位 → 在滑轨上的比例（0 为最左、1 为最右），与 RangeSlider 按 trackRange 线性摆放手柄一致。 */
private fun indexFraction(index: Float, yearCount: Int): Float =
    (index - LOWER_END_INDEX) / (upperEndIndex(yearCount) - LOWER_END_INDEX)

/** 档位 → 年份；两端档位（不限下限 / 不限上限）返回 null。steps 让拖动吸附到整数档，取整防浮点误差。 */
private fun indexToYear(index: Float, yearCount: Int): Int? {
    val i = index.roundToInt()
    return if (i <= 0 || i >= yearCount + 1) null else SLIDER_MIN_YEAR + i - 1
}

/** 标题行：左「时间」、中读数、右「不限」文字按钮（无值时不出）。 */
@Composable
private fun AirtimeHeader(airtime: SearchAirtime?, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        SheetSectionTitle(stringResource(R.string.search_time), Modifier.weight(1f).alignByBaseline())
        Text(
            airtime.airtimeReadout(),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.alignByBaseline().testTag("SearchTimeReadout"),
        )
        if (airtime != null) {
            TextButton(onClick = onClear, modifier = Modifier.testTag("SearchTimeClear")) {
                Text(stringResource(R.string.search_time_any))
            }
        }
    }
}

/** 手柄下方说明：同一年可再选月份；范围只能按排名、收藏排序。 */
private fun airtimeNoteRes(airtime: SearchAirtime?): Int = when {
    airtime == null -> R.string.search_time_month_hint
    airtime.isSingleYear() -> R.string.search_time_same_year_hint
    else -> R.string.search_time_range_hint
}

/**
 * 刻度：两端「更早」「更晚」贴边，中间三个参考年份按真实位置摆放。
 *
 * 参考年份用与手柄相同的「年份 → 档位 → 比例」换算（[yearToIndex]、[indexFraction]），文字中心对准手柄
 * 在该比例处的中心：中间档位的手柄排在滑轨内缩 [SLIDER_HANDLE_INSET] 之后的区间里（见该常量），贴边时收进容器内。
 * 用自定义 Layout 而不是 BiasAlignment：后者按比例对齐的是文字边缘，中心会偏开「文字宽 ×（0.5 − 比例）」，
 * 2010 年约偏两三档。placeRelative 在 RTL 下随滑块一起镜像。
 */
@Composable
private fun SliderScale(yearCount: Int) {
    Layout(
        content = {
            ScaleTick(stringResource(R.string.search_time_earlier_end))
            SCALE_YEARS.forEach { ScaleTick(stringResource(R.string.search_time_year, it)) }
            ScaleTick(stringResource(R.string.search_time_later_end))
        },
        modifier = Modifier.fillMaxWidth().testTag("SearchTimeScale"),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val width = constraints.maxWidth
        val inset = SLIDER_HANDLE_INSET.roundToPx()
        // 各刻度的横向位置：两端贴边，中间参考年份对准手柄中心
        val first = placeables.first()
        val last = placeables.last()
        val years = SCALE_YEARS.mapIndexed { i, year ->
            val tick = placeables[i + 1]
            val center = inset + ((width - 2 * inset) * indexFraction(yearToIndex(year), yearCount)).roundToInt()
            tick to (center - tick.width / 2).coerceIn(0, width - tick.width)
        }
        // 重要性：两端 > 1990（正中）> 1970 > 2010；字号大、屏幕窄时互相压住的参考年份不画
        val order = listOf(first to 0, last to width - last.width, years[1], years[0], years[2])
        val kept = keepSeparated(order.map { (tick, x) -> x.toFloat()..(x + tick.width).toFloat() }, SCALE_TICK_GAP.toPx())
        layout(width, placeables.maxOf { it.height }) {
            order.forEachIndexed { i, (tick, x) -> if (kept[i]) tick.placeRelative(x, 0) }
        }
    }
}

/**
 * 中间档位的手柄中心离滑块两端的距离。From M3：手柄宽 4dp（component-tokens.md Slider `HandleWidth`）、
 * 16dp 高的滑轨圆角 8dp（input.md:297 XS `Track shape`）。Compose 的 RangeSlider 在有档位时，
 * 滑轨从半个手柄宽处开始，中间档位再排在两端圆角之内（最两端的档位才贴到滑轨端点），
 * 所以内缩 = 半个手柄宽 + 滑轨圆角。
 */
private val SLIDER_HANDLE_INSET = 4.dp / 2 + 8.dp

/** 刻度上标出的参考年份（原型：1970 / 1990 / 2010）。 */
private val SCALE_YEARS = listOf(1970, 1990, 2010)

/** 相邻刻度字之间至少留多宽（我的设计决定，not in M3）。 */
private val SCALE_TICK_GAP = 8.dp

@Composable
private fun ScaleTick(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 月份 1～12（6 列）。只有两个手柄停在同一年时才可点；否则整排置灰不可点。
 * 选中月份后再点一次回到该年不限月份。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthRow(airtime: SearchAirtime?, onChange: (SearchAirtime?) -> Unit) {
    val year = airtime.takeIf { it.isSingleYear() }?.startYear
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetSectionTitle(
            stringResource(R.string.search_month),
            color = if (year != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing),
            verticalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing),
            maxItemsInEachRow = MONTH_COLUMNS,
        ) {
            (1..12).forEach { month ->
                MonthChip(
                    month = month,
                    selected = year != null && airtime?.month == month,
                    enabled = year != null,
                    onClick = {
                        if (year != null) {
                            onChange(
                                SearchAirtime(
                                    startYear = year,
                                    endYear = year,
                                    month = if (airtime?.month == month) null else month,
                                ),
                            )
                        }
                    },
                )
            }
        }
    }
}

/** 月份 chip：两种手柄不在同一年时整排置灰（[SelectableChip] 的 enabled，走 M3 FilterChip 默认的 disabled 态）。 */
@Composable
private fun MonthChip(month: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    SelectableChip(
        label = stringResource(R.string.search_month_value, month),
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.testTag("SearchMonth-$month"),
    )
}

/** 滑块最早年份（原型：年份从 1960 起）。 */
internal const val SLIDER_MIN_YEAR = 1960

/** 月份排列列数（原型：6 列）。 */
private const val MONTH_COLUMNS = 6

/**
 * 手柄的触摸高度下限：M3 RangeSlider 的手柄 44dp 高（component-tokens.md Slider `HandleHeight`），
 * 整块控件按此给足触摸面积。
 */
private val SLIDER_TOUCH_HEIGHT = 44.dp
