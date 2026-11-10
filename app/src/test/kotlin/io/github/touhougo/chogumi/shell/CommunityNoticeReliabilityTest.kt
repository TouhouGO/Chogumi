package io.github.touhougo.chogumi.shell

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.NotificationRepository
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.SystemNoticeLedger
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.testAuthRepository
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.testWebClient
import io.github.touhougo.chogumi.core.data.repository.testWebSessionRepository
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.github.touhougo.chogumi.core.ui.common.SystemNotices
import io.github.touhougo.chogumi.feature.community.notice.CommunityNotices
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CommunityNoticeReliabilityTest {
    @get:org.junit.Rule val resources = TestResources()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val tokens by lazy { testTokenStore(resources, 7) }
    private val data by lazy { PreferenceDataStoreFactory.create(scope = resources.scope) { File(resources.directory("notice-ledger"), "p.preferences_pb") } }
    private val prefs by lazy { PreferencesRepository(data) }
    private val ledger by lazy { SystemNoticeLedger(data) }
    private val foreground = MutableStateFlow(false)
    private val requests = ConcurrentRequestRecorder()
    private val notifyId = AtomicInteger(100)
    private val messages = AtomicInteger(0)
    private var messageGate: (suspend () -> Unit)? = null
    private var truncated = false
    private var failing = false
    private val client by lazy { resources.apiClient { request ->
        requests.record(request)
        if (failing) return@apiClient respond("", HttpStatusCode.InternalServerError)
        if (truncated) {
            val template = """{"id":#ID#,"type":2,"sender":{"id":9,"username":"fixture_sender","nickname":"示例发送者","avatar":{"large":"","medium":"","small":""},"group":10,"sign":"","joinedAt":0,"isFriend":false},"title":"本地话题","mainID":10,"relatedID":20,"createdAt":1780000000,"unread":true}"""
            val rows = (102..141).joinToString(",") { template.replace("#ID#", it.toString()) }
            return@apiClient respond("""{"data":[$rows],"total":41}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        respond("""{"data":[{"id":${notifyId.get()},"type":2,"sender":{"id":9,"username":"fixture_sender","nickname":"示例发送者","avatar":{"large":"","medium":"","small":""},"group":10,"sign":"","joinedAt":0,"isFriend":false},"title":"本地夹具话题","mainID":10,"relatedID":20,"createdAt":1780000000,"unread":true}],"total":1}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
    } }
    private val web by lazy { resources.own(testWebClient { messageGate?.invoke(); respond("""{"pm_count":${messages.get()},"pm_ignore_url":"/pm/ignore_unread.chii?gh=TEST"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }) }
    private val sessions by lazy { testWebSessionRepository(tokens, web) }
    private val auth by lazy { testAuthRepository(resources, client, tokens, AuthConfig("test", "secret", "chogumi://oauth/callback"), resources.scope, webClient = web) }
    private val repository by lazy { NotificationRepository(NextApi(client), tokens, sessions, prefs, resources.scope) }
    private val notices by lazy { CommunityNotices(context, repository, prefs, ledger, auth, foreground) }

    @Before fun setup() = runBlocking {
        shadowOf(context.packageManager).apply {
            val component = ComponentName(context.packageName, "FixtureLaunchActivity")
            addActivityIfNotPresent(component)
            addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
        shadowOf(manager).setNotificationsEnabled(true)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        prefs.setCommunityNotifications(true)
        sessions.save(tokens.session(), WebSession("chii_auth=local-fixture"))
        auth.ensureInitialized()
    }

    @Test fun `真实Worker调用生产仓库并发布新增`() = runBlocking {
        notices.check()
        notifyId.set(101)
        val executor = java.util.concurrent.Executor { it.run() }
        val taskExecutor = androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor(executor)
        val factory = object : androidx.work.WorkerFactory() {
            override fun createWorker(appContext: android.content.Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters): androidx.work.ListenableWorker? = null
        }
        fun <T> unused(type: Class<T>): T = requireNotNull(type.cast(java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> null }))
        val params = androidx.work.WorkerParameters(
            java.util.UUID.randomUUID(), androidx.work.Data.EMPTY, emptyList(), androidx.work.WorkerParameters.RuntimeExtras(), 0, 0,
            executor, kotlinx.coroutines.Dispatchers.Default, taskExecutor, factory,
            unused(androidx.work.ProgressUpdater::class.java), unused(androidx.work.ForegroundUpdater::class.java),
        )
        val worker = io.github.touhougo.chogumi.feature.community.notice.CommunityNoticeWorker(context, params)
        worker.javaClass.getDeclaredField("notices\$delegate").apply { isAccessible = true }.set(worker, lazyOf(notices))
        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
        assertEquals(setOf(101), SystemNotices.shownIds(context, SystemNotices.NOTICE_TAG))
    }

    @Test fun `类别被关闭的提醒保留待发_短信仍发_类别恢复后补发`() = runBlocking {
        notices.check()
        SystemNotices.ensureChannels(context)
        val reply = SystemNotices.channelId(io.github.touhougo.chogumi.core.model.NoticeChannel.REPLY)
        manager.deleteNotificationChannel(reply)
        manager.createNotificationChannel(android.app.NotificationChannel(reply, "fixture", NotificationManager.IMPORTANCE_NONE))
        notifyId.set(101); messages.set(2)
        notices.check()
        assertTrue(SystemNotices.shownIds(context, SystemNotices.NOTICE_TAG).isEmpty())
        assertEquals(setOf(0), SystemNotices.shownIds(context, SystemNotices.MESSAGE_TAG))
        assertEquals(setOf(101), ledger.deferredNotices(7))
        // 只改宿主系统持有的channel模拟用户在系统设置开启，不由App重建来改变用户选择。
        manager.getNotificationChannel(reply).importance = NotificationManager.IMPORTANCE_DEFAULT
        notices.check()
        assertEquals(setOf(101), SystemNotices.shownIds(context, SystemNotices.NOTICE_TAG))
        assertTrue(ledger.deferredNotices(7).isEmpty())
    }

    @Test fun `未读列表被截断时保留未确认已读的旧通知`() = runBlocking {
        notices.check()
        notifyId.set(101)
        notices.check()
        truncated = true
        notices.check()
        assertTrue(101 in SystemNotices.shownIds(context, SystemNotices.NOTICE_TAG))
    }

    @Test fun `网络失败不推进基线_下一次成功补发`() = runBlocking {
        notices.check()
        notifyId.set(101)
        failing = true
        notices.check()
        assertEquals(100, ledger.noticeBaseline(7))
        failing = false
        notices.check()
        assertEquals(setOf(101), SystemNotices.shownIds(context, SystemNotices.NOTICE_TAG))
    }

    @Test fun `真实检查首轮只建基线_下一轮新增发布且不消耗未读_重复不重发`() = runBlocking {
        notices.check()
        assertTrue(SystemNotices.shownIds(context, SystemNotices.NOTICE_TAG).isEmpty())
        assertEquals(100, ledger.noticeBaseline(7))
        notifyId.set(101); messages.set(2)
        notices.check()
        val shown = SystemNotices.shownIds(context, SystemNotices.NOTICE_TAG)
        assertEquals(setOf(101), shown)
        assertEquals(setOf(0), SystemNotices.shownIds(context, SystemNotices.MESSAGE_TAG))
        assertTrue(requests.toList().none { it.url.encodedPath.contains("clear-notify") })
        notices.check()
        assertEquals(shown, SystemNotices.shownIds(context, SystemNotices.NOTICE_TAG))
    }

    @Test fun `通知权限关闭时不得提前记已发_恢复权限后还应发新增`() = runBlocking {
        notices.check()
        shadowOf(manager).setNotificationsEnabled(false)
        notifyId.set(101); messages.set(2)
        notices.check()
        shadowOf(manager).setNotificationsEnabled(true)
        notices.check()
        assertEquals(setOf(101), SystemNotices.shownIds(context, SystemNotices.NOTICE_TAG))
    }

    @Test fun `短信挂起期间切号_真实检查不应发布旧账号通知`() = runBlocking {
        notices.check()
        notifyId.set(101)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        messageGate = { entered.complete(Unit); release.await() }
        val pending = resources.scope.async { notices.check() }
        withTimeout(10000) { entered.await() }
        tokens.completeSession(tokens.beginSession("other"), 8, "other")
        release.complete(Unit)
        withTimeout(10000) { pending.await() }
        assertTrue(SystemNotices.shownIds(context, SystemNotices.NOTICE_TAG).isEmpty())
    }
}
