package io.github.touhougo.chogumi.feature.profile

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavEntry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.data.repository.BlockedUsersFeed
import io.github.touhougo.chogumi.core.data.repository.CommentRepository
import io.github.touhougo.chogumi.core.data.repository.FakeCommentRepository
import io.github.touhougo.chogumi.core.data.repository.IndexRepository
import io.github.touhougo.chogumi.core.data.repository.NoBlockedUsers
import io.github.touhougo.chogumi.core.data.repository.StillUserModeration
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.model.UserModeration
import io.github.touhougo.chogumi.core.network.api.IndexApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.navigation.AppNavDisplay
import io.github.touhougo.chogumi.core.ui.common.navigation.AppNavigator
import io.github.touhougo.chogumi.core.ui.common.navigation.PageSheetRegistry
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import io.github.touhougo.chogumi.core.ui.common.navigation.rememberAppNavigator
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
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
 * 目录删除的页面接线：驱动真实 [IndexDetailsScreen]、Nav3 entry 生命周期与 [io.github.touhougo.chogumi.core.ui.common.navigation.AppNavigator]。
 * 删除端是受控 MockEngine，不接触服务器；回包晚到时只能返回仍在前台的目录页，不能弹掉后来进入的子页。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-w448dp-h900dp")
class IndexDeleteNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val resources = TestResources()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val deleteGate = CompletableDeferred<Unit>()
    private val deleteRequests = AtomicInteger()
    private val backCalls = AtomicInteger()

    private lateinit var navigator: AppNavigator
    private lateinit var viewModel: IndexDetailsViewModel

    @Volatile
    private var deleteStatus = HttpStatusCode.OK

    private val repository by lazy {
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/p1/indexes/1" -> respond(INDEX_JSON, HttpStatusCode.OK, json)
                request.method == HttpMethod.Get && request.url.encodedPath == "/p1/indexes/1/related" -> respond(ENTRIES_JSON, HttpStatusCode.OK, json)
                request.method == HttpMethod.Get && request.url.encodedPath == "/p1/indexes/1/comments" -> respond("[]", HttpStatusCode.OK, json)
                request.method == HttpMethod.Delete && request.url.encodedPath == "/p1/indexes/1" -> {
                    deleteRequests.incrementAndGet()
                    deleteGate.await()
                    respond("{}", deleteStatus, json)
                }
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        val client = resources.apiClient(engine)
        IndexRepository(IndexApi(client), UserApi(client), testTokenStore(resources, userId = 1))
    }

    private fun setContent(): org.koin.core.KoinApplication {
        val application = koinApplication {
            modules(
                module {
                    single { repository }
                    single<CommentRepository> { FakeCommentRepository(resources.scope) }
                    single<UserModeration> { StillUserModeration() }
                    single<BlockedUsersFeed> { NoBlockedUsers() }
                    viewModel { params -> IndexDetailsViewModel(params.get(), get(), get(), get(), get()).also { viewModel = it } }
                },
            )
        }
        compose.setContent {
            KoinIsolatedContext(application) {
                ChogumiTheme {
                    navigator = rememberAppNavigator()
                    val sheets = remember { PageSheetRegistry() }
                    AppNavDisplay(
                        navigator = navigator,
                        entryProvider = { route ->
                            NavEntry(route) {
                                when (route) {
                                    Route.Home -> Box(Modifier.fillMaxSize().testTag(HOME))
                                    Route.IndexDetails(1) -> IndexDetailsScreen(
                                        indexId = 1,
                                        isLoggedIn = true,
                                        onLogin = {},
                                        onBack = {
                                            backCalls.incrementAndGet()
                                            navigator.goBack()
                                        },
                                        onEntryClick = { target -> navigator.navigate(Route.SubjectDetails(target.id)) }, // 本用例的目录只收条目
                                        onUserClick = {},
                                    )
                                    Route.SubjectDetails(2) -> Box(Modifier.fillMaxSize().testTag(CHILD))
                                    else -> error("本测试不该打开 $route")
                                }
                            }
                        },
                        sheets = sheets,
                    )
                }
            }
        }
        compose.waitForIdle()
        return application
    }

    private fun awaitUi(condition: () -> Boolean) {
        compose.waitUntil(WAIT_BUDGET_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    private fun openIndexAndStartDelete() {
        compose.runOnIdle { navigator.navigate(Route.IndexDetails(1)) }
        awaitUi {
            compose.onAllNodesWithText("测试条目").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(context.getString(R.string.page_more)).performClick()
        compose.onNodeWithText(context.getString(R.string.delete)).performClick()
        compose.onNodeWithText(context.getString(R.string.delete)).performClick()
        awaitUi { deleteRequests.get() == 1 && viewModel.uiState.value.isDeleting }
    }

    @Test
    fun `删除回包前进入子页_成功晚到不弹掉子页`() {
        val application = setContent()
        try {
            openIndexAndStartDelete()
            compose.onNodeWithText("测试条目").performClick()
            compose.onNodeWithTag(CHILD).assertExists()

            deleteGate.complete(Unit)
            awaitUi { !viewModel.uiState.value.isDeleting }

            compose.onNodeWithTag(CHILD).assertExists()
            assertEquals("目录页退到后台后不代替用户返回", 0, backCalls.get())
            assertEquals(1, deleteRequests.get())

            // 从子页回来：已经删掉的目录页不留在栈里，回到前台就离开
            compose.runOnIdle { navigator.goBack() }
            awaitUi { compose.onAllNodesWithTag(HOME).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("回到已删除的目录页时离开一次", 1, backCalls.get())
        } finally {
            application.close()
        }
    }

    @Test
    fun `目录页仍在前台_删除成功只返回一次`() {
        val application = setContent()
        try {
            openIndexAndStartDelete()
            compose.runOnIdle { viewModel.deleteIndex() }

            deleteGate.complete(Unit)
            awaitUi { !viewModel.uiState.value.isDeleting }

            compose.onNodeWithTag(HOME).assertExists()
            assertEquals("重复删除在途时不接受第二次", 1, backCalls.get())
            assertEquals(1, deleteRequests.get())
        } finally {
            application.close()
        }
    }

    @Test
    fun `删除失败留在目录页且不返回`() {
        deleteStatus = HttpStatusCode.InternalServerError
        val application = setContent()
        try {
            openIndexAndStartDelete()
            deleteGate.complete(Unit)
            awaitUi { !viewModel.uiState.value.isDeleting && viewModel.failures.current.value != null }

            compose.onNodeWithText("测试条目").assertExists()
            assertEquals(0, backCalls.get())
            assertEquals(1, deleteRequests.get())
        } finally {
            application.close()
        }
    }

    @Test
    fun `取消删除不发请求也不返回`() {
        val application = setContent()
        try {
            compose.runOnIdle { navigator.navigate(Route.IndexDetails(1)) }
            awaitUi { compose.onAllNodesWithText("测试条目").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(context.getString(R.string.page_more)).performClick()
            compose.onNodeWithText(context.getString(R.string.delete)).performClick()
            compose.onNodeWithText(context.getString(R.string.cancel)).performClick()

            compose.onNodeWithText("测试条目").assertExists()
            assertEquals(0, deleteRequests.get())
            assertEquals(0, backCalls.get())
        } finally {
            application.close()
        }
    }

    private companion object {
        const val HOME = "delete_test_home"
        const val CHILD = "delete_test_child"

        val INDEX_JSON = """
            {"id":1,"uid":1,"type":0,"title":"测试目录","desc":"","private":false,"total":1,"replies":0,"collects":0,
             "stats":{"subject":{"anime":1}},"award":0,"createdAt":1700000000,"updatedAt":1700000000}
        """.trimIndent()

        val ENTRIES_JSON = """
            {"data":[{"id":11,"cat":0,"rid":0,"type":2,"sid":2,"order":0,"comment":"","award":"","createdAt":1,
              "subject":{"id":2,"name":"test","nameCN":"测试条目","type":2,"info":"","metaTags":[],
              "rating":{"rank":0,"count":[],"score":0,"total":0},"locked":false,"nsfw":false}}],"total":1}
        """.trimIndent()
    }
}
