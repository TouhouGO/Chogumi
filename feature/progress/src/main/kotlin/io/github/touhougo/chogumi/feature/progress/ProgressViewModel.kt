package io.github.touhougo.chogumi.feature.progress

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.progress.EpTapCoordinator
import io.github.touhougo.chogumi.core.data.progress.ProgressOrigin
import io.github.touhougo.chogumi.core.data.progress.ProgressSubject
import io.github.touhougo.chogumi.core.data.repository.CollectionObserver
import io.github.touhougo.chogumi.core.data.repository.CollectionProgressUpdate
import io.github.touhougo.chogumi.core.data.repository.CollectionStore
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.repository.SubjectRepository
import io.github.touhougo.chogumi.core.data.repository.WatchStateStore
import io.github.touhougo.chogumi.core.data.util.storageOutcomeOf
import io.github.touhougo.chogumi.core.foundation.AppTime
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.map
import io.github.touhougo.chogumi.core.foundation.settled
import io.github.touhougo.chogumi.core.model.CollectionEditing
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.EpPendingVisual
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeDataState
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.ProgressCategory
import io.github.touhougo.chogumi.core.model.ProgressCategoryGroup
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.ProgressFilterOption
import io.github.touhougo.chogumi.core.model.ProgressOrder
import io.github.touhougo.chogumi.core.model.ProgressSort
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.model.availableProgressCategories
import io.github.touhougo.chogumi.core.model.availableProgressTags
import io.github.touhougo.chogumi.core.model.episodeDataState
import io.github.touhougo.chogumi.core.model.matchesCategoryFilter
import io.github.touhougo.chogumi.core.model.matchesTagFilter
import io.github.touhougo.chogumi.core.model.progressPanelTags
import io.github.touhougo.chogumi.core.model.sortProgress
import io.github.touhougo.chogumi.core.model.stableOrder
import io.github.touhougo.chogumi.core.ui.collection.CollectionSheetController
import io.github.touhougo.chogumi.core.ui.collection.CollectionSheetState
import io.github.touhougo.chogumi.core.ui.collection.CollectionWorkSource
import io.github.touhougo.chogumi.core.ui.common.navigation.ProgressEntryRequests
import io.github.touhougo.chogumi.core.ui.common.navigation.ProgressEntryRequest
import io.github.touhougo.chogumi.core.ui.composables.subjectTypeTabsOf
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** 进度页的页序（null = 全部）：与分类栏同一份（[subjectTypeTabsOf]），第 i 页就是第 i 个 tab。 */
val PROGRESS_TYPES: List<SubjectType?> = subjectTypeTabsOf(includeAll = true)

/**
 * 预取章节时的并发上限。**设计决定（非 M3）**：进度页可能有上百个条目，
 * 按列表顺序排队、同时最多 4 个，避免一次打出上百个请求把界面拖卡。
 */
private const val EPISODE_LOAD_CONCURRENCY = 4

