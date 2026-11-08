package io.github.touhougo.chogumi.feature.home

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.progress.EpTapCoordinator
import io.github.touhougo.chogumi.core.data.progress.ProgressOrigin
import io.github.touhougo.chogumi.core.data.progress.ProgressSubject
import io.github.touhougo.chogumi.core.data.repository.AiringTimeRepository
import io.github.touhougo.chogumi.core.data.repository.CollectionObserver
import io.github.touhougo.chogumi.core.data.repository.CollectionProgressUpdate
import io.github.touhougo.chogumi.core.data.repository.CollectionStore
import io.github.touhougo.chogumi.core.data.repository.HomeRepository
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.util.loadOutcome
import io.github.touhougo.chogumi.core.data.util.storageAsLoad
import io.github.touhougo.chogumi.core.foundation.AppTime
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.advance
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.AiringMinute
import io.github.touhougo.chogumi.core.model.airingMinuteOf
import io.github.touhougo.chogumi.core.model.CollectionEditing
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.EpPendingVisual
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.FriendSubjectGroupBo
import io.github.touhougo.chogumi.core.model.HomeProgressItemBo
import io.github.touhougo.chogumi.core.model.HomeSection
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.ScheduleDayGroup
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.ToWatchLimit
import io.github.touhougo.chogumi.core.model.ToWatchRank
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.model.aggregateFriendsActivities
import io.github.touhougo.chogumi.core.model.groupScheduleByDay
import io.github.touhougo.chogumi.core.model.lastActiveAt
import io.github.touhougo.chogumi.core.model.stableOrder
import io.github.touhougo.chogumi.core.ui.collection.CollectionSheetController
import io.github.touhougo.chogumi.core.ui.collection.CollectionSheetState
import io.github.touhougo.chogumi.core.ui.collection.CollectionWorkSource
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val FRIENDS_SECTION = "friends"

/** 「继续」区块最多显示几条。**设计决定（非 M3）**：首页不是进度页，其余由区块头「全部」进入进度页。 */
internal const val CONTINUE_LIMIT = 5

/** 一个首页条目进度相关的全部派生结果：按钮状态、进度计数与点击时交给协调器的条目信息。 */
data class HomeProgressCard(
    val subject: SubjectBo,
    val epButtonState: EpButtonState,
    val progressSubject: ProgressSubject,
    val progressCount: ProgressCount,
)

data class HomeToWatchItem(
    val card: HomeProgressCard,
    val airedUnwatchedCount: Int,
)

data class HomeScheduleItem(
    val subject: SubjectBo,
    val nextUpcomingSort: Double,
    val airedUnwatchedCount: Int,
    /** 这一天的播出时刻（bangumi-data 的每周首播时刻按放送日的星期对上才算）；没有就是 null。 */
    val airing: AiringMinute?,
)

data class HomeContinueItem(
    val card: HomeProgressCard,
    /** 收藏表里的这一行：列表行的「N / 总数」从它读（与进度页同源）。 */
    val collection: UserCollectionBo,
)

/**
 * 一次 load 内待看顺序的阶段（只前进不后退）：
 * - [WAITING]：本次结果未到，不合成区块，免得拿上一次 load 的旧快照排序；
 * - [PROVISIONAL]：缓存先到，按最近相关时刻排好先显示，但不记录顺序——网络结果可能改变最近看过时刻；
 * - [FROZEN]：本次网络结果到了（或本次拿不到网络结果：出错、缓存未过期而不请求），按此刻排序并冻结。
 */
private enum class ToWatchOrderPhase { WAITING, PROVISIONAL, FROZEN }

private data class HomeProgressInput(
    val generation: Int,
    val phase: ToWatchOrderPhase,
    val items: List<HomeProgressItemBo>?,
)

