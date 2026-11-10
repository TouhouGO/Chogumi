package io.github.touhougo.chogumi.feature.home.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.touhougo.chogumi.core.data.progress.AdvanceTarget
import io.github.touhougo.chogumi.core.data.progress.EpTapCoordinator
import io.github.touhougo.chogumi.core.data.progress.ProgressOrigin
import io.github.touhougo.chogumi.core.data.repository.AiringTimeRepository
import io.github.touhougo.chogumi.core.data.repository.AuthRepository
import io.github.touhougo.chogumi.core.data.repository.CalendarRepository
import io.github.touhougo.chogumi.core.data.repository.CollectionObserver
import io.github.touhougo.chogumi.core.data.repository.HomeRepository
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.SessionState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 这个 App 的全部桌面小组件（刷新时逐个更新）。 */
internal fun allWidgets(): List<GlanceAppWidget> = listOf(ProgressWidget(), SubjectWidget(), HeatWidget(), TodayWidget(), WeekWidget())

/**
 * 小组件上点 +1、放送提醒上点「看过了」：交给标进度协调器（首页、进度页、详情页同一个），小组件发起的失败画在那一行上。
 *
 * 协调器先写本地（小组件跟着本地刷新，点下去就是新进度）、停手 2 秒后才提交。点击是一次广播，广播结束后进程随时可能被回收，
 * 所以 [advance] 一直挂起到这一轮写入（含失败回滚）结束（[EpTapCoordinator.active] 不再有这部）：广播没结束，提交就不会丢。
 */
internal class WidgetProgressActions(
    private val source: WidgetWorksSource,
    private val coordinator: EpTapCoordinator,
) {
    private val _failed = MutableStateFlow<Set<Int>>(emptySet())

    /** 从小组件发起、写失败（本地已回滚）的作品：那一行写「没同步上，点一下重试」，再点一次就清掉。 */
    val failed: StateFlow<Set<Int>> = _failed.asStateFlow()

    /**
     * 收协调器报回的失败，只认小组件发起的那些；由 [WidgetUpdates] 在应用级作用域里一直收着。
     * 换号作废的点击（[Failure.SessionChanged]）属于上一个账号，不画到下一个账号的那一行上。
     */
    suspend fun collectFailures() {
        coordinator.errors.collect { error ->
            if (error.origin == ProgressOrigin.WIDGET && error.failure != Failure.SessionChanged) _failed.update { it + error.subjectId }
        }
    }

    /** 换了账号：上一个账号的失败标记不留给下一个。 */
    fun forgetFailures() = _failed.update { emptySet() }

    suspend fun advance(subjectId: Int) = advance(ProgressOrigin.WIDGET, subjectId) { true }

    /**
     * 放送提醒上的「看过了」：这部的下一集还是通知说的那一集（[episodeId]）才标；点之前已经在别处标过了就什么也不写。
     * 失败只有本地回滚（没有地方可画）。
     */
    suspend fun advanceEpisode(subjectId: Int, episodeId: Int) = advance(ProgressOrigin.NOTIFICATION, subjectId) { work ->
        (work.button as? EpButtonState.Next)?.episode?.id == episodeId
    }

    private suspend fun advance(origin: ProgressOrigin, subjectId: Int, accept: (WidgetWork) -> Boolean) {
        // 账号在点下那一刻取（advanceResolved），读列表期间换了号，这次点击作废
        val queued = coordinator.advanceResolved(origin) {
            val ready = source.load() as? WidgetWorks.Ready
            // 点的那一行已经不在列表里（别处看完了、换了号）：什么也不写，小组件刷新后显示现在的样子
            ready?.works?.firstOrNull { it.subjectId == subjectId }?.takeIf(accept)?.let { work ->
                if (origin == ProgressOrigin.WIDGET) _failed.update { it - subjectId }
                AdvanceTarget(work.progressSubject, ready.today)
            }
        }
        if (!queued) return
        if (withTimeoutOrNull(ROUND_START_MILLIS) { coordinator.active.first { subjectId in it } } == null) return
        withTimeoutOrNull(ROUND_END_MILLIS) { coordinator.active.first { subjectId !in it } }
    }

    private companion object {
        /** 点击进协调器的队列到开始这一轮：本地读写，正常是毫秒级。 */
        const val ROUND_START_MILLIS = 5_000L

        /** 停手 2 秒再提交、加上一次请求与失败回滚；广播最长约一分钟，留出余量。 */
        const val ROUND_END_MILLIS = 45_000L
    }
}

