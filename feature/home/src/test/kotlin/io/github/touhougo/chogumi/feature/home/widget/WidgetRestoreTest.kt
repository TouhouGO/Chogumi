package io.github.touhougo.chogumi.feature.home.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.test.core.app.ApplicationProvider
import io.github.touhougo.chogumi.core.foundation.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetRestoreTest {
    @Test fun `目标写盘中断后重试沿用旧快照_不重复覆盖成功目标`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = shadowOf(AppWidgetManager.getInstance(context))
        val info = AppWidgetProviderInfo().apply { provider = ComponentName(context, SubjectWidgetReceiver::class.java) }
        manager.addBoundWidget(6101, info)
        manager.addBoundWidget(6102, info)
        updateAppWidgetState(context, AppWidgetId(6101)) { it[SubjectKey] = 101 }
        updateAppWidgetState(context, AppWidgetId(6102)) { it[SubjectKey] = 202 }
        // 包住 Glance 已创建的真实 DataStore，只注入持续写失败，其他存取仍走原文件。
        val type = Class.forName("androidx.glance.state.GlanceState")
        val field = type.getDeclaredField("dataStores").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stores = field.get(null) as MutableMap<String, DataStore<Preferences>>
        val original = stores.getValue("appWidget-6101")
        val failing = AtomicBoolean(true)
        stores["appWidget-6101"] = object : DataStore<Preferences> by original {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (failing.get()) throw IOException("fixture disk unavailable")
                return original.updateData(transform)
            }
        }
        try {
            val old = intArrayOf(6101,6102)
            val new = intArrayOf(6102,6101)
            assertEquals(Outcome.Failed(io.github.touhougo.chogumi.core.foundation.Failure.Storage), restoreSubjectWidgets(context,old,new))
            updateAppWidgetState(context,AppWidgetId(6102)) { it[SubjectKey] = 303 }
            failing.set(false)
            assertEquals(Outcome.Success(Unit),restoreSubjectWidgets(context,old,new))
            val widget = SubjectWidget()
            assertEquals(202,widget.getAppWidgetState<Preferences>(context,AppWidgetId(6101))[SubjectKey])
            assertEquals(303,widget.getAppWidgetState<Preferences>(context,AppWidgetId(6102))[SubjectKey])
        } finally { stores["appWidget-6101"] = original }
    }

    @Test fun `恢复交换ID先读整批_重复执行不覆盖后来选择`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = shadowOf(AppWidgetManager.getInstance(context))
        val info = AppWidgetProviderInfo().apply { provider = ComponentName(context, SubjectWidgetReceiver::class.java) }
        manager.addBoundWidget(4101, info)
        manager.addBoundWidget(4102, info)
        updateAppWidgetState(context, AppWidgetId(4101)) { it[SubjectKey] = 101 }
        updateAppWidgetState(context, AppWidgetId(4102)) { it[SubjectKey] = 202 }
        val old = intArrayOf(4101,4102)
        val new = intArrayOf(4102,4101)
        assertEquals(Outcome.Success(Unit), restoreSubjectWidgets(context, old, new))
        val widget = SubjectWidget()
        assertEquals(101, widget.getAppWidgetState<Preferences>(context,AppWidgetId(4102))[SubjectKey])
        assertEquals(202, widget.getAppWidgetState<Preferences>(context,AppWidgetId(4101))[SubjectKey])
        updateAppWidgetState(context, AppWidgetId(4102)) { it[SubjectKey] = 303 }
        assertEquals(Outcome.Success(Unit), restoreSubjectWidgets(context, old, new))
        assertEquals(303, widget.getAppWidgetState<Preferences>(context,AppWidgetId(4102))[SubjectKey])
        assertEquals(202, widget.getAppWidgetState<Preferences>(context,AppWidgetId(4101))[SubjectKey])
    }

    @Test fun `恢复新ID保留各自作品_其他类型状态不改`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = shadowOf(AppWidgetManager.getInstance(context))
        manager.addBoundWidget(5201, AppWidgetProviderInfo().apply { provider = ComponentName(context, SubjectWidgetReceiver::class.java) })
        manager.addBoundWidget(5202, AppWidgetProviderInfo().apply { provider = ComponentName(context, ProgressWidgetReceiver::class.java) })
        updateAppWidgetState(context, AppWidgetId(4201)) { it[SubjectKey] = 101 }
        updateAppWidgetState(context, AppWidgetId(4202)) { it[SubjectKey] = 202 }
        updateAppWidgetState(context, AppWidgetId(5202)) { it[SubjectKey] = 999 }
        assertEquals(Outcome.Success(Unit), restoreSubjectWidgets(context,intArrayOf(4201,4202),intArrayOf(5201,5202)))
        val widget = SubjectWidget()
        assertEquals(101,widget.getAppWidgetState<Preferences>(context,AppWidgetId(5201))[SubjectKey])
        assertEquals(999,widget.getAppWidgetState<Preferences>(context,AppWidgetId(5202))[SubjectKey])
    }
}
