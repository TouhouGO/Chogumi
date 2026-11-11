package io.github.touhougo.chogumi.feature.home.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetDispatchTest {
    @Test fun `旧混淆映射仍在时_更新按系统类型只发给自己的实例`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val system = shadowOf(AppWidgetManager.getInstance(context))
        val expected = widgetReceivers().mapIndexed { index, receiver ->
            val component = ComponentName(context, receiver)
            val info = AppWidgetProviderInfo().apply { provider = component }
            system.addInstalledProvider(info)
            val ids = listOf(index * 10 + 101, index * 10 + 102)
            ids.forEach { system.addBoundWidget(it, info) }
            component to ids.toSet()
        }.toMap()
        // 写进 Glance 自己的 DataStore，而非另造一个错误映射模型。
        val manager = GlanceAppWidgetManager(context)
        manager.getGlanceIds(SubjectWidget::class.java)
        val field = GlanceAppWidgetManager::class.java.getDeclaredField("dataStoreSingleton").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val store = field.get(null) as androidx.datastore.core.DataStore<Preferences>
        store.updateData { old -> old.toMutablePreferences().apply {
            this[stringSetPreferencesKey("list::Providers")] = expected.keys.map { it.className }.toSet()
            expected.keys.forEach { this[stringPreferencesKey("provider:${it.className}")] = "cc8" }
        } }
        assertTrue(manager.getGlanceIds(SubjectWidget::class.java).isEmpty())
        requestWidgetUpdates(context)
        val sent = shadowOf(context).broadcastIntents.filter { it.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE }
            .associate { it.component to it.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)!!.toSet() }
        assertEquals(expected, sent)
        assertEquals(expected, placedWidgets(context).mapValues { it.value.toSet() })
        // Glance Receiver 正常更新时调用的真实登记入口，验证遗留值能覆盖且每类重新隔离。
        val register = GlanceAppWidgetManager::class.java.declaredMethods.single { it.name.startsWith("updateReceiver") }
        register.isAccessible = true
        widgetReceivers().forEach { type ->
            val receiver = type.getDeclaredConstructor().newInstance()
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                register.invoke(manager, receiver, receiver.glanceAppWidget, continuation)
            }
            val ids = manager.getGlanceIds(receiver.glanceAppWidget.javaClass).map { manager.getAppWidgetId(it) }.toSet()
            assertEquals(expected[ComponentName(context, type)], ids)
        }
    }
}