data class ProgressUiState(
    val query: String = "",
    val collectionStatus: CollectionType = CollectionType.DOING,
    /**
     * 用户在排序菜单里选的档位与方向（**不持久化**）。某一页没有这一档时（书籍没有「最近更新」）那一页按
     * [ProgressOrder.on] 换成最近操作，这里仍记着用户的选择，翻回有这一档的页又按它排。菜单读 [pageOrder]。
     */
    val order: ProgressOrder = ProgressOrder(),
    val selectedSubjectType: Int = 0,
    /**
     * 第一次入口请求处理完了（[selectedSubjectType] 已是该开的那页）。初始请求要先读设置里的默认分类，
     * 读到之前选中页还没定：界面先按当前选中页（默认「全部」）建翻页状态，它变 true 时重建，直接停在默认分类，不从「全部」滑过去。
     */
    val opened: Boolean = false,
    /** 「标签」已选标签：组内多选、取并集。 */
    val selectedTags: Set<String> = emptySet(),
    /** 「标签」可选项（带部数与封面）：类型 tab 与收藏状态筛过、但未经本面板筛的那一批里出现过的标签，按部数排。 */
    val tagOptions: List<ProgressFilterOption<String>> = emptyList(),
    /** 「分类」已选（维度 + 取值）：同维度取并集、跨维度取交集，且与「标签」取交集。 */
    val selectedCategories: Set<ProgressCategory> = emptySet(),
    /**
     * 「分类」可选项：按词典的维度分组，只列当前这批条目里实际出现过的取值。
     * 「全部」页签（词典无跨类型维度）或没有取值出现过时为空，界面据此整组不渲染。
     */
    val categoryGroups: List<ProgressCategoryGroup> = emptyList(),
    /**
     * 筛选面板开着时是打开那一刻已选的标签（面板里排在最前，开着时点选不让格子挪位置，见 [panelTags]）；
     * null = 面板关着。
     */
    val filterPanelPinned: Set<String>? = null,
    /**
     * 第几次打开筛选面板：每点一次筛选入口加一，界面按它起一个新面板。上一个面板正在滑下去时页面已经能点（PageModalSheet 收起中
     * 就把触摸还给页面），这时再点入口也必须打开——不能因为上一个还没滑完、状态还算开着就把这一下吞掉。
     */
    val filterPanelSession: Int = 0,
    /**
     * 每一页（[PROGRESS_TYPES] 的页序）的列表：都按同一套条件筛（收藏状态、搜索、标签、分类，
     * 标签与分类按各页自己的可选项收敛），各自保持顺序。翻页途中看到的邻页就是落定后的样子。
     */
    val pages: List<List<UserCollectionBo>> = PROGRESS_TYPES.map { emptyList() },
    val isRefreshing: Boolean = false,
    val pendingVisuals: Map<Int, EpPendingVisual> = emptyMap(),
    /**
     * 逐集状态（本地真源的投影）；某条目不在其中 = 逐集表里没有它的行，不一定是还不知道（见 [emptyWatchStates]）。
     * 读一部的逐集状态一律经 [watchStatesOf]。
     */
    val watchStates: Map<Int, Map<Int, EpisodeStatus>> = emptyMap(),
    /**
     * 逐集请求已返回、但服务端一集都没有的条目（刚公布、还没录入章节的新番）。这样的条目在逐集表里没有行，
     * [watchStates] 永远不会带上它，只能在这里记下「已返回、确实为空」。
     *
     * 只记零行的：有行的请求，那些行要经快照才到 [watchStates]，比请求返回晚；请求一返回就算已知的话，
     * 这段空档里下一集会按「一集都没看」算成第 1 集。
     */
    val emptyWatchStates: Set<Int> = emptySet(),
    /** 每部作品最后一次「看过某集」的时刻（与 [watchStates] 出自同一份快照），排序用。 */
    val latestWatchedAt: Map<Int, Long> = emptyMap(),
    /** 已加载的章节（列表一出来就按顺序为在看的动画/三次元预取，见 [ProgressViewModel.prefetchEpisodes]）。 */
    val episodesMap: Map<Int, List<EpisodeBo>> = emptyMap(),
    /** 这一轮章节或逐集状态请求失败的条目：这一行的 EP 槽位按「没有可点的进度动作」留空，一轮全部成功后移除。 */
    val episodesFailed: Set<Int> = emptySet(),
    /**
     * 各类型收藏同步失败的原因（类型 → [Failure]）：选中页没有条目时画重试块（[syncError]）。
     * 有条目时一轮里的失败另经 [ProgressViewModel.failures] 提示一次，见 [ProgressViewModel.load]。
     *
     * 「全部」页签并行同步五个类型，失败必须记下是**哪一个**：某个类型下一次同步成功时只清它自己的条目，
     * 别的类型成功不会把这个类型的真实失败藏起来。
     */
    val syncErrors: Map<SubjectType, Failure> = emptyMap(),
    /**
     * 每一页**重新排序**的次数（下拉刷新、改条件时 +1；标记进度等原位更新、单纯翻页都不变）。
     * 那一页据此回到顶部：否则列表按 key 锚定原先的首个可见条目，重排到首位的条目会落在视口上方。
     */
    val pageEpochs: List<Int> = PROGRESS_TYPES.map { 0 },
    /**
     * 在当前收藏状态下已经同步过一次的类型。没同步过、本地也没有条目的页还不知道是不是真的空，画骨架；
     * 同步过还是空的才写「没有条目」。
     */
    val syncedTypes: Set<SubjectType> = emptySet(),
    val isLoading: Boolean = true,
    /** 业务日（[AppTime.today]）：EP 按钮、连载波浪按它判，跨零点随之重算。 */
    val today: LocalDate,
    /** 长按一行打开的收藏面板，只由 [CollectionSheetController] 改。 */
    val collectionSheet: CollectionSheetState = CollectionSheetState(),
) {
    /**
     * 同步失败里说哪一条：按类型固定顺序取第一个（重试块与一轮的失败提示都用这个次序）。没有失败时为 null。
     * 写操作失败不在这里，走 [ProgressViewModel.failures]。
     */
    val syncError: Failure?
        get() = ContentDomains.firstNotNullOfOrNull { syncErrors[it] }

    /** 选中页的列表。 */
    val collections: List<UserCollectionBo> get() = pages[selectedSubjectType]

    /**
     * 一部的逐集状态——进度页读它的唯一处：逐集表里有行就是那些行，逐集请求返回了零行（[emptyWatchStates]）是空表，
     * 否则为 null（请求还没返回）。与 [episodesMap] 同一个口径：null 是「还没返回」，空是「返回了、确实没有」。
     */
    fun watchStatesOf(subjectId: Int): Map<Int, EpisodeStatus>? =
        watchStates[subjectId] ?: emptyMap<Int, EpisodeStatus>().takeIf { subjectId in emptyWatchStates }

    /** 选中页实际按什么排（排序菜单显示它）：见 [order]。 */
    val pageOrder: ProgressOrder get() = order.on(PROGRESS_TYPES[selectedSubjectType])

    /** 生效的筛选有几项（标签与分类，已按这一页收敛）：筛选入口上的数字徽标。排序不算筛选。 */
    val activeFilterCount: Int get() = selectedTags.size + selectedCategories.size

    val filtersOpen: Boolean get() = filterPanelPinned != null

    /** 面板里标签的次序：打开时已选的在前，其余按部数（[progressPanelTags]）。 */
    val panelTags: List<ProgressFilterOption<String>> get() = progressPanelTags(tagOptions, filterPanelPinned.orEmpty())

    /** 任何一页重新排序就变、单纯翻页不变：可收起的搜索栏据此回到全开。 */
    val reorderCount: Int get() = pageEpochs.sum()

    /**
     * 第 [page] 页画成什么（唯一判定）：还不知道有没有条目时画骨架——选中页看这一轮同步是否在途，
     * 其余页看这个收藏状态下同步过没有（「全部」页要五类都同步过）；没有条目而选中页同步失败画重试块；
     * 知道了却没有条目写「没有条目」；否则画列表。
     */
    fun shapeOf(page: Int): ProgressPageShape {
        val items = pages[page]
        val selected = page == selectedSubjectType
        val unknown = if (selected) isLoading else !(PROGRESS_TYPES[page]?.let(::listOf) ?: ContentDomains).all { it in syncedTypes }
        return when {
            items.isNotEmpty() -> ProgressPageShape.LIST
            unknown -> ProgressPageShape.LOADING
            selected && syncError != null -> ProgressPageShape.ERROR
            else -> ProgressPageShape.EMPTY
        }
    }
}

