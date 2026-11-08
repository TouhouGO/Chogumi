package io.github.touhougo.chogumi.feature.home.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.weekdayNarrowLabel
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 本周追番这一次画的东西。 */
internal sealed interface WeekContent {
    data object SignedOut : WeekContent

    data object LoadFailed : WeekContent

    /** 七列（[weekDaysOf]）、这七天播的在追作品一共几部、取到的封面。 */
    data class Ready(val days: List<WeekDay>, val count: Int, val covers: Map<Int, Bitmap>) : WeekContent
}

internal fun weekContentOf(calendar: WidgetCalendar): WeekContent = when (calendar) {
    WidgetCalendar.Failed -> WeekContent.LoadFailed
    is WidgetCalendar.Ready -> if (!calendar.signedIn) {
        WeekContent.SignedOut
    } else {
        val days = weekDaysOf(calendar.week, calendar.today)
        WeekContent.Ready(days, days.flatMap { it.entries }.distinctBy { it.subject.id }.size, emptyMap())
    }
}

/** 本周追番一列在一个尺寸下的排法：封面多高（3:4），一列放几格（放不下时最后一格写 +N）。 */
internal data class WeekLayout(val coverHeight: Dp, val slots: Int)

/**
 * [size] 大的本周追番怎么排：七列均分宽度，封面按列宽取（3:4，最高 [WEEK_COVER_MAX_HEIGHT]），列高减去星期那一行，放得下几张就几格。
 */
internal fun weekLayoutOf(size: DpSize): WeekLayout {
    val column = (size.width - WEEK_FRAME_HORIZONTAL * 2) / WEEK_DAYS - WEEK_COLUMN_GAP
    val coverHeight = min((column - WEEK_COLUMN_INSET * 2) * 4 / 3, WEEK_COVER_MAX_HEIGHT)
    val space = size.height - ListFramePadding * 2 - HeaderHeight - WEEK_HEADER_GAP - WEEK_COLUMN_PADDING * 2 - WEEK_LABEL_HEIGHT
    val slots = (space / (coverHeight + WEEK_COVER_GAP)).toInt().coerceIn(0, WEEK_MAX_SLOTS)
    return WeekLayout(coverHeight, slots)
}

/** 一列 [slots] 格里画哪几部、最后写不写 +N：放得下就全画，放不下时最后一格让给「+剩下几部」。 */
internal fun weekColumnOf(count: Int, slots: Int): Pair<Int, Int> =
    if (count <= slots) count to 0 else (slots - 1).coerceAtLeast(0).let { shown -> shown to count - shown }

/**
 * 本周追番（4×2 起）：周一起七列，每列是从今天起七天里的那一天（与每日放送页的星期选择器同一个模型），今天那个字垫次要色调。
 * 每列按播出时刻排这天播的我在追的封面，放不下写 +N（[weekLayoutOf]）。点封面打开作品页，点别处打开每日放送。
 */
