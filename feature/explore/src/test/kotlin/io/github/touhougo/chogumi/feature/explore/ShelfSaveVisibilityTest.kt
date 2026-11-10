package io.github.touhougo.chogumi.feature.explore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.data.db.explore.ExploreShelfDao
import io.github.touhougo.chogumi.core.data.db.explore.ExploreShelfEntity
import io.github.touhougo.chogumi.core.data.repository.ExploreLayoutRepository
import io.github.touhougo.chogumi.core.data.repository.FakeExploreShelfDao
import io.github.touhougo.chogumi.core.data.repository.FakeTagCatalogDao
import io.github.touhougo.chogumi.core.data.repository.NsfwFilter
import io.github.touhougo.chogumi.core.data.repository.NsfwPreferenceRepository
import io.github.touhougo.chogumi.core.data.repository.TagCatalogRepository
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.data.repository.testWebClient
import io.github.touhougo.chogumi.core.data.repository.testWebSessionRepository
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TileShelfSource
import io.github.touhougo.chogumi.core.model.WorkShelfStyle
import io.github.touhougo.chogumi.core.model.defaultShelfLayout
import io.github.touhougo.chogumi.core.model.tileCandidates
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.PrivacyApi
import io.github.touhougo.chogumi.core.network.testApiClient
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate

/** 编辑保存失败留在原面板，保存期间不允许再次提交或编辑名字；从「添加模块」新建、保存成功回到整理页本身。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class ShelfSaveVisibilityTest {
    @get:Rule
    val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clients = mutableListOf<HttpClient>()
    private val engines = mutableListOf<HttpClientEngine>()
    private val directories = mutableListOf<File>()
    private val gate = CompletableDeferred<Unit>()
    private val entered = CompletableDeferred<Unit>()
    private val failures = FailureNotices()
    private var ui by mutableStateOf(ExploreUiState(today = LocalDate.of(2026, 10, 24)))

    @After
    fun tearDown() {
        cancelAndAwait(listOf(scope))
        clients.forEach { it.close() }
        engines.forEach { it.close() }
        cancelAndAwait(clients.map { CoroutineScope(it.coroutineContext) } + engines.map { CoroutineScope(it.coroutineContext) })
        directories.forEach { it.deleteRecursively() }
    }

    /** 页面真用的 [ShelfArranger]，状态写进 [ui]（[ArrangeSheets] 读的就是它）。 */
    private fun arranger(dao: ExploreShelfDao): ShelfArranger {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "shelf-save-${System.nanoTime()}").apply { mkdirs(); directories += this }
        fun store(name: String) = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "$name.preferences_pb") }
        val tokenStore = TokenStore(store("tokens"))
        val client = testApiClient { error("保存不请求接口") }.also { clients += it; engines += it.engine }
        val web = testWebClient { error("保存不请求网页") }.also { clients += it; engines += it.engine }
        val nsfw = NsfwPreferenceRepository(store("nsfw"), PrivacyApi(client), tokenStore, testWebSessionRepository(tokenStore, web))
        val state = MutableStateFlow(ShelfArrangeState())
        return ShelfArranger(
            scope, state, { ui.arrange }, { change -> ui = ui.copy(arrange = change(ui.arrange)); state.value = ui.arrange }, failures,
            ExploreLayoutRepository(dao), TagCatalogRepository(NextApi(client), FakeTagCatalogDao(), NsfwFilter(nsfw), AppClock { 0L }),
        )
    }

    @Test
    fun 从添加模块新建保存成功后两层面板一起收起_回到整理页() {
        val dao = FakeExploreShelfDao()
        val arranger = arranger(dao)
        arranger.openAdding()
        compose.setContent { ChogumiTheme { PageSheetTestHost { ArrangeSheets(ui, arranger, LocalDate.of(2026, 10, 24).year) } } }
        compose.onNodeWithTag("add_shelf_list").assertIsDisplayed()
        compose.runOnIdle {
            arranger.newTilesShelf(SubjectType.ANIME)
            arranger.addTile(tileCandidates(SubjectType.ANIME, TileShelfSource.Custom("")).first().label.key)
        }
        compose.onNodeWithTag("shelf_editor_save").performClick()
        compose.waitUntil { !ui.arrange.adding && ui.arrange.editor == null }
        compose.onNodeWithTag("add_shelf_list").assertDoesNotExist()
        compose.onNodeWithTag("shelf_editor_save").assertDoesNotExist()
        assertTrue("新模块写进了布局", dao.writes >= 1)
    }

    @Test
    fun 保存挂起保留面板_失败后保留输入并恢复重试() {
        val dao = object : ExploreShelfDao by FakeExploreShelfDao() {
            override suspend fun replaceType(subjectType: Int, rows: List<ExploreShelfEntity>) {
                entered.complete(Unit)
                gate.await()
                throw IOException("保存失败")
            }
        }
        val arranger = arranger(dao)
        arranger.openEditor(SubjectType.ANIME, defaultShelfLayout(SubjectType.ANIME).single { it.id == "preset:奇幻" })
        arranger.setName("保留的输入")
        arranger.setStyle(WorkShelfStyle.SPINE)
        compose.setContent { ChogumiTheme { PageSheetTestHost { ArrangeSheets(ui, arranger, LocalDate.of(2026, 10, 24).year) } } }
        compose.onNodeWithTag("shelf_editor_save").performClick()
        compose.waitUntil { entered.isCompleted }
        compose.onNodeWithTag("shelf_editor_save").assertIsDisplayed()
        compose.onNodeWithTag("shelf_editor_name").assertIsNotEnabled()
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitUntil { failures.current.value != null }
        compose.onNodeWithTag("shelf_editor_save").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle {
            assertEquals("保留的输入", (ui.arrange.editor as ShelfEditor.Works).name)
            assertFalse(ui.arrange.saving)
            assertNotNull(ui.arrange.editor)
            assertTrue(ui.arrange.closing.isEmpty())
        }
    }
}
