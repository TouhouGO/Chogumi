@file:OptIn(androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi::class)
package io.github.touhougo.chogumi.feature.home.widget

import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@VisualCapture
@RunWith(AndroidJUnit4::class)
class WidgetHostCapture {
    @Test fun 五类小组件在常规宽度和窄尺寸的浅深色截图() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (dark in listOf(false,true)) {
            for (width in listOf(448,380,800,110,180)) {
                val context = WidgetHostFixtures.context(instrumentation.context,dark,if (width == 380) 1.1f else 1f)
                WidgetHostFixtures.bodies().forEachIndexed { index,body ->
                    if (width == 110 && body.second !in listOf("SubjectBody","HeatBody")) return@forEachIndexed
                    val height = if (width == 110) 110 else 226
                    val remote = WidgetHostFixtures.render(context,GlanceRemoteViews(),body,width,height)
                    instrumentation.runOnMainSync {
                        val view = WidgetHostFixtures.measure(context,remote,width,height)
                        saveScreenshot("widget-host", "${index}-${body.second}-${width}-${if (dark) "dark" else "light"}", WidgetHostFixtures.bitmap(view))
                    }
                }
            }
        }
    }
}
