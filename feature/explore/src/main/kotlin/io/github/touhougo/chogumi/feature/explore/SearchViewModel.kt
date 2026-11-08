package io.github.touhougo.chogumi.feature.explore

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.common.paging.Pager
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.ExploreLayoutRepository
import io.github.touhougo.chogumi.core.data.repository.SearchRepository
import io.github.touhougo.chogumi.core.data.repository.TagCatalogRepository
import io.github.touhougo.chogumi.core.data.util.launchWrite
import io.github.touhougo.chogumi.core.data.util.storageAsLoad
import io.github.touhougo.chogumi.core.foundation.AppTime
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CollectionEditing
import io.github.touhougo.chogumi.core.model.CollectionMarker
import io.github.touhougo.chogumi.core.model.PinnedShelf
import io.github.touhougo.chogumi.core.model.SearchAirtime
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SearchDimensionOption
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.SearchValidation
import io.github.touhougo.chogumi.core.model.SearchValidationError
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagCatalogEntry
import io.github.touhougo.chogumi.core.model.TagFilter
import io.github.touhougo.chogumi.core.model.WordPick
import io.github.touhougo.chogumi.core.model.concreteOrNull
import io.github.touhougo.chogumi.core.model.isActive
import io.github.touhougo.chogumi.core.model.isPinnable
import io.github.touhougo.chogumi.core.model.normalizeSearchCriteria
import io.github.touhougo.chogumi.core.model.pinnedShelfOf
import io.github.touhougo.chogumi.core.model.presetKey
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkController
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkState
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class SearchUiState(
    val form: SearchForm = SearchForm(),
    val validationErrors: List<SearchValidationError> = emptyList(),
    /** 条件代次：新条件生效时自增，列表据此滚回顶部。 */
    val generation: Long = 0L,
    /** 当前条件的结果（分页，跨页按 id 去重）；没有条件时是空的、不在加载。 */
    val results: PagedList<SubjectBo> = PagedList(isLoading = false),
    val history: List<String> = emptyList(),
    /** 面板搜索框的当前输入。唯一真源在这里，面板不自己记一份。 */
    val panelQuery: String = "",
    /** 当前输入命中的标签目录条目（按使用次数倒序，已按 NSFW 开关过滤）。没拉过目录时为空。 */
    val catalogSuggestions: List<TagCatalogEntry> = emptyList(),
    /** 结果海报的长按标记，只由 [SearchViewModel.posterMarks] 改。 */
    val posterMarks: PosterMarkState = PosterMarkState(),
    /**
     * 探索页上条件与当前条件判等（[pinnedShelfOf]）的那条货架；不在探索页上是 null。
     * 图钉的选中态、名字栏显示的到底是路由带来的 [SearchForm.title] 还是货架自己的名字，
     * 都由它一个字段决定。
     */
    val pinned: PinnedShelf? = null,
    /** 名字栏是否处于就地改名状态（纯 UI 开关，不进表单）。 */
    val renaming: Boolean = false,
)

/**
 * 筛选面板（[SearchFilterPanel]）只改草稿的那几个动作：搜索页与探索页的货架编辑共用同一个面板，各自实现这一小组。
 * 草稿怎么变一律经 [reduce]（只改草稿的事件），实现方不另写。
 */
interface FilterDraftActions {
    /** 面板搜索框的输入。改输入即清掉上一次的目录结果，避免旧建议挂在新输入下。 */
    fun onPanelQueryChange(value: String)
    fun onDraftTagRemove(tag: TagFilter)
    fun onDraftWordPick(word: String, pick: WordPick)
    fun onDraftDimensionToggle(dimension: SearchDimension, option: SearchDimensionOption)
    fun onDraftAirtimeChange(airtime: SearchAirtime?)
    fun onDraftClear()
}

