package io.github.touhougo.chogumi.shell

import android.app.Application
import android.content.Intent
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.FaultablePreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.testAuthRepository
import io.github.touhougo.chogumi.core.data.repository.NotificationRepository
import io.github.touhougo.chogumi.core.data.repository.testWebSessionRepository
import io.github.touhougo.chogumi.core.data.repository.noWebClient
import io.github.touhougo.chogumi.core.data.repository.testFriendsRepository
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.ui.common.SystemNotices
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExternalNoticeIntentTest {
    @Test fun `公开Activity拒绝伪造通知动作_合法动作仍按原会话标已读`() = runBlocking {
        val resources = TestResources()
        val main = TestMain()
        main.install()
        var vm: ShellViewModel? = null
        try {
            val requests = ConcurrentRequestRecorder()
            val tokens = testTokenStore(resources, 7)
            val client = resources.apiClient { request ->
                requests.record(request)
                if (request.url.encodedPath == "/p1/clear-notify") respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                else respond("{\"data\":[],\"total\":0}", headers = headersOf("Content-Type", "application/json"))
            }
            val prefs = FaultablePreferencesRepository(resources).repository
            val auth = testAuthRepository(resources, client, tokens, AuthConfig("fixture", "fixture", "chogumi://oauth/callback"), resources.scope)
            val notices = NotificationRepository(NextApi(client), tokens, testWebSessionRepository(tokens, resources.own(noWebClient())), prefs, resources.scope)
            auth.ensureInitialized()
            vm = withContext(main.dispatcher) { ShellViewModel(auth, prefs, notices, testFriendsRepository(resources, UserApi(client), tokens), testAppTime(1791763200000L)) }
            val activity = AppActivity()
            activity.javaClass.getDeclaredField("viewModel\$delegate").apply { isAccessible = true }.set(activity, lazyOf(requireNotNull(vm)))
            val intent = Intent().setClassName("io.github.touhougo.chogumi", AppActivity::class.java.name)
                .putExtra(SystemNotices.EXTRA_NOTICE_ID, 987654)
            withContext(main.dispatcher) {
                activity.javaClass.getDeclaredMethod("handleIntent", Intent::class.java).apply { isAccessible = true }.invoke(activity, intent)
            }
            val page = requireNotNull(vm)
            val before = page.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.children.toSet()
            val forged = Intent(intent).putExtra(SystemNotices.EXTRA_NOTICE_PROOF, "forged")
            val actions = withContext(main.dispatcher) {
                activity.javaClass.getDeclaredMethod("handleIntent", Intent::class.java).apply { isAccessible = true }.invoke(activity, forged)
                page.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.children.filter { it !in before }.toList()
            }
            actions.forEach { it.join() }
            assertTrue("公开入口的 extras 不能授权远程写入", requests.toList().none { it.url.encodedPath == "/p1/clear-notify" })
            val proof = notices.systemNoticeAction(tokens.session(), 987654)
            intent.putExtra(SystemNotices.EXTRA_NOTICE_PROOF, proof)
            withContext(main.dispatcher) {
                activity.javaClass.getDeclaredMethod("handleIntent", Intent::class.java).apply { isAccessible = true }.invoke(activity, intent)
            }
            awaitUntil("合法动作发出已读请求") { requests.toList().any { it.url.encodedPath == "/p1/clear-notify" } }
        } finally {
            main.uninstall(listOfNotNull(vm?.viewModelScope))
            resources.close()
        }
    }
}
