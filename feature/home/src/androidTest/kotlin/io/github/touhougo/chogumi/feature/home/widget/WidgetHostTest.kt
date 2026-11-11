@file:OptIn(androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi::class)
package io.github.touhougo.chogumi.feature.home.widget

import android.widget.TextView
import android.appwidget.AppWidgetHostView
import android.view.View
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetHostTest {
    @Test fun 多类与同类实例切尺寸后仍保留各自内容() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val bodies = WidgetHostFixtures.bodies()
        val renderers = bodies.map { GlanceRemoteViews() }
        val hosts = bodies.map { AppWidgetHostView(context) }
        for ((width,height) in listOf(300 to 110, 180 to 226, 448 to 226, 300 to 110)) {
            bodies.forEachIndexed { index, body ->
                val remote = WidgetHostFixtures.render(context, renderers[index], body, width, height)
                instrumentation.runOnMainSync {
                    val host = hosts[index]
                    host.updateAppWidget(remote)
                    val density = context.resources.displayMetrics.density
                    host.measure(View.MeasureSpec.makeMeasureSpec((width*density).toInt(),View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((height*density).toInt(),View.MeasureSpec.EXACTLY))
                    host.layout(0,0,host.measuredWidth,host.measuredHeight)
                    val texts = WidgetHostFixtures.descendants(host).filterIsInstance<TextView>()
                    assertTrue("${body.second} 必须有可见内容", texts.any { it.width > 0 && it.text.isNotEmpty() })
                    if (body.second == "SubjectBody") {
                        val id = (body.third as SubjectContent.Work).work.subjectId
                        assertTrue("单部作品实例保留选择 $id", texts.any { it.width > 0 && it.text.contains(id.toString()) })
                    }
                }
            }
        }
    }

    @Test fun 最小合法宽度标题和操作都可见() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val bodies = WidgetHostFixtures.bodies()
        for ((index,width) in listOf(1 to 110, 3 to 180, 0 to 180)) {
            val remote = WidgetHostFixtures.render(context, GlanceRemoteViews(), bodies[index], width, 110)
            instrumentation.runOnMainSync {
                val host = WidgetHostFixtures.measure(context,remote,width,110)
                val texts = WidgetHostFixtures.descendants(host).filterIsInstance<TextView>()
                assertTrue("作品标题必须可见", texts.any { it.text.contains("101") && it.width > 0 })
                assertTrue("进度操作必须可见", texts.any { it.text.toString() == "+1" && it.width > 0 })
            }
        }
    }
}