data class HomeUiState(
    val toWatch: List<HomeToWatchItem> = emptyList(),
    val scheduleGroups: List<ScheduleDayGroup<HomeScheduleItem>> = emptyList(),
    /** 已按 [continueFilter]（实际生效值）过滤、按首页快照时间倒序、截断到 [CONTINUE_LIMIT] 条。 */
    val continueItems: List<HomeContinueItem> = emptyList(),
    /**
     * 「继续」区实际出现的分类，按全 App 的 [ContentDomains] 顺序；不足两个时为空
     * （此时「全部」与唯一分类内容相同，界面据此隐藏 chip 行）。
     */
    val continueChips: List<SubjectType> = emptyList(),
    /**
     * 「继续」**实际生效**的分类过滤（`null` = 全部）。选中的分类消失时退回 `null`，
     * 「全部」入口也用它——判定只在 [sectionsOf] 里做一次。
     */
    val continueFilter: SubjectType? = null,
    /** 「继续」候选被顶部全覆盖：不画列表与 chip，只留一行「查看全部进度」入口。 */
    val continueCollapsed: Boolean = false,
    val doingItems: List<UserCollectionBo> = emptyList(),
    /** 「好友在看」区块：五类有一类成功即可就绪，全部失败为 Failed；就绪空结果隐藏，等待与失败仍显示反馈。 */
    val friends: Load<List<FriendSubjectGroupBo>> = Load.Pending,
    val pendingVisuals: Map<Int, EpPendingVisual> = emptyMap(),
    /**
     * 首个 `/p1/home` 结果（缓存或网络）：[Load.Pending] 时只画骨架，避免区块后插导致列表停在中间；
     * 无缓存又取不到时是 [Load.Failed]，整页给重试。已有内容时的刷新失败只发提示，这里仍是 [Load.Ready]。
     */
    val progress: Load<Unit> = Load.Pending,
    val refreshing: Boolean = false,
    val showFriends: Boolean = false,
    /** 业务日（[AppTime.today]）：区块与顶部日期按它推出，跨零点随之重算。 */
    val today: LocalDate,
    /** 首页待看顺序是否已进入冻结阶段（网络全量条目完成排序并冻结，后续操作保持列表稳定）。 */
    val toWatchFrozen: Boolean = false,
    /** 长按「继续」一行打开的收藏面板，只由 [CollectionSheetController] 改。 */
    val collectionSheet: CollectionSheetState = CollectionSheetState(),
)

internal data class HomeSections(
    /** 待看轮播**最终展示**的条目（已按冻结顺序排好并截断到 limit）。 */
    val toWatch: List<HomeToWatchItem>,
    /** 冻结后的**全量**待看顺序（含没展示出来的条目）；轮播换内容时按它保持原位，追平离开后由下一名补位。 */
    val toWatchOrder: List<Int>,
    val scheduleGroups: List<ScheduleDayGroup<HomeScheduleItem>>,
    val continueItems: List<HomeContinueItem>,
    val continueChips: List<SubjectType>,
    val continueFilter: SubjectType?,
    /** 「继续」一个候选都不剩（顶部已覆盖全部）且顶部有内容：模块收成一行「查看全部进度」。 */
    val continueCollapsed: Boolean,
    val doingItems: List<UserCollectionBo>,
)

/** 后台准备的不可变候选；不读取冻结顺序，也不决定最终展示与排除集合。 */
internal data class HomeSectionCandidates(
    val toWatch: List<HomeToWatchItem>,
    val scheduleGroups: List<ScheduleDayGroup<HomeScheduleItem>>,
    val continueItems: List<HomeContinueItem>,
    val doingItems: List<UserCollectionBo>,
)

/** 每次选择都有自己的身份，包括「全部」和 A → B → A，旧计算无法清除或覆盖新选择。 */
internal data class ContinueFilterRequest(val revision: Long = 0, val type: SubjectType? = null)

/** 是否仍属于当前选择：所有提交与失效收敛共用这一个判定。 */
internal fun ContinueFilterRequest.isCurrent(current: ContinueFilterRequest): Boolean =
    revision == current.revision

private data class HomeSectionsResult(
    val input: HomeProgressInput,
    val candidates: HomeSectionCandidates?,
    val limit: Int,
    val requestedFilter: ContinueFilterRequest,
    /** 这一轮候选按哪一天算的：发布时一并写进状态，日期与区块同一拍。 */
    val today: LocalDate,
    val observed: Load<*>,
    val snapshotRead: Boolean = false,
    val snapshotFailure: Failure? = null,
)

private fun HomeSectionsResult.isCurrent(input: HomeProgressInput, filter: ContinueFilterRequest): Boolean =
    this.input == input && requestedFilter.isCurrent(filter)

