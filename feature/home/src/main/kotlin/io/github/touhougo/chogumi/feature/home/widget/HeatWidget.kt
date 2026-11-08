package io.github.touhougo.chogumi.feature.home.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import io.github.touhougo.chogumi.core.data.repository.AuthRepository
import io.github.touhougo.chogumi.core.data.repository.LocalCareerStatistics
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.displayZone
import io.github.touhougo.chogumi.core.foundation.settled
import io.github.touhougo.chogumi.core.model.YearRing
import io.github.touhougo.chogumi.core.model.ringLevelOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.feature.home.R as HomeR
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import kotlin.math.ceil
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 热力图的一个月：[levels] 一天一格（[ringLevelOf] 的档，与生涯的年轮同一个分档），今天以后的日子是 null。 */
internal data class HeatMonth(val month: YearMonth, val levels: List<Int?>)

/** 热力图这一次画的东西：[months] 早的在前、最后一个是这个月，[monthEpisodes] 是这个月到今天看了几集。 */
internal sealed interface HeatContent {
    data object SignedOut : HeatContent

    data object LoadFailed : HeatContent

    data class Ready(val months: List<HeatMonth>, val monthEpisodes: Int) : HeatContent
}

/** 截到 [today] 的最近 [count] 个月（含这个月），每天看了几集来自生涯的年轮（[YearRing]，按本机时区分日子）。 */
internal fun heatOf(rings: List<YearRing>, today: LocalDate, count: Int): HeatContent.Ready {
    val episodes = rings.flatMap { it.days.entries }.associate { (date, day) -> date to day.episodes }
    val current = YearMonth.from(today)
    val months = (count - 1 downTo 0).map { back ->
        val month = current.minusMonths(back.toLong())
        HeatMonth(month, (1..month.lengthOfMonth()).map { day -> month.atDay(day).takeUnless { it.isAfter(today) }?.let { ringLevelOf(episodes[it] ?: 0) } })
    }
    val monthEpisodes = (1..today.dayOfMonth).sumOf { episodes[current.atDay(it)] ?: 0 }
    return HeatContent.Ready(months, monthEpisodes)
}

/** 热力图读的数据：本机收藏算出的生涯统计（个人页生涯同一份）里的年轮。 */
internal class WidgetHeatSource(
    private val authRepository: AuthRepository,
    private val statistics: LocalCareerStatistics,
    private val clock: AppClock,
) {
    suspend fun load(): HeatContent {
        when (widgetSignInOf(authRepository)) {
            WidgetSignIn.SIGNED_IN -> Unit
            WidgetSignIn.SIGNED_OUT -> return HeatContent.SignedOut
            WidgetSignIn.UNREADABLE -> return HeatContent.LoadFailed
        }
        val today = Instant.ofEpochMilli(clock.nowMillis()).atZone(displayZone()).toLocalDate()
        return when (val read = statistics.observeStatistics(today.year).settled()) {
            is Outcome.Success -> heatOf(read.value.overall.rhythm?.rings.orEmpty(), today, HEAT_MONTHS)
            is Outcome.Failed -> HeatContent.LoadFailed
        }
    }

    private companion object {
        /** 宽的排法画几个月。 */
        const val HEAT_MONTHS = 12
    }
}

/**
 * 观看热力图：4×2 起是最近 12 个月（一个月一行、一天一格），2×2 是这个月的日历（一周一行，从周一起）。
 * 颜色与分档同生涯的年轮；今天以后的日子只画轮廓。点它打开 App。
 */
internal class HeatWidget : GlanceAppWidget(), KoinComponent {
    private val source: WidgetHeatSource by inject()
    private val updates: WidgetUpdates by inject()
    private val preferences: PreferencesRepository by inject()

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideLiveContent(context, updates, preferences, load = { source.load() }) { HeatBody(it) }
    }

    @Composable
    private fun HeatBody(content: HeatContent) {
        val context = LocalContext.current
        val title = context.getString(R.string.career_sec_ring)
        Column(widgetFrame().padding(horizontal = 16.dp, vertical = 12.dp).clickable(openApp(context))) {
            when (content) {
                HeatContent.SignedOut -> {
                    WidgetHeader(title, null)
                    SignInContent()
                }
                HeatContent.LoadFailed -> {
                    WidgetHeader(title, null)
                    MessageContent(context.getString(R.string.widget_load_failed))
                }
                is HeatContent.Ready -> {
                    WidgetHeader(title, context.resources.getQuantityString(R.plurals.widget_heat_month, content.monthEpisodes, content.monthEpisodes))
                    val size = LocalSize.current
                    val width = size.width - FRAME_PADDING * 2
                    val height = size.height - FRAME_PADDING * 2 - HEADER_SPACE
                    if (size.width >= YEAR_MIN_WIDTH) YearGrid(content.months, width, height) else MonthGrid(content.months.last(), width, height)
                }
            }
        }
    }

    private companion object {
        /** 至少这么宽才画一年（4×2 约 276dp 宽，2×2 约 130dp）。 */
        val YEAR_MIN_WIDTH = 200.dp
        val FRAME_PADDING = 12.dp
        val HEADER_SPACE = 32.dp
    }
}

