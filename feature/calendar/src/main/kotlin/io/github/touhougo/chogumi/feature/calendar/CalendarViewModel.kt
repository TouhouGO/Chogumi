package io.github.touhougo.chogumi.feature.calendar

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.AiringTimeRepository
import io.github.touhougo.chogumi.core.data.repository.CalendarRepository
import io.github.touhougo.chogumi.core.data.repository.CollectionObserver
import io.github.touhougo.chogumi.core.data.repository.CollectionProgressUpdate
import io.github.touhougo.chogumi.core.data.repository.CollectionProgressSnapshot
import io.github.touhougo.chogumi.core.data.repository.HomeRepository
import io.github.touhougo.chogumi.core.data.repository.SubjectRepository
import io.github.touhougo.chogumi.core.foundation.AppTime
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.map
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.AiringNow
import io.github.touhougo.chogumi.core.model.CalendarDaySections
import io.github.touhougo.chogumi.core.model.CalendarItemBo
import io.github.touhougo.chogumi.core.model.CollectionEditing
import io.github.touhougo.chogumi.core.model.CollectionMarker
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.airingDateOf
import io.github.touhougo.chogumi.core.model.airingNowOf
import io.github.touhougo.chogumi.core.model.calendarWeek
import io.github.touhougo.chogumi.core.model.followedWorksOf
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkController
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

data class CalendarUiState(
    /**
     * 放送日里的「现在」（[airingNowOf]，北京时间 4 点翻页）：[AiringNow.weekday] 是今天（选择器上的小圆点标它，打开时选中它），
     * [AiringNow.minute] 画今天那页的「现在」线；每分钟随 [AppTime.now] 走。
     */
    val now: AiringNow,
    /** 选中的星期：打开时是今天，之后由用户决定（跨 4 点不替用户换页）。 */
    val selectedDay: Int = now.weekday,
    /**
     * 整周放送表，按星期分好「我在追」、按时刻分组的全部与「时刻未知」：还没算出来是 Pending，一份都没拿到是 Failed（整页报错 + 重试）。
     * 只在放送表与收藏合并完之后写，网络拍到了、合并还没算完时仍是 Pending；已有数据时刷新失败走提示条，表留在原地。
     */
    val calendar: Load<Map<Int, CalendarDaySections>> = Load.Pending,
    /** 取回的放送表（没合并收藏）；刷新途中与刷新失败时留着上一份 Ready，由 [CalendarViewModel] 的区块收集推进。 */
    val rawCalendar: Load<Map<Int, List<CalendarItemBo>>> = Load.Pending,
    val progressCounts: Map<Int, ProgressCount> = emptyMap(),
    val isRefreshing: Boolean = false,
    /** 海报长按标记（「我在追」与时间线的行），只由 [CalendarViewModel.posterMarks] 改。 */
    val posterMarks: PosterMarkState = PosterMarkState(),
) {
    /** 今天是放送日里的星期几（1=周一…7=周日）。 */
    val today: Int get() = now.weekday
}