/**
 * 今日首页 ViewModel。
 *
 * 数据：`/p1/home` 进度区块（章节元数据）+ 收藏表（在看状态、书籍话数卷数、游戏音乐）+ 逐集状态与最近看过时刻，
 * 三者在后台准备候选（[prepareSectionCandidates]），主线程串行确定最终展示（[sectionsOf]），区块归属只走 [EpisodeTracking.homeSection]。
 * 进度点击交给 [EpTapCoordinator]（乐观更新、合并提交、失败回滚）。
 *
 * ## 待看只显示前 N 部（[ToWatchLimit]）
 *
 * 「待看」不是全量列表，是快捷标记入口：只保留最可能马上被标记的前 N 部，全量交给进度页。
 * 冻结与截断都在 [sectionsOf] 里做（顺序冻结之后 `take(limit)`）——这样标记一集不会把
 * 别的可见条目挤出去；`limit` 改了只是换取多少，已冻结的次序不动。
 * 冻结记录的是**全量**顺序：某部追平离开待看时，下一名按全量名次补进可见区，而不是甩到末尾。
 * 没进轮播、又不在时间表里的条目回落到「继续」（规则见 [sectionsOf]）。
 */
class HomeViewModel(
    private val homeRepository: HomeRepository,
    private val collectionStore: CollectionStore,
    private val collectionObserver: CollectionObserver,
    private val epTapCoordinator: EpTapCoordinator,
    private val preferencesRepository: PreferencesRepository,
    private val airingTimeRepository: AiringTimeRepository,
    collectionEditing: CollectionEditing,
    private val appTime: AppTime,
    private val computationDispatcher: CoroutineDispatcher,
) : StateViewModel<HomeUiState>(HomeUiState(today = appTime.today.value)) {

    /** 本页的失败提示：加载失败（可重试）、本页发起的写入失败（本地已回滚）、收藏面板取不到或存不上。 */
    val failures = FailureNotices()

    /** 长按「继续」一行打开的收藏面板：长按哪部才取哪部。存好后各区块跟着本地收藏表重算，不用另外回读。 */
    private val collectionSheet = CollectionSheetController(
        scope = viewModelScope,
        read = { currentState.collectionSheet },
        write = { change -> updateState { it.copy(collectionSheet = change(it.collectionSheet)) } },
        failures = failures,
        source = CollectionWorkSource.Fetch(collectionEditing::observeWork),
        save = collectionEditing::save,
        remove = collectionEditing::remove,
    )

    // 条目与阶段一起发布，禁止新阶段与上一轮条目错拍。
    private val progressInput = MutableStateFlow(HomeProgressInput(0, ToWatchOrderPhase.WAITING, null))
    /**
     * 用户点选的类型过滤（原始请求）。它不是「实际生效的过滤」——[sectionsOf] 会把它与
     * 「继续」实际出现的分类比对，消失的分类退回「全部」；生效值作为产出写进 [HomeUiState.continueFilter]。
     */
    private val requestedContinueFilter = MutableStateFlow(ContinueFilterRequest())

    /** 放送时刻表（条目 id → 每周首播时刻）：追更时间表同一天按它排、封面标它；表还没读到或读不出时为空，都没有时刻。 */
    private val broadcastStarts = MutableStateFlow<Map<Int, Long>>(emptyMap())

    /** 待看条数上限（偏好）：读到之前为 null，进度合并等它；读不出时提示带重试，合并照样等。 */
    private val toWatchLimit = MutableStateFlow<Int?>(null)

    // 只在主线程 load/collect 中读写；后台候选计算不能访问它。
    private var toWatchOrder: List<Int>? = null
    private var loadJob: Job? = null
    private var progressObservationJob: Job? = null

    init {
        follow(failures, { airingTimeRepository.observeBroadcastStarts() }) { starts -> broadcastStarts.value = starts }
        // 追更时间表的时刻与每日放送同一份表：到期才取、取不到不提示，表一变 broadcastStarts 自己跟上
        viewModelScope.launch { airingTimeRepository.keepFresh(appTime.now) }
        // 首页读的两项偏好同一次订阅：读失败只提示一条，一次重试把两项都重订上。
        follow(failures, { combine(preferencesRepository.appFeatures, preferencesRepository.toWatchLimit, ::Pair).storageAsLoad() }) { (features, limit) ->
            toWatchLimit.value = limit
            val wasShown = currentState.showFriends
            updateState { it.copy(showFriends = features.community) }
            if (features.community && !wasShown && currentState.friends.valueOrNull() == null) loadFriends()
        }
        observeProgressInputs()
        viewModelScope.launch {
            epTapCoordinator.pending.collect { map -> updateState { it.copy(pendingVisuals = map) } }
        }
        viewModelScope.launch {
            epTapCoordinator.errors.collect { error ->
                if (error.origin == ProgressOrigin.HOME) failures.post(error.failure)
            }
        }
        load(false)
    }

    private fun observeProgressInputs() {
        progressObservationJob?.cancel()
        progressObservationJob = viewModelScope.launch {
            var observationFailure: Failure? = null
            var snapshotFailure: Failure? = null
            // 收藏与逐集状态只当「该重算了」的信号；跨零点同样要重算（未播 / 已播、时间表都按今天算）。
            val observed = collectionObserver.observeProgress().map { update ->
                when (update) {
                    CollectionProgressUpdate.Reset -> Load.Pending
                    is CollectionProgressUpdate.Value -> update.load
                }
            }
            // 过滤与条数上限先并成一对：带名字的 combine 到五条流为止，第五条给放送时刻表
            val viewPrefs = combine(requestedContinueFilter, toWatchLimit.filterNotNull(), ::Pair)
            combine(
                progressInput,
                observed,
                viewPrefs,
                appTime.today,
                broadcastStarts,
            ) { input, observedToday, (filter, limit), today, starts ->
                if (observedToday !is Load.Ready) return@combine HomeSectionsResult(input, null, limit, filter, today, observedToday)
                // 长期订阅仅提供失效通知；重新读取已提交的当前数据，不能消费排队中的旧拍并冻结。
                val snapshot = if (input.items == null || input.phase == ToWatchOrderPhase.WAITING) null
                    else collectionObserver.progressSnapshot()
                val candidates = (snapshot as? Load.Ready)?.value?.let {
                    prepareSectionCandidates(
                        requireNotNull(input.items), it.collections, it.episodes.statesBySubject,
                        today, it.episodes.latestWatchedAtBySubject, starts,
                    )
                }
                HomeSectionsResult(input, candidates, limit, filter, today, observedToday, snapshot != null, (snapshot as? Load.Failed)?.failure)
            }.flowOn(computationDispatcher).collect { result ->
                if (!result.isCurrent(progressInput.value, requestedContinueFilter.value)) return@collect
                val previousFailure = observationFailure ?: snapshotFailure
                when (val observed = result.observed) {
                    is Load.Failed -> observationFailure = observed.failure
                    is Load.Ready -> observationFailure = null
                    else -> Unit
                }
                if (result.snapshotRead) snapshotFailure = result.snapshotFailure
                val currentFailure = observationFailure ?: snapshotFailure
                if (currentFailure != null && currentFailure != previousFailure) {
                    failures.post(currentFailure, retry = ::observeProgressInputs)
                }
                val candidates = result.candidates ?: return@collect
                // 此处到状态发布之间不挂起：读取冻结序、最终截断、补漏与记录新序属于同一次接受。
                val sections = sectionsOf(
                    candidates, result.requestedFilter.type,
                    previousToWatchOrder = if (result.input.phase == ToWatchOrderPhase.FROZEN) toWatchOrder else null,
                    toWatchLimit = result.limit,
                )
                val acceptedRequest = if (result.requestedFilter.type != null && sections.continueFilter == null) {
                    val reset = result.requestedFilter.copy(revision = result.requestedFilter.revision + 1, type = null)
                    if (!requestedContinueFilter.compareAndSet(result.requestedFilter, reset)) return@collect
                    reset
                } else result.requestedFilter
                // 冻结全量顺序由 sectionsOf 返回（含未展示条目），只在本轮已冻结时记录。
                if (result.input.phase == ToWatchOrderPhase.FROZEN) toWatchOrder = sections.toWatchOrder
                updateState { current ->
                    if (!result.copy(requestedFilter = acceptedRequest).isCurrent(progressInput.value, requestedContinueFilter.value)) current
                    else current.copy(
                        toWatch = sections.toWatch,
                        scheduleGroups = sections.scheduleGroups,
                        continueItems = sections.continueItems,
                        continueChips = sections.continueChips,
                        continueFilter = sections.continueFilter,
                        continueCollapsed = sections.continueCollapsed,
                        doingItems = sections.doingItems,
                        progress = Load.Ready(Unit),
                        toWatchFrozen = result.input.phase == ToWatchOrderPhase.FROZEN,
                        today = result.today,
                    )
                }
            }
        }
    }

    fun refresh() = load(true)

    /** 长按「继续」一行：打开这部的收藏面板（与详情页 FAB 同一个面板）。 */
    fun openCollectionSheet(subjectId: Int) = collectionSheet.open(subjectId)

    fun saveCollection(edits: CollectionEdits) = collectionSheet.save(edits)

    fun removeCollection() = collectionSheet.remove()

    fun closeCollectionSheet(edits: CollectionEdits) = collectionSheet.close(edits)

    fun retryCollectionEpisodes() = collectionSheet.retryEpisodes()

    fun onContinueFilterChange(type: SubjectType?) {
        requestedContinueFilter.update { it.copy(revision = it.revision + 1, type = type) }
    }

    fun onEpButtonTap(card: HomeProgressCard) {
        epTapCoordinator.advance(ProgressOrigin.HOME, card.progressSubject, currentState.today)
    }

    /** 章节按钮完结后的「标为看过」：与收藏面板同一条保存路径（[CollectionSheetController.markDone]）。 */
    fun markDone(card: HomeProgressCard) = collectionSheet.markDone(card.subject.id)

    private fun load(refresh: Boolean) {
        loadJob?.cancel()
        val previous = progressInput.value
        val request = previous.generation + 1
        progressInput.value = HomeProgressInput(request, ToWatchOrderPhase.WAITING, previous.items)
        toWatchOrder = null
        updateState { it.copy(refreshing = refresh, toWatchFrozen = false, progress = it.progress.advance(Load.Pending)) }
        loadJob = viewModelScope.launch {
            // 「也在进行」各类型的取页失败：整页没失败时才提示一次，整页已是重试页就不再叠一条提示条
            var alsoDoingFailure: Failure? = null
            coroutineScope {
                launch {
                    homeRepository.getHomeProgress(refresh).collect { result ->
                        if (request != progressInput.value.generation) return@collect
                        when (result) {
                            is Load.Ready -> {
                                publishProgress(request, if (result.fromCache) ToWatchOrderPhase.PROVISIONAL else ToWatchOrderPhase.FROZEN, result.value)
                                // 网络首拍到了就收起下拉刷新；长篇章节的后台补全不占着转圈
                                if (!result.fromCache) updateState { it.copy(refreshing = false) }
                            }
                            is Load.Failed -> {
                                // 无缓存又失败：整页给重试，不能永远停在骨架上；已有内容时内容留着，只发提示
                                if (progressInput.value.items == null) {
                                    updateState { it.copy(refreshing = false, progress = Load.Failed(result.failure)) }
                                } else {
                                    publishProgress(request, ToWatchOrderPhase.FROZEN)
                                    updateState { it.copy(refreshing = false) }
                                    failures.post(result.failure, retry = ::refresh)
                                }
                            }
                            Load.Pending -> Unit
                        }
                    }
                    // 流结束仍未冻结：缓存未过期、没有发请求——以已显示的缓存冻结
                    if (currentState.progress !is Load.Failed) publishProgress(request, ToWatchOrderPhase.FROZEN)
                }
                // 也在进行：`/p1/home` 只有带进度的类型；没有进度的（游戏、音乐）走 v0 在看列表（落收藏表，区块从收藏表读），
                // 与 EpisodeTracking.homeSection 把它们归入 IN_PROGRESS 同一个判定
                for (type in ContentDomains.filterNot(EpisodeTracking::hasProgress)) launch {
                    collectionStore.getUserCollections(type, CollectionType.DOING, refresh).collect { result ->
                        if (request == progressInput.value.generation && result is Load.Failed) alsoDoingFailure = result.failure
                    }
                }
                // 首次加载由 appFeatures 的收集负责（开关打开时拉一次），这里只管下拉刷新，避免启动时重复请求
                if (refresh && currentState.showFriends) loadFriends()
            }
            if (request == progressInput.value.generation) {
                updateState { it.copy(refreshing = false) }
                if (currentState.progress !is Load.Failed) alsoDoingFailure?.let { failures.post(it, retry = ::refresh) }
            }
        }
    }

    private fun publishProgress(request: Int, phase: ToWatchOrderPhase, items: List<HomeProgressItemBo>? = null) {
        progressInput.update { current ->
            if (current.generation != request) current
            else current.copy(phase = maxOf(current.phase, phase), items = items ?: current.items ?: emptyList())
        }
    }

    /** 「好友在看」的唯一加载入口：首次显示与下拉刷新都走这里，同一区块的上一轮先取消。 */
    private fun loadFriends() =
        launchSection(FRIENDS_SECTION, failures, { loadOutcome { friendsActivities() } }, { it.friends }) { copy(friends = it) }

    private suspend fun friendsActivities(): Outcome<List<FriendSubjectGroupBo>> = coroutineScope {
        val results = ContentDomains.map { type -> async { homeRepository.getFriendsSubjectCollections(type, limit = 10) } }.awaitAll()
        val succeeded = results.mapNotNull { it.valueOrNull() }
        if (succeeded.isEmpty()) Outcome.Failed(results.firstNotNullOf { it.failureOrNull() })
        else Outcome.Success(aggregateFriendsActivities(succeeded.flatten(), appTime.now.value))
    }
}

