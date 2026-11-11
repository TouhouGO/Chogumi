@file:OptIn(androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi::class, androidx.compose.runtime.InternalComposeApi::class)
package io.github.touhougo.chogumi.feature.home.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.touhougo.chogumi.core.data.progress.ProgressSubject
import io.github.touhougo.chogumi.core.model.BookUnit
import io.github.touhougo.chogumi.core.model.CalendarEntry
import io.github.touhougo.chogumi.core.model.CalendarItemBo
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import java.time.LocalDate

/** 离线宿主只提供领域输入，布局始终调用组件实际 Body；不会启动应用会话或发送进度请求。 */
internal object WidgetHostFixtures {
    val date: LocalDate = LocalDate.of(2026, 11, 11)
    fun work(id: Int) = WidgetWork(id, "作品 $id · 测试标题", null, ProgressCount.Known(BookUnit.VOL, 2, 12),
        EpButtonState.BookNext(2, BookUnit.VOL), ProgressSubject(id, SubjectType.BOOK, emptyList()))

    fun bodies(): List<Triple<Any, String, Any>> {
        val work = work(101)
        val entry = CalendarEntry(CalendarItemBo(SubjectBo(id = 101, type = SubjectType.ANIME, name = "作品101", nameCn = ""), 20), null)
        return listOf(
            Triple(ProgressWidget(), "ProgressBody", ProgressContent(WidgetWorks.Ready(listOf(work, work(202)), date), emptySet(), emptyMap())),
            Triple(SubjectWidget(), "SubjectBody", SubjectContent.Work(work, false, null)),
            Triple(SubjectWidget(), "SubjectBody", SubjectContent.Work(work(202), false, null)),
            Triple(TodayWidget(), "TodayBody", TodayContent.Ready(3, listOf(TodayRow(entry, work)), emptySet(), emptyMap())),
            Triple(HeatWidget(), "HeatBody", heatOf(emptyList(), date, 12)),
            Triple(WeekWidget(), "WeekBody", WeekContent.Ready((1..7).map { WeekDay(it, it == 3, listOf(entry)) }, 1, emptyMap())),
        )
    }

    fun context(base: Context, dark: Boolean, fontScale: Float): Context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        this.fontScale = fontScale
    })

    @Composable private fun body(item: Triple<Any, String, Any>) {
        item.first.javaClass.declaredMethods.single { it.name == item.second }.apply { isAccessible = true }
            .invoke(item.first, item.third, currentComposer, 0)
    }

    suspend fun render(context: Context, renderer: GlanceRemoteViews, item: Triple<Any, String, Any>, width: Int, height: Int): RemoteViews {
        // 构造实际 WidgetPalette；领域夹具不要求读应用账号或配色存储。
        val palette = WidgetPalette::class.java.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(lightColorScheme(), darkColorScheme()) as WidgetPalette
        return renderer.compose(context, DpSize(width.dp, height.dp)) {
            CompositionLocalProvider(LocalWidgetPalette provides palette) { body(item) }
        }.remoteViews
    }

    fun measure(context: Context, remote: RemoteViews, width: Int, height: Int): View {
        val parent = FrameLayout(context)
        val view = remote.apply(context, parent)
        parent.addView(view)
        val density = context.resources.displayMetrics.density
        parent.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
        return parent
    }

    fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    fun bitmap(view: View): Bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
}
