package io.github.touhougo.chogumi.feature.home.widget

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.robolectric.Shadows.shadowOf
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.touhougo.chogumi.core.data.db.home.HomeProgressDao
import io.github.touhougo.chogumi.core.data.db.home.HomeProgressEntity
import io.github.touhougo.chogumi.core.data.db.calendar.CalendarEntity
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import io.github.touhougo.chogumi.core.data.repository.AiringTimeRepository
import io.github.touhougo.chogumi.core.data.repository.FakeAiringTimeDao
import io.github.touhougo.chogumi.core.data.repository.FakeCalendarDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeHomeProgressDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.SystemNoticeLedger
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testAuthRepository
import io.github.touhougo.chogumi.core.data.repository.testCalendarRepository
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testHomeRepository
import io.github.touhougo.chogumi.core.data.repository.testNsfwFilter
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.network.api.BangumiDataApi
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.ui.common.SystemNotices
import io.github.touhougo.chogumi.feature.home.notice.AiringReminders
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.After
import org.junit.Before

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiringReminderReliabilityTest {
    private lateinit var originalZone: java.util.TimeZone
    @Before fun rememberZone() { originalZone = java.util.TimeZone.getDefault() }
    @After fun restoreZone() { java.util.TimeZone.setDefault(originalZone) }
    @get:org.junit.Rule val resources = TestResources()

    @Test fun `取放送数据中途从A换B_不得给B发A的在追作品`() = exercise(switchAccount = true, blockPermission = false)
    @Test fun `未发出的放送提醒恢复权限后仍应发送`() = exercise(switchAccount = false, blockPermission = true)
    @Test fun `每日定时汇总经真实来源发布_重新跑不重复`() = exercise(switchAccount = false, blockPermission = false, daily = true)
    @Test fun `每小时真实Worker离线刷新失败仍按缓存发开播提醒`() = exercise(switchAccount = false, blockPermission = false, worker = true)

    private fun exercise(switchAccount: Boolean, blockPermission: Boolean, daily: Boolean = false, worker: Boolean = false) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).apply {
            val component = ComponentName(context.packageName, "FixtureLaunchActivity")
            addActivityIfNotPresent(component)
            addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = ZonedDateTime.of(2026, 11, 10, 12, 10, 0, 0, ZoneOffset.ofHours(8)).toInstant().toEpochMilli()
        val clock = TestClock(now)
        val tokens = testTokenStore(resources, 7, "accountA")
        val client = resources.apiClient { respond("", HttpStatusCode.NotFound) }
        val auth = testAuthRepository(resources, client, tokens, AuthConfig("test", "secret", "chogumi://oauth/callback"), resources.scope)
        auth.ensureInitialized()
        val subjects = FakeSubjectDao()
        subjects.upsert(SubjectEntity(101, 2, "A only", "A账号在追的作品", updatedAt = now))
        val collectionDao = FakeUserCollectionDao()
        collectionDao.upsert(UserCollectionEntity(101, 2, 3, updatedAt = now, accountId = 7))
        val episodeDao = FakeEpisodeDao()
        val collections = testCollections(resources, CollectionApi(client), subjects, collectionDao, tokens, FakeEpisodeWatchStateDao(), episodeDao, clock = clock)
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val realHomeDao = FakeHomeProgressDao()
        val homeDao = object : HomeProgressDao by realHomeDao {
            override fun observeByAccount(accountId: Int): Flow<List<HomeProgressEntity>> = flow {
                if (accountId == 7) { arrived.complete(Unit); release.await() }
                emitAll(realHomeDao.observeByAccount(accountId))
            }
        }
        val home = testHomeRepository(NextApi(client), homeDao, subjects, tokens, collections, episodeDao, clock)
        val calendarDao = FakeCalendarDao()
        calendarDao.upsertAll(listOf(CalendarEntity(2, 101, "A only", "A账号在追的作品", updatedAt = now)))
        val calendar = testCalendarRepository(resources, NextApi(client), calendarDao, subjects, testNsfwFilter(resources), tokens, collections.store, clock)
        val times = AiringTimeRepository(BangumiDataApi(client), FakeAiringTimeDao(), clock, Dispatchers.Default)
        val source = WidgetCalendarSource(calendar, times, collections.observer, home, auth, WidgetWorksSource(home, collections.observer, auth, clock), clock)
        val prefsFile = File(resources.directory("airing-prefs"), "p.preferences_pb")
        val data = PreferenceDataStoreFactory.create(scope = resources.scope) { prefsFile }
        val prefs = PreferencesRepository(data)
        prefs.setAiringNotifications(true)
        val ledger = SystemNoticeLedger(data)
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        val reminders = AiringReminders(context, prefs, ledger, source, auth, clock)
        if (daily) { prefs.setAiringReminderMode(io.github.touhougo.chogumi.core.model.AiringReminderMode.DAILY); prefs.setAiringDailyMinute(12 * 60) }
        if (blockPermission) shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        suspend fun runProduction() {
            if (!worker) { reminders.run(); return }
            val executor = java.util.concurrent.Executor { it.run() }
            val taskExecutor = androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor(executor)
            val factory = object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters): androidx.work.ListenableWorker? = null
            }
            fun <T> unused(type: Class<T>): T = requireNotNull(type.cast(java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> null }))
            val params = androidx.work.WorkerParameters(java.util.UUID.randomUUID(), androidx.work.Data.EMPTY, emptyList(), androidx.work.WorkerParameters.RuntimeExtras(), 0, 0, executor, Dispatchers.Default, taskExecutor, factory, unused(androidx.work.ProgressUpdater::class.java), unused(androidx.work.ForegroundUpdater::class.java))
            val w = io.github.touhougo.chogumi.feature.home.notice.AiringReminderWorker(context, params)
            mapOf("reminders" to reminders, "homeRepository" to home, "calendarRepository" to calendar, "airingTimeRepository" to times).forEach { (name, value) ->
                w.javaClass.getDeclaredField(name + "\$delegate").apply { isAccessible = true }.set(w, lazyOf(value))
            }
            kotlin.test.assertEquals(androidx.work.ListenableWorker.Result.success(), w.doWork())
        }
        val pending = resources.scope.async { runProduction() }
        withTimeout(10000) { arrived.await() }
        if (switchAccount) {
            tokens.completeSession(tokens.beginSession("accountB-token"), 8, "accountB")
            awaitUntil("当前账号是B") { (auth.sessionState.value as? io.github.touhougo.chogumi.core.model.SessionState.Authenticated)?.account?.userId == 8 }
        }
        release.complete(Unit)
        withTimeout(10000) { pending.await() }
        if (blockPermission) { shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true); reminders.run() }
        if (!switchAccount && !blockPermission) runProduction()
        val shown = SystemNotices.shownIds(context, if (daily) SystemNotices.DIGEST_TAG else SystemNotices.AIRING_TAG)
        val posted = ledger.airingPosted(8, java.time.LocalDate.of(2026, 11, 10))
        if (switchAccount) {
            assertTrue(101 !in posted, "B没有收藏这部作品，不能记成B已发")
            assertTrue(101 !in shown, "换号后不能发A的在追作品")
        } else {
            kotlin.test.assertEquals(setOf(if (daily) 0 else 101), shown, "真正未发出的提醒不能提前记已发")
        }
    }
}
