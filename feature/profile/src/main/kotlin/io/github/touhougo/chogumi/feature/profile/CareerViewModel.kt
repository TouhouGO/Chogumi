package io.github.touhougo.chogumi.feature.profile

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.CollectionStatsDataSource
import io.github.touhougo.chogumi.core.data.repository.OtherCareerDataSource
import io.github.touhougo.chogumi.core.data.repository.SyncProgress
import io.github.touhougo.chogumi.core.data.repository.UserRepository
import io.github.touhougo.chogumi.core.foundation.AppTime
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.map
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CareerStatistics
import io.github.touhougo.chogumi.core.model.CollectionStatistics
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 生涯的原始状态。界面不直接读这些字段判断画什么：完整统计的一类经 [CareerState.content]（[careerContentOf]），
 * 生涯卡经 [CareerState.cardContent]（[careerCardContentOf]）。
 */
data class CareerState(
    val isSelf: Boolean,
    val selectedType: SubjectType = SubjectType.ANIME,
    /** 本人：本地统计流的最新一拍（每类一份 + 合起来一份）；null = 还没到。 */
    val ownStats: CareerStatistics? = null,
    /**
     * 他人：资料（称呼与服务端摘要：每类每状态的数）。等待中阶梯与卡片画骨架，取不到时给重试（[CareerViewModel.retryOwner]）。
     * 本人不读它。
     */
    val owner: Load<UserBo> = Load.Pending,
    /** 他人：每类的汇总进度 / 结果（会话内）。 */
    val summaries: Map<SubjectType, OtherSummary> = emptyMap(),
    /**
     * 他人：已汇总的各类合起来的统计（生涯卡的均分、称号、题材与封面读它）；null = 可自动汇总的类别还没汇总完，
     * 卡片先只画服务端摘要的计数。一类都没汇总成（都在上限以上或都失败）时是全零快照。只要摘要（`summaryOnly`）时一直是 null。
     */
    val otherOverall: CollectionStatistics? = null,
    /**
     * 他人：生涯卡的封面拼图，先到的那份留着、不再换（[firstCovers]）：通常是看得最多那一类最近一页里挑的；
     * 汇总先出来（会话缓存里已有）就是汇总挑的。汇总挑出另一份也不换：整块换图每格先空再变。
     */
    val otherCovers: List<SubjectBo> = emptyList(),
    /** 本人：全量同步进度（数据层的唯一进度源，原样转交）。 */
    val sync: SyncProgress = SyncProgress(),
    /** 本人：上次成功同步的时刻（epoch 毫秒）。 */
    val lastSyncedAt: Long? = null,
    /** 本人：用户主动下拉 / 点 ⟳ 的那一次是否还在跑（下拉指示器只认它）。 */
    val refreshing: Boolean = false,
) {
    /** 他人：称呼（昵称，空则用户名）；null = 本人或资料还没到。 */
    val ownerName: String? get() = owner.valueOrNull()?.let(::displayNameOf)

    private val serverCounts: Load<Map<SubjectType, Map<CollectionType, Int>>> get() = owner.map { it.subjectStats }

    fun content(type: SubjectType = selectedType): CareerContent =
        careerContentOf(type, isSelf, ownStats?.byType, serverCounts, summaries[type])

    /** 生涯卡此刻画哪一种：全部类型合起来；[publicRatings] 关掉时不和大众比。 */
    internal fun cardContent(publicRatings: Boolean): CareerCardContent =
        careerCardContentOf(isSelf, ownStats, serverCounts, otherOverall, otherCovers, summaries, publicRatings)
}