/** 小组件上的 +1（[WidgetProgressActions.advance]），参数是作品 id。 */
internal class AdvanceEpisodeAction : ActionCallback, KoinComponent {
    private val actions: WidgetProgressActions by inject()
    private val updates: WidgetUpdates by inject()

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val subjectId = parameters[SubjectIdKey] ?: return
        updates.ensureRunning()
        actions.advance(subjectId)
        updates.updateNow()
    }

    companion object {
        val SubjectIdKey = ActionParameters.Key<Int>("subjectId")
    }
}

/**
 * 小组件什么时候重画——全 App 唯一的入口：[widgetInputs] 里任何一个变了（本机的收藏与逐集状态、写失败、换号、配色设置、
 * 进程的配置），就把所有小组件重画一遍。连着来的变化合成一次。
 *
 * 只在桌面上有小组件时收：[ensureRunning] 由 App 启动（有小组件时）与小组件的接收器调用，最后一个小组件移除时停。
 * App 不在时靠 [WidgetRefreshWorker] 每小时取一次首页进度、重画一次（跨零点、在网页上改了进度）。
 */
@OptIn(FlowPreview::class)
class WidgetUpdates internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val collectionObserver: CollectionObserver,
    private val preferences: PreferencesRepository,
    private val authRepository: AuthRepository,
    private val actions: WidgetProgressActions,
) {
    private var job: Job? = null
    private val _revision = MutableStateFlow(0L)

    /** 每次 [updateNow] 加一：开着的小组件会话按它重读（见 [provideLiveContent]）。 */
    internal val revision: StateFlow<Long> = _revision.asStateFlow()

    /** App 启动时调用：桌面上有小组件才开始收。 */
    suspend fun startIfPlaced() {
        if (anyPlaced()) ensureRunning()
    }

    @Synchronized
    internal fun ensureRunning() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { actions.collectFailures() }
            // 只认真的换号（登出、换了另一个账号）：会话初始化那一拍不算
            launch { accounts().drop(1).collect { actions.forgetFailures() } }
            widgetInputs(context, preferences, collectionObserver.observeProgress(), actions.failed, accounts())
                .debounce(UPDATE_DEBOUNCE_MILLIS).collect { updateNow() }
        }
    }

    /** 一种小组件的最后一个被移走时由接收器调用：别的小组件也都不在了，就停止收、取消每小时的刷新。 */
    internal fun stopIfNonePlaced() {
        scope.launch {
            if (anyPlaced()) return@launch
            synchronized(this@WidgetUpdates) {
                job?.cancel()
                job = null
            }
            WidgetRefreshWorker.cancel(context)
        }
    }

    internal suspend fun updateNow() {
        _revision.update { it + 1 }
        allWidgets().forEach { widget -> outcomeOf { widget.updateAll(context) } }
    }

    private fun accounts() = authRepository.sessionState.map { (it as? SessionState.Authenticated)?.account }.distinctUntilChanged()

    private suspend fun anyPlaced(): Boolean {
        val manager = GlanceAppWidgetManager(context)
        return allWidgets().any { widget -> outcomeOf { manager.getGlanceIds(widget.javaClass) }.valueOrNull().orEmpty().isNotEmpty() }
    }

    private companion object {
        const val UPDATE_DEBOUNCE_MILLIS = 300L
    }
}

/**
 * 小组件画面读的一切，任何一个变了画面就旧了：[data]（本机的收藏与逐集状态、写失败、账号）、App 的配色设置，
 * 以及这个进程的配置。颜色与文字是在这个进程里按当前配置算好再交给桌面的：「系统取色」读的系统配色（换壁纸后系统换配色，
 * 进程收到的就是一次配置变更）、界面语言都跟着配置走，所以配置每变一次都要重画。
 */
internal fun widgetInputs(context: Context, preferences: PreferencesRepository, vararg data: Flow<*>): Flow<Unit> = merge(
    *data.map { flow -> flow.map { } }.toTypedArray(),
    preferences.themePreferences.map { },
    preferences.themeMode.map { },
    configurationChanges(context),
)

