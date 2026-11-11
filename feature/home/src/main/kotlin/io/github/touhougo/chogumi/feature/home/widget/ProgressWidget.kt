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
import io.github.touhougo.chogumi.core.res.R
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 在看列表这一次画的东西：作品、写失败的那几部、取到的封面。 */
internal data class ProgressContent(val works: WidgetWorks, val failed: Set<Int>, val covers: Map<Int, Bitmap>)

/**
 * 在看列表（4×1 起，可拉大）：标题行写在看几部，下面一行一部，先待看、再其余在追的（[widgetWorksOf]），行数随高度
 * （[listLayoutOf]）。每行点封面或文字打开作品页，点 +1 标下一集。
 */
internal class ProgressWidget : GlanceAppWidget(), KoinComponent {
    private val source: WidgetWorksSource by inject()
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
                val works = source.load()
                val covers = (works as? WidgetWorks.Ready)
                    ?.let { ready -> coverBitmapsOf(context, ready.works.map { it.coverRef }, LIST_MAX_ROWS, coverWidthOf(ListCoverMaxHeight), ListCoverMaxHeight) }
                    .orEmpty()
                ProgressContent(works, actions.failed.value, covers)
            },
        ) { ProgressBody(it) }
    }

    @Composable
    private fun ProgressBody(content: ProgressContent) {
        val context = LocalContext.current
        val title = context.getString(R.string.widget_progress_title)
        val count = (content.works as? WidgetWorks.Ready)?.works?.size ?: 0
        val layout = listLayoutOf(LocalSize.current.height, count)
        // 只有画作品行时框才按版面让空；标题加一句话（没登录、读不出来、没有在看的）照常留空
        val vertical = if (count == 0) ListFramePadding else layout.verticalPadding
        Column(widgetFrame().padding(horizontal = 16.dp, vertical = vertical)) {
            when (val works = content.works) {
                WidgetWorks.SignedOut -> {
                    WidgetHeader(title, null)
                    SignInContent()
                }
                is WidgetWorks.Failed -> {
                    WidgetHeader(title, null)
                    MessageContent(context.getString(R.string.widget_load_failed))
                }
                is WidgetWorks.Ready -> {
                    if (layout.header || count == 0) {
                        WidgetHeader(title, if (count > 0) context.resources.getQuantityString(R.plurals.work_count, count, count) else null)
                    }
                    if (count == 0) {
                        MessageContent(context.getString(R.string.widget_empty))
                    } else {
                        works.works.take(layout.rows).forEach { work ->
                            WorkRow(
                                work,
                                work.subjectId in content.failed,
                                content.covers[work.subjectId],
                                layout.coverHeight,
                                LocalSize.current.width - 32.dp,
                                ButtonShape.PLUS_ONE,
                                GlanceModifier.height(layout.rowHeight),
                            )
                        }
                    }
                }
            }
        }
    }
}

class ProgressWidgetReceiver : ChogumiWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ProgressWidget()
}