/**
 * 生涯卡（「概览」tab）与完整统计页的 ViewModel——**两处同一个类**，各自一个实例，
 * 数据都来自数据层的唯一来源（本人：Room 统计流 + 同步进度；他人：会话内缓存），不在 VM 之间搬数据。
 *
 * - 本人：打开即请求一次**非强制**同步（数据层的 30 分钟新鲜期与「同一时刻只跑一次」挡住重复），
 *   [refresh] 强制同步。
 * - 他人：资料（服务端摘要）到了就画阶梯与卡片上的计数，同时取看得最多那一类最近一页的封面；每一类不超过自动上限就自动汇总，
 *   超过则等 [summarize]；[summaryOnly] 时一律不汇总。可自动汇总的都汇总完后，把汇总成的各类合起来算一份，
 *   生涯卡这时补上均分、称号与题材。资料取不到时由 [retryOwner] 重取。
 * - 本人同步失败（内容一直在：本地统计流）走 [failures]，界面以提示条说明，提示条上可重试。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CareerViewModel(
    /** 目标用户名；null / 空 = 自己。 */
    private val username: String?,
    initialType: SubjectType,
    /**
     * 他人：只要服务端摘要（每类每状态的数），不拉收藏、不汇总（用户卡片用：读帖时顺手点开看一眼，不该触发全量下载）。
     * 均分、称号与题材要汇总才有，这时不画；封面只取最近一页挑；统计留给完整统计页。
     */
    private val summaryOnly: Boolean,
    private val collectionStats: CollectionStatsDataSource,
    private val otherCareers: OtherCareerDataSource,
    private val userRepository: UserRepository,
    /** 「今年」的来源：逐年序列画到今年为止（业务日的年份），跨年随之重算。 */
    private val appTime: AppTime,
) : StateViewModel<CareerState>(CareerState(isSelf = username.isNullOrBlank(), selectedType = initialType)) {

    /** 逐年序列画到哪一年（业务日 [AppTime.today] 的年份），只在跨年时变。 */
    private val currentYear: Flow<Int> = appTime.today.map { it.year }.distinctUntilChanged()

    private val isSelf: Boolean get() = currentState.isSelf

    /** 本人：同步失败（自动同步与下拉都算），内容留在原地。 */
    val failures = FailureNotices()

    init {
        if (isSelf) {
            follow(failures, { currentYear.flatMapLatest(collectionStats::observeStatistics) }) { stats ->
                updateState { it.copy(ownStats = stats) }
            }
            viewModelScope.launch {
                collectionStats.syncProgress.collect { progress -> updateState { it.copy(sync = progress) } }
            }
            sync(force = false)
        } else {
            loadOwner(username!!)
            // 跨年：已汇总的类别按新的一年重算（收藏在会话缓存里，不重新拉）
            viewModelScope.launch {
                currentYear.drop(1).collect {
                    currentState.summaries.filterValues { it is OtherSummary.Loaded }.keys.forEach(::startSummary)
                }
            }
        }
    }

    /** 切类型：只换选中项；他人的新类型按需开始汇总。 */
    fun selectType(type: SubjectType) {
        updateState { it.copy(selectedType = type) }
        autoSummarize(type)
    }

    /** 本人：下拉 / ⟳ 强制同步。他人没有这个入口（不是你的数据，不同步）。 */
    fun refresh() {
        if (!isSelf || currentState.refreshing) return
        sync(force = true)
    }

    /** 他人：资料取不到之后的「重试」。 */
    fun retryOwner() {
        val name = username ?: return
        if (isSelf || currentState.owner !is Load.Failed) return
        loadOwner(name)
    }

    /** 他人：「汇总 X 的 N 部」与失败后的「重试」。数据层只缓存成功结果，失败后重试自然会重拉。 */
    fun summarize(type: SubjectType = currentState.selectedType) {
        if (isSelf) return
        startSummary(type)
    }

    private fun sync(force: Boolean) {
        updateState { it.copy(refreshing = force) }
        viewModelScope.launch {
            val result = collectionStats.refreshAllUserCollections(force = force)
            val last = collectionStats.lastSyncedAt()
            updateState { it.copy(refreshing = false, lastSyncedAt = last) }
            result.failureOrNull()?.let { failures.post(it, retry = ::refresh) }
        }
    }

    /**
     * 服务端摘要：与主页资料同一个来源（`UserRepository.getUser`），但**必须直连网络**（`refresh = true`，主页同一条路径）。
     * 用户缓存表不存每类每状态的数（`UserEntity` 没有这一列）：命中缓存的资料 `subjectStats` 恒为空，
     * 会把有收藏的人误判成「没有公开的收藏」。取到第一份就够——阶梯只要每类每状态的数。
     * 主页与生涯同时打开时两边同一次请求（数据层按用户名共享在途请求）。
     */
    private fun loadOwner(name: String) = launchSection(
        "owner",
        failures,
        { userRepository.getUser(name, refresh = true) },
        { it.owner },
        afterCollected = {
            if (currentState.owner is Load.Ready) {
                loadCoverPreview(name)
                if (!summaryOnly) {
                    ContentDomains.forEach(::autoSummarize)
                    refreshOverall()
                }
            }
        },
    ) { copy(owner = it) }

    /**
     * 卡片先画计数（封面空），再只取看得最多那一类最近的一页收藏（会话缓存里有就读缓存），挑出封面拼图补上；取不到就停在没有封面。
     * 汇总一页不等：869 部是 29 页、几秒钟，计数与封面不该陪着等。均分、称号、题材要汇总才有，这里不碰。
     */
    private fun loadCoverPreview(name: String) {
        val counts = currentState.owner.valueOrNull()?.subjectStats ?: return
        val top = mainTypeOf(counts) ?: return
        viewModelScope.launch {
            val picks = otherCareers.coverPicksFor(name, top, appTime.today.value.year).valueOrNull() ?: return@launch
            updateState { it.copy(otherCovers = firstCovers(it.otherCovers, picks)) }
        }
    }

    /**
     * 还没开始时，当前类在 [careerContentOf] 看来「该自动汇总」，或本次会话已汇总过（缓存里有），就开始。
     * 后者让完整统计页与重进的主页直接读缓存：startSummary 同步置 Running，界面不会先闪出「汇总」按钮。
     */
    private fun autoSummarize(type: SubjectType) {
        val name = username ?: return
        if (isSelf || currentState.summaries[type] != null) return
        // 还没开始汇总时，careerContentOf 只在「不超过自动上限」时给 Summarizing（超过给 AwaitingConfirm）。
        if (currentState.content(type) is CareerContent.Summarizing || otherCareers.hasCached(name, type)) startSummary(type)
    }

    private fun startSummary(type: SubjectType) {
        val name = username ?: return
        if (currentState.summaries[type] is OtherSummary.Running) return
        setSummary(type, OtherSummary.Running(SyncProgress(active = true)))
        viewModelScope.launch {
            val result = otherCareers.statisticsFor(name, type, appTime.today.value.year) { progress ->
                setSummary(type, OtherSummary.Running(progress))
            }
            val next = when (result) {
                is Outcome.Success -> OtherSummary.Loaded(result.value)
                is Outcome.Failed -> OtherSummary.Failed(result.failure)
            }
            setSummary(type, next)
            refreshOverall()
        }
    }

    /**
     * 他人：没有一类还在汇总时，把汇总成的各类合起来算一份（读同一份会话缓存，不重拉）。
     * 之后再汇总一类（点「汇总」、跨年重算）会重新合一次。
     */
    private fun refreshOverall() {
        val name = username ?: return
        val summaries = currentState.summaries
        if (summaries.values.any { it is OtherSummary.Running }) return
        val loaded = summaries.filterValues { it is OtherSummary.Loaded }.keys
        viewModelScope.launch {
            val overall = if (loaded.isEmpty()) null else otherCareers.overallStatisticsFor(name, loaded, appTime.today.value.year).valueOrNull()
            updateState {
                it.copy(otherOverall = overall ?: CollectionStatistics(), otherCovers = firstCovers(it.otherCovers, overall?.coverPicks.orEmpty()))
            }
        }
    }

    private fun setSummary(type: SubjectType, summary: OtherSummary) {
        updateState { it.copy(summaries = it.summaries + (type to summary)) }
    }
}

/** 生涯卡封面的唯一规则：已经有（画出来了）就留着，还没有才用这次到的。 */
private fun firstCovers(shown: List<SubjectBo>, incoming: List<SubjectBo>): List<SubjectBo> = shown.ifEmpty { incoming }