/** 一年：一个月一行、一天一格；左边每隔几行写一个月份，隔几行由一行多高定（字放得下才写）。 */
@Composable
private fun YearGrid(months: List<HeatMonth>, width: Dp, height: Dp) {
    val context = LocalContext.current
    val palette = LocalWidgetPalette.current
    val locale = context.resources.configuration.locales[0]
    val cell = min((width - MONTH_LABEL_WIDTH) / MAX_DAYS, height / months.size)
    val labelEvery = ceil(LABEL_LINE.value / cell.value).toInt().coerceAtLeast(1)
    Row {
        // 月份一列：几行一组，组里第一个月写名字
        GlanceColumn(months.groupsOf(labelEvery)) { group ->
            Box(GlanceModifier.width(MONTH_LABEL_WIDTH).height(cell * group.size)) {
                Text(
                    group.first().month.month.getDisplayName(TextStyle.SHORT, locale),
                    style = widgetText(WidgetType.tiny, palette.onSurfaceVariant),
                    maxLines = 1,
                )
            }
        }
        GlanceColumn(months) { month ->
            GlanceRow(month.levels) { level -> HeatCell(level, cell) }
        }
    }
}

/** 这个月：一周一行（周一起），格子里写日期，格子太小就不写。 */
@Composable
private fun MonthGrid(month: HeatMonth, width: Dp, height: Dp) {
    val palette = LocalWidgetPalette.current
    val lead = month.month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
    val days: List<Pair<Int, Int?>?> = List(lead) { null } + month.levels.mapIndexed { index, level -> index + 1 to level }
    val weeks = days.groupsOf(WEEK_DAYS)
    val cell = min(width / WEEK_DAYS, height / weeks.size)
    GlanceColumn(weeks) { week ->
        GlanceRow(week) { day ->
            if (day == null) {
                Box(GlanceModifier.size(cell)) {}
            } else {
                Box(GlanceModifier.size(cell), contentAlignment = Alignment.Center) {
                    HeatCell(day.second, cell)
                    if (cell >= DAY_NUMBER_MIN_CELL) {
                        val ink = day.second?.let { palette.heatInk[it] } ?: palette.onSurfaceVariant
                        Text(day.first.toString(), style = widgetText(WidgetType.tiny, ink), maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 一格：有数的按档填色，今天以后（[level] 为 null）只画轮廓。格间距与圆角在图形资源里（格间 2dp，与生涯的年轮一样）。 */
@Composable
private fun HeatCell(level: Int?, size: Dp) {
    val palette = LocalWidgetPalette.current
    Image(
        ImageProvider(if (level == null) HomeR.drawable.widget_heat_pending else HomeR.drawable.widget_heat_cell),
        contentDescription = null,
        modifier = GlanceModifier.size(size),
        contentScale = ContentScale.FillBounds,
        colorFilter = ColorFilter.tint(if (level == null) palette.pending else palette.heat[level]),
    )
}

/** Glance 的一行最多放 10 个子项（多的会被截掉）：多的按 10 个一组再套一层。 */
@Composable
private fun <T> GlanceRow(items: List<T>, item: @Composable (T) -> Unit) {
    Row { items.groupsOf(GLANCE_MAX_CHILDREN).forEach { chunk -> Row { chunk.forEach { item(it) } } } }
}

/** 同 [GlanceRow]，竖着排。 */
@Composable
private fun <T> GlanceColumn(items: List<T>, item: @Composable (T) -> Unit) {
    Column { items.groupsOf(GLANCE_MAX_CHILDREN).forEach { chunk -> Column { chunk.forEach { item(it) } } } }
}

/**
 * 按 [size] 个一组切开：日历的一周、几个月一组的月份标签、Glance 一层最多 10 个子项。组的大小由日期与 Glance 的限制定，
 * 不是按宽度推列数的卡片网格（格子边长另按小组件的尺寸算）。
 */
private fun <T> List<T>.groupsOf(size: Int): List<List<T>> = chunked(size)

private const val GLANCE_MAX_CHILDREN = 10
private const val MAX_DAYS = 31
private const val WEEK_DAYS = 7
private val MONTH_LABEL_WIDTH = 28.dp

/** 月份与日期那一行字大约多高（labelSmall 的行高）。 */
private val LABEL_LINE = 16.dp

/** 格子至少这么大才在里面写日期。 */
private val DAY_NUMBER_MIN_CELL = 18.dp

class HeatWidgetReceiver : ChogumiWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HeatWidget()
}
