package io.github.touhougo.chogumi.feature.home.widget

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.data.repository.TestPreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WidgetInputsTest {

    @Test
    fun `换壁纸后系统配色更新（进程配置变更）小组件要重画`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val preferences = TestPreferencesRepository()
        val beats = AtomicInteger()
        val collector = CoroutineScope(Dispatchers.Default).launch {
            widgetInputs(app, preferences.repository).collect { beats.incrementAndGet() }
        }
        // 配色设置与明暗各发一拍初值，之后没有别的输入在变
        awaitUntil("配色设置的初值到了") { beats.get() >= 2 }
        val settled = beats.get()

        // 收集方在别的线程上登记回调：配置一直变，直到登记好之后的那一次变更到达
        awaitUntil("配置变更带来一次重画") {
            app.onConfigurationChanged(Configuration(app.resources.configuration))
            beats.get() > settled
        }

        collector.cancelAndJoin()
        preferences.close()
    }
}