/** 一页的三种形态，见 [ProgressUiState.shapeOf]。 */
enum class ProgressPageShape { LOADING, EMPTY, ERROR, LIST }

/**
 * 网络负责更新共享收藏库，页面筛选观察同一份收藏；进度写入由 [EpTapCoordinator] 统一调度。
 *
 * 列表顺序经 [stableOrder] 保持，每页一份：标记进度后条目只更新进度、不改变位置；
 * 下拉刷新、改条件（收藏状态、排序、标签、分类、清除）、重新进入页面时才重新排序（[reorderAll]）。
 * 单纯翻页不重排：翻回去的页还是原来的顺序与位置。
 */
class ProgressViewModel(
    private val collectionStore: CollectionStore,
    private val collectionObserver: CollectionObserver,
    private val watchStateStore: WatchStateStore,
    private val subjectRepository: SubjectRepository,
    private val epTapCoordinator: EpTapCoordinator,
    private val entryRequests: ProgressEntryRequests,
    private val preferencesRepository: PreferencesRepository,
    collectionEditing: CollectionEditing,
    appTime: AppTime,
    private val computationDispatcher: CoroutineDispatcher,
) : StateViewModel<ProgressUiState>(ProgressUiState(today = appTime.today.value)) {

    /** 本页的失败提示：进度写入失败（本地已回滚）、标记看完失败、收藏面板取不到或存不上。 */
    val failures = FailureNotices()

    /** 长按一行打开的收藏面板：长按哪部才取哪部。存好后列表跟着本地收藏表变，不用另外回读。 */
    private val collectionSheet = CollectionSheetController(
        scope = viewModelScope,
        read = { currentState.collectionSheet },
        write = { change -> updateState { it.copy(collectionSheet = change(it.collectionSheet)) } },
        failures = failures,
        source = CollectionWorkSource.Fetch(collectionEditing::observeWork),
        save = collectionEditing::save,
        remove = collectionEditing::remove,
    )

    private var loadJob: Job? = null
    private var filterJob: Job? = null
    private var allCollections: List<UserCollectionBo> = emptyList()
    private var generation = 0
    private var pendingReorders = 1
    /** 显式刷新要求重取逐集状态、而那一轮筛选还没算完：记到下一次算完的筛选里，被新一轮筛选取消也不丢。 */
    private var pendingEpisodeRefresh = false
    /** 每页上次排定的顺序（条目 id）；null = 下一次 [applyFilter] 重新排序。 */
    private val orders = MutableList<List<Int>?>(PROGRESS_TYPES.size) { null }
    /** 正在请求逐集状态的条目：只做在途去重，请求结束后移除。 */
    private val episodeLoads = mutableSetOf<Int>()
    /** 已成功取过逐集状态的条目：普通列表更新不再重复请求，显式刷新仍会覆盖。 */
    private val episodeStatesLoaded = mutableSetOf<Int>()
    /** 逐集请求在途时收到的显式刷新：当前请求结束后再取一次，不静默丢掉。 */
    private val pendingEpisodeRefreshes = mutableSetOf<Int>()
    private val episodeLoadPermits = Semaphore(EPISODE_LOAD_CONCURRENCY)

    init {
        viewModelScope.launch {
            appTime.today.collect { today ->
                updateState { it.copy(today = today) }
                applyFilter()
            }
        }
        follow(failures, { collectionObserver.observeProgress().mapNotNull { (it as? CollectionProgressUpdate.Value)?.load } }) { snapshot ->
            allCollections = snapshot.collections
            updateState {
                it.copy(watchStates = snapshot.episodes.statesBySubject, latestWatchedAt = snapshot.episodes.latestWatchedAtBySubject)
            }
            applyFilter()
        }
        viewModelScope.launch {
            epTapCoordinator.pending.collect { map ->
                updateState { it.copy(pendingVisuals = map) }
            }
        }
        viewModelScope.launch {
            epTapCoordinator.errors.collect { error ->
                if (error.origin == ProgressOrigin.PROGRESS) failures.post(error.failure)
            }
        }
        viewModelScope.launch {
            // 首次收集到的是当前最新请求（默认视图，或首页已发出的请求），初始加载也由它触发。
            // 默认视图开在设置里选的分类：每次建页读一次，之后在设置里改它不动已经开着的页。
            entryRequests.latest.collectLatest { request -> resolveEntry(request) }
        }
    }

    private suspend fun resolveEntry(request: ProgressEntryRequest) {
        if (!entryStillValid(request)) return
        val result = if (request.isDefaultView) {
            storageOutcomeOf { preferencesRepository.progressDefaultType.first() }
        } else {
            Outcome.Success(request.type)
        }
        if (!entryStillValid(request)) return
        when (result) {
            is Outcome.Success -> enter(result.value)
            is Outcome.Failed -> failures.post(result.failure, retry = { viewModelScope.launch { resolveEntry(request) } })
        }
    }

    private fun entryStillValid(request: ProgressEntryRequest): Boolean =
        entryRequests.latest.value == request && (!request.isDefaultView || !currentState.opened)

    /**
     * 处理一次入口请求（从首页进入，或建页时的默认视图）：切到 [type]（null = 全部），搜索、收藏状态、标签与分类都回到默认，重新拉取。
     * **不动排序与方向**——排序不是筛选，用户调好的排序不因从首页再进来而丢掉。
     */
    private fun enter(type: SubjectType?) {
        val index = PROGRESS_TYPES.indexOf(type).coerceAtLeast(0)
        updateState {
            it.copy(
                selectedSubjectType = index,
                opened = true,
                query = "",
                collectionStatus = CollectionType.DOING,
                selectedTags = emptySet(),
                selectedCategories = emptySet(),
                syncedTypes = emptySet(),
            )
        }
        reorderAll()
        applyFilter(); load()
    }

    /**
     * 条目进度计数（唯一判定点 [EpisodeTracking.progressCount]）。
     * 这一轮的章节或逐集请求失败了（[ProgressUiState.episodesFailed]）且逐集数据不齐时，计数回落到收藏记录，不停在骨架上。
     *
     * 界面层直接使用本函数返回的 [ProgressCount]，**禁止在 UI 层写 when(type)**。
     */
    fun progressCount(item: UserCollectionBo, state: ProgressUiState): ProgressCount {
        val episodes = state.episodesMap[item.subjectId]
        val watchStates = state.watchStatesOf(item.subjectId)
        return EpisodeTracking.progressCount(
            subjectType = item.subjectType,
            episodes = episodes,
            watchStates = watchStates,
            epStatus = item.epStatus,
            volStatus = item.volStatus,
            totalEps = item.subject?.eps ?: 0,
            totalVols = item.subject?.volumes ?: 0,
            collectionType = item.type,
            loadFailed = item.subjectId in state.episodesFailed,
        )
    }

    /**
     * EP 按钮状态（唯一判定点 [EpisodeTracking.epButtonState]）。
     *
     * 只有留槽位的行（[EpisodeTracking.reservesEpSlot]：带进度的类型且「在看」）有按钮。动画/三次元在章节请求返回、逐集状态也到齐之前是 [EpButtonState.Loading]——
     * 状态未知时算出来的「下一集」必然是第 1 集，那是错的。
     * 数据到了哪一步的唯一判定点是 [episodeDataState]，此处不得再写一遍；判的是**请求有没有结果**
     * （章节成功：[ProgressUiState.episodesMap] 里有这个键；逐集成功：[ProgressUiState.watchStatesOf] 不为 null；
     * 任一失败：[ProgressUiState.episodesFailed]），不是列表是不是空、逐集表里有没有行。
     * 失败后槽位留空（不挂永久占位），重试成功再变回按钮。
     */
    fun epButtonState(item: UserCollectionBo, state: ProgressUiState): EpButtonState {
        if (!EpisodeTracking.reservesEpSlot(item.subjectType, item.type)) return EpButtonState.Empty
        val episodes = state.episodesMap[item.subjectId]
        val watchStates = state.watchStatesOf(item.subjectId)
        return when (episodeDataState(item.subjectType, episodes, watchStates, item.subjectId in state.episodesFailed)) {
            EpisodeDataState.LOADING -> EpButtonState.Loading
            EpisodeDataState.FAILED -> EpButtonState.Empty
            EpisodeDataState.READY -> EpisodeTracking.epButtonState(
                subjectType = item.subjectType,
                episodes = episodes.orEmpty(),
                watchStates = watchStates.orEmpty(),
                today = state.today,
                epStatus = item.epStatus,
                volStatus = item.volStatus,
                totalEps = item.subject?.eps ?: 0,
                totalVols = item.subject?.volumes ?: 0,
            )
        }
    }

    /**
     * 进度条是否画成波浪（原型：连载中波浪、其余直线）。判定唯一经 [EpisodeTracking.isOngoing]；
     * 章节尚未加载（含书籍等不按集推进的类型）时为 false，画直线。
     */
    fun isOngoing(item: UserCollectionBo, state: ProgressUiState): Boolean =
        EpisodeTracking.isOngoing(state.episodesMap[item.subjectId].orEmpty(), state.today)

    /** 长按一行：打开这部的收藏面板（与详情页 FAB 同一个面板）。 */
    fun openCollectionSheet(subjectId: Int) = collectionSheet.open(subjectId)

    fun saveCollection(edits: CollectionEdits) = collectionSheet.save(edits)

    fun removeCollection() = collectionSheet.remove()

    fun closeCollectionSheet(edits: CollectionEdits) = collectionSheet.close(edits)

    fun retryCollectionEpisodes() = collectionSheet.retryEpisodes()

    /** 页内操作先于默认入口时，以用户选的页面为准并启动它的加载。 */
    private fun editView(
        reorder: Boolean = false,
        reload: Boolean = false,
        refresh: Boolean = false,
        change: (ProgressUiState) -> ProgressUiState,
    ) {
        val wasUnopened = !currentState.opened
        updateState { change(it).copy(opened = true) }
        if (reorder) reorderAll()
        applyFilter(forceEpisodeRefresh = refresh)
        if (reload || wasUnopened) load(refresh)
    }

    fun onQueryChange(value: String) {
        if (value == currentState.query) return
        editView { it.copy(query = value) }
    }
    /** 切到第 [index] 页（点 tab 或手指翻页落定）：各页的顺序不动，只同步新的这一页。 */
    fun onSelectSubjectType(index: Int) {
        if (index !in PROGRESS_TYPES.indices || (currentState.opened && index == currentState.selectedSubjectType)) return
        editView(reload = true) { it.copy(selectedSubjectType = index) }
    }
    fun onSelectStatus(status: CollectionType) {
        if (status == CollectionType.UNKNOWN || (currentState.opened && status == currentState.collectionStatus)) return
        editView(reorder = true, reload = true) { it.copy(collectionStatus = status, syncedTypes = emptySet()) }
    }
    /**
     * 排序菜单的上一组：换到另一档并回到它的默认方向；点的就是这一页正在用的档不做事（方向在下一组里选）。
     * 比较规则一律走 [sortProgress]（core:model 纯函数）。改档或改方向都算「重新排序」（[reorderAll]，各页回到顶部）。
     */
    fun onSelectSort(sort: ProgressSort) {
        if (currentState.opened && sort == currentState.pageOrder.sort) return
        editView(reorder = true) { it.copy(order = ProgressOrder(sort)) }
    }

    /** 排序菜单的下一组：这一页正在用的档按 [reversed] 的方向排。 */
    fun onSelectDirection(reversed: Boolean) {
        val current = currentState.pageOrder
        if (currentState.opened && reversed == current.reversed) return
        editView(reorder = true) { it.copy(order = current.copy(reversed = reversed)) }
    }

    /** 打开筛选面板（[ProgressUiState.filterPanelSession] 加一）：记下此刻已选的标签，面板开着时它们排在最前、不随点选挪位置。 */
    fun openFilters() = updateState { it.copy(filterPanelPinned = it.selectedTags, filterPanelSession = it.filterPanelSession + 1) }

    /** 第 [session] 次打开的筛选面板滑下去了；之后又打开过（[ProgressUiState.filterPanelSession] 已经变了）就不关。 */
    fun closeFilters(session: Int) = updateState { if (session == it.filterPanelSession) it.copy(filterPanelPinned = null) else it }

    /** 反转一个「标签」标签。 */
    fun onToggleTag(tag: String) {
        editView(reorder = true) { it.copy(selectedTags = it.selectedTags.toggled(tag)) }
    }

    /** 反转一个「分类」取值。 */
    fun onToggleCategory(category: ProgressCategory) {
        editView(reorder = true) { it.copy(selectedCategories = it.selectedCategories.toggled(category)) }
    }
    /**
     * 筛选面板的「清除」：只清标签与分类，也就是面板里能选的东西。搜索词与收藏状态不在面板里，不动；
     * 排序不是筛选，也不动。
     */
    fun clearPanelFilters() {
        editView(reorder = true) { it.copy(selectedTags = emptySet(), selectedCategories = emptySet()) }
    }
    fun refresh() {
        // 立刻按现有数据重排（不等网络回来的那次发射——数据没变时 Room 未必再发射，
        // 重排就会拖到下一次无关的数据变化，比如一次 EP 点击，届时条目反而跳位）。
        editView(reorder = true, reload = true, refresh = true) { it }
    }

    /**
     * 列表一出来就为在看、且当前 tab + 收藏状态下的动画/三次元预取章节与逐集状态
     * （并发有上限，同一条目同一时刻只请求一次；显式刷新覆盖已成功加载的条目）。
     *
     * 传的是**未过本面板筛的那一批**（[applyFilter] 传 `matching`，不是 `ordered`）：
     * 「分类」「标签」的可选项来自这一批，若只预取已经显示出来的条目，
     * 被筛掉的条目永远拿不到逐集数据。顺序仍是列表顺序，首屏条目最先就绪。
     *
     * 不等条目滑进屏幕再拉：那样 EP 按钮要在条目出现后「缓一下」才凭空冒出来（实机上出现过）。
     */
    private fun prefetchEpisodes(items: List<UserCollectionBo>, forceRefresh: Boolean = false) {
        for (item in items) {
            if (!EpisodeTracking.loadsEpisodeStates(item.subjectType, item.type)) continue
            val id = item.subjectId
            // 成功过的不重取；失败过的等用户明确刷新再试，章节变化引出的重筛不自行重试
            if (!forceRefresh && (id in episodeStatesLoaded || id in currentState.episodesFailed)) continue
            if (!episodeLoads.add(id)) {
                if (forceRefresh) pendingEpisodeRefreshes.add(id)
                continue
            }
            viewModelScope.launch {
                var force = forceRefresh
                while (true) {
                    val failure = episodeLoadPermits.withPermit { loadEpisodes(item, force) }
                    if (force && failure != null) {
                        // 收藏同步失败已有本轮提示；等它落定（不占并发名额）后只在没有更高优先级的同步失败时补逐集失败。
                        loadJob?.join()
                        if (currentState.syncError == null) failures.post(failure, retry = ::refresh)
                    }
                    if (!pendingEpisodeRefreshes.remove(id)) {
                        episodeLoads.remove(id)
                        return@launch
                    }
                    force = true
                }
            }
        }
    }

    /**
     * 取一部的章节与逐集状态。首次加载在本地两样都没有时走一个端点；显式刷新始终重取逐集状态。
     * 失败保留已有内容并返回失败原因，由调用方决定是否提示（只有显式刷新提示）。
     */
    private suspend fun loadEpisodes(item: UserCollectionBo, forceRefresh: Boolean): Failure? {
        val id = item.subjectId
        val needsStates = forceRefresh || id !in currentState.watchStates
        // 章节与逐集状态本地都还没有：用户逐集端点一路分页就带回两样（[WatchStateStore.refreshEpisodeHistory]），
        // 不必另从 /v0/episodes 取章节。本地已有章节时先出章节（进度条的波浪只看章节），再补逐集状态。
        val oneTrip = needsStates && !subjectRepository.hasCachedEpisodes(id)
        val episodes = if (oneTrip) {
            watchStateStore.refreshEpisodeHistory(id).map { it.episodes }
        } else {
            subjectRepository.getEpisodes(id).settled()
        }
        updateState {
            if (episodes is Outcome.Success) it.copy(episodesMap = it.episodesMap + (id to episodes.value))
            else it.copy(episodesFailed = it.episodesFailed + id)
        }
        if (episodes is Outcome.Success) applyFilter()
        val states = if (needsStates && !oneTrip) watchStateStore.refreshEpisodeWatchStates(id) else null
        // 逐集请求返回了零行：一次请求那一路的章节与逐集行出自同一份响应、一一对应，零章节就是零行
        val noRows = (oneTrip && episodes is Outcome.Success && episodes.value.isEmpty()) ||
            (states is Outcome.Success && states.value.isEmpty())
        // 两个请求有一个失败，这一轮就算失败；失败标记留到下一轮全部成功才摘，重试途中槽位一直留空、不先跳回占位
        val failed = episodes !is Outcome.Success || states is Outcome.Failed
        if (!failed) episodeStatesLoaded.add(id)
        updateState {
            it.copy(
                emptyWatchStates = when {
                    noRows -> it.emptyWatchStates + id
                    failed -> it.emptyWatchStates
                    else -> it.emptyWatchStates - id
                },
                episodesFailed = if (failed) it.episodesFailed + id else it.episodesFailed - id,
            )
        }
        return (episodes as? Outcome.Failed)?.failure ?: (states as? Outcome.Failed)?.failure
    }

    fun onEpButtonTap(item: UserCollectionBo) {
        epTapCoordinator.advance(
            origin = ProgressOrigin.PROGRESS,
            subject = ProgressSubject(
                subjectId = item.subjectId,
                subjectType = item.subjectType,
                episodes = currentState.episodesMap[item.subjectId].orEmpty(),
                totalEps = item.subject?.eps ?: 0,
                totalVols = item.subject?.volumes ?: 0,
            ),
            today = currentState.today,
        )
    }

    /** 章节按钮完结后的「标为看过」：与收藏面板同一条保存路径（[CollectionSheetController.markDone]）。 */
    fun markDone(item: UserCollectionBo) = collectionSheet.markDone(item.subjectId)

    /**
     * 同步当前类型 tab 与收藏状态下的收藏；「重试」与下拉刷新共用本入口（[refresh]）。
     *
     * ## 错误按类型记录
     *
     * 失败记进 [ProgressUiState.syncErrors]（类型 → 原因）。某类型**同步成功**时只清它自己的条目；
     * 开始新一轮时也**不**清掉仍在本轮范围内的类型的旧错误——否则重试刚发出，别的类型先回包，
     * 失败就先被抹掉，等这个类型再失败又冒出来；只有它自己成功，失败才该清。
     *
     * 一轮结束时这一页已有条目，这一轮的失败合成一条提示（按类型固定顺序取第一个，带重试）；
     * 没有条目时不提示，页面画重试块（[ProgressUiState.shapeOf]）。
     * 不在本轮范围内的类型（切到单个类型的 tab）的旧错误与当前页面无关，随新一轮丢弃。
     *
     * 命中缓存的回包（`fromCache = true`）只是先回显旧数据，网络同步还在后面，不算同步成功。
     *
     * ## 重试选整页重来
     *
     * 「重试」重跑本页全部类型，而不是只重跑失败的类型：它与下拉刷新是同一个入口，
     * 不另开第二条带自己的取消、代次与收尾的加载路径（红线三）；成功类型的重复同步只是一次幂等的全量覆盖。
     */
    private fun load(refresh: Boolean = false) {
        loadJob?.cancel()
        val request = ++generation
        val selected = PROGRESS_TYPES[currentState.selectedSubjectType]
        val types = selected?.let(::listOf) ?: ContentDomains
        val status = currentState.collectionStatus
        updateState {
            it.copy(
                isRefreshing = refresh,
                isLoading = true,
                syncErrors = it.syncErrors.filterKeys { type -> type in types },
            )
        }
        loadJob = viewModelScope.launch {
            val roundFailures = mutableMapOf<SubjectType, Failure>()
            coroutineScope {
                types.forEach { type -> launch {
                    collectionStore.getUserCollections(type, status, refresh).collect { result ->
                        if (request == generation) when (result) {
                            is Load.Ready -> if (!result.fromCache) updateState {
                                it.copy(syncErrors = it.syncErrors - type, syncedTypes = it.syncedTypes + type)
                            }
                            is Load.Failed -> {
                                roundFailures[type] = result.failure
                                updateState { it.copy(syncErrors = it.syncErrors + (type to result.failure)) }
                            }
                            Load.Pending -> Unit
                        }
                    }
                } }
            }
            if (request != generation) return@launch
            // 当前页是否有内容由筛选结果决定；筛选仍在后台时不能拿上一页的内容判断失败提示。
            while (true) {
                val pending = filterJob
                pending?.join()
                if (pending === filterJob) break
            }
            if (request != generation) return@launch
            updateState { it.copy(isLoading = false, isRefreshing = false) }
            val failure = ContentDomains.firstNotNullOfOrNull { roundFailures[it] }
            if (failure != null && currentState.collections.isNotEmpty()) failures.post(failure, retry = ::refresh)
        }
    }

    /** 条件变了：每一页下一次 [applyFilter] 都重新排序、回到顶部。 */
    private fun reorderAll() {
        orders.fill(null)
        pendingReorders++
    }

    /**
     * 每一页都经 [progressPageOf] 筛出列表，再经 [stableOrder] 保持各自的原位；选中页的标签与分类可选项、
     * 收敛后的已选项写回状态（面板与数字徽标读它们）。
     *
     * ## 已选标签与已选分类的单点收敛
     *
     * 可选项随类型 tab 与收藏状态变化。已选项若不在这一批里，继续拿它筛会让列表缺项、面板里却看不到任何已选的格子——
     * 正是「偷偷筛着」。因此每页都按「已选 ∩ 这一页的可选项」筛（[progressPageOf]），选中页再把收敛后的集合写回状态：
     * 翻到别的类型时，上一个类型才有的标签与分类随之清掉（各类型词典互不通用），翻页途中邻页的样子与落定后一致。
     *
     * 只在**这一处**做：切 tab、切收藏状态、改条件都经过本函数。
     */
    private fun applyFilter(forceEpisodeRefresh: Boolean = false) {
        filterJob?.cancel()
        if (forceEpisodeRefresh) pendingEpisodeRefresh = true
        val state = currentState
        val collections = allCollections
        val priorOrders = orders.toList()
        val reorders = pendingReorders
        filterJob = viewModelScope.launch {
            val (results, pages) = withContext(computationDispatcher) { progressPagesOf(collections, state, priorOrders) }
            pages.forEachIndexed { page, items -> orders[page] = items.map { it.subjectId } }
            pendingReorders = 0
            val forceEpisodes = pendingEpisodeRefresh
            pendingEpisodeRefresh = false
            val selected = results[state.selectedSubjectType]
            updateState { it.copy(
                pages = pages,
                tagOptions = selected.tagOptions,
                selectedTags = selected.selectedTags,
                categoryGroups = selected.categoryGroups,
                selectedCategories = selected.selectedCategories,
                pageEpochs = it.pageEpochs.map { epoch -> epoch + reorders },
            ) }
            prefetchEpisodes(selected.matching, forceEpisodes)
        }
    }
}

