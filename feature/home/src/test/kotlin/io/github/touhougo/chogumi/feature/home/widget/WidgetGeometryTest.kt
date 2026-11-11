@file:OptIn(androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi::class, androidx.compose.runtime.InternalComposeApi::class)
package io.github.touhougo.chogumi.feature.home.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import io.github.touhougo.chogumi.core.data.repository.TestPreferencesRepository
import io.github.touhougo.chogumi.core.data.progress.ProgressSubject
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.BookUnit
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.CalendarItemBo
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.calendarDaySections
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetGeometryTest {
    @Composable private fun body(widget: Any, name: String, content: Any) {
        val method = widget.javaClass.declaredMethods.single { it.name == name }
        method.isAccessible = true
        method.invoke(widget, content, currentComposer, 0)
    }
    @Test fun `单部作品最窄尺寸仍有标题`() { inspectProductionLayouts("SubjectBody") }
    @Test fun `今日放送最窄尺寸仍有标题`() { inspectProductionLayouts("TodayBody") }
    @Test fun `热力图格子不越过内容区`() { inspectProductionLayouts("HeatBody") }
    @Test fun `正常宽度仍保留作品标题`() { inspectProductionLayouts("normal") }
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun x(view: View): Int = view.left + ((view.parent as? View)?.let(::x) ?: 0)
    private fun inspectProductionLayouts(target: String) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = shadowOf(context.packageManager)
        val entry = ComponentName(context.packageName, "fixture.LaunchActivity")
        pm.addActivityIfNotPresent(entry)
        pm.addIntentFilterForActivity(entry, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        val prefs = TestPreferencesRepository()
        try {
            val palette = WidgetPalette.of(context, prefs.repository)
            val work = WidgetWork(101, "Fixture Subject 101", null, ProgressCount.Known(BookUnit.VOL,2,12), EpButtonState.BookNext(2, BookUnit.VOL), ProgressSubject(101, SubjectType.BOOK, emptyList()))
            val heat = heatOf(emptyList(), LocalDate.of(2026,11,11),12)
            val entryRow = calendarDaySections(3,listOf(CalendarItemBo(SubjectBo(id=101,type=SubjectType.ANIME,name="Fixture 101",nameCn=""),20)),emptySet(),emptyMap()).unknown.first()
            val cases = listOf(
                Triple(HeatWidget(), "HeatBody", heat) to DpSize(130.dp,226.dp),
                Triple(SubjectWidget(), "SubjectBody", SubjectContent.Work(work,false,null)) to DpSize(110.dp,110.dp),
                Triple(TodayWidget(), "TodayBody", TodayContent.Ready(3,listOf(TodayRow(entryRow,work)),emptySet(),emptyMap())) to DpSize(180.dp,110.dp),
                Triple(ProgressWidget(), "ProgressBody", ProgressContent(WidgetWorks.Ready(listOf(work), LocalDate.of(2026,11,11)),emptySet(),emptyMap())) to DpSize(180.dp,40.dp)
            )
            val normal = listOf(
                Triple(SubjectWidget(), "SubjectBody", SubjectContent.Work(work,false,null)) to DpSize(300.dp,110.dp),
                Triple(TodayWidget(), "TodayBody", TodayContent.Ready(3,listOf(TodayRow(entryRow,work)),emptySet(),emptyMap())) to DpSize(300.dp,110.dp)
            )
            for ((case,size) in (if (target == "normal") normal else cases.filter { it.first.second == target })) {
                val rv = GlanceRemoteViews().compose(context,size) { CompositionLocalProvider(LocalWidgetPalette provides palette) { body(case.first,case.second,case.third) } }.remoteViews
                val parent = FrameLayout(context)
                val v = rv.apply(context,parent)
                parent.addView(v)
                parent.measure(View.MeasureSpec.makeMeasureSpec(size.width.value.toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size.height.value.toInt(),View.MeasureSpec.EXACTLY))
                parent.layout(0,0,parent.measuredWidth,parent.measuredHeight)
                if (case.second == "SubjectBody" || case.second == "TodayBody") {
                    val title = all(v).filterIsInstance<TextView>().single { it.text.toString() == work.title }
                    assertTrue("${case.second} $size: title width=${title.width}, expected >= 40", title.width >= 40)
                }
                if (case.second == "HeatBody") {
                    val cells = all(v).filterIsInstance<ImageView>().filter { it.width == it.height && it.width in 1..30 }
                    assertTrue("月历必须有格子", cells.isNotEmpty())
                    val cell = cells.maxBy { x(it) + it.width }
                    assertTrue("HeatBody $size: last cell right=${x(cell)+cell.width}, padded content right=${size.width.value-16}", x(cell)+cell.width <= size.width.value-16)
                }
            }
        } finally { prefs.close() }
    }
}
