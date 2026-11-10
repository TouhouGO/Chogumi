package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.progress.EpTapCoordinator
import io.github.touhougo.chogumi.core.data.progress.ProgressOrigin
import io.github.touhougo.chogumi.core.data.progress.ProgressSubject
import io.github.touhougo.chogumi.core.data.repository.CollectionObserver
import io.github.touhougo.chogumi.core.data.repository.CollectionStore
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.model.StatsModule
import io.github.touhougo.chogumi.core.model.defaultStatsLayout
import io.github.touhougo.chogumi.core.ui.arrange.ArrangeState
import io.github.touhougo.chogumi.core.data.repository.StatsLayoutRepository
import io.github.touhougo.chogumi.core.data.repository.SubjectRepository
import io.github.touhougo.chogumi.core.data.repository.WatchStateStore
import io.github.touhougo.chogumi.core.data.util.loadOnce
import io.github.touhougo.chogumi.core.data.util.storageAsLoad
import io.github.touhougo.chogumi.core.foundation.AppTime
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.advance
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.orEmpty
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.CollectionEditing
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionMarker
import io.github.touhougo.chogumi.core.model.CollectionWork
import io.github.touhougo.chogumi.core.model.EpPendingVisual
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeProgressTarget
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.RelatedCharacterBo
import io.github.touhougo.chogumi.core.model.RelatedPersonBo
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectInfoPartition
import io.github.touhougo.chogumi.core.model.SubjectRatingSplit
import io.github.touhougo.chogumi.core.model.SubjectRelationBo
import io.github.touhougo.chogumi.core.model.SubjectSeries
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.statistics.subjectStatsOf
import io.github.touhougo.chogumi.core.model.subjectInfoPartition
import io.github.touhougo.chogumi.core.model.subjectTypeSpec
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.CollectionSheetController
import io.github.touhougo.chogumi.core.ui.collection.CollectionSheetState
import io.github.touhougo.chogumi.core.ui.collection.CollectionWorkSource
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkController
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkState
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 详情页收藏 FAB 的四态，由收藏这一区块（[SubjectDetailsUiState.collection]）与收藏写入在途（[SubjectDetailsUiState.collectionWrite]）
 * 投影而来，同一时刻必居其一。SAVING 优先：写入在途时 FAB 显示 loading 圈 + 原状态文案，填补「发出写入到新状态上屏」之间的反馈空窗。
 */
enum class CollectionFabState(
    /**
     * 忙（在等结果）：按钮内嵌 loading，点了什么也不做，读屏念「不可用」。
     * 开面板与读屏状态都只看这一个判定（[SubjectDetailsViewModel.openEditSheet]、`CollectionFab`）。
     */
    val busy: Boolean,
) {
    /** 收藏写入在途（面板保存、点集替用户建收藏）：按钮可见，内嵌 loading + 上一状态文案；点击不开面板。 */
    SAVING(busy = true),

    /** 收藏还没给出结果（没有缓存、网络在途）：按钮可见但内嵌 loading，点击不开面板。 */
    LOADING(busy = true),

    /** 收藏已就绪（含确认还没收藏）：可以开面板。 */
    READY(busy = false),

    /** 收藏取不到（非 404 失败且没有缓存）：按钮可见，点击提示并给重试，绝不开空壳面板。 */
    SETTLED_NO_DATA(busy = false),
}

