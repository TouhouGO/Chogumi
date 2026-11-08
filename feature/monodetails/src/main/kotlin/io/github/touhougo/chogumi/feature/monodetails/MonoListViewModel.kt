package io.github.touhougo.chogumi.feature.monodetails

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.MonoRepository
import io.github.touhougo.chogumi.core.data.util.loadOnce
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.model.CastRole
import io.github.touhougo.chogumi.core.model.MonoCharacterRefBo
import io.github.touhougo.chogumi.core.model.MonoRelationBo
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.MonoWorkBo
import io.github.touhougo.chogumi.core.model.MonoWorkFilter
import io.github.touhougo.chogumi.core.model.MonoWorkOption
import io.github.touhougo.chogumi.core.model.MonoWorkSort
import io.github.touhougo.chogumi.core.model.PersonCastFilter
import io.github.touhougo.chogumi.core.model.PersonCastFilterOptions
import io.github.touhougo.chogumi.core.model.personCastTypeOptions
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.common.paging.PagedListViewModel
import io.github.touhougo.chogumi.core.common.paging.Pager
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 「全部」作品子页的状态：全量作品 + 当前筛选与用户选的排序（没选时用默认排序）。 */
data class MonoWorksUiState(
    val works: Load<List<MonoWorkBo>> = Load.Pending,
    val filter: MonoWorkFilter = MonoWorkFilter(),
    /** 用户在子页上选的排序；null 表示用默认排序（[io.github.touhougo.chogumi.core.model.defaultMonoWorkSort]）。 */
    val requestedSort: MonoWorkSort? = null,
)

/**
 * 「全部」作品子页：取全量作品（与详情页同一份仓库缓存），筛选与排序都在本地做——
 * 两个作品接口都没有排序参数，在已加载的一页上排是错的。
 */
class MonoWorksViewModel(
    private val monoType: MonoType,
    private val id: Int,
    private val monoRepository: MonoRepository,
) : StateViewModel<MonoWorksUiState>(MonoWorksUiState()) {

    init {
        load()
    }

    fun retry() = load()

    fun selectFilter(filter: MonoWorkFilter) {
        updateState { it.copy(filter = filter) }
    }

    fun selectSort(sort: MonoWorkSort) {
        updateState { it.copy(requestedSort = sort) }
    }

    // 只在没取到时整页重试，失败落在页面上，不另发提示
    private fun load() =
        launchSection("works", failures = null, { loadOnce { monoRepository.getAllWorks(monoType, id) } }, { it.works }) {
            copy(works = it)
        }
}

/** 关联人物：同一人多种关系合成一张卡（与详情页区块同一个 [mergeByEntity]），跨页累积后整体合并。 */
class MonoRelationsListViewModel(
    private val monoType: MonoType,
    private val id: Int,
    private val monoRepository: MonoRepository,
) : PagedListViewModel<MonoRelationBo>() {
    override suspend fun fetchPage(offset: Int) = outcomeOf { monoRepository.getRelations(monoType, id, offset = offset) }

    override fun present(fetched: List<MonoRelationBo>) = fetched.mergeByEntity()

    init {
        loadFirstPage()
    }
}

/**
 * 出演角色两排筛选的取值状态：计数逐项向接口取，[Loading] 期间页面保持首屏加载（筛选行与列表一起出现，
 * 不在列表到了之后再插进来把列表往下顶）；[Unavailable] 是任一项取失败，不画筛选、不影响列表。
 */
sealed interface CastFilterOptionsState {
    data object Loading : CastFilterOptionsState
    data class Ready(val options: PersonCastFilterOptions) : CastFilterOptionsState
    data object Unavailable : CastFilterOptionsState
}

/** 人物出演的完整角色子页：当前筛选、两排筛选的选项与计数、角色列表。 */
data class PersonCharactersUiState(
    val filter: PersonCastFilter = PersonCastFilter(),
    val options: CastFilterOptionsState = CastFilterOptionsState.Loading,
    val characters: PagedList<MonoCharacterRefBo> = PagedList(),
)

/** 人物出演的完整角色列表：筛选变了就从第 0 页重取（新一页到手前旧条目留在原地）。 */
class PersonCharactersViewModel(
    private val personId: Int,
    private val monoRepository: MonoRepository,
) : StateViewModel<PersonCharactersUiState>(PersonCharactersUiState()) {

    private val characters = Pager(
        scope = viewModelScope,
        read = { currentState.characters },
        write = { f -> updateState { it.copy(characters = f(it.characters)) } },
    ) { offset -> outcomeOf { monoRepository.getPersonCharacters(personId, offset = offset, filter = currentState.filter) } }

    fun retry() = characters.refresh()

    fun loadMore() = characters.loadMore()

    fun selectFilter(filter: PersonCastFilter) {
        if (filter == currentState.filter) return
        updateState { it.copy(filter = filter) }
        characters.refresh(keepItems = true)
        loadOptions()
    }

    /** 计数请求同一时刻只有一路：筛选连点时旧的计数作废，不会晚到覆盖新的。 */
    private var optionsJob: Job? = null

    /**
     * 每个选项向接口取一次总数（`limit=1`），并发进行，规则见 [PersonCastFilterOptions]。
     * 首次（不带筛选）决定有哪些项；之后按当前筛选重取计数、项不变。
     * 首次失败就不画筛选（[CastFilterOptionsState.Unavailable]），不影响列表；重取失败保留上一轮的计数。
     *
     * 每个组合的总数只取一次（[totalOf] 的缓存）：类型那排的计数只随角色类型变、角色类型那排只随作品类型变，
     * 所以切一排只有另一排真正发请求（最多 5 个），切回选过的组合一个也不发（产品决定的减量）。
     */
    private fun loadOptions() {
        optionsJob?.cancel()
        optionsJob = viewModelScope.launch {
            val previous = (currentState.options as? CastFilterOptionsState.Ready)?.options
            val filter = currentState.filter
            val result = outcomeOf {
                coroutineScope {
                    val types = (previous?.types?.map { it.value } ?: personCastTypeOptions).map { type ->
                        async { MonoWorkOption(type, totalOf(PersonCastFilter(type = type, role = filter.role))) }
                    }
                    val roles = (previous?.roles?.map { it.value } ?: CastRole.entries).map { role ->
                        async { MonoWorkOption(role, totalOf(PersonCastFilter(type = filter.type, role = role))) }
                    }
                    PersonCastFilterOptions(
                        types = types.awaitAll().filter { previous != null || it.count > 0 },
                        roles = roles.awaitAll().filter { previous != null || it.count > 0 },
                    )
                }
            }
            when (result) {
                is Outcome.Success -> updateState { it.copy(options = CastFilterOptionsState.Ready(result.value)) }
                is Outcome.Failed -> if (previous == null) updateState { it.copy(options = CastFilterOptionsState.Unavailable) }
            }
        }
    }

    /** 各筛选组合的总数：本页面生命周期内不变，取过就不再取。只在主线程读写（viewModelScope）。 */
    private val totals = HashMap<PersonCastFilter, Int>()

    private suspend fun totalOf(filter: PersonCastFilter): Int =
        totals[filter] ?: monoRepository.getPersonCharacters(personId, limit = 1, filter = filter).total
            .also { totals[filter] = it }

    init {
        characters.refresh()
        loadOptions()
    }
}
