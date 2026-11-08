package io.github.touhougo.chogumi.feature.messages

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.data.repository.NotificationRepository
import io.github.touhougo.chogumi.core.data.repository.PmRepository
import io.github.touhougo.chogumi.core.data.repository.testAccountMe
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.testDraftRepository
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.testUserRepository
import io.github.touhougo.chogumi.core.data.repository.testWebSessionRepository
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 会话页的回复面板与别处的编辑器一样存草稿：在面板里写的字由页面交给编辑器存下，关掉面板再打开，没发出去的还在。
 * 驱动的是真正的页面（[PmConversationScreen]）与它的 ViewModel，页面漏接输入就存不下。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w400dp-h900dp")
class PmConversationDraftTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val resources = TestResources()

    /**
     * 等 ViewModel 走到 [condition]（只等一旦成立就一直成立的）。仓库在后台线程做完，续体投回主线程的消息队列；
     * 这里的 waitUntil 只推进界面的时钟、不跑那个队列，所以每次看之前先把队列里到期的消息跑完。
     */
    private fun awaitViewModel(condition: () -> Boolean) = compose.waitUntil(WAIT_BUDGET_MS) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    @Test
    fun `回复面板里写的字_关掉再打开还在`() {
        val site = PmSite()
        val tokenStore = testTokenStore(resources, userId = 400200)
        val webSessions = testWebSessionRepository(tokenStore, resources.own(site.client))
        val preferences = PreferencesRepository(
            PreferenceDataStoreFactory.create(scope = resources.scope) { File(resources.directory("pm-prefs"), "prefs.preferences_pb") },
        )
        val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val notifications = NotificationRepository(
            NextApi(resources.apiClient { respond("""{"data":[],"total":0}""", headers = json) }),
            tokenStore,
            webSessions,
            preferences,
            resources.scope,
        )
        val repository = PmRepository(tokenStore, webSessions, notifications, resources.scope, testAccountMe(resources, tokenStore), Dispatchers.Unconfined)
        val users = testUserRepository(resources, UserApi(resources.apiClient { respond("", HttpStatusCode.NotFound) }), tokenStore, CachedUsers)
        val drafts = testDraftRepository(resources.scope, tokens = tokenStore)
        runBlocking { webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e")) }
        var viewModel: PmConversationViewModel? = null
        val application = koinApplication {
            modules(module { viewModel { params -> PmConversationViewModel(params.get(), repository, users, drafts, AppClock.SYSTEM).also { viewModel = it } } })
        }
        try {
            compose.setContent {
                KoinIsolatedContext(application) {
                    ChogumiTheme {
                        PageSheetTestHost {
                            PmConversationScreen(PmChatKey.Peer("anonymous_test"), isLoggedIn = true, onLogin = {}, onBack = {}, onUserClick = {})
                        }
                    }
                }
            }
            // 写给一个人、没有会话：读到就弹出写第一封的面板
            awaitViewModel { viewModel?.uiState?.value?.composer?.target != null }
            compose.onNodeWithText("说点什么…").performTextInput("没写完的信")
            val vm = requireNotNull(viewModel)

            compose.runOnIdle { vm.composer.close() }
            compose.runOnIdle { vm.reply() }
            awaitViewModel { vm.uiState.value.composer.target != null }

            assertEquals("没写完的信", vm.uiState.value.composer.text)
        } finally {
            application.close()
        }
    }
}