data class SubjectDetailsUiState(
    /** 条目本体（SWR）：决定整页的加载与失败；取不到且没有内容可留时整页报错 + 重试，已有内容时的失败走 [SubjectDetailsViewModel.failures]。 */
    val subjectLoad: Load<SubjectBo> = Load.Pending,
    /**
     * 自己对这部作品的收藏：就绪的 null = 确认还没收藏（服务端 404）。取不到时已显示的留着（[Load.advance]）——
     * 已就绪的收藏不会被之后的失败打回「取不到」，未就绪时绝不拿默认值开面板。
     */
    val collection: Load<UserCollectionBo?> = Load.Pending,
    /** 章节、关联、角色、制作人员：各是一拍 [Load]，各自加载、失败只落在本区块（初值都是「等待」）。 */
    val episodes: Load<List<EpisodeBo>> = Load.Pending,
    val episodeWatchStates: Map<Int, EpisodeStatus> = emptyMap(),
    /** 本条目停手待提交的这一轮（[EpTapCoordinator.pending]）：章节方格「看到这里」那一格的倒计时环朝它的提交时刻走。 */
    val pendingCommit: EpPendingVisual? = null,
    val relations: Load<List<SubjectRelationBo>> = Load.Pending,
    val characters: Load<List<RelatedCharacterBo>> = Load.Pending,
    val persons: Load<List<RelatedPersonBo>> = Load.Pending,
    /** 系列（沿前传、续集走出的同类型作品）：概览与统计 tab 共用，条目本体到了（知道类型）才取。 */
    val series: Load<SubjectSeries> = Load.Pending,
    /**
     * 只有统计 tab 用的三项：网页透视页的评分拆分、推荐作品、最近一批收藏的时刻。第一次切到统计 tab 才取
     * （透视页是服务端现算的网页，不为没看统计的人去要），之前一直是「等待」。
     */
    val ratingSplit: Load<SubjectRatingSplit> = Load.Pending,
    val recs: Load<List<SubjectBo>> = Load.Pending,
    val recentCollectTimes: Load<List<Long>> = Load.Pending,
    /** 用户最后点选的选项卡。实际选中的是它经 [resolveSection] 纠偏后的 [selectedSection]。 */
    val requestedSection: SubjectDetailsSection = SubjectDetailsSection.OVERVIEW,
    /**
     * 功能可见性（完整 / 专注、公共评分开 / 关）：真源是偏好 DataStore，经 [PreferencesRepository.appFeatures] 提供。
     * 选项卡判定（[resolveSection] / [visibleSectionsFor]）与渲染共用这一个值，界面不另做筛除。
     */
    val features: AppFeatures = AppFeatures(community = true, publicRatings = true, coverScores = true, friendMarks = true),
    val isRefreshing: Boolean = false,
    val statsLayoutLoad: Load<List<StatsModule>> = Load.Ready(defaultStatsLayout()),
    val statsArrange: ArrangeState<StatsEditor> = ArrangeState(),
    /** 收藏面板（开没开、暂存的修改、保存在途），只由 [CollectionSheetController] 改。 */
    val collectionSheet: CollectionSheetState = CollectionSheetState(),
    /**
     * 一笔改收藏状态的写入结束了、新的收藏还没回读到 [collection]：面板保存成功之后，或点集替用户建收藏（[EpTapCoordinator.creatingCollection]）
     * 从发出起。FAB 一直忙到回读的第一拍上屏，从「保存中」直接过渡到新状态，中间不闪回旧状态。
     */
    val collectionWrite: Boolean = false,
    /** 关联作品海报的长按标记（标的是关联的那一部），只由 [SubjectDetailsViewModel.posterMarks] 改。 */
    val posterMarks: PosterMarkState = PosterMarkState(),
) {
    val statsLayout: List<StatsModule> get() = statsLayoutLoad.valueOrNull() ?: defaultStatsLayout()

    val subject: SubjectBo? get() = subjectLoad.valueOrNull()

    /** 条目本体还没回来（整页骨架）。 */
    val isLoading: Boolean get() = subjectLoad is Load.Pending

    /** 条目本身取不到（且没有缓存）：整页报错 + 重试。 */
    val error: Failure? get() = subjectLoad.failureOrNull()

    /** 收藏操作四态，见 [CollectionFabState]。派生属性，不参与 data class 相等性。 */
    val fabState: CollectionFabState
        get() = when {
            collectionSheet.saving || collectionWrite -> CollectionFabState.SAVING
            else -> when (collection) {
                Load.Pending -> CollectionFabState.LOADING
                is Load.Ready -> CollectionFabState.READY
                is Load.Failed -> CollectionFabState.SETTLED_NO_DATA
            }
        }

    /** 条目的总话数、总卷数（规格表：话数取 `eps`，卷数取 `volumes`；未知为 0）：进度写入用。 */
    private val totalEps: Int get() = subject?.eps ?: 0
    private val totalVols: Int get() = subject?.volumes ?: 0

    /** 收藏面板改的就是本页这部作品：作品与收藏都到了才有，面板显示、保存与「标为看过」的判定都从它取。 */
    val collectionWork: CollectionWork? by lazy {
        val collection = collection as? Load.Ready ?: return@lazy null
        subject?.let { CollectionWork(it, collection.value, episodes, episodeWatchStates) }
    }

    /** 章节方格下方要不要建议「标为看过」：收藏已到位，且按已保存的样子该建议（看完了、还不是看过）。 */
    val offersMarkDone: Boolean
        get() = collectionWork?.editor?.let { it.suggestsDone(it.saved) } == true

    /** 角色区块的三态（[sectionStateOf] 唯一判定）。概览、人物 tab 与 [hasPeople] 共用。 */
    val charactersState: SectionState
        get() = sectionStateOf(characters)

    /** 制作人员区块的三态（同上）。 */
    val personsState: SectionState
        get() = sectionStateOf(persons)

    /** 每个不可变状态实例只分类一次，入口判定、资料面板与人物 tab 共用；不保存第二份业务状态。 */
    val infoPartition: SubjectInfoPartition by lazy {
        subject?.let {
            subjectInfoPartition(it, displayedTitle = displayNameOf(it), linkedStaffPositions = persons.orEmpty().map { person -> person.relation }.toSet())
        } ?: SubjectInfoPartition(emptyList(), emptyList())
    }

    /** 人物 tab 是否可见：关联列表确认为空且没有其他制作人员原文时才隐藏。 */
    val hasPeople: Boolean
        get() = charactersState != SectionState.EMPTY || personsState != SectionState.EMPTY || infoPartition.otherStaffFacts.isNotEmpty()

    /** 可见的选项卡（[visibleSectionsFor] 唯一判定）。 */
    val visibleSections: List<SubjectDetailsSection>
        get() = visibleSectionsFor(subject, features, hasPeople)

    /** 实际选中的选项卡：[requestedSection] 不可见时回落（[resolveSection] 唯一纠偏）。 */
    val selectedSection: SubjectDetailsSection
        get() = resolveSection(requestedSection, subject, features, hasPeople)

    /**
     * 发起进度写入所需的条目信息（全详情页唯一的构造处：章节网格与特别篇共用）。
     * 书籍按规格表固定记卷（[io.github.touhougo.chogumi.core.model.ProgressUnit.bookUnitOverride]）。
     */
    fun progressSubject(subjectId: Int): ProgressSubject = ProgressSubject(
        subjectId = subjectId,
        subjectType = subject?.type ?: SubjectType.ANIME,
        episodes = episodes.orEmpty(),
        totalEps = totalEps,
        totalVols = totalVols,
        hasCollection = collection.valueOrNull() != null,
        bookUnitOverride = subjectTypeSpec(subject?.type).progressUnit?.bookUnitOverride,
    )
}

