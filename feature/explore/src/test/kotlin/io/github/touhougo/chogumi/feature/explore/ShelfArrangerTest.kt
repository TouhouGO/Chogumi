package io.github.touhougo.chogumi.feature.explore

import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.data.repository.ExploreLayoutRepository
import io.github.touhougo.chogumi.core.data.repository.FakeExploreShelfDao
import io.github.touhougo.chogumi.core.data.repository.FakeTagCatalogDao
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testNsfwFilter
import io.github.touhougo.chogumi.core.data.repository.testTagCatalogRepository
import io.github.touhougo.chogumi.core.model.FixedWorkShelf
import io.github.touhougo.chogumi.core.model.ShelfEntry
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TileShelfEntry
import io.github.touhougo.chogumi.core.model.TileShelfSource
import io.github.touhougo.chogumi.core.model.WordPick
import io.github.touhougo.chogumi.core.model.WorkShelfEntry
import io.github.touhougo.chogumi.core.model.WorkShelfStyle
import io.github.touhougo.chogumi.core.model.defaultShelfLayout
import io.github.touhougo.chogumi.core.model.isPinnable
import io.github.touhougo.chogumi.core.model.shelfChoicesFor
import io.github.touhougo.chogumi.core.model.tileCandidates
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.ui.arrange.ArrangeSheet
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShelfArranger]：整理列表、编辑面板、「添加货架」的动作都经 core:model 的纯函数写进布局。
 *
 * 等待都落在单调信号上：布局表写过的次数、目录写进的行。
 */
class ShelfArrangerTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()

    private val anime = SubjectType.ANIME
    private val scopes = mutableListOf<CoroutineScope>()

    private val dao = FakeExploreShelfDao()
    private val layouts = ExploreLayoutRepository(dao)

    @After
    fun tearDown() = runBlocking {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
    }

    private data class Harness(val arranger: ShelfArranger, val state: MutableStateFlow<ShelfArrangeState>, val scope: CoroutineScope, val failures: FailureNotices, val calls: AtomicInteger, val catalog: FakeTagCatalogDao)

    private fun harness(succeedAfterFirst: Boolean = false): Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + CoroutineExceptionHandler { _, error -> throw error }).also { scopes += it }
        val calls = AtomicInteger()
        val catalog = FakeTagCatalogDao()
        val client = resources.apiClient {
            if (calls.incrementAndGet() > 1 && succeedAfterFirst) {
                respond("""{"data":[{"name":"动画标签","count":10}],"total":1}""", HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()))
            } else respond("{}", HttpStatusCode.NotFound)
        }
        val state = MutableStateFlow(ShelfArrangeState())
        val failures = FailureNotices()
        val arranger = ShelfArranger(
            scope = scope,
            state = state,
            read = { state.value },
            write = { change -> state.update(change) },
            failures = failures,
            layouts = layouts,
            tagCatalog = testTagCatalogRepository(NextApi(client), catalog, testNsfwFilter(resources)),
        )
        return Harness(arranger, state, scope, failures, calls, catalog)
    }

    @Test
    fun `首次目录失败提示重试且保留面板输入`() = runBlocking {
        val h = harness(succeedAfterFirst = true)
        val ranking = defaultShelfLayout(anime).first()
        h.arranger.openEditor(anime, ranking)
        h.arranger.openWords()
        h.arranger.wordActions.onPanelQueryChange("动画")
        h.arranger.wordActions.onDraftWordPick("草稿", WordPick.ADD)
        val draft = (h.state.value.editor as ShelfEditor.Works).form.filterDraft
        awaitUntil("目录失败提示") { h.failures.current.value != null }
        val notice = h.failures.current.value!!
        assertTrue(notice.retry != null)
        notice.retry!!.invoke()
        awaitUntil("目录重试写入") { h.catalog.rowsOf(anime.value).isNotEmpty() }
        assertEquals(2, h.calls.get())
        assertEquals(listOf("动画标签"), h.catalog.search(anime.value, "动画", 10).map { it.name })
        assertEquals("动画", (h.state.value.editor as ShelfEditor.Works).panelQuery)
        assertEquals(draft, (h.state.value.editor as ShelfEditor.Works).form.filterDraft)
    }

    @Test
    fun `旧筛选面板提示重试不会请求新面板`() = runBlocking {
        val h = harness()
        h.arranger.openEditor(anime, defaultShelfLayout(anime).first())
        h.arranger.openWords()
        awaitUntil("首次失败") { h.failures.current.value != null }
        val old = h.failures.current.value!!
        h.arranger.dismissWords()
        h.arranger.openWords()
        h.arranger.wordActions.onPanelQueryChange("新面板")
        awaitUntil("新面板失败") { h.calls.get() == 2 && h.failures.current.value !== old }
        val jobs = withContext(h.scope.coroutineContext.minusKey(kotlinx.coroutines.Job)) {
            val before = h.scope.coroutineContext.job.children.toSet()
            old.retry!!.invoke()
            h.scope.coroutineContext.job.children.filter { it !in before }.toList()
        }
        jobs.joinAll()
        assertEquals(2, h.calls.get())
        assertEquals("新面板", (h.state.value.editor as ShelfEditor.Works).panelQuery)
    }

    /** 布局表整份写过 [count] 次之后的布局（写的次数只增不减）。 */
    private suspend fun layoutAfterWrites(count: Int): List<ShelfEntry> {
        awaitUntil("布局写过 $count 次") { dao.writes >= count }
        return layouts.observe(anime).first()
    }

    @Test
    fun `拖动排序与左滑移除写进布局`() = runBlocking {
        val (arranger) = harness()
        val ids = defaultShelfLayout(anime).map { it.id }

        arranger.move(anime, listOf(ids[2], ids[0], ids[1]))
        assertEquals(listOf(ids[2], ids[0], ids[1]) + ids.drop(3), layoutAfterWrites(1).map { it.id })

        arranger.remove(anime, ids[0])
        assertTrue(layoutAfterWrites(2).single { it.id == ids[0] }.hidden)
    }

    @Test
    fun `固定货架的编辑面板_只能换样式_保存写进布局后面板收起`() = runBlocking {
        val (arranger, state) = harness()
        val ranking = defaultShelfLayout(anime).single { it.id == "fixed:${FixedWorkShelf.RANKING.name}" }

        arranger.openEditor(anime, ranking)
        val editor = state.value.editor as ShelfEditor.Works
        assertTrue(editor.isFixed)
        assertTrue(editor.canSave)

        arranger.setStyle(WorkShelfStyle.SPINE)
        arranger.save(name = "")
        assertEquals(WorkShelfStyle.SPINE, (layoutAfterWrites(1).single { it.id == ranking.id } as WorkShelfEntry).style)
        assertEquals(ArrangeSheet.EDITOR, state.value.closing)

        arranger.editorClosed()
        assertNull(state.value.editor)
        assertNull(state.value.closing)
    }

    @Test
    fun `编辑面板上点掉唯一的条件_不能保存`() = runBlocking {
        val (arranger, state) = harness()
        arranger.openEditor(anime, defaultShelfLayout(anime).single { it.id == "preset:奇幻" })
        val condition = (state.value.editor as ShelfEditor.Works).form.applied.activeConditions().single()

        arranger.removeCondition(condition)

        val editor = state.value.editor as ShelfEditor.Works
        assertFalse(editor.form.applied.isPinnable())
        assertFalse(editor.canSave)
        assertNull("点掉条件不留下筛选面板的草稿", editor.form.filterDraft)
    }

    @Test
    fun `新建方块货架_保存后放在最前`() = runBlocking {
        val (arranger) = harness()
        val tile = tileCandidates(anime, TileShelfSource.Custom("")).first().label.key

        arranger.newTilesShelf(anime)
        arranger.addTile(tile)
        arranger.save(name = "我的")

        val created = layoutAfterWrites(1).first() as TileShelfEntry
        assertEquals(TileShelfSource.Custom("我的"), created.source)
        assertEquals(listOf(tile), created.tileKeys)
    }

    @Test
    fun `添加货架里点一行_在页面上的移除_不在的放上来到最前`() = runBlocking {
        val (arranger) = harness()
        val fantasy = { layout: List<ShelfEntry> -> shelfChoicesFor(anime, layout).presets.flatMap { it.second }.single { it.entry.id == "preset:奇幻" } }

        arranger.toggle(anime, fantasy(defaultShelfLayout(anime)))
        val hidden = layoutAfterWrites(1)
        assertTrue(hidden.single { it.id == "preset:奇幻" }.hidden)

        arranger.toggle(anime, fantasy(hidden))
        val back = layoutAfterWrites(2).first()
        assertEquals("preset:奇幻", back.id)
        assertFalse(back.hidden)
    }
}
