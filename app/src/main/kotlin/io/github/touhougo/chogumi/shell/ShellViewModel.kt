package io.github.touhougo.chogumi.shell

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.AuthRepository
import io.github.touhougo.chogumi.core.data.repository.FriendsRepository
import io.github.touhougo.chogumi.core.data.repository.NotificationRepository
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.util.failuresAsLoad
import io.github.touhougo.chogumi.core.data.util.storageOutcomeOf
import io.github.touhougo.chogumi.core.foundation.AppTime
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.CoverQuality
import io.github.touhougo.chogumi.core.model.Friends
import io.github.touhougo.chogumi.core.model.ReadingTextSize
import io.github.touhougo.chogumi.core.model.RefreshRate
import io.github.touhougo.chogumi.core.model.SessionState
import io.github.touhougo.chogumi.core.model.SignedInAccount
import io.github.touhougo.chogumi.core.model.ThemeMode
import io.github.touhougo.chogumi.core.model.ThemePreferences
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ShellState(
    val session: SessionState = SessionState.Initializing,
    val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    val themePreferences: ThemePreferences = ThemePreferences(),
    /**
     * 功能可见性（完整 / 专注、公共评分开 / 关）：由偏好 DataStore 的 [PreferencesRepository.appFeatures] 提供，
     * 这里是**唯一提供点**的取值来源（AppShell 把它放进 LocalAppFeatures）。
     */
    val features: AppFeatures = AppFeatures(community = true, publicRatings = true, coverScores = true, friendMarks = true),
    /** 阅读字号档位：AppShell 放进 LocalReadingTextSize（唯一提供点）。 */
    val readingTextSize: ReadingTextSize = ReadingTextSize.DEFAULT,
    /** 封面画质档位：AppShell 放进 LocalCoverQuality（唯一提供点）。 */
    val coverQuality: CoverQuality = CoverQuality.DEFAULT,
    /** 刷新率存值（`RefreshRate`）：AppActivity 交给窗口（`requestRefreshRate`，唯一落点）。 */
    val refreshRate: Int = RefreshRate.SYSTEM,
    /** 简单返回动画：AppShell 交给页面区（`AppNavDisplay`，唯一落点）。 */
    val simpleBack: Boolean = false,
    /** 社区角标的数：未读通知 + 未读短信（`NotificationRepository.unreadTotal`）。 */
    val unreadCount: Int = 0,
    /** 本地会话或偏好读不出来（整屏报「无法恢复本地设置」+ 重试）。 */
    val restoreFailed: Boolean = false,
    /** 这一轮恢复已过了 [SLOW_START_MILLIS]（计时到点记下，重试时清掉）；怎么显示由 [initializing]、[slowStart] 判定。 */
    val restoreOverdue: Boolean = false,
    /** 盖在页面上放手势引导：这台设备上还没放过（偏好里没有记录，新装与升级上来都是），这一次也还没看完或跳过。 */
    val gestureTour: Boolean = false,
) {
    private val restoring: Boolean get() = session == SessionState.Initializing && !restoreFailed

    /** 启动屏（splash）是否还留着：还在恢复、没出错、也还没到慢启动提示。 */
    val initializing: Boolean get() = restoring && !restoreOverdue

    /** 慢启动提示：还在恢复、没出错，但超过 [SLOW_START_MILLIS]。撤下启动屏、显示「正在读取」，但**继续等**，读完自动进入。 */
    val slowStart: Boolean get() = restoring && restoreOverdue

    /** 当前账号；没登录时为 null。 */
    val account: SignedInAccount? get() = (session as? SessionState.Authenticated)?.account
}