interface SearchActions : FilterDraftActions {
    fun onQueryChange(value: String)
    fun onQueryClear()
    fun onSearch()
    fun onFilterTypeChange(type: SubjectType?)
    fun onSortChange(sort: SearchSort)

    // 筛选面板：编辑草稿，保存才生效
    fun onFiltersOpen()
    fun onFiltersDismiss()
    fun onFiltersSave()
    fun onRetry()
    fun loadMore()
    fun onHistoryClick(keyword: String)
    fun onClearHistory()

    /** 图钉：已固定则取消，否则固定（默认名字由 [defaultPresetName] 给出）。 */
    fun onPinToggle(defaultPresetName: String)
    fun onRenameStart()
    fun onRenameCancel()

    /** 改名落盘。[title] 已由界面 trim；空串不保存。 */
    fun onRenameSubmit(title: String)
}

/**
 * 搜索 ViewModel（历史持久化）。表单事件统一经 reducer，UI 不自己算下一态。
 * 结果按已提交的条件经 [Pager.show] 显示：切排序、切分类再切回来，本页面里取过的列表原样回来，不重取。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val searchRepository: SearchRepository,
    private val tagCatalogRepository: TagCatalogRepository,
    private val layoutRepository: ExploreLayoutRepository,
    appTime: AppTime,
    collectionMarker: CollectionMarker,
    collectionEditing: CollectionEditing,
    criteria: SearchCriteria? = null,
    title: String? = null,
    openFilters: Boolean = false,
) : StateViewModel<SearchUiState>(SearchUiState()), SearchActions {
    private var filterRound = 0L

    /** 业务日（[AppTime.today]）：放送时间筛选的「今年」按它，跨年随之变。 */
    val today: StateFlow<LocalDate> = appTime.today

    /** 本页的失败提示：搜索历史读不出或写不进、结果海报上长按标记的提交失败。 */
    val failures = FailureNotices()

    /** 结果海报的长按标记：标状态、撤销、收藏管理面板。 */
    val posterMarks = PosterMarkController(
        scope = viewModelScope,
        read = { currentState.posterMarks },
        write = { change -> updateState { it.copy(posterMarks = change(it.posterMarks)) } },
        failures = failures,
        marker = collectionMarker,
        editing = collectionEditing,
        today = appTime.today,
    )

    /**
     * 结果的取页：换条件时 [Pager.show] 作废旧条件还在路上的请求，慢结果不会覆盖新结果。
     * 换到本页还没显示过的条件时，第一页先用上次存下的（新鲜期内不发请求）；重试与再次提交照旧重取。
     */
    private val results = Pager(
        scope = viewModelScope,
        read = { currentState.results },
        write = { f -> updateState { it.copy(results = f(it.results)) } },
        present = { fetched -> fetched.distinctBy(SubjectBo::id) },
        fetchFirstShown = { searchRepository.search(currentState.form.applied, reuseFirstPage = true) },
    ) { offset -> searchRepository.search(currentState.form.applied, offset = offset) }

    /**
     * 唯一表单事件入口。
     * 非法候选只更新 validationErrors 与 reducer 给出的表单（已提交条件不变，仅保存失败时关面板）；不发请求、不动已有结果。
     * 合法且需提交时走 submit；否则只写表单并清旧校验错误。
     */
    private fun dispatch(intent: SearchIntent) {
        val reduction = reduce(currentState.form, intent)

        if (reduction.errors.isNotEmpty()) {
            updateState { it.copy(form = reduction.form, validationErrors = reduction.errors) }
            return
        }

        if (!reduction.submit) {
            updateState {
                it.copy(
                    form = reduction.form,
                    validationErrors = emptyList(),
                )
            }
            return
        }

        submit(reduction.form, reduction.writeHistory)
    }

    private fun submit(form: SearchForm, writeHistory: Boolean) {
        updateState { it.copy(form = form, generation = it.generation + 1, validationErrors = emptyList()) }
        val criteria = form.applied
        if (!criteria.isActive()) {
            results.clear()
            return
        }
        if (writeHistory && criteria.query.isNotBlank()) viewModelScope.launch { searchRepository.recordSearch(criteria.query).failureOrNull()?.let { failures.post(it) } }
        results.show(criteria)
    }

    override fun onQueryChange(value: String) = dispatch(SearchIntent.QueryChanged(value))

    override fun onQueryClear() = dispatch(SearchIntent.QueryCleared)

    override fun onSearch() = dispatch(SearchIntent.SearchSubmitted)

    override fun onFilterTypeChange(type: SubjectType?) = dispatch(SearchIntent.TypeChanged(type))

    override fun onSortChange(sort: SearchSort) = dispatch(SearchIntent.SortChanged(sort))

    override fun onFiltersOpen() {
        val round = ++filterRound
        dispatch(SearchIntent.FiltersOpened)
        updateState { it.copy(panelQuery = "", catalogSuggestions = emptyList()) }
        // 打开面板即按需拉目录：后台跑、不阻塞面板；拉完由下面的查目录流自然接上，
        // 用户下一次输入就有目录建议。具体类型才有目录（「全部」没有 type）。
        val type = currentState.form.applied.subjectType?.concreteOrNull() ?: return
        viewModelScope.refreshCatalog(tagCatalogRepository, type, failures) {
            filterRound == round && currentState.form.filterDraft != null
        }
    }

    override fun onFiltersDismiss() {
        dispatch(SearchIntent.FiltersDismissed)
        // 面板关了就丢掉输入：下次打开是一块空白的搜索框与「其他已选」。
        updateState { it.copy(panelQuery = "", catalogSuggestions = emptyList()) }
    }

    override fun onDraftTagRemove(tag: TagFilter) = dispatch(SearchIntent.DraftTagRemoved(tag))

    /**
     * 面板搜索框输入：写进 UI 状态（唯一真源），并立刻清空上一次的目录结果——
     * 查目录是异步的，旧结果留在这里会短暂挂在新输入下面，看起来像查错了。
     * 查目录本身由 init 里的目录查询流负责。
     */
    override fun onPanelQueryChange(value: String) {
        updateState { it.copy(panelQuery = value, catalogSuggestions = emptyList()) }
    }

    override fun onDraftWordPick(word: String, pick: WordPick) = dispatch(SearchIntent.DraftWordPicked(word, pick))

    override fun onDraftDimensionToggle(dimension: SearchDimension, option: SearchDimensionOption) =
        dispatch(SearchIntent.DraftDimensionToggled(dimension, option))

    override fun onDraftAirtimeChange(airtime: SearchAirtime?) = dispatch(SearchIntent.DraftAirtimeChanged(airtime))

    override fun onDraftClear() = dispatch(SearchIntent.DraftCleared)

    override fun onFiltersSave() = dispatch(SearchIntent.FiltersSaved)

    override fun onHistoryClick(keyword: String) = dispatch(SearchIntent.HistoryClicked(keyword))

    override fun onRetry() {
        // 复用当前已提交条件重搜，不读草稿、不重复写历史；完全空态不会永远 loading。
        submit(currentState.form, writeHistory = false)
    }

    override fun loadMore() = results.loadMore()

    override fun onClearHistory() {
        viewModelScope.launch { searchRepository.clearRecentSearches().failureOrNull()?.let { failures.post(it) } }
    }

    /**
     * 图钉：探索页上已有这条货架则移除（= 隐藏，「添加货架」里还能加回来），否则按 [defaultPresetName] 新放一条上去。
     *
     * 可用性判定（关键词为空 + 具体类型 + 至少一个条件）不在这里——界面按同一条件禁用按钮，ViewModel 也
     * 拦一道：空条件放上去的货架是一张「全部动画」，不允许写进表。
     */
    override fun onPinToggle(defaultPresetName: String) {
        val criteria = currentState.form.applied
        if (!criteria.isPinnable()) return
        val type = criteria.subjectType ?: return
        viewModelScope.launchWrite(failures) {
            val existing = currentState.pinned
            if (existing != null) {
                layoutRepository.hide(type, existing.id)
                setRenaming(false)
            } else {
                layoutRepository.pin(criteria, defaultPresetName)
            }
        }
    }

    override fun onRenameStart() = setRenaming(true)

    override fun onRenameCancel() = setRenaming(false)

    /** 写入成功才收起改名框；失败时框和输入都留着，提示走 [failures]。空串不保存，直接收起，名字照旧。 */
    override fun onRenameSubmit(title: String) {
        val type = currentState.form.applied.subjectType
        val pinned = currentState.pinned
        val trimmed = title.trim()
        // 空串不保存（原型 maxlength 之外的另一条规则：清空名字不该把货架变成无名卡片）。
        if (trimmed.isEmpty() || type == null || pinned == null) {
            setRenaming(false)
            return
        }
        viewModelScope.launchWrite(failures) {
            layoutRepository.rename(type, pinned.id, trimmed)
            setRenaming(false)
        }
    }

    private fun setRenaming(value: Boolean) {
        updateState { it.copy(renaming = value) }
    }

    init {
        // 初始条件只有一个来源：路由带来的整组条件（详情页点标签、探索页入口与货架都已转好）。
        // 没有条件就是空搜索，什么都不发。
        criteria?.let { seeded ->
            when (val normalized = normalizeSearchCriteria(seeded)) {
                is SearchValidation.Valid -> submit(
                    SearchForm(applied = normalized.value, title = title),
                    writeHistory = false,
                )
                // 非法条件只显示错误，不发 HTTP
                is SearchValidation.Invalid ->
                    updateState { it.copy(validationErrors = normalized.errors) }
            }
        }
        if (openFilters) {
            onFiltersOpen()
        }
        viewModelScope.launch {
            // 始终对当前 state 做 copy，只更新 history，不用初始化快照回写覆盖 form。
            searchRepository.recentSearches.collect { history ->
                updateState { it.copy(history = history.valueOrNull().orEmpty()) }
                history.failureOrNull()?.let { failures.post(it) }
            }
        }
        // 面板搜索框 → 目录查询（[suggestionsFor]）；查询结果写回前再核一次输入，防止取消竞态把旧结果写上屏。
        viewModelScope.launch {
            tagCatalogRepository.suggestionsFor(uiState.map { it.panelQuery to it.form.applied.subjectType })
                .collect { (query, entries) ->
                    // 只有输入还是发起这次查询时的那个值才写：查询被取消后仍可能把旧结果送进来。
                    if (currentState.panelQuery == query) {
                        updateState { it.copy(catalogSuggestions = entries) }
                    }
                }
        }
        // 图钉对着的货架：观察当前分类的探索页布局，再挑出条件与当前条件判等的那条（[pinnedShelfOf]）。
        // 条件每次 reduce 都变，所以观察 state 而不是某一次快照；类型一变布局就换了，类型在判等键里。
        // 布局表读不出时提示带重试，图钉当作没对上。
        follow(failures, {
            uiState
                .map { it.form.applied }
                .distinctUntilChangedBy { it.presetKey() }
                .flatMapLatest { criteria ->
                    val type = criteria.subjectType.concreteOrNull()
                    if (type == null) {
                        flowOf(null)
                    } else {
                        layoutRepository.observe(type).map { layout -> pinnedShelfOf(type, layout, criteria) }
                    }
                }
                .storageAsLoad()
        }) { shelf ->
            // 条件刚变、而新条件恰好等于另一条货架时，这里给出那条货架的名字与选中态——
            // 与「名字失效」合在一处：路由带来的 title 在 reduce 里已经因为条件变了被清掉。
            updateState { it.copy(pinned = shelf) }
        }
    }
}