/** 一次以同一份输入计算全部页及其稳定顺序。 */
private suspend fun progressPagesOf(
    collections: List<UserCollectionBo>,
    state: ProgressUiState,
    orders: List<List<Int>?>,
): Pair<List<ProgressPage>, List<List<UserCollectionBo>>> {
    val results = PROGRESS_TYPES.map { type ->
        coroutineContext.ensureActive()
        progressPageOf(
            type, collections, state.collectionStatus, state.query, state.selectedTags,
            state.selectedCategories, state.order, state.latestWatchedAt, state.episodesMap, state.today,
        )
    }
    return results to results.mapIndexed { page, result -> stableOrder(orders[page], result.items) { it.subjectId } }
}

/** 一页筛出来的结果：列表、这一页的可选项与收敛后的已选项（见 [progressPageOf]）。 */
internal class ProgressPage(
    /** 只按类型与收藏状态取的这一批：可选项出自它，章节预取也按它（被筛掉的条目也要有逐集数据）。 */
    val matching: List<UserCollectionBo>,
    val tagOptions: List<ProgressFilterOption<String>>,
    val categoryGroups: List<ProgressCategoryGroup>,
    val selectedTags: Set<String>,
    val selectedCategories: Set<ProgressCategory>,
    /** 排好序的列表（还没过 [stableOrder]）。 */
    val items: List<UserCollectionBo>,
)