/** 先异步恢复本地会话和主题，再显示页面；不等待任何首页网络请求。 */
class ShellViewModel(
    private val authRepository: AuthRepository,
    private val preferencesRepository: PreferencesRepository,
    private val notificationRepository: NotificationRepository,
    friendsRepository: FriendsRepository,
    appTime: AppTime,
) : StateViewModel<ShellState>(ShellState()) {
    /** 「现在」（整分钟一跳）：AppShell 放进 LocalNow（唯一提供点），相对时间与日期头据此每分钟重算。 */
    val now: StateFlow<Long> = appTime.now

    /** 当前账号关注的人：AppShell 放进 LocalFriends（唯一提供点），名字旁的「好友」小牌据此画，加好友、取消好友后当场变。 */
    val friends: StateFlow<Friends> = friendsRepository.changes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FRIENDS_STOP_TIMEOUT_MILLIS), Friends.NONE)

    private val links = Channel<Route>(Channel.UNLIMITED)

    /**
     * 从别的应用点进来的链接要打开的页面，每个只交出一次：AppShell 在导航建好之后收、推到当前 tab 上。
     * 启动还没恢复完时进来的在这里等着；Activity 重建不丢（ViewModel 活过重建）。
     */
    val linkRoutes: Flow<Route> = links.receiveAsFlow()

    private var initialization: Job? = null

    /** 这一次进来手势引导看完或跳过了：偏好记下之前、记不下来时都立刻收起（[finishGestureTour]）。 */
    private val gestureTourFinished = MutableStateFlow(false)

    init { retryInitialization() }

    /**
     * 恢复本地会话与偏好。**慢不等于失败**：不能用 `withTimeout(5_000)` 一到就取消并报「本地设置读取超时」——
     * 新装 / 更新后第一次冷启动（ART 还在编译、DataStore 首读）常常超过 5 秒，读得出来也会被当成错误、要用户手点重试。
     * 超过 [SLOW_START_MILLIS] 只是撤下启动屏、显示「正在读取」（读取真卡死时用户也能重试），读完自动进入；
     * 只有真的读失败才显示错误。
     */
    fun retryInitialization() {
        initialization?.cancel()
        updateState { it.copy(session = SessionState.Initializing, restoreFailed = false, restoreOverdue = false) }
        initialization = viewModelScope.launch {
            val slowNotice = launch {
                delay(SLOW_START_MILLIS)
                updateState { it.copy(restoreOverdue = true) }
            }
            authRepository.ensureInitialized()
            restoredStates().collect { load ->
                slowNotice.cancel()
                when (load) {
                    is Load.Ready -> updateState { load.value }
                    // 只有本地偏好读不出来（DataStore 磁盘 / 损坏）才走到这里；会话错误已由 AuthRepository 映射成 InitializationError
                    is Load.Failed -> updateState { it.copy(restoreFailed = true) }
                    Load.Pending -> Unit
                }
            }
        }
    }

    /** 会话、外观、功能可见性、未读数、要不要放手势引导合成整页状态；会话恢复完成前不发，读失败时最后一拍是 [Load.Failed]。 */
    private fun restoredStates(): Flow<Load<ShellState>> = combine(
        authRepository.sessionState,
        // 外观几项先合成一组：combine 的定型重载最多五路。
        combine(
            preferencesRepository.themeMode,
            preferencesRepository.themePreferences,
            preferencesRepository.readingTextSize,
            preferencesRepository.coverQuality,
            combine(preferencesRepository.refreshRate, preferencesRepository.simpleBack, ::Pair),
        ) { themeMode, themePreferences, readingTextSize, coverQuality, (refreshRate, simpleBack) ->
            Appearance(themeMode, themePreferences, readingTextSize, coverQuality, refreshRate, simpleBack)
        },
        preferencesRepository.appFeatures,
        notificationRepository.unreadTotal,
        combine(preferencesRepository.gestureTourShown, gestureTourFinished) { shown, finished -> !shown && !finished },
    ) { session, appearance, features, unreadCount, gestureTour ->
        ShellState(
            session = session,
            themeMode = appearance.themeMode,
            themePreferences = appearance.themePreferences,
            features = features,
            readingTextSize = appearance.readingTextSize,
            coverQuality = appearance.coverQuality,
            refreshRate = appearance.refreshRate,
            simpleBack = appearance.simpleBack,
            unreadCount = unreadCount,
            restoreFailed = session == SessionState.InitializationError,
            gestureTour = gestureTour,
        )
    }
        .dropWhile { it.session == SessionState.Initializing }
        .map<ShellState, Load<ShellState>> { Load.Ready(it) }
        .failuresAsLoad()

    /**
     * 手势引导看完或跳过：当场收起，记下放过了，之后只从设置的「使用技巧」重看。记不下来时（存储故障）下次启动再放一次，
     * 用户对此无能为力，不提示。
     */
    fun finishGestureTour() {
        gestureTourFinished.value = true
        viewModelScope.launch { storageOutcomeOf { preferencesRepository.setGestureTourShown() } }
    }

    fun openLink(route: Route) {
        links.trySend(route)
    }

    fun refreshNotifications() {
        viewModelScope.launch {
            notificationRepository.refreshUnreadCount()
        }
    }

    /**
     * 点开了一条社区提醒的系统通知：它标为已读（与在铃铛面板里点开一样）。标不上时它留在未读里，铃铛面板照常显示；
     * 用户对此无能为力，不提示。
     */
    fun markNoticeRead(id: Int) {
        viewModelScope.launch { notificationRepository.markRead(id) }
    }
}

/** 外观偏好的一组（主题、配色、阅读字号、封面画质、刷新率、简单返回动画），只在 [ShellViewModel.restoredStates] 里合成。 */
private data class Appearance(
    val themeMode: ThemeMode,
    val themePreferences: ThemePreferences,
    val readingTextSize: ReadingTextSize,
    val coverQuality: CoverQuality,
    val refreshRate: Int,
    val simpleBack: Boolean,
)

/**
 * 恢复本地会话与偏好超过这么久才撤下启动屏、提示「正在读取」。正常启动远在此之前就恢复完，到这时还没完成才值得
 * 告诉用户并给出重试；再短，慢而正常的首次冷启动也会闪出提示。我的设计决定（not in M3）。
 */
internal const val SLOW_START_MILLIS = 5_000L

/** 界面不再订阅好友名单后多久停止收集：转屏重建期间不断开，退到后台久了才停。 */
private const val FRIENDS_STOP_TIMEOUT_MILLIS = 5_000L