/** 后台计算章节状态、冷却排序和时间表；冻结、截断及补漏只由 [sectionsOf] 决定。 */
internal fun prepareSectionCandidates(
    items: List<HomeProgressItemBo>,
    collections: List<UserCollectionBo>,
    watchStates: Map<Int, Map<Int, EpisodeStatus>>,
    today: LocalDate,
    latestWatchedAt: Map<Int, Long> = emptyMap(),
    /** 放送时刻表（条目 id → 每周首播时刻）：追更时间表同一天按它排、每张海报带时刻；空表就都没有时刻。 */
    broadcastStarts: Map<Int, Long>,
): HomeSectionCandidates {
    val collectionById = collections.associateBy { it.subjectId }
    data class Entry(val item: HomeProgressItemBo, val collection: UserCollectionBo, val states: Map<Int, EpisodeStatus>) {
        val section = EpisodeTracking.homeSection(item.subject.type, item.eps, states, today)
        val card = HomeProgressCard(
            subject = item.subject,
            epButtonState = EpisodeTracking.epButtonState(
                subjectType = item.subject.type,
                episodes = item.eps,
                watchStates = states,
                today = today,
                epStatus = collection.epStatus,
                volStatus = collection.volStatus,
                totalEps = item.subject.eps ?: 0,
                totalVols = item.subject.volumes ?: 0,
            ),
            progressSubject = ProgressSubject(
                subjectId = item.subject.id,
                subjectType = item.subject.type,
                episodes = item.eps,
                totalEps = item.subject.eps ?: 0,
                totalVols = item.subject.volumes ?: 0,
            ),
            progressCount = EpisodeTracking.progressCount(
                subjectType = item.subject.type,
                episodes = item.eps,
                watchStates = states,
                epStatus = collection.epStatus,
                volStatus = collection.volStatus,
                totalEps = item.subject.eps ?: 0,
                totalVols = item.subject.volumes ?: 0,
                collectionType = collection.type,
            ),
        )
    }
    val entries = items.mapNotNull { item ->
        val collection = collectionById[item.subject.id]?.takeIf { it.type == CollectionType.DOING } ?: return@mapNotNull null
        Entry(item, collection, watchStates[item.subject.id].orEmpty())
    }
    val entriesById = entries.associateBy { it.item.subject.id }

    // 排序与冷却只走 EpisodeTracking.rankToWatch（唯一判定点）：本函数不自己过滤或比较。
    val ranked = EpisodeTracking.rankToWatch(
        entries.filter { it.section == HomeSection.TO_WATCH }
            .map { entry ->
                // 「最近操作时刻」的唯一判定点在 core:model（与进度页共用同一个函数）。
                val activeAt = lastActiveAt(entry.item.updatedAt, latestWatchedAt[entry.item.subject.id])
                ToWatchRank(
                    subjectId = entry.item.subject.id,
                    lastActiveAt = activeAt,
                    relevantAt = EpisodeTracking.toWatchRecency(
                        episodes = entry.item.eps,
                        watchStates = entry.states,
                        today = today,
                        lastActiveAt = activeAt,
                    ),
                    airedUnwatchedCount = EpisodeTracking.airedUnwatchedCount(entry.item.eps, entry.states, today),
                )
            },
        today,
    )
    val toWatch = ranked.map { rank ->
        val entry = requireNotNull(entriesById[rank.subjectId])
        HomeToWatchItem(
            card = entry.card,
            airedUnwatchedCount = rank.airedUnwatchedCount,
        )
    }
    val ongoing = entries.filter { it.section == HomeSection.TO_WATCH || it.section == HomeSection.SCHEDULE_ONLY }
    // 这一天的播出时刻：bgm.tv 的播出日（深夜番归前一天）与 bangumi-data 的首播时刻按放送日的星期对上才算（airingMinuteOf）
    fun airingOf(entry: Entry, date: LocalDate): AiringMinute? =
        broadcastStarts[entry.item.subject.id]?.let { start -> airingMinuteOf(date.dayOfWeek.value, start) }
    val schedule = groupScheduleByDay(ongoing, today, { it.item.eps }) { entry, date -> airingOf(entry, date) }.map { group ->
        ScheduleDayGroup(
            daysUntil = group.daysUntil,
            date = group.date,
            items = group.items.map { entry ->
                HomeScheduleItem(
                    subject = entry.item.subject,
                    // groupScheduleByDay 已按 nextUpcomingEpisode 分组，此处必然非 null
                    nextUpcomingSort = requireNotNull(EpisodeTracking.nextUpcomingEpisode(entry.item.eps, today)).sort,
                    airedUnwatchedCount = EpisodeTracking.airedUnwatchedCount(entry.item.eps, entry.states, today),
                    airing = airingOf(entry, group.date),
                )
            },
        )
    }
    // 这里只确定可参与补漏的领域候选并按快照时间排好，不排除顶部作品。
    val continueItems = entries.filter { it.section != HomeSection.IN_PROGRESS }
        .sortedByDescending { it.item.updatedAt }
        .map { HomeContinueItem(it.card, it.collection) }
    val doing = collections.filter {
        it.type == CollectionType.DOING &&
            EpisodeTracking.homeSection(it.subjectType, emptyList(), emptyMap(), today) == HomeSection.IN_PROGRESS
    }
    return HomeSectionCandidates(toWatch, schedule, continueItems, doing)
}

