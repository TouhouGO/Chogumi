package io.github.touhougo.chogumi.feature.home.widget

import android.content.Context
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult
import androidx.test.core.app.ApplicationProvider
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeHomeProgressDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectSectionDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.FaultablePreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.testAuthRepository
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testHomeRepository
import io.github.touhougo.chogumi.core.data.repository.testNsfwFilter
import io.github.touhougo.chogumi.core.data.repository.testSubjectRepository
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetReliabilityTest {
    @get:org.junit.Rule val resources = TestResources()

    @Test
    fun `真实小组件刷新订阅偏好读失败_不能漏到应用级异常处理器`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val escaped = CompletableDeferred<Throwable>()
        val applicationScope = resources.own(CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> escaped.complete(error) }))
        val fault = FaultablePreferencesRepository(resources)
        fault.failReads.set(true)
        val (updates) = realUpdates(context, fault.repository, applicationScope)
        updates.ensureRunning()
        awaitUntil("偏好读取失败") { fault.failedReads.get() > 0 }
        assertNull(if (escaped.isCompleted) escaped.await() else null, "偏好读取异常不能漏出")
        fault.failReads.set(false)
        awaitUntil("存储恢复后实际重画") { updates.revision.value > 0 }
        assertNull(if (escaped.isCompleted) escaped.await() else null, "重画订阅必须恢复")
    }

    @Test
    fun `配置页的小组件已被移走_选择作品不应抛未处理异常`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = FaultablePreferencesRepository(resources)
        val (updates) = realUpdates(context, preferences.repository, resources.scope)
        // 打开配置页后，桌面移走了这块；id不等于INVALID_APPWIDGET_ID，但系统已查不到provider。
        val result = chooseSubject(context, 723167, 101, updates)
        assertEquals(io.github.touhougo.chogumi.core.foundation.Outcome.Success(false), result)
    }

    @Test
    fun `本机会话读不出来时整帧仍交出来源画的读不出来`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val unreadable = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("tokens unreadable") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("tokens unwritable")
        }
        val (updates, source) = realUpdates(context, FaultablePreferencesRepository(resources).repository, resources.scope, TokenStore(unreadable))
        assertEquals(WidgetWorks.Failed(Failure.Storage), updates.currentAccountFrame { source.load() })
    }

    @Test
    fun `取整帧期间换了号_按新账号重取一次`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tokens = testTokenStore(resources, 7)
        val (updates) = realUpdates(context, FaultablePreferencesRepository(resources).repository, resources.scope, tokens)
        var loads = 0
        val frame = updates.currentAccountFrame {
            loads++
            if (loads == 1) tokens.completeSession(tokens.beginSession("other"), 8, "other")
            loads
        }
        assertEquals(2, frame)
    }

    @Test
    fun `系统恢复广播里_更新仍拿到广播的异步句柄_迁移已排上`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!WorkManagerImpl.isInitialized()) WorkManager.initialize(context, Configuration.Builder().build())
        val work = WorkManager.getInstance(context)
        val preferences = FaultablePreferencesRepository(resources).repository
        val widgets = realUpdates(context, preferences, resources.scope)
        // 接收器与小组件经 KoinComponent 取依赖，读的是全局那一份。经 KoinContext 接口启动：`startKoin { }` 的写法会被编译插件当成应用的整张图校验。
        KoinPlatformTools.defaultContext().startKoin(koinApplication {
            modules(module { single { widgets.updates }; single { widgets.source }; single { widgets.actions }; single { preferences } })
        })
        val receiver = SubjectWidgetReceiver()
        try {
            context.registerReceiver(receiver, IntentFilter(AppWidgetManager.ACTION_APPWIDGET_RESTORED), Context.RECEIVER_NOT_EXPORTED)
            // 系统恢复后发来的那一条：AppWidgetProvider 在这一次 onReceive 里先调 onRestored、再调 onUpdate。
            context.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_RESTORED).setPackage(context.packageName)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS, intArrayOf(4301))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(5301)))
            shadowOf(Looper.getMainLooper()).idle()
            // 影子在每次 goAsync() 时记下当时还在手上的句柄：同一次广播里取过一次再取，系统给的是空。
            val broadcast = assertNotNull(shadowOf(receiver).originalPendingResult, "这次广播里最后一次 goAsync() 仍取得到广播的句柄")
            awaitUntil("Glance 的更新结束这次广播") { Shadow.extract<ShadowBroadcastPendingResult>(broadcast).future.isDone }
            awaitUntil("作品选择的迁移排进 WorkManager") { work.getWorkInfosByTag(SubjectWidgetRestoreWorker::class.java.name).get().isNotEmpty() }
        } finally {
            context.unregisterReceiver(receiver)
            work.cancelAllWork().result.get()
            stopKoin()
        }
    }

    private suspend fun realUpdates(
        context: Context,
        preferences: io.github.touhougo.chogumi.core.data.repository.PreferencesRepository,
        applicationScope: CoroutineScope,
        tokenStore: TokenStore = testTokenStore(resources, 7),
    ): Widgets {
        val client = resources.apiClient { respond("", HttpStatusCode.NotFound) }
        val clock = TestClock(0)
        val subjectDao = FakeSubjectDao()
        val episodeDao = FakeEpisodeDao()
        val collections = testCollections(resources, CollectionApi(client), subjectDao, FakeUserCollectionDao(), tokenStore, FakeEpisodeWatchStateDao(), episodeDao, clock = clock)
        val home = testHomeRepository(NextApi(client), FakeHomeProgressDao(), subjectDao, tokenStore, collections, episodeDao, clock)
        val subjects = testSubjectRepository(resources, SubjectApi(client), NextApi(client), subjectDao, episodeDao, FakeSubjectSectionDao(), testNsfwFilter(resources), tokenStore, clock)
        val coordinator = collections.epTapCoordinator(collections.progressOrchestrator(subjects), resources.scope, { 0L }, { })
        val auth = testAuthRepository(resources, client, tokenStore, AuthConfig("fixture", "fixture", "chogumi://oauth/callback"), resources.scope)
        auth.ensureInitialized()
        val source = WidgetWorksSource(home, collections.observer, auth, clock)
        val actions = WidgetProgressActions(source, coordinator)
        return Widgets(WidgetUpdates(context, applicationScope, collections.observer, preferences, auth, actions), source, actions)
    }

    private data class Widgets(val updates: WidgetUpdates, val source: WidgetWorksSource, val actions: WidgetProgressActions)

}