/** 进程的配置每变一次发一拍：登记在应用上的 [ComponentCallbacks]，收集期间一直登记着。 */
private fun configurationChanges(context: Context): Flow<Unit> = callbackFlow {
    val app = context.applicationContext
    val callbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            trySend(Unit)
        }

        @Deprecated("系统已弃用，内存紧张与小组件画面无关")
        override fun onLowMemory() = Unit
    }
    app.registerComponentCallbacks(callbacks)
    awaitClose { app.unregisterComponentCallbacks(callbacks) }
}

/**
 * 小组件的内容：先读一次、画出来，之后每次 [WidgetUpdates.updateNow] 重读一次（连同配色）。
 *
 * 会话还开着时（Glance 在最后一次更新后留它一会儿），`updateAll` 只重读 Glance 自己的状态、不重跑 `provideGlance`，
 * 所以重读挂在 [WidgetUpdates.revision] 上；会话关了，下一次更新从头跑 `provideGlance`，也就是第一次读。
 */
internal suspend fun <T> GlanceAppWidget.provideLiveContent(
    context: Context,
    updates: WidgetUpdates,
    preferences: PreferencesRepository,
    load: suspend () -> T,
    content: @Composable (T) -> Unit,
): Nothing {
    suspend fun frame() = WidgetPalette.of(context, preferences) to load()
    val seen = updates.revision.value
    val first = frame()
    provideContent {
        val current by produceState(first) {
            updates.revision.filter { it != seen }.collectLatest { value = frame() }
        }
        CompositionLocalProvider(LocalWidgetPalette provides current.first) { content(current.second) }
    }
}

/**
 * 各种小组件共用的接收器：放上、系统要更新时确保 [WidgetUpdates] 在收、排上每小时的刷新；一种小组件全移走时问一遍别的还在不在。
 */
abstract class ChogumiWidgetReceiver : GlanceAppWidgetReceiver(), KoinComponent {
    private val updates: WidgetUpdates by inject()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        updates.ensureRunning()
        WidgetRefreshWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        updates.stopIfNonePlaced()
    }
}

/**
 * 每小时取一次首页进度、放送表与放送时刻表（都是到期才真去网络，见 `HomeRepository.getHomeProgress`、`CalendarRepository.getCalendar`、
 * `AiringTimeRepository.refreshIfStale`），然后重画所有小组件（放送日北京时间 4 点翻页也靠这一下跟上，断网也照跑）。
 */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val homeRepository: HomeRepository by inject()
    private val calendarRepository: CalendarRepository by inject()
    private val airingTimeRepository: AiringTimeRepository by inject()
    private val updates: WidgetUpdates by inject()

    override suspend fun doWork(): Result {
        // 取不到就留着上一次画的：小组件上的内容来自本机，下一轮再取
        refreshCalendarSources(homeRepository, calendarRepository, airingTimeRepository)
        updates.updateNow()
        return Result.success()
    }

    internal companion object {
        private const val NAME = "widget-refresh"

        /** 已排上的沿用原来的时刻，只换成这一份的条件（[ExistingPeriodicWorkPolicy.UPDATE]）。 */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, refreshRequest())
        }

        /** 不要求联网：断网时取数各自失败，照样按本机的数据重画，放送日跨过 4 点也翻页。 */
        fun refreshRequest(): PeriodicWorkRequest = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(1, TimeUnit.HOURS).build()

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}

/**
 * 到期才真去网络地取一次首页进度、放送表与放送时刻表（`HomeRepository.getHomeProgress`、`CalendarRepository.getCalendar`、
 * `AiringTimeRepository.refreshIfStale` 各自判到没到期）；取不到就留着本机的。小组件与放送提醒的每小时刷新都经它。
 */
internal suspend fun refreshCalendarSources(
    homeRepository: HomeRepository,
    calendarRepository: CalendarRepository,
    airingTimeRepository: AiringTimeRepository,
) {
    outcomeOf { homeRepository.getHomeProgress().collect { } }
    outcomeOf { calendarRepository.getCalendar().collect { } }
    airingTimeRepository.refreshIfStale()
}
