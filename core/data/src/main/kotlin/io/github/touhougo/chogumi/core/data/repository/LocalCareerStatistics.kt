package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.mapper.toBo
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeDao
import io.github.touhougo.chogumi.core.data.db.subject.getBySubjectIdsChunked
import io.github.touhougo.chogumi.core.data.util.storageAsLoad
import io.github.touhougo.chogumi.core.data.util.storageFailuresAsLoad
import io.github.touhougo.chogumi.core.model.statistics.StatisticsInput
import io.github.touhougo.chogumi.core.model.statistics.calculateCollectionStatistics
import io.github.touhougo.chogumi.core.model.statistics.countsForLineup
import io.github.touhougo.chogumi.core.foundation.displayZone
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.CareerStatistics
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.EpisodeMark
import io.github.touhougo.chogumi.core.model.EpisodeMarks
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * 本人收藏的生涯统计（个人页统计的 [CollectionStatsDataSource]）：统计从本地收藏与逐集状态算，同步交给 [CollectionSync]。
 */
class LocalCareerStatistics(
    private val observer: CollectionObserver,
    private val sync: CollectionSync,
    /** 章节表：本地生涯统计要按逐集口径数「看过的正片」，需要正片列表。 */
    private val episodeDao: EpisodeDao,
    /** 生涯阵容读写的角色与制作人员缓存（条目详情页的同一份）。 */
    private val lineupSource: SubjectLineupSource,
    /**
     * 统计计算的调度器（生产是 Koin 的 computationDispatcher = Dispatchers.Default）——统计要分块查上千部收藏的章节
     * 再跑纯函数，绝不能落在订阅方（`viewModelScope` = Main）的线程上。
     */
    private val computationDispatcher: CoroutineDispatcher,
) : CollectionStatsDataSource, CollectionSyncing by sync {
    /**
     * 本地收藏的生涯统计——**唯一入口**，观察收藏与逐集状态的变化并重算。
     *
     * 观看集数走**进度页同款口径** [EpisodeTracking.progressCount]（逐集表里「看过」的正片条数），
     * 不是条目级 `epStatus` 加总——统计页与进度页的集数只有这一种口径，两处才不会对不上。
     * 没有逐集表的条目（从未在详情页/首页加载过章节）用 `epStatus` 兜底，不把整部作品凭空算成零集。
     *
     * **标记一集也会重算**：逐集快照 [CollectionObserver.observeEpisodeWatchStateSnapshot] 是订阅的一部分，
     * 因此集数 KPI 与进度页同拍。章节表的写入（[EpisodeDao.observeLastWrite]）也订阅：逐集状态原样、只新存了章节时
     * 逐集快照不发拍，首播日与正片口径却变了。用 `conflate` 让正在算的一轮算完、期间的连续变化合成一次补算：
     * 持续写入时不会一直作废重来，最后一轮一定是最新输入。
     *
     * **计算落在 [computationDispatcher]**（`flowOn`）：分块查章节 + 上千部的纯函数不能跑在主线程。
     *
     * **按类型各算一份，再合起来算一份**：逐集计数先对全部收藏算一次，再按 [UserCollectionBo.subjectType] 分组、
     * 以及不分组，喂给同一个纯函数；五种可同步类型恒在结果里（没有收藏的是全零快照）。
     *
     * [currentYear] 由调用方给定（本层不读系统时钟）。
     */
    override fun observeStatistics(currentYear: Int): Flow<Load<CareerStatistics>> =
        combine(
            observer.observeProgress().map { update ->
                when (update) {
                    CollectionProgressUpdate.Reset -> Load.Ready(CollectionProgressSnapshot(emptyList(), EpisodeWatchStateSnapshot()))
                    is CollectionProgressUpdate.Value -> update.load
                }
            }.onStart { emit(Load.Pending) },
            flow { emitAll(episodeDao.observeLastWrite()) }.storageAsLoad().onStart { emit(Load.Pending) },
            flow { emitAll(lineupSource.observeLineupChanges()) }.storageAsLoad().onStart { emit(Load.Pending) },
        ) { snapshot, episodes, lineups ->
            when {
                snapshot is Load.Failed -> snapshot
                episodes is Load.Failed -> episodes
                lineups is Load.Failed -> lineups
                snapshot is Load.Ready && episodes is Load.Ready && lineups is Load.Ready ->
                    Load.Ready(snapshot.value.collections to snapshot.value.episodes)
                else -> Load.Pending
            }
        }
            .filter { it !is Load.Pending }
            // 成功拍即使数据相同也是重新计算的信号；只合并同一失败原因，避免两个分支同时失败提示两遍。
            .distinctUntilChanged { previous, next ->
                previous is Load.Failed && next is Load.Failed && previous == next
            }
            // conflate 保留最新待算输入，map 完成本轮后再补算；阵容逐部写入不能反复取消统计。
            .conflate()
            .map { load ->
                when (load) {
                    is Load.Ready -> Load.Ready(computeLocalStatistics(load.value.first, load.value.second, currentYear))
                    is Load.Failed -> load
                    Load.Pending -> Load.Pending
                }
            }
            .storageFailuresAsLoad()
            .flowOn(computationDispatcher)

    /**
     * 本地统计的唯一计算体：口径真源是 [EpisodeTracking.progressCount]（唯一判定点）。
     *
     * 有逐集记录时用它；逐集表尚未加载时才退回收藏自带的 `epStatus`（下界），**绝不覆盖**已有结果。
     * 节奏类的图取同一批章节里正片的「看过」时刻（[EpisodeTracking.mainEpisodes]，与集数同一口径），日子按本机时区算。
     */
    private suspend fun computeLocalStatistics(
        collections: List<UserCollectionBo>,
        snapshot: EpisodeWatchStateSnapshot,
        currentYear: Int,
    ): CareerStatistics {
        val subjectIds = collections.map { it.subjectId }
        val episodesBySubject = if (subjectIds.isEmpty()) emptyMap()
        else episodeDao.getBySubjectIdsChunked(subjectIds).groupBy { it.subjectId }

        val watched = mutableMapOf<Int, Int>()
        val marks = mutableMapOf<Int, List<EpisodeMark>>()
        for (collection in collections) {
            if (!EpisodeTracking.tracksEpisodes(collection.subjectType)) continue
            val states = snapshot.statesBySubject[collection.subjectId]
            val chapters = episodesBySubject[collection.subjectId]?.map { it.toBo() }
            val watchedAt = snapshot.watchedAtBySubject[collection.subjectId]
            if (chapters != null && watchedAt != null) {
                marks[collection.subjectId] = EpisodeTracking.mainEpisodes(chapters).mapNotNull { episode ->
                    watchedAt[episode.id]?.let { EpisodeMark(markedAt = it, airdate = EpisodeTracking.airdateOf(episode)) }
                }
            }
            if (chapters != null && !states.isNullOrEmpty()) {
                val count = EpisodeTracking.progressCount(
                    subjectType = collection.subjectType,
                    episodes = chapters,
                    watchStates = states,
                    collectionType = collection.type,
                )
                if (count is ProgressCount.Known && count.current > 0) watched[collection.subjectId] = count.current
            } else {
                collection.epStatus.takeIf { it > 0 }?.let { watched[collection.subjectId] = it }
            }
        }
        val episodeMarks = EpisodeMarks(marks, displayZone())
        val lineups = lineupSource.cachedLineups(
            collections.filter { countsForLineup(it.type, it.subjectType) }.associate { it.subjectId to it.subjectType },
        )
        fun statisticsOf(group: List<UserCollectionBo>) = StatisticsInput(
            collections = group,
            watchedMainEpisodes = group.mapNotNull { c -> watched[c.subjectId]?.let { c.subjectId to it } }.toMap(),
            episodesApproximate = false,
            currentYear = currentYear,
            episodeMarks = episodeMarks,
            lineups = lineups,
        ).calculateCollectionStatistics()
        val byType = collections.groupBy { it.subjectType }
        return CareerStatistics(
            byType = ContentDomains.associateWith { subjectType -> statisticsOf(byType[subjectType].orEmpty()) },
            overall = statisticsOf(collections.filter { it.subjectType in ContentDomains }),
        )
    }
}
