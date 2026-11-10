package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.data.util.ObservedPreferences
import io.github.touhougo.chogumi.core.model.AiringReminderMode
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.CommunityCheckInterval
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.CoverQuality
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.model.NotificationSettings
import io.github.touhougo.chogumi.core.model.ReadingTextSize
import io.github.touhougo.chogumi.core.model.RefreshRate
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.ThemeColorMode
import io.github.touhougo.chogumi.core.model.ThemePreferences
import io.github.touhougo.chogumi.core.model.ThemePreset
import io.github.touhougo.chogumi.core.model.ThemeMode
import io.github.touhougo.chogumi.core.model.ToWatchLimit
import io.github.touhougo.chogumi.core.model.UpdateCheckInterval
import io.github.touhougo.chogumi.core.model.appFeatures
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 应用偏好（独占 chogumi_preferences DataStore 文件，保留云备份能力）。
 *
 * 键名真源见 [PreferenceKeys]。
 *
 * **不含 NSFW 开关**：NSFW 显示由 bgm.tv 账号设置（`p1/privacy`）决定，
 * 本地只存按账号隔离的缓存，见 [NsfwPreferenceRepository]。
 */
class PreferencesRepository(
    dataStore: DataStore<Preferences>,
) {
    private val prefs = ObservedPreferences(dataStore)

    val themePreferences: Flow<ThemePreferences> = select { prefs ->
        ThemePreferences(
            mode = prefs.enumOr(PreferenceKeys.COLOR_MODE, ThemeColorMode.SEED),
            seedArgb = prefs[PreferenceKeys.COLOR_SEED]?.takeIf { it in 0xFF000000L..0xFFFFFFFFL }
                ?: ThemePreferences.DEFAULT_SEED,
            preset = prefs.enumOr(PreferenceKeys.COLOR_PRESET, ThemePreset.INK),
        )
    }

    suspend fun setThemePreferences(value: ThemePreferences) {
        require(value.seedArgb in 0xFF000000L..0xFFFFFFFFL) { "主题色必须是不透明的 ARGB 颜色" }
        prefs.edit {
            it[PreferenceKeys.COLOR_MODE] = value.mode.name
            it[PreferenceKeys.COLOR_SEED] = value.seedArgb
            it[PreferenceKeys.COLOR_PRESET] = value.preset.name
        }
    }

    val themeMode: Flow<ThemeMode> = select { it.enumOr(PreferenceKeys.THEME_MODE, ThemeMode.FOLLOW_SYSTEM) }

    suspend fun setThemeMode(value: ThemeMode) {
        prefs.edit { it[PreferenceKeys.THEME_MODE] = value.name }
    }

    /**
     * 体验模式（完整 / 专注），缺省与未知值一律回落到 [ExperienceMode.FULL]。
     *
     * 与 [themeMode] 同一套存取方式（同一个偏好 DataStore、同一个键名真源 [PreferenceKeys]）。
     * 本 Flow 只提供**档位**；功能可见性由 [appFeatures] 唯一派生，此处不做判定。
     */
    val experienceMode: Flow<ExperienceMode> = select { it.enumOr(PreferenceKeys.EXPERIENCE_MODE, ExperienceMode.FULL) }

    suspend fun setExperienceMode(value: ExperienceMode) {
        prefs.edit { it[PreferenceKeys.EXPERIENCE_MODE] = value.name }
    }

    /**
     * 显示公共评分开关，缺省为 true。与体验模式正交。
     */
    val showPublicRatings: Flow<Boolean> = select { prefs ->
        prefs[PreferenceKeys.SHOW_PUBLIC_RATINGS] ?: true
    }

    suspend fun setShowPublicRatings(value: Boolean) {
        prefs.edit { it[PreferenceKeys.SHOW_PUBLIC_RATINGS] = value }
    }

    /** 封面显示评分开关，缺省为 true。只存开关本身；公共评分关掉时它不起作用，由 [appFeatures] 派生。 */
    val showCoverScores: Flow<Boolean> = select { prefs ->
        prefs[PreferenceKeys.SHOW_COVER_SCORES] ?: true
    }

    suspend fun setShowCoverScores(value: Boolean) {
        prefs.edit { it[PreferenceKeys.SHOW_COVER_SCORES] = value }
    }

    /** 好友标识开关，缺省为 true。只存开关本身；专注模式下它不起作用，由 [appFeatures] 派生。 */
    val showFriendMarks: Flow<Boolean> = select { prefs ->
        prefs[PreferenceKeys.SHOW_FRIEND_MARKS] ?: true
    }

    suspend fun setShowFriendMarks(value: Boolean) {
        prefs.edit { it[PreferenceKeys.SHOW_FRIEND_MARKS] = value }
    }

    /**
     * 首页「待看」显示条数，缺省与不在 [ToWatchLimit.OPTIONS] 里的值一律回落 [ToWatchLimit.DEFAULT]。
     *
     * 可选值真源就是 [ToWatchLimit]——**不在本仓库里再抄一份档位清单**（改了档位这里自动跟上）。
     */
    val toWatchLimit: Flow<Int> = select { prefs ->
        ToWatchLimit.coerceOr(prefs[PreferenceKeys.HOME_TO_WATCH_LIMIT])
    }

    suspend fun setToWatchLimit(value: Int) {
        require(value in ToWatchLimit.OPTIONS) { "首页待看条数必须是 ToWatchLimit.OPTIONS 里的一档，收到 $value" }
        prefs.edit { it[PreferenceKeys.HOME_TO_WATCH_LIMIT] = value }
    }

    /** 阅读字号档位，缺省与未知值回落 [ReadingTextSize.DEFAULT]。 */
    val readingTextSize: Flow<ReadingTextSize> = select { it.enumOr(PreferenceKeys.READING_TEXT_SIZE, ReadingTextSize.DEFAULT) }

    suspend fun setReadingTextSize(value: ReadingTextSize) {
        prefs.edit { it[PreferenceKeys.READING_TEXT_SIZE] = value.name }
    }

    /**
     * 进度页启动时打开的分类：null = 全部（缺省）。存的不是五个内容分类之一（[ContentDomains]，比如改名、降级安装留下的值）
     * 也读成全部。只在进度页刚建好、还没有首页的入口请求时读一次，之后的切换不改它。
     */
    val progressDefaultType: Flow<SubjectType?> = select { prefs ->
        prefs.enumOrNull<SubjectType>(PreferenceKeys.PROGRESS_DEFAULT_TYPE)?.takeIf { it in ContentDomains }
    }

    suspend fun setProgressDefaultType(value: SubjectType?) {
        require(value == null || value in ContentDomains) { "进度页默认分类只能是全部或五个内容分类之一，收到 $value" }
        prefs.edit {
            if (value == null) {
                it.remove(PreferenceKeys.PROGRESS_DEFAULT_TYPE)
            } else {
                it[PreferenceKeys.PROGRESS_DEFAULT_TYPE] = value.name
            }
        }
    }

    /** 封面画质档位，缺省与未知值回落 [CoverQuality.DEFAULT]。 */
    val coverQuality: Flow<CoverQuality> = select { it.enumOr(PreferenceKeys.COVER_QUALITY, CoverQuality.DEFAULT) }

    suspend fun setCoverQuality(value: CoverQuality) {
        prefs.edit { it[PreferenceKeys.COVER_QUALITY] = value.name }
    }

    /**
     * 刷新率存值（整数 Hz），缺省 [RefreshRate.SYSTEM]。不按档位收敛：档位来自屏幕，这块屏上用哪一档由 [RefreshRate.effective] 判，
     * 换到有这一档的屏上选择还在。
     */
    val refreshRate: Flow<Int> = select { prefs -> prefs[PreferenceKeys.REFRESH_RATE] ?: RefreshRate.SYSTEM }

    suspend fun setRefreshRate(value: Int) {
        require(value >= RefreshRate.SYSTEM) { "刷新率是跟随系统或一个正的 Hz 数，收到 $value" }
        prefs.edit { it[PreferenceKeys.REFRESH_RATE] = value }
    }

    /** 手势引导放过没有（看完或跳过都算）：启动时只自动放一次，之后只从设置的「使用技巧」重看。 */
    val gestureTourShown: Flow<Boolean> = select { prefs ->
        prefs[PreferenceKeys.GESTURE_TOUR_SHOWN] ?: false
    }

    suspend fun setGestureTourShown() {
        prefs.edit { it[PreferenceKeys.GESTURE_TOUR_SHOWN] = true }
    }

    /** 自动检查更新开没开：默认开。 */
    val updateCheck: Flow<Boolean> = select { prefs -> prefs[PreferenceKeys.UPDATE_CHECK] ?: true }

    suspend fun setUpdateCheck(value: Boolean) {
        prefs.edit { it[PreferenceKeys.UPDATE_CHECK] = value }
    }

    /** 自动检查更新的间隔：默认 [UpdateCheckInterval.DEFAULT]。 */
    val updateCheckInterval: Flow<UpdateCheckInterval> =
        select { it.enumOr(PreferenceKeys.UPDATE_CHECK_INTERVAL, UpdateCheckInterval.DEFAULT) }

    suspend fun setUpdateCheckInterval(value: UpdateCheckInterval) {
        prefs.edit { it[PreferenceKeys.UPDATE_CHECK_INTERVAL] = value.name }
    }

    /** 上次查到最新版的时刻（epoch 毫秒），没查到过为 null：检查间隔从它起算（`updateCheckDue`）。 */
    val updateCheckedAt: Flow<Long?> = select { prefs -> prefs[PreferenceKeys.UPDATE_CHECKED_AT] }

    suspend fun setUpdateCheckedAt(now: Long) {
        prefs.edit { it[PreferenceKeys.UPDATE_CHECKED_AT] = now }
    }

    /** 用户「忽略」过的那一版的 versionCode，没有为 null：它和更早的版本不再提示（`updateOfferOf`）。 */
    val ignoredUpdateVersionCode: Flow<Int?> = select { prefs -> prefs[PreferenceKeys.UPDATE_IGNORED_VERSION_CODE] }

    suspend fun ignoreUpdate(versionCode: Int) {
        prefs.edit { it[PreferenceKeys.UPDATE_IGNORED_VERSION_CODE] = versionCode }
    }

    /** 系统通知的设置（设置 › 通知）：两个开关默认关，各自的方式与时刻见 [NotificationSettings]。 */
    val notificationSettings: Flow<NotificationSettings> = select { prefs ->
        NotificationSettings(
            community = prefs[PreferenceKeys.NOTIFY_COMMUNITY] ?: false,
            interval = prefs.enumOr(PreferenceKeys.NOTIFY_COMMUNITY_INTERVAL, CommunityCheckInterval.DEFAULT),
            airing = prefs[PreferenceKeys.NOTIFY_AIRING] ?: false,
            airingMode = prefs.enumOr(PreferenceKeys.NOTIFY_AIRING_MODE, AiringReminderMode.AT_AIRING),
            dailyMinute = NotificationSettings.dailyMinuteOf(prefs[PreferenceKeys.NOTIFY_AIRING_DAILY_MINUTE]),
        )
    }

    suspend fun setCommunityNotifications(value: Boolean) {
        prefs.edit { it[PreferenceKeys.NOTIFY_COMMUNITY] = value }
    }

    suspend fun setCommunityCheckInterval(value: CommunityCheckInterval) {
        prefs.edit { it[PreferenceKeys.NOTIFY_COMMUNITY_INTERVAL] = value.name }
    }

    suspend fun setAiringNotifications(value: Boolean) {
        prefs.edit { it[PreferenceKeys.NOTIFY_AIRING] = value }
    }

    suspend fun setAiringReminderMode(value: AiringReminderMode) {
        prefs.edit { it[PreferenceKeys.NOTIFY_AIRING_MODE] = value.name }
    }

    suspend fun setAiringDailyMinute(value: Int) {
        require(value in 0 until NotificationSettings.MINUTES_PER_DAY) { "汇总时刻是一天里的分钟数（0..1439），收到 $value" }
        prefs.edit { it[PreferenceKeys.NOTIFY_AIRING_DAILY_MINUTE] = value }
    }

    /** 第一次打开提醒时问过「后台运行」没有：只问一次，之后从设置里的「后台运行」去。 */
    val backgroundPrompted: Flow<Boolean> = select { prefs -> prefs[PreferenceKeys.NOTIFY_BACKGROUND_PROMPTED] ?: false }

    suspend fun setBackgroundPrompted() {
        prefs.edit { it[PreferenceKeys.NOTIFY_BACKGROUND_PROMPTED] = true }
    }

    /**
     * 全仓唯一的功能可见性流：集中 combine 四个偏好，经纯函数 [appFeatures] 派生。
     * 各 ViewModel 与 LocalAppFeatures 只订阅这一条，不各自 combine。
     */
    val appFeatures: Flow<AppFeatures> = combine(
        experienceMode,
        showPublicRatings,
        showCoverScores,
        showFriendMarks,
    ) { mode, showRatings, showCover, showFriends ->
        appFeatures(mode = mode, showPublicRatings = showRatings, showCoverScores = showCover, showFriendMarks = showFriends)
    }.distinctUntilChanged()

    /** 一个偏好的流：只在它的值变了才发，别的键写入时不重发同值（订阅方不必跟着重算、重组）。 */
    private fun <T> select(read: (Preferences) -> T): Flow<T> = prefs.data.map(read).distinctUntilChanged()
}

/** 枚举偏好存的是名字：缺省与认不出的名字（改名、降级安装）一律回落 [default]。各个枚举偏好都走这一条。 */
private inline fun <reified E : Enum<E>> Preferences.enumOr(key: Preferences.Key<String>, default: E): E =
    enumOrNull<E>(key) ?: default

/** 按名字读一个枚举偏好；缺省与认不出的名字为 null。 */
private inline fun <reified E : Enum<E>> Preferences.enumOrNull(key: Preferences.Key<String>): E? =
    enumValues<E>().firstOrNull { it.name == this[key] }