internal class WeekWidget : GlanceAppWidget(), KoinComponent {
    private val source: WidgetCalendarSource by inject()
    private val updates: WidgetUpdates by inject()
    private val preferences: PreferencesRepository by inject()

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideLiveContent(
            context,
            updates,
            preferences,
            load = {
                when (val content = weekContentOf(source.load())) {
                    is WeekContent.Ready -> {
                        // 每天前几部的封面：一列最多画这么多，再多的写在 +N 里
                        val refs = content.days.flatMap { day -> day.entries.take(WEEK_MAX_SLOTS) }
                            .map { CoverRef(it.subject.id, it.subject.coverUrl) }
                        val height = WEEK_COVER_MAX_HEIGHT
                        content.copy(covers = coverBitmapsOf(context, refs, WEEK_COVER_LIMIT, coverWidthOf(height), height, WEEK_COVER_CORNER))
                    }
                    else -> content
                }
            },
        ) { WeekBody(it) }
    }

    @Composable
    private fun WeekBody(content: WeekContent) {
        val context = LocalContext.current
        val title = context.getString(R.string.widget_week_title)
        val openCalendar = openApp(context, BgmWeb.calendar())
        Column(widgetFrame().padding(horizontal = WEEK_FRAME_HORIZONTAL, vertical = ListFramePadding)) {
            when (content) {
                WeekContent.SignedOut -> {
                    WidgetHeader(title, null, openCalendar)
                    SignInContent()
                }
                WeekContent.LoadFailed -> {
                    WidgetHeader(title, null, openCalendar)
                    MessageContent(context.getString(R.string.widget_calendar_load_failed))
                }
                is WeekContent.Ready -> {
                    val count = content.count
                    WidgetHeader(title, if (count > 0) context.resources.getQuantityString(R.plurals.work_count, count, count) else null, openCalendar)
                    if (count == 0) {
                        MessageContent(context.getString(R.string.widget_week_empty), openCalendar)
                    } else {
                        val layout = weekLayoutOf(LocalSize.current)
                        Spacer(GlanceModifier.height(WEEK_HEADER_GAP))
                        // 七列，列间距写在每列两边（Glance 一行最多 10 个子项，不另放间隔）
                        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                            content.days.forEach { day ->
                                Box(GlanceModifier.defaultWeight().fillMaxHeight().padding(horizontal = WEEK_COLUMN_GAP / 2)) {
                                    WeekColumn(day, layout, content.covers, openCalendar)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun WeekColumn(day: WeekDay, layout: WeekLayout, covers: Map<Int, Bitmap>, onClick: Action) {
        val context = LocalContext.current
        val palette = LocalWidgetPalette.current
        val (shown, more) = weekColumnOf(day.entries.size, layout.slots)
        Column(
            GlanceModifier.fillMaxSize().padding(vertical = WEEK_COLUMN_PADDING).clickable(onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 今天只在星期那个字上垫一块次要色调：整列铺底时，封面少的那几天下面空出的一截更显眼
            val label = weekdayNarrowLabel(context.resources, day.weekday)
            if (day.today) {
                Box(
                    GlanceModifier.size(WEEK_TODAY_MARK_WIDTH, WEEK_LABEL_HEIGHT).background(palette.tonal).cornerRadius(WEEK_LABEL_HEIGHT / 2),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = widgetText(WidgetType.tiny, palette.onTonal), maxLines = 1)
                }
            } else {
                Text(label, style = widgetText(WidgetType.tiny, palette.onSurfaceVariant), maxLines = 1, modifier = GlanceModifier.height(WEEK_LABEL_HEIGHT))
            }
            day.entries.take(shown).forEach { entry ->
                Box(GlanceModifier.padding(top = WEEK_COVER_GAP)) { WidgetCover(entry.subject.id, covers[entry.subject.id], layout.coverHeight, WEEK_COVER_CORNER) }
            }
            if (more > 0) {
                Text(
                    context.getString(R.string.widget_week_more, more),
                    style = widgetText(WidgetType.tiny, palette.onSurfaceVariant),
                    maxLines = 1,
                    modifier = GlanceModifier.padding(top = WEEK_COVER_GAP),
                )
            }
        }
    }
}

private const val WEEK_DAYS = 7

/** 一列最多几格（Glance 一列最多 10 个子项；封面位图都在一个跨进程包里）。 */
private const val WEEK_MAX_SLOTS = 4

/** 一次最多取几张封面（每张按 [WEEK_COVER_MAX_HEIGHT] 取，见 [coverBitmapsOf]）。 */
private const val WEEK_COVER_LIMIT = 24

private val WEEK_FRAME_HORIZONTAL = 16.dp
private val WEEK_HEADER_GAP = 4.dp
private val WEEK_COLUMN_GAP = 4.dp
private val WEEK_COLUMN_INSET = 4.dp
private val WEEK_COLUMN_PADDING = 4.dp

/** 今天那个字底下那块的宽（高同字行）。 */
private val WEEK_TODAY_MARK_WIDTH = 24.dp
private val WEEK_COVER_GAP = 4.dp
private val WEEK_COVER_MAX_HEIGHT = 40.dp

/** 小封面（约 30dp 宽）的圆角：M3 最小一档 4dp；系统的内部圆角放在这么窄的图上四角连成药丸。 */
private val WEEK_COVER_CORNER = 4.dp

/** 星期那一行字的高（labelSmall 的行高）。 */
private val WEEK_LABEL_HEIGHT = 16.dp

class WeekWidgetReceiver : ChogumiWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeekWidget()
}