/**
 * 最终展示的唯一纯函数：冻结顺序 → 轮播截断 → 扣除顶部实际展示集合 → 继续筛选/截断。
 * 输入已完成章节计算与排序；这里仅处理作品列表，不重新遍历章节。
 * VM 在主线程接受当前结果时调用，使用刚刚接受的冻结序，不消费后台提前读到的旧序。
 */
internal fun sectionsOf(
    candidates: HomeSectionCandidates,
    continueFilter: SubjectType?,
    previousToWatchOrder: List<Int>? = null,
    toWatchLimit: Int = Int.MAX_VALUE,
): HomeSections {
    val ordered = stableOrder(previousToWatchOrder, candidates.toWatch, newItemsFirst = false) { it.card.subject.id }
    val toWatch = ordered.take(toWatchLimit)
    val displayedAboveIds = buildSet {
        toWatch.forEach { add(it.card.subject.id) }
        candidates.scheduleGroups.forEach { group -> group.items.forEach { add(it.subject.id) } }
    }
    val continueCandidates = candidates.continueItems.filter { it.card.subject.id !in displayedAboveIds }
    val continueDomains = ContentDomains.filter { domain -> continueCandidates.any { it.card.subject.type == domain } }
    val effectiveFilter = continueFilter?.takeIf { it in continueDomains }
    val continueItems = continueCandidates
        .filter { effectiveFilter == null || it.card.subject.type == effectiveFilter }
        .take(CONTINUE_LIMIT)
    // 单一分类不画与「全部」重复的 chip；候选全被顶部覆盖时只保留完整进度入口。
    return HomeSections(
        toWatch = toWatch,
        toWatchOrder = ordered.map { it.card.subject.id },
        scheduleGroups = candidates.scheduleGroups,
        continueItems = continueItems,
        continueChips = if (continueDomains.size >= 2) continueDomains else emptyList(),
        continueFilter = effectiveFilter,
        continueCollapsed = continueCandidates.isEmpty() && displayedAboveIds.isNotEmpty(),
        doingItems = candidates.doingItems,
    )
}
