package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.airing.AiringTimeDao
import io.github.touhougo.chogumi.core.data.db.airing.AiringTimeEntity
import io.github.touhougo.chogumi.core.data.db.airing.AiringTimeMetaEntity
import io.github.touhougo.chogumi.core.data.util.isCacheStale
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.data.util.storageAsLoad
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.weeklyBroadcastStartOf
import io.github.touhougo.chogumi.core.network.api.BangumiDataApi
import io.github.touhougo.chogumi.core.network.model.bangumidata.BangumiDataItemDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 放送时刻表（bangumi-data）：每日放送页给每部作品标播出时刻的数据层。
 *
 * 整份数据隔 [REFRESH_TTL_MILLIS] 才再取，带上次的 ETag，没变时服务端回 304、只记一次成功。取失败（没网、超时、
 * GitHub 不可达）保留旧表，[FAILURE_BACKOFF_MILLIS] 内不再试。显示时刻的页面（首页、每日放送）在时经 [keepFresh]
 * 跟着分钟钟点查到没到期，只开其中一页、或一页开着过了几小时都跟得上。这份数据是放送表之外的补充：取不到时页面照常显示、
 * 没时刻的作品归「时刻未知」，不提示用户（设计决策）；调用方只在意「这一轮做完了」。
 *
 * 表里只有 bgm.tv 条目 id 与每周首播时刻（[airingTimeRows]）；时刻怎么落到放送日由 core:model 的 `airingMinuteOf` 判。
 */
class AiringTimeRepository(
    private val api: BangumiDataApi,
    private val dao: AiringTimeDao,
    private val clock: AppClock,
    /** 整份近九千条的筛选在这里跑，不占发起刷新的主线程。 */
    private val computationDispatcher: CoroutineDispatcher,
) {
    private val refreshLock = Mutex()

    /** 条目 id → 每周首播时刻（epoch 毫秒），表一变就发新的一份；读不出来是一拍 [Failure.Storage]（[storageAsLoad]）。 */
    fun observeBroadcastStarts(): Flow<Load<Map<Int, Long>>> =
        dao.observeAll().map { rows -> rows.associate { it.subjectId to it.broadcastStart } }.storageAsLoad()

    /**
     * 页面在时让表保持新鲜：[ticks] 每跳一次（`AppTime.now`，前台每分钟）就 [refreshIfStale] 一次，没到期只是读一次元数据。
     * 失败只是这一跳没取到（`refreshIfStale` 不抛），收集随调用方的作用域结束。
     */
    suspend fun keepFresh(ticks: Flow<Long>) = ticks.collect { refreshIfStale() }

    /** 到期（[airingTimesDue]）就取一份新的，否则什么也不做；并发调用在锁后二次检查，只取一轮。 */
    suspend fun refreshIfStale(): Outcome<Unit> = outcomeOf {
        refreshLock.withLock {
            val meta = dao.getMeta() ?: AiringTimeMetaEntity()
            val now = clock.nowMillis()
            if (!airingTimesDue(meta, now)) return@withLock
            when (val fetched = outcomeOf { api.getData(meta.etag) }) {
                is Outcome.Failed -> {
                    dao.upsertMeta(meta.copy(attemptedAt = now))
                    throw FailureException(fetched.failure)
                }
                is Outcome.Success -> when (val data = fetched.value) {
                    BangumiDataApi.Result.NotModified -> dao.upsertMeta(meta.copy(refreshedAt = now, attemptedAt = now))
                    is BangumiDataApi.Result.Fresh -> {
                        val rows = withContext(computationDispatcher) { airingTimeRows(data.items) }
                        dao.replaceAll(rows, AiringTimeMetaEntity(refreshedAt = now, attemptedAt = now, etag = data.etag))
                    }
                }
            }
        }
    }

    companion object {
        /** 成功之后多久再取：bangumi-data 一季里一周改几次；没变时只是一次 304，隔几小时跟上。 */
        const val REFRESH_TTL_MILLIS = 6L * 60 * 60 * 1000

        /** 失败之后多久再试：页面在时每分钟查一次，没网时不必每一跳都等它失败一次。 */
        const val FAILURE_BACKOFF_MILLIS = 15L * 60 * 1000
    }
}

/** 该不该再取：上次成功已过 [AiringTimeRepository.REFRESH_TTL_MILLIS]，且上次尝试已过 [AiringTimeRepository.FAILURE_BACKOFF_MILLIS]。 */
internal fun airingTimesDue(meta: AiringTimeMetaEntity, nowMillis: Long): Boolean =
    isCacheStale(meta.refreshedAt, AiringTimeRepository.REFRESH_TTL_MILLIS, nowMillis) &&
        isCacheStale(meta.attemptedAt, AiringTimeRepository.FAILURE_BACKOFF_MILLIS, nowMillis)

/** bangumi-data 里 bgm.tv 的站点名。 */
private const val BANGUMI_SITE = "bangumi"

/**
 * 整份数据 → 表行：只留有 bgm.tv 条目 id、每周播一次（[weeklyBroadcastStartOf]）的作品；同一条目录了两次时取开播晚的那条
 * （数据源偶有重复录入，晚的那条是后来修正的）。
 */
internal fun airingTimeRows(items: List<BangumiDataItemDto>): List<AiringTimeEntity> {
    val latestBegin = HashMap<Int, String>()
    val starts = HashMap<Int, Long>()
    for (item in items) {
        val subjectId = item.sites.firstOrNull { it.site == BANGUMI_SITE }?.id?.toIntOrNull() ?: continue
        val start = weeklyBroadcastStartOf(item.broadcast) ?: continue
        val previous = latestBegin[subjectId]
        if (previous == null || item.begin > previous) {
            latestBegin[subjectId] = item.begin
            starts[subjectId] = start
        }
    }
    return starts.map { (subjectId, start) -> AiringTimeEntity(subjectId, start) }
}
