package io.github.touhougo.chogumi.feature.monodetails

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.repository.MonoRepository
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.CastRole
import io.github.touhougo.chogumi.core.model.MonoCharacterRefBo
import io.github.touhougo.chogumi.core.model.MonoRelationBo
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.MonoWorkBo
import io.github.touhougo.chogumi.core.model.MonoWorkFacet
import io.github.touhougo.chogumi.core.model.MonoWorkFilter
import io.github.touhougo.chogumi.core.model.MonoWorkOption
import io.github.touhougo.chogumi.core.model.MonoWorkSort
import io.github.touhougo.chogumi.core.model.PersonCastFilter
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.personCastTypeOptions
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class MonoListViewModelTest {

    private val main = TestMain()
    private val viewModels = mutableListOf<MonoRelationsListViewModel>()
    private val worksViewModels = mutableListOf<MonoWorksViewModel>()
    private val characterViewModels = mutableListOf<PersonCharactersViewModel>()

    @BeforeTest
    fun setUp() {
        main.install()
    }

    @AfterTest
    fun tearDown() {
        main.uninstall(viewModels.map { it.viewModelScope } + worksViewModels.map { it.viewModelScope } + characterViewModels.map { it.viewModelScope })
    }

    private fun relation(id: Int, relation: String) =
        MonoRelationBo(id = id, name = "p$id", nameCn = "", relation = relation, image = null)

    /** 只实现人物关联分页；其余接口本测试不触达。 */
    private class FakeRelationsRepository(
        private val pages: Map<Int, () -> PagedResult<MonoRelationBo>>,
    ) : FakeMonoRepository() {
        override suspend fun getRelations(type: MonoType, id: Int, limit: Int, offset: Int) = pages.getValue(offset)()
    }

    private suspend fun newViewModel(repository: MonoRepository) = withContext(main.dispatcher) {
        MonoRelationsListViewModel(MonoType.PERSON, 1, repository).also { viewModels += it }
    }

    @Test
    fun `关联人物触底续取按数据层游标追加，跨页的同一人合成一张卡`() = runBlocking {
        val vm = newViewModel(
            FakeRelationsRepository(
                mapOf(
                    0 to { PagedResult(listOf(relation(1, "父亲"), relation(2, "同事")), total = 4, nextOffset = 2) },
                    2 to { PagedResult(listOf(relation(1, "师父"), relation(3, "朋友")), total = 4, nextOffset = null) },
                ),
            ),
        )
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { !it.isLoading && it.nextOffset == 2 } }

        withContext(main.dispatcher) { vm.loadMore() }
        val state = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.nextOffset == null && !it.isLoadingMore } }

        assertEquals(listOf(1, 2, 3), state.items.map { it.id })
        assertEquals("父亲 / 师父", state.items.first().relation)
    }

    @Test
    fun `续取失败只在页脚报错并保留已加载内容，再次续取可恢复`() = runBlocking {
        var failNext = true
        val vm = newViewModel(
            FakeRelationsRepository(
                mapOf(
                    0 to { PagedResult(listOf(relation(1, "父亲")), total = 2, nextOffset = 1) },
                    1 to {
                        if (failNext) {
                            failNext = false
                            throw IllegalStateException("断网")
                        }
                        PagedResult(listOf(relation(2, "朋友")), total = 2, nextOffset = null)
                    },
                ),
            ),
        )
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { !it.isLoading && it.nextOffset == 1 } }

        withContext(main.dispatcher) { vm.loadMore() }
        val failed = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.loadMoreError != null && !it.isLoadingMore } }
        assertEquals(listOf(1), failed.items.map { it.id })
        assertEquals(1, failed.nextOffset)
        assertNull(failed.error)

        withContext(main.dispatcher) { vm.loadMore() }
        val recovered = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.nextOffset == null && !it.isLoadingMore } }
        assertEquals(listOf(1, 2), recovered.items.map { it.id })
        assertNull(recovered.loadMoreError)
    }

    private fun work(id: Int, position: String, type: SubjectType = SubjectType.ANIME, votes: Int = 100, score: Double = 7.0) =
        MonoWorkBo(
            id = id, name = "w$id", nameCn = "", image = null, subjectType = type,
            ratingScore = score, ratingTotal = votes, positions = listOf(position),
        )

    private class FakeWorksRepository(private val handler: suspend () -> List<MonoWorkBo>) : FakeMonoRepository() {
        var calls = 0
        override suspend fun getAllWorks(type: MonoType, id: Int, refresh: Boolean): List<MonoWorkBo> {
            calls++
            return handler()
        }
    }

    private suspend fun newWorksViewModel(repository: MonoRepository) = withContext(main.dispatcher) {
        MonoWorksViewModel(monoType = MonoType.PERSON, id = 1, monoRepository = repository).also { worksViewModels += it }
    }

    @Test
    fun `全部作品子页取一次全量，筛选与排序只改状态不再请求`() = runBlocking {
        val repo = FakeWorksRepository { listOf(work(1, "主演"), work(2, "作词", SubjectType.MUSIC)) }
        val vm = newWorksViewModel(repo)
        val loaded = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.works is Load.Ready } }
        assertEquals(listOf(1, 2), (loaded.works as Load.Ready).value.map { it.id })
        assertEquals(MonoWorkFilter(), loaded.filter)
        assertNull(loaded.requestedSort, "没选排序时用默认排序")

        withContext(main.dispatcher) {
            vm.selectFilter(MonoWorkFilter(facet = MonoWorkFacet.Position("作词")))
            vm.selectSort(MonoWorkSort.TIME)
        }
        val state = vm.uiState.value
        assertEquals(MonoWorkFilter(facet = MonoWorkFacet.Position("作词")), state.filter)
        assertEquals(MonoWorkSort.TIME, state.requestedSort)
        assertEquals(1, repo.calls, "筛选与排序都在本地做")
    }

    @Test
    fun `全部作品子页加载失败可重试`() = runBlocking {
        var fail = true
        val repo = FakeWorksRepository {
            if (fail) {
                fail = false
                throw IllegalStateException("断网")
            }
            listOf(work(1, "主演"))
        }
        val vm = newWorksViewModel(repo)
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.works is Load.Failed } }

        withContext(main.dispatcher) { vm.retry() }
        val recovered = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.works is Load.Ready } }
        assertEquals(listOf(1), (recovered.works as Load.Ready).value.map { it.id })
        assertEquals(2, repo.calls)
    }

    @Test
    fun `人物角色分页使用仓库游标且保留同角色不同出演记录`() = runBlocking {
        val requests = mutableListOf<Int>()
        val character = MonoCharacterRefBo(1, "角色", "", null, "作品甲")
        val repository = object : FakeMonoRepository() {
            override suspend fun getPersonCharacters(id: Int, limit: Int, offset: Int, filter: PersonCastFilter): PagedResult<MonoCharacterRefBo> {
                // 筛选项计数探针（limit = 1）不计入分页请求。
                if (limit == 1) return PagedResult(emptyList(), total = 0, nextOffset = null)
                requests += offset
                return if (offset == 0) {
                    PagedResult((1..30).map { character.copy(id = it) }, total = 31, nextOffset = 30)
                } else {
                    PagedResult(listOf(character.copy(subjectName = "作品乙")), total = 31, nextOffset = null)
                }
            }
        }
        val vm = withContext(main.dispatcher) {
            PersonCharactersViewModel(1, repository).also { characterViewModels += it }
        }
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { !it.characters.isLoading && it.characters.nextOffset == 30 } }
        withContext(main.dispatcher) { vm.loadMore() }
        val completed = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { !it.characters.isLoadingMore && it.characters.nextOffset == null } }
        assertEquals(listOf(0, 30), requests)
        assertEquals(List(30) { "作品甲" } + "作品乙", completed.characters.items.map { it.subjectName })
        assertEquals(1, completed.characters.items.first().id)
        assertEquals(1, completed.characters.items.last().id)
        assertEquals(31, completed.characters.total)
    }

    @Test
    fun `人物角色首屏失败重试后空态无错误`() = runBlocking {
        var calls = 0
        val repository = object : FakeMonoRepository() {
            override suspend fun getPersonCharacters(id: Int, limit: Int, offset: Int, filter: PersonCastFilter): PagedResult<MonoCharacterRefBo> {
                // 筛选项计数探针（limit = 1）不计入分页请求。
                if (limit == 1) return PagedResult(emptyList(), total = 0, nextOffset = null)
                calls++
                if (calls == 1) throw IllegalStateException("断网")
                return PagedResult(emptyList(), total = 0, nextOffset = null)
            }
        }
        val vm = withContext(main.dispatcher) {
            PersonCharactersViewModel(1, repository).also { characterViewModels += it }
        }
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { !it.characters.isLoading && it.characters.error != null } }
        withContext(main.dispatcher) { vm.retry() }
        val empty = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { !it.characters.isLoading && it.characters.error == null } }
        assertTrue(empty.characters.items.isEmpty())
        assertNull(empty.characters.nextOffset)
        assertEquals(2, calls)
    }

    @Test
    fun `人物角色首屏被过滤为空仍保留游标并续取后续可见角色`() = runBlocking {
        val requests = mutableListOf<Int>()
        val character = MonoCharacterRefBo(31, "角色", "", null, "作品")
        val repository = object : FakeMonoRepository() {
            override suspend fun getPersonCharacters(id: Int, limit: Int, offset: Int, filter: PersonCastFilter): PagedResult<MonoCharacterRefBo> {
                // 筛选项计数探针（limit = 1）不计入分页请求。
                if (limit == 1) return PagedResult(emptyList(), total = 0, nextOffset = null)
                requests += offset
                return if (offset == 0) {
                    // 服务端首批 30 条全被 NSFW 过滤，total 仍是过滤前的 31。
                    PagedResult(emptyList(), total = 31, nextOffset = 30)
                } else {
                    PagedResult(listOf(character), total = 31, nextOffset = null)
                }
            }
        }
        val vm = withContext(main.dispatcher) {
            PersonCharactersViewModel(1, repository).also { characterViewModels += it }
        }
        val emptyPage = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { !it.characters.isLoading && it.characters.nextOffset == 30 } }
        assertTrue(emptyPage.characters.items.isEmpty())
        withContext(main.dispatcher) { vm.loadMore() }
        val completed = withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { !it.characters.isLoadingMore && it.characters.nextOffset == null } }
        assertEquals(listOf(0, 30), requests)
        assertEquals(listOf(character), completed.characters.items)
    }

    @Test
    fun `人物角色筛选：只列计数大于 0 的选项，切换后带筛选从第 0 页重取`() = runBlocking {
        val pageRequests = ConcurrentLinkedQueue<Pair<Int, PersonCastFilter>>()
        val repository = object : FakeMonoRepository() {
            override suspend fun getPersonCharacters(id: Int, limit: Int, offset: Int, filter: PersonCastFilter): PagedResult<MonoCharacterRefBo> {
                if (limit == 1) {
                    // 计数：只有动画与主角有数据
                    val total = if (filter.type == SubjectType.ANIME || filter.role == CastRole.MAIN) 5 else 0
                    return PagedResult(emptyList(), total = total, nextOffset = null)
                }
                pageRequests += offset to filter
                return PagedResult(listOf(MonoCharacterRefBo(1, "角色", "", null, "作品")), total = 1, nextOffset = null)
            }
        }
        val vm = withContext(main.dispatcher) {
            PersonCharactersViewModel(1, repository).also { characterViewModels += it }
        }
        // Ready 是终态（单调），等它而不是等「非 Loading」：失败的 Unavailable 也该让断言失败而不是空指针
        val options = (withTimeout(WAIT_BUDGET_MS) { vm.uiState.map { it.options }.first { it is CastFilterOptionsState.Ready } } as CastFilterOptionsState.Ready).options
        assertEquals(listOf(MonoWorkOption(SubjectType.ANIME, 5)), options.types)
        assertEquals(listOf(MonoWorkOption(CastRole.MAIN, 5)), options.roles)
        // 首页取到才算开始：初始状态本来就不在加载，等「不在加载」会赶在第一次取页之前
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.first { it.characters.items.isNotEmpty() } }
        val anime = PersonCastFilter(type = SubjectType.ANIME)
        withContext(main.dispatcher) { vm.selectFilter(anime) }
        // 请求记录在别的线程写、不进状态流：按它自己等，而不是放在状态流的条件里等状态再变一次
        awaitUntil("带筛选从第 0 页重取") { pageRequests.size >= 2 }
        assertEquals(listOf(0 to PersonCastFilter(), 0 to anime), pageRequests.toList())
    }

    @Test
    fun `人物角色筛选：计数取失败时不画筛选，状态落在 Unavailable 而不是一直加载`() = runBlocking {
        val repository = object : FakeMonoRepository() {
            override suspend fun getPersonCharacters(id: Int, limit: Int, offset: Int, filter: PersonCastFilter): PagedResult<MonoCharacterRefBo> {
                if (limit == 1) error("计数失败")
                return PagedResult(listOf(MonoCharacterRefBo(1, "角色", "", null, "作品")), total = 1, nextOffset = null)
            }
        }
        val vm = withContext(main.dispatcher) {
            PersonCharactersViewModel(1, repository).also { characterViewModels += it }
        }
        assertEquals(
            CastFilterOptionsState.Unavailable,
            withTimeout(WAIT_BUDGET_MS) { vm.uiState.map { it.options }.first { it != CastFilterOptionsState.Loading } },
        )
    }

    @Test
    fun `人物角色筛选：选了作品类型后角色类型的计数带上该类型重取，项不变`() = runBlocking {
        val repository = object : FakeMonoRepository() {
            override suspend fun getPersonCharacters(id: Int, limit: Int, offset: Int, filter: PersonCastFilter): PagedResult<MonoCharacterRefBo> {
                if (limit == 1) {
                    // 主角：动画 3 部、游戏 2 部；配角只在动画里有 4 部
                    val total = when (filter.role) {
                        CastRole.MAIN -> when (filter.type) { null -> 5; SubjectType.ANIME -> 3; SubjectType.GAME -> 2; else -> 0 }
                        CastRole.SUPPORTING -> if (filter.type == null || filter.type == SubjectType.ANIME) 4 else 0
                        null -> when (filter.type) { SubjectType.ANIME -> 7; SubjectType.GAME -> 2; else -> 0 }
                        else -> 0
                    }
                    return PagedResult(emptyList(), total = total, nextOffset = null)
                }
                return PagedResult(listOf(MonoCharacterRefBo(1, "角色", "", null, "作品")), total = 1, nextOffset = null)
            }
        }
        val vm = withContext(main.dispatcher) {
            PersonCharactersViewModel(1, repository).also { characterViewModels += it }
        }
        val initial = (withTimeout(WAIT_BUDGET_MS) { vm.uiState.map { it.options }.first { it is CastFilterOptionsState.Ready } } as CastFilterOptionsState.Ready).options
        assertEquals(listOf(MonoWorkOption(CastRole.MAIN, 5), MonoWorkOption(CastRole.SUPPORTING, 4)), initial.roles)
        withContext(main.dispatcher) { vm.selectFilter(PersonCastFilter(type = SubjectType.GAME)) }
        // 计数是终态：等到带上「游戏」的那一轮落定（配角计 0，但项还在）
        val game = withTimeout(WAIT_BUDGET_MS) {
            vm.uiState.map { it.options }.first { (it as? CastFilterOptionsState.Ready)?.options?.roles?.firstOrNull()?.count == 2 }
        } as CastFilterOptionsState.Ready
        assertEquals(listOf(MonoWorkOption(CastRole.MAIN, 2), MonoWorkOption(CastRole.SUPPORTING, 0)), game.options.roles)
        // 类型计数不带类型自身：与第一次相同
        assertEquals(initial.types, game.options.types)
    }

    @Test
    fun `人物角色筛选：切一排只给另一排发计数，切回选过的组合不再发`() = runBlocking {
        val probes = ConcurrentLinkedQueue<PersonCastFilter>()
        val repository = object : FakeMonoRepository() {
            override suspend fun getPersonCharacters(id: Int, limit: Int, offset: Int, filter: PersonCastFilter): PagedResult<MonoCharacterRefBo> {
                if (limit == 1) {
                    probes += filter
                    // 各组合给不同的数，换一轮计数后状态才看得出来
                    val total = 10 * ((filter.type?.ordinal ?: -1) + 2) + ((filter.role?.ordinal ?: -1) + 2)
                    return PagedResult(emptyList(), total = total, nextOffset = null)
                }
                return PagedResult(listOf(MonoCharacterRefBo(1, "角色", "", null, "作品")), total = 1, nextOffset = null)
            }
        }
        val vm = withContext(main.dispatcher) {
            PersonCharactersViewModel(1, repository).also { characterViewModels += it }
        }
        val initial = withTimeout(WAIT_BUDGET_MS) { vm.uiState.map { it.options }.first { it is CastFilterOptionsState.Ready } }
        val initialProbes = probes.size
        assertEquals(personCastTypeOptions.size + CastRole.entries.size, initialProbes)

        // 选「游戏」：只有角色类型那排带上「游戏」重取（探测次数只增不减，是单调条件）
        withContext(main.dispatcher) { vm.selectFilter(PersonCastFilter(type = SubjectType.GAME)) }
        awaitUntil("角色类型那排按「游戏」重取完") { probes.size >= initialProbes + CastRole.entries.size }
        assertEquals(
            CastRole.entries.map { PersonCastFilter(type = SubjectType.GAME, role = it) }.toSet(),
            probes.drop(initialProbes).toSet(),
        )
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.map { it.options }.first { it is CastFilterOptionsState.Ready && it != initial } }

        // 切回「全部」：计数回到首轮的值（这一轮的终态），而且组合都取过，一个请求也不再发
        val beforeBack = probes.size
        withContext(main.dispatcher) { vm.selectFilter(PersonCastFilter()) }
        withTimeout(WAIT_BUDGET_MS) { vm.uiState.map { it.options }.first { it == initial } }
        assertEquals(beforeBack, probes.size)
    }
}