/**
 * 一页（[type]，null = 全部）在当前条件下的样子，选中页与邻页都经这里：按收藏状态取一批，
 * 「标签」「分类」按这一页的可选项收敛后筛（标签并集 [matchesTagFilter]，分类同维并集、跨维交集
 * [matchesCategoryFilter]），再按搜索词筛、按这一页实际的排序（[ProgressOrder.on]）经 [sortProgress] 排。
 * 一个判定都不在这里重写。
 */
internal fun progressPageOf(
    type: SubjectType?,
    all: List<UserCollectionBo>,
    status: CollectionType,
    query: String,
    selectedTags: Set<String>,
    selectedCategories: Set<ProgressCategory>,
    order: ProgressOrder,
    latestWatchedAt: Map<Int, Long>,
    episodes: Map<Int, List<EpisodeBo>>,
    today: LocalDate,
): ProgressPage {
    val matching = all.filter { (type == null || it.subjectType == type) && it.type == status }
    // 选项来自「未被本面板筛过」的这一批：选中一个取值不会让其余取值消失（否则用户再也取消不掉已选的项）。
    val tagOptions = availableProgressTags(matching, latestWatchedAt)
    val categoryGroups = availableProgressCategories(type, matching, latestWatchedAt)
    val availableCategories = categoryGroups.flatMap { group -> group.options.map { it.key } }.toSet()
    val tags = selectedTags intersect tagOptions.map { it.key }.toSet()
    val categories = selectedCategories intersect availableCategories
    val keyword = query.trim()
    val filtered = matching.filter { item ->
        matchesTagFilter(item.tags, tags) &&
            matchesCategoryFilter(item.subject?.metaTags.orEmpty(), categories) &&
            (keyword.isEmpty() || item.subject?.let { it.nameCn.contains(keyword, true) || it.name.contains(keyword, true) } == true)
    }
    val items = sortProgress(filtered, order.on(type), today, latestWatchedAt, episodes)
    return ProgressPage(matching, tagOptions, categoryGroups, tags, categories, items)
}

/** 有就去掉、没有就加上。 */
private fun <T> Set<T>.toggled(value: T): Set<T> = if (value in this) this - value else this + value