/** 条目详情 ViewModel：详情/章节/收藏/关联/角色 五路数据 */
class SubjectDetailsViewModel(
    // 导航参数：页面经 koinViewModel { parametersOf(subjectId) } 传入，注册见 SubjectDetailsModule
    private val subjectId: Int,
    private val subjectRepository: SubjectRepository,
    private val collectionStore: CollectionStore,
    private val collectionObserver: CollectionObserver,
    private val watchStateStore: WatchStateStore,
    private val collectionEditing: CollectionEditing,
    private val epTapCoordinator: EpTapCoordinator,
    /** 体验模式真源：选项卡可用集合由它经 features() 派生。 */
    private val preferencesRepository: PreferencesRepository,
    appTime: AppTime,
    collectionMarker: CollectionMarker,
    /** 用户点选的选项卡跟着页面的存档走：进程被回收后回到这一页，还停在原来的 tab 上。 */
    private val savedState: SavedStateHandle,
    statsLayouts: StatsLayoutRepository,
) : StateViewModel<SubjectDetailsUiState>(
    SubjectDetailsUiState(
        requestedSection = savedState.get<String>(REQUESTED_SECTION)
            ?.let { name -> SubjectDetailsSection.entries.firstOrNull { it.name == name } }
            ?: SubjectDetailsSection.OVERVIEW,
    ),
) {

    /** 业务日（[AppTime.today]）：章节网格、进度区按它判已播 / 未播，停在页面上跨零点也随之变。 */
    val today: StateFlow<LocalDate> = appTime.today

    /**
     * 统计 tab 的一拍（各张卡由 [subjectStatsOf] 唯一计算）：统计读的那部分状态（[statsSourceOf]）、业务日与当前时刻任一变了就重算，
     * 只在统计 tab 收集时计算。条目本体还没到为 null。
     */
    internal val stats: StateFlow<SubjectStatsTab?> = combine(
        uiState.map(::statsSourceOf).distinctUntilChanged(),
        appTime.today,
        appTime.now,
    ) { source, today, now ->
        source?.let { statsTabOf(it, subjectStatsOf(it.input, today = today, nowMillis = now)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** 统计 tab 的三项开始取了没有：第一次切到统计 tab 时取，之后下拉刷新随其他区块一起刷新。 */
    private var statsRequested = false

    /** 下拉刷新的次数：系列在关联内容没变时也据此重走（[observeSeries]）。 */
    private val seriesRefreshes = MutableStateFlow(0)

    /** 本页的失败提示：收藏 / 进度保存失败、收藏信息没取到、有内容时刷新失败、章节写入失败。 */
    val failures = FailureNotices()

    internal val statsArranger = StatsArranger(viewModelScope, statsLayouts,
        read = { currentState.statsArrange },
        write = { change -> updateState { it.copy(statsArrange = change(it.statsArrange)) } },
        currentLayout = { currentState.statsLayout }, failures = failures)

    init {
        launchSection("stats-layout", failures, { statsLayouts.observe().storageAsLoad() }, { it.statsLayoutLoad }) { copy(statsLayoutLoad = it) }
    }

    /** 收藏面板：作品就是本页这部（[SubjectDetailsUiState.collectionWork]），保存成功后回读收藏。 */
    private val collectionSheet = CollectionSheetController(
        scope = viewModelScope,
        read = { currentState.collectionSheet },
        write = { change -> updateState { it.copy(collectionSheet = change(it.collectionSheet)) } },
        failures = failures,
        source = CollectionWorkSource.OnPage(current = { currentState.collectionWork }, retryEpisodes = ::retryEpisodes),
        save = collectionEditing::save,
        remove = collectionEditing::remove,
        onSaved = ::reloadAfterWrite,
    )

    /** 关联作品海报的长按标记：标状态、撤销、关联那一部的收藏管理面板。 */
    val posterMarks = PosterMarkController(
        scope = viewModelScope,
        read = { currentState.posterMarks },
        write = { change -> updateState { it.copy(posterMarks = change(it.posterMarks)) } },
        failures = failures,
        marker = collectionMarker,
        editing = collectionEditing,
        today = appTime.today,
    )

    fun refresh() {
        updateState { it.copy(isRefreshing = true) }
        load(refresh = true)
        seriesRefreshes.update { it + 1 }
    }

    // -----------------------------------------------------------------------
    // 章节方格：点击即乐观更新本地真源，停手合并后由 EpTapCoordinator 提交（失败回滚并报错）
    // -----------------------------------------------------------------------

    /**
     * 章节方格：看到 [target]（显式空目标表示清空；乐观更新，停手合并提交）。
     * 点哪一格是看到哪一集（点「看到这里」是退一集）由方格经 [EpisodeTracking.tapOf] 算好，这里只写。
     */
    fun onWatchUpTo(target: EpisodeProgressTarget) {
        epTapCoordinator.watchUpTo(ProgressOrigin.DETAILS, currentState.progressSubject(subjectId), target)
    }

    /** 特别篇方格：只切换这一集看过与否（不计入正片进度）。 */
    fun onToggleSpecial(episode: EpisodeBo) {
        epTapCoordinator.toggleSpecial(ProgressOrigin.DETAILS, currentState.progressSubject(subjectId), episode)
    }

    /** FAB 点击：只有 [CollectionFabState.READY] 打开收藏面板；忙时什么也不做（见 [CollectionFabState.busy]）。 */
    fun openEditSheet() {
        when (currentState.fabState) {
            CollectionFabState.READY -> collectionSheet.open(subjectId)
            // 收藏取不到（如离线、网络失败）：提示并给重试，绝不打开空壳面板
            CollectionFabState.SETTLED_NO_DATA -> failures.post(R.string.collection_load_failed, retry = ::loadCollection)
            CollectionFabState.LOADING, CollectionFabState.SAVING -> Unit
        }
    }

    /** 面板滑完退场后到这里，见 [CollectionSheetController.close]。 */
    fun removeCollection() = collectionSheet.remove()

    fun closeEditSheet(edits: CollectionEdits) = collectionSheet.close(edits)

    /** 保存面板上的修改，见 [CollectionSheetController.save]：成功后面板退场、回读收藏；失败时面板留着、经 [failures] 提示。 */
    fun onSaveCollection(edits: CollectionEdits) = collectionSheet.save(edits)

    /** 章节方格「全部看完了」卡片：把收藏标为看过，与收藏面板同一条保存路径（忙时不接受）。 */
    fun markDone() = collectionSheet.markDone(subjectId)

    fun selectSection(section: SubjectDetailsSection) {
        savedState[REQUESTED_SECTION] = section.name
        updateState { it.copy(requestedSection = section) }
        if (section == SubjectDetailsSection.STATS) requestStats()
    }

    /** 统计 tab 第一次出现（点到、或存档恢复时就停在它上面）才取它的三项。 */
    private fun requestStats() {
        if (statsRequested) return
        statsRequested = true
        loadStats(refresh = false)
    }

    fun retryEpisodes() = loadEpisodes()

    fun retryPersons() = loadPersons()

    fun retryCharacters() = loadCharacters()

    fun retryRelated() = loadRelations()

    fun retryRatingSplit() = loadRatingSplit(refresh = false)

    fun retryRecs() = loadRecs(refresh = false)

    fun retryRecentCollectTimes() = loadRecentCollectTimes(refresh = false)

    init {
        load()
        observeSeries()
        if (currentState.requestedSection == SubjectDetailsSection.STATS) requestStats()
        // 功能可见性变化时选中态跟着回落（社区 tab 可能刚刚被隐藏）：selectedSection 是派生值，这里只更新真源。
        follow(failures, { preferencesRepository.appFeatures.storageAsLoad() }) { features ->
            updateState { it.copy(features = features) }
        }
        follow(failures, { collectionObserver.observeEpisodeWatchStates(subjectId) }) { states ->
            updateState { it.copy(episodeWatchStates = states) }
        }
        viewModelScope.launch {
            epTapCoordinator.pending
                .map { it[subjectId] }
                .distinctUntilChanged()
                .collect { pending -> updateState { it.copy(pendingCommit = pending) } }
        }
        viewModelScope.launch {
            // 点集替用户建收藏：从发出起 FAB 就忙，到这轮写入结束后的回读上屏为止（下面回读收藏时落下）。
            epTapCoordinator.creatingCollection
                .map { subjectId in it }
                .distinctUntilChanged()
                .filter { it }
                .collect { updateState { it.copy(collectionWrite = true) } }
        }
        viewModelScope.launch {
            // 本条目的这轮写入结束（含失败回滚）后回读收藏：条目级进度计数以服务端为准。
            epTapCoordinator.active
                .map { subjectId in it }
                .distinctUntilChanged()
                .drop(1) // 初值「不在写入中」不是一轮写入的结束
                .collect { active -> if (!active) loadCollection(afterWrite = true) }
        }
        viewModelScope.launch {
            epTapCoordinator.errors.collect { error ->
                if (error.origin == ProgressOrigin.DETAILS && error.subjectId == subjectId) failures.post(error.failure)
            }
        }
    }

    /**
     * 进页与下拉刷新：条目本体与各区块同时发，谁先到谁先上屏。区块的请求只要条目 id，不等本体，
     * 本体的网络拍慢不会把角色、关联、章节一起拖住；状态各写各的字段（[updateState] 是原子更新）。
     */
    private fun load(refresh: Boolean = false) {
        loadSubject(refresh)
        loadCollection()
        loadEpisodes(refresh)
        refreshWatchStates()
        loadRelations(refresh)
        loadCharacters(refresh)
        loadPersons(refresh)
        if (statsRequested) loadStats(refresh)
    }

    /** 条目本体（SWR：缓存拍与网络拍都上屏）。没有内容才整页报错，有内容只提示；失败也要收起下拉转圈。 */
    private fun loadSubject(refresh: Boolean) = launchSection(
        "subject",
        failures,
        { subjectRepository.getSubject(subjectId, refresh) },
        { it.subjectLoad },
        alongside = { load -> if (load is Load.Pending) this else copy(isRefreshing = false) },
    ) { copy(subjectLoad = it) }

    /** 面板保存成功后：FAB 忙到回读上屏（[SubjectDetailsUiState.collectionWrite]）。 */
    private fun reloadAfterWrite() {
        updateState { it.copy(collectionWrite = true) }
        loadCollection(afterWrite = true)
    }

    /**
     * 读收藏。[afterWrite]：这是一笔收藏写入之后的回读，第一拍有结果（就绪或取不到）与写入在途的忙态落下是同一次
     * 状态更新；别的读取（进页、重试）不碰它，免得早发的读取在写入途中把忙态抹掉。
     */
    private fun loadCollection(afterWrite: Boolean = false) {
        // 不按区块取消上一轮：写入后的回读被后来的读取取消，忙态就永远落不下
        viewModelScope.launch {
            collectionStore.getCollection(subjectId).collectSection(
                failures,
                retry = { loadCollection() },
                get = { it.collection },
                alongside = { load -> if (afterWrite && load !is Load.Pending) copy(collectionWrite = false) else this },
            ) { copy(collection = it) }
        }
    }

    // 章节存**完整**列表（含 SP/OP/ED）：网格下方要列特别篇，正片 / 非正片的切分统一走 EpisodeTracking。
    // 四个区块各自独立：并行收集，缓存拍与网络拍到了就上屏（见 launchSection）。

    private fun loadEpisodes(refresh: Boolean = false) =
        launchSection("episodes", failures, { subjectRepository.getEpisodes(subjectId, refresh) }, { it.episodes }) {
            copy(episodes = it)
        }

    /**
     * 逐集状态从服务端重取并落库，方格经 [CollectionObserver.observeEpisodeWatchStates] 跟着变。章节已在屏上时取不到要说：
     * 不然方格停在旧状态上，用户以为刷新过了。未登录本来就没有逐集状态，换了账号这一轮作废，这两种不提示。
     */
    private fun refreshWatchStates() {
        viewModelScope.launch {
            val failure = watchStateStore.refreshEpisodeWatchStates(subjectId).failureOrNull() ?: return@launch
            if (failure == Failure.NotLoggedIn || failure == Failure.SessionChanged) return@launch
            if (currentState.episodes.valueOrNull() != null) failures.post(failure, retry = ::refresh)
        }
    }

    private fun loadRelations(refresh: Boolean = false) =
        launchSection("relations", failures, { subjectRepository.getRelations(subjectId, refresh) }, { it.relations }) {
            copy(relations = it)
        }

    private fun loadCharacters(refresh: Boolean = false) =
        launchSection("characters", failures, { subjectRepository.getCharacters(subjectId, refresh) }, { it.characters }) {
            copy(characters = it)
        }

    private fun loadPersons(refresh: Boolean = false) =
        launchSection("persons", failures, { subjectRepository.getPersons(subjectId, refresh) }, { it.persons }) {
            copy(persons = it)
        }

    /**
     * 系列从本页的关联出发（[SubjectRepository.getSeries]），只走同类型的作品：条目本体（类型）到了、关联的内容每变一次就重走，
     * 不为它再请求一次本页的关联。关联取不到时系列也取不到；关联重试或刷新后内容变了跟着重走。整页只有这一个收集者（在 init 里起）。
     * 缓存拍与网络拍只差来源：过期缓存拍之后到的网络拍内容没变，正在走的这一轮接着走，不取消了从头再来。
     * 下拉刷新（[seriesRefreshes]）关联没变也重走一次：上一轮可能因下游作品的关联取不到而走短了，系列又没有自己的重试。
     */
    private fun observeSeries() {
        viewModelScope.launch {
            val inputs = uiState
                .map { state ->
                    val relations = state.relations.let { if (it is Load.Ready) it.copy(fromCache = false) else it }
                    state.subject?.type?.let { type -> type to relations }
                }
                .filterNotNull()
                .filter { (_, relations) -> relations !is Load.Pending }
                .distinctUntilChanged()
            combine(inputs, seriesRefreshes) { input, _ -> input }
                .collectLatest { (type, relations) ->
                    when (relations) {
                        // 系列跟着关联走，没有单独的重试：提示条只说一声
                        is Load.Ready -> loadOnce { subjectRepository.getSeries(subjectId, type, relations.value) }
                            .collectSection(failures, retry = null, { it.series }) { copy(series = it) }
                        is Load.Failed -> updateState { it.copy(series = it.series.advance(Load.Failed(relations.failure))) }
                        Load.Pending -> Unit
                    }
                }
        }
    }

    private fun loadStats(refresh: Boolean) {
        loadRatingSplit(refresh)
        loadRecs(refresh)
        loadRecentCollectTimes(refresh)
    }

    private fun loadRatingSplit(refresh: Boolean) =
        launchSection("ratingSplit", failures, { subjectRepository.getRatingSplit(subjectId, refresh) }, { it.ratingSplit }) {
            copy(ratingSplit = it)
        }

    private fun loadRecs(refresh: Boolean) =
        launchSection("recs", failures, { subjectRepository.getRecs(subjectId, refresh) }, { it.recs }) { copy(recs = it) }

    private fun loadRecentCollectTimes(refresh: Boolean) =
        launchSection("recentCollectTimes", failures, { subjectRepository.getRecentCollectTimes(subjectId, refresh) }, { it.recentCollectTimes }) {
            copy(recentCollectTimes = it)
        }
}

/** [SubjectDetailsViewModel] 在页面存档里记用户点选的选项卡用的键。 */
private const val REQUESTED_SECTION = "requestedSection"