/** 每日放送 ViewModel（星期 1=周一…7=周日，与 API key 对齐） */
class CalendarViewModel(
    private val calendarRepository: CalendarRepository,
    private val airingTimeRepository: AiringTimeRepository,
    private val collectionObserver: CollectionObserver,
    private val subjectRepository: SubjectRepository,
    private val homeRepository: HomeRepository,
    private val appTime: AppTime,
    collectionMarker: CollectionMarker,
    collectionEditing: CollectionEditing,
    /** 合并放送表、收藏、逐集状态与放送时刻并算进度：每部在看作品都要扫一遍整周，收藏或逐集状态一变就重跑，不占主线程。 */
    private val computationDispatcher: CoroutineDispatcher,
) : StateViewModel<CalendarUiState>(CalendarUiState(now = airingNowOf(appTime.now.value))) {

    /** 已有数据时刷新失败的提示（数据留在原地）、本地存储读不出、海报长按标记的提交失败。 */
    val failures = FailureNotices()

    /** 海报长按标记：标状态、撤销、收藏管理面板。 */
    val posterMarks = PosterMarkController(
        scope = viewModelScope,
        read = { currentState.posterMarks },
        write = { change -> updateState { it.copy(posterMarks = change(it.posterMarks)) } },
        failures = failures,
        marker = collectionMarker,
        editing = collectionEditing,
        today = appTime.today,
    )

    /** 本地收藏与逐集快照只是合并输入；失败时 [follow] 保留上一拍，换号时观察器先发空拍清掉旧账号。 */
    private val localProgress = MutableStateFlow<CollectionProgressSnapshot?>(null)

    /**
     * 在看作品本机已有的章节（与首页同一份，[HomeRepository.observeCachedEpisodes]），「我在追」按它判哪天播；
     * 还没读到是 null，合并等它（同 [localProgress]）。
     */
    private val airingEpisodes = MutableStateFlow<Map<Int, List<EpisodeBo>>?>(null)

    /** 放送时刻表（条目 id → 每周首播时刻）；表还没读到或读不出时为空，作品都归「时刻未知」。 */
    private val broadcastStarts = MutableStateFlow<Map<Int, Long>>(emptyMap())

    init {
        viewModelScope.launch {
            appTime.now.collect { now -> updateState { it.copy(now = airingNowOf(now)) } }
        }
        follow(failures, { collectionObserver.observeProgress().mapNotNull { (it as? CollectionProgressUpdate.Value)?.load } }) { snapshot ->
            localProgress.value = snapshot
        }
        follow(failures, { airingTimeRepository.observeBroadcastStarts() }) { starts -> broadcastStarts.value = starts }
        follow(failures, { homeRepository.observeCachedEpisodes() }) { episodes -> airingEpisodes.value = episodes }
        // 放送时刻表顺带跟上：到期才取、取不到不提示（AiringTimeRepository），表一变 broadcastStarts 自己跟上
        viewModelScope.launch { airingTimeRepository.keepFresh(appTime.now) }
        // 观察本地收藏、逐集状态快照与本地章节，由 EpisodeTracking.progressCount 计算进度单一真源
        viewModelScope.launch { observeCalendar() }
        load(refresh = false)
    }

    fun onSelectDay(day: Int) {
        if (day in 1..7) updateState { it.copy(selectedDay = day) }
    }

    fun refresh() {
        updateState { it.copy(isRefreshing = true) }
        load(refresh = true)
    }

    /**
     * 放送表合并本地收藏、章节与放送时刻算出整周（[calendarWeek]：在追的放在章节说的那一天）与进度。章节只读本地：
     * 判哪天播读与首页同一份（[airingEpisodes]），进度读本地全章节（[SubjectRepository.observeCachedEpisodes]），
     * 都不借首页进度那条会取网络的流；本地没有章节的在追作品按收藏记录给进度（「看过 N / M 集」），不会停在加载骨架上。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observeCalendar() {
        val doing = localProgress.filterNotNull().map { snapshot -> snapshot.collections.filter { it.type == CollectionType.DOING } }
        val localEpisodes = doing.map { rows -> rows.mapTo(HashSet()) { it.subjectId } }
            .distinctUntilChanged()
            .flatMapLatest { ids -> subjectRepository.observeCachedEpisodes(ids) }
        val today = appTime.now.map(::airingDateOf).distinctUntilChanged()
        combine(
            combine(uiState.map { it.rawCalendar }.distinctUntilChanged(), today, ::Pair),
            localProgress.filterNotNull(),
            localEpisodes,
            airingEpisodes.filterNotNull(),
            broadcastStarts,
        ) { (rawCalendar, date), snapshot, episodesBySubject, airingEpisodesBySubject, starts ->
            val doingCollections = snapshot.collections.filter { it.type == CollectionType.DOING }
            val episodeSnapshot = snapshot.episodes
            val calendarSubjects = rawCalendar.valueOrNull().orEmpty().values.asSequence().flatten()
                .map { it.subject }.associateBy { it.id }
            val calendar = rawCalendar.map { days ->
                calendarWeek(days, followedWorksOf(snapshot.collections, days, airingEpisodesBySubject), date, starts)
            }

            // 进度计算：全 App 唯一判定点 EpisodeTracking.progressCount（动画只数正片）
            val progressCounts = doingCollections.associate { collection ->
                val calendarSubject = calendarSubjects[collection.subjectId]
                collection.subjectId to EpisodeTracking.progressCount(
                    subjectType = collection.subjectType,
                    episodes = episodesBySubject[collection.subjectId],
                    watchStates = episodeSnapshot.statesBySubject[collection.subjectId],
                    epStatus = collection.epStatus,
                    volStatus = collection.volStatus,
                    totalEps = calendarSubject?.eps ?: collection.subject?.eps ?: 0,
                    totalVols = calendarSubject?.volumes ?: collection.subject?.volumes ?: 0,
                    collectionType = collection.type,
                    // 这一页不取章节：本地没有就不会再来，按收藏记录给数，不画加载骨架。
                    loadFailed = true,
                )
            }
            calendar to progressCounts
        }.flowOn(computationDispatcher).collect { (calendar, progressCounts) ->
            updateState { it.copy(calendar = calendar, progressCounts = progressCounts) }
        }
    }

    /**
     * SWR：缓存拍先渲染，网络拍再刷新。已有数据时等待与失败都不抹掉它，失败用提示条说明原因；一份都没有时失败就是整页报错 + 重试。
     * 这一轮有了结果（成功或失败）才收起下拉转圈；失败也要收，否则转圈卡死。
     */
    private fun load(refresh: Boolean = false) {
        launchSection(
            "calendar",
            failures,
            { calendarRepository.getCalendar(refresh) },
            { it.rawCalendar },
            alongside = { if (it is Load.Pending) this else copy(isRefreshing = false) },
        ) { copy(rawCalendar = it) }
    }
}
