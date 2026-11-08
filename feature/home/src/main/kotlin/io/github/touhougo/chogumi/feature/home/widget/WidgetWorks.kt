package io.github.touhougo.chogumi.feature.home.widget

import io.github.touhougo.chogumi.core.data.progress.ProgressSubject
import io.github.touhougo.chogumi.core.data.repository.AuthRepository
import io.github.touhougo.chogumi.core.data.repository.CollectionObserver
import io.github.touhougo.chogumi.core.data.repository.CollectionProgressUpdate
import io.github.touhougo.chogumi.core.data.repository.HomeRepository
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.businessDateOf
import io.github.touhougo.chogumi.core.foundation.settled
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.SessionState
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.feature.home.HomeProgressCard
import io.github.touhougo.chogumi.feature.home.HomeSectionCandidates
import io.github.touhougo.chogumi.feature.home.prepareSectionCandidates
import java.time.LocalDate
import kotlinx.coroutines.flow.mapNotNull

/** 小组件上的一部作品（在看列表的一行、单部作品的整块）：首页同一张进度卡（[HomeProgressCard]）投影出来的。 */
internal data class WidgetWork(
    val subjectId: Int,
    val title: String,
    val coverUrl: String?,
    val progress: ProgressCount,
    val button: EpButtonState,
    /** 点 +1 时交给标进度协调器的那一份（与首页同一个）。 */
    val progressSubject: ProgressSubject,
)

/** 小组件这一次要画的东西。 */
internal sealed interface WidgetWorks {
    /** 没登录：小组件只放一个登录按钮。 */
    data object SignedOut : WidgetWorks

    /** 在看的作品，[works] 为空就是「还没有在看的作品」。 */
    data class Ready(val works: List<WidgetWork>, val today: LocalDate) : WidgetWorks

    /** 本机一份进度都读不出来（没有缓存、网络也不通）。 */
    data class Failed(val failure: Failure) : WidgetWorks
}

/**
 * 小组件的在看列表：先「待看」（有已播未看的，按首页的排序），再其余在追的（按最近动过），没有进度的类型（游戏、音乐）不进。
 * 区块归属与排序都已在 [prepareSectionCandidates] 里按首页的规则做完，这里只是把两段接起来、去掉重复的那部。
 */
internal fun widgetWorksOf(candidates: HomeSectionCandidates): List<WidgetWork> =
    (candidates.toWatch.map { it.card } + candidates.continueItems.map { it.card })
        .distinctBy { it.subject.id }
        .map { it.toWidgetWork() }

private fun HomeProgressCard.toWidgetWork() = WidgetWork(
    subjectId = subject.id,
    title = displayNameOf(subject),
    coverUrl = subject.coverUrl,
    progress = progressCount,
    button = epButtonState,
    progressSubject = progressSubject,
)

/**
 * 小组件读的在看进度：首页那一份（`/p1/home` 的缓存，没有缓存时才等网络）加本机的收藏与逐集状态，
 * 经首页同一个 [prepareSectionCandidates] 算出来，所以小组件上的「下一集」与首页的 EP 按钮永远是同一集。
 *
 * 「今天」在每次读的时候按 [clock] 取：小组件常在 App 不在前台时刷新，那时 `AppTime` 不走。
 */
internal class WidgetWorksSource(
    private val homeRepository: HomeRepository,
    private val collectionObserver: CollectionObserver,
    private val authRepository: AuthRepository,
    private val clock: AppClock,
) {
    suspend fun load(): WidgetWorks {
        val result = outcomeOf {
            when (widgetSignInOf(authRepository)) {
                WidgetSignIn.SIGNED_IN -> Unit
                WidgetSignIn.SIGNED_OUT -> return@outcomeOf WidgetWorks.SignedOut
                WidgetSignIn.UNREADABLE -> return@outcomeOf WidgetWorks.Failed(Failure.Storage)
            }
            val items = when (val read = homeRepository.getHomeProgress().settled()) {
                is Outcome.Success -> read.value
                is Outcome.Failed -> return@outcomeOf WidgetWorks.Failed(read.failure)
            }
            val progress = collectionObserver.observeProgress().mapNotNull { (it as? CollectionProgressUpdate.Value)?.load }.settled()
            val snapshot = when (progress) {
                is Outcome.Success -> progress.value
                is Outcome.Failed -> return@outcomeOf WidgetWorks.Failed(progress.failure)
            }
            val today = businessDateOf(clock.nowMillis())
            val candidates = prepareSectionCandidates(
                items = items,
                collections = snapshot.collections,
                watchStates = snapshot.episodes.statesBySubject,
                today = today,
                latestWatchedAt = snapshot.episodes.latestWatchedAtBySubject,
                // 小组件不画追更时间表，时刻只用在那里
                broadcastStarts = emptyMap(),
            )
            WidgetWorks.Ready(widgetWorksOf(candidates), today)
        }
        return when (result) {
            is Outcome.Success -> result.value
            is Outcome.Failed -> WidgetWorks.Failed(result.failure)
        }
    }
}

/** 小组件读数据之前先看登没登录：没登录画登录按钮，本机会话读不出来画「读不出来」。 */
internal enum class WidgetSignIn { SIGNED_IN, SIGNED_OUT, UNREADABLE }

internal suspend fun widgetSignInOf(authRepository: AuthRepository): WidgetSignIn {
    // App 没开过时会话还没读：先读一次（读过就立刻返回）
    authRepository.ensureInitialized()
    return when (authRepository.sessionState.value) {
        is SessionState.Authenticated -> WidgetSignIn.SIGNED_IN
        SessionState.Unauthenticated -> WidgetSignIn.SIGNED_OUT
        SessionState.Initializing, SessionState.InitializationError -> WidgetSignIn.UNREADABLE
    }
}
