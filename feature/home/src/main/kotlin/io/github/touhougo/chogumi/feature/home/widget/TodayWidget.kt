package io.github.touhougo.chogumi.feature.home.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.layout.Column
import androidx.glance.layout.height
import androidx.glance.layout.padding
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.calendarWatchersLabel
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 今日放送这一次画的东西。 */
internal sealed interface TodayContent {
    /** 放送表读不出来。 */
    data object LoadFailed : TodayContent

    /** 放送日的星期 [weekday]、今天的行（[todayRowsOf]）、小组件上写失败的作品与取到的封面。 */
    data class Ready(val weekday: Int, val rows: List<TodayRow>, val failed: Set<Int>, val covers: Map<Int, Bitmap>) : TodayContent
}

/**
 * 今日放送（4×1 起，默认 4×3）：标题行写今天星期几（放送日，北京时间 4 点翻页），下面一行一部：今天播的我在追的排前面，
 * 与在看列表同一行（进度与 +1），行首写播出时刻；其余今天播的按时刻接在后面，写在看人数。行数随高度（[listLayoutOf]）。
 * 点行打开作品页，点标题行打开每日放送。没登录也能看放送表，只是没有「我在追」。
 */
internal class TodayWidget : GlanceAppWidget(), KoinComponent {
    private val source: WidgetCalendarSource by inject()
    private val actions: WidgetProgressActions by inject()
    private val updates: WidgetUpdates by inject()
    private val preferences: PreferencesRepository by inject()

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideLiveContent(
            context,
            updates,
            preferences,
            load = {
                when (val calendar = source.load()) {
                    WidgetCalendar.Failed -> TodayContent.LoadFailed
                    is WidgetCalendar.Ready -> {
                        val rows = todayRowsOf(calendar.week.getValue(calendar.today), calendar.works)
                        val covers = coverBitmapsOf(
                            context,
                            rows.map { CoverRef(it.entry.subject.id, it.entry.subject.coverUrl) },
                            LIST_MAX_ROWS,
                            coverWidthOf(ListCoverMaxHeight),
                            ListCoverMaxHeight,
                        )
                        TodayContent.Ready(calendar.today, rows, actions.failed.value, covers)
                    }
                }
            },
        ) { TodayBody(it) }
    }

    @Composable
    private fun TodayBody(content: TodayContent) {
        val context = LocalContext.current
        val title = context.getString(R.string.widget_today_title)
        val openCalendar = openApp(context, BgmWeb.calendar())
        when (content) {
            TodayContent.LoadFailed -> Column(widgetFrame().padding(horizontal = 16.dp, vertical = ListFramePadding)) {
                WidgetHeader(title, null, openCalendar)
                MessageContent(context.getString(R.string.widget_calendar_load_failed))
            }
            is TodayContent.Ready -> {
                val layout = listLayoutOf(LocalSize.current.height, content.rows.size)
                val empty = content.rows.isEmpty()
                // 只有画作品行时框才按版面让空；标题加一句「今天没有放送」照常留空
                val vertical = if (empty) ListFramePadding else layout.verticalPadding
                Column(widgetFrame().padding(horizontal = 16.dp, vertical = vertical)) {
                    if (layout.header || empty) {
                        WidgetHeader(title, context.resources.getStringArray(R.array.weekday_names)[content.weekday - 1], openCalendar)
                    }
                    if (empty) {
                        MessageContent(context.getString(R.string.widget_today_empty), openCalendar)
                    } else {
                        content.rows.take(layout.rows).forEach { row -> TodayRowItem(row, content, layout) }
                    }
                }
            }
        }
    }

    /** 一行：在追且有进度的是在看列表的那一行，其余写在看人数（放送表里没有的在追作品人数是 0，不写）。 */
    @Composable
    private fun TodayRowItem(row: TodayRow, content: TodayContent.Ready, layout: ListLayout) {
        val context = LocalContext.current
        val subject = row.entry.subject
        val modifier = GlanceModifier.height(layout.rowHeight)
        // 没有时刻也留着这一列，各行的封面对齐
        val time = row.entry.airing?.label().orEmpty()
        val cover = content.covers[subject.id]
        if (row.work != null) {
            WorkRow(row.work, subject.id in content.failed, cover, layout.coverHeight, ButtonShape.PLUS_ONE, modifier, time)
        } else {
            WidgetRow(subject.id, displayNameOf(subject), cover, layout.coverHeight, modifier, time, details = {
                if (row.entry.watchers > 0) DetailLine(calendarWatchersLabel(context.resources, row.entry.watchers))
            })
        }
    }
}

class TodayWidgetReceiver : ChogumiWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
