package io.github.touhougo.chogumi.feature.settings

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.DisplayScaleRepository
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.util.failuresAsLoad
import io.github.touhougo.chogumi.core.data.util.launchWrite
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.AppIcon
import io.github.touhougo.chogumi.core.model.CoverQuality
import io.github.touhougo.chogumi.core.model.DisplayScale
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.model.ReadingTextSize
import io.github.touhougo.chogumi.core.model.RefreshRate
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.ThemeMode
import io.github.touhougo.chogumi.core.model.ThemePreferences
import io.github.touhougo.chogumi.core.model.ToWatchLimit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** 外观、首页与进度、评分与社区三个分类页共用的状态：本地偏好与桌面图标。 */
data class PreferenceSettingsUiState(
    val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    val themePreferences: ThemePreferences = ThemePreferences(),
    /** 界面大小存值（[DisplayScale]）：这块屏上实际用哪一档由页面按屏宽收敛。改了 Activity 重建，新密度才生效。 */
    val displayScale: Int = DisplayScale.SYSTEM,
    /** 刷新率存值（[RefreshRate]）：这块屏上选中哪一档由页面按屏幕的模式收敛。 */
    val refreshRate: Int = RefreshRate.SYSTEM,
    /** 桌面图标：真源是系统里开着的那个启动入口，读到之前按清单默认画。 */
    val appIcon: AppIcon = AppIcon.DEFAULT,
    /** 体验模式（完整 / 专注）：本地偏好，切换即时生效。 */
    val experienceMode: ExperienceMode = ExperienceMode.FULL,
    /** 名字旁画不画「好友」小牌：本地偏好，只在完整模式下起作用。 */
    val showFriendMarks: Boolean = true,
    /** 是否显示公共评分：本地偏好，与体验模式正交。 */
    val showPublicRatings: Boolean = true,
    /** 封面上画不画分数角标：本地偏好，只在 [showPublicRatings] 开着时起作用。 */
    val showCoverScores: Boolean = true,
    /** 首页「待看」显示条数：本地偏好，取值为 [ToWatchLimit.OPTIONS] 中的一档。 */
    val toWatchLimit: Int = ToWatchLimit.DEFAULT,
    /** 进度页启动时打开的分类（null = 全部）：本地偏好，下次建进度页时生效。 */
    val progressDefaultType: SubjectType? = null,
    /** 阅读字号档位：本地偏好，帖子与楼层正文跟随。 */
    val readingTextSize: ReadingTextSize = ReadingTextSize.DEFAULT,
    /** 封面画质档位：本地偏好，作品封面跟随。 */
    val coverQuality: CoverQuality = CoverQuality.DEFAULT,
)

/** 外观、首页与进度、评分与社区三个分类页的界面事件（失败提示不在这里：走 ViewModel 的 `failures`）。 */
interface PreferenceSettingsActions {
    fun onThemeModeChange(value: ThemeMode)

    /** 体验模式切换：专注模式隐藏社区功能。 */
    fun onExperienceModeChange(value: ExperienceMode)

    /** 好友标识开关切换：关闭时任何地方都不画「好友」小牌。 */
    fun onShowFriendMarksChange(value: Boolean)

    /** 显示公共评分开关切换：关闭时隐藏他人的评分与排名数字。 */
    fun onShowPublicRatingsChange(value: Boolean)

    /** 封面显示评分开关切换：关闭时海报封面不画分数角标。 */
    fun onShowCoverScoresChange(value: Boolean)

    /** 首页「待看」显示条数变更（取值见 `ToWatchLimit.OPTIONS`）。 */
    fun onToWatchLimitChange(value: Int)

    /** 进度页默认分类变更（null = 全部）。 */
    fun onProgressDefaultTypeChange(value: SubjectType?)

    /** 阅读字号档位变更。 */
    fun onReadingTextSizeChange(value: ReadingTextSize)

    /** 封面画质档位变更。 */
    fun onCoverQualityChange(value: CoverQuality)

    /** 主题配色偏好变更。 */
    fun onThemePreferencesChange(value: ThemePreferences)

    /** 界面大小变更（取值见 `DisplayScale.OPTIONS`）。 */
    fun onDisplayScaleChange(value: Int)

    /** 刷新率变更（取值见 `RefreshRate.optionsFor`）。 */
    fun onRefreshRateChange(value: Int)

    /** 换桌面图标。 */
    fun onAppIconChange(value: AppIcon)
}

/** 外观、首页与进度、评分与社区三个分类页的 ViewModel（写偏好即时生效，主题经 ShellViewModel 联动）。 */
class PreferenceSettingsViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val displayScaleRepository: DisplayScaleRepository,
    private val appIcons: AppIcons,
) : StateViewModel<PreferenceSettingsUiState>(PreferenceSettingsUiState()), PreferenceSettingsActions {

    /** 偏好读写失败的提示。 */
    val failures = FailureNotices()

    /**
     * 切换主题模式：**只写偏好**，状态只从 `preferencesRepository.themeMode` 的 collect 来（单一真源）。
     */
    override fun onThemeModeChange(value: ThemeMode) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setThemeMode(value) }
    }

    override fun onThemePreferencesChange(value: ThemePreferences) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setThemePreferences(value) }
    }

    /** 界面大小：只写存值，界面状态只从 `displayScaleRepository.percent` 来；Activity 看到存值变了自己重建。 */
    override fun onDisplayScaleChange(value: Int) {
        displayScaleRepository.set(value)
    }

    /** 刷新率：只写偏好，界面状态只从 `preferencesRepository.refreshRate` 的 collect 来；Activity 跟着存值请求模式。 */
    override fun onRefreshRateChange(value: Int) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setRefreshRate(value) }
    }

    override fun onAppIconChange(value: AppIcon) {
        showAppIcon(select = value)
    }

    /** 界面只画系统里开着的那个入口：写完再读一次，不自己记选了哪个。 */
    private fun showAppIcon(select: AppIcon? = null) {
        viewModelScope.launchWrite(failures) {
            select?.let { appIcons.select(it) }
            val icon = appIcons.current()
            updateState { it.copy(appIcon = icon) }
        }
    }

    /**
     * 切换体验模式：**只写偏好**，界面状态只从 `preferencesRepository.experienceMode` 的 collect 来（单一真源）。
     */
    override fun onExperienceModeChange(value: ExperienceMode) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setExperienceMode(value) }
    }

    /** 切换好友标识：只写偏好，界面状态只从 `preferencesRepository.showFriendMarks` 的 collect 来（单一真源）。 */
    override fun onShowFriendMarksChange(value: Boolean) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setShowFriendMarks(value) }
    }

    /**
     * 切换公共评分显示：**只写偏好**，界面状态只从 `preferencesRepository.showPublicRatings` 的 collect 来（单一真源）。
     */
    override fun onShowPublicRatingsChange(value: Boolean) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setShowPublicRatings(value) }
    }

    /** 切换封面评分显示：只写偏好，界面状态只从 `preferencesRepository.showCoverScores` 的 collect 来（单一真源）。 */
    override fun onShowCoverScoresChange(value: Boolean) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setShowCoverScores(value) }
    }

    /**
     * 首页「待看」条数：**只写偏好**，界面状态只从 `preferencesRepository.toWatchLimit` 的 collect 来（单一真源）。
     * 合法性由仓库的 `setToWatchLimit` 断言（选项真源是 `ToWatchLimit`），此处不重复校验。
     */
    override fun onToWatchLimitChange(value: Int) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setToWatchLimit(value) }
    }

    /** 进度页默认分类：只写偏好，界面状态只从 `preferencesRepository.progressDefaultType` 的 collect 来（单一真源）。 */
    override fun onProgressDefaultTypeChange(value: SubjectType?) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setProgressDefaultType(value) }
    }

    /** 阅读字号：只写偏好，界面状态只从 `preferencesRepository.readingTextSize` 的 collect 来（单一真源）。 */
    override fun onReadingTextSizeChange(value: ReadingTextSize) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setReadingTextSize(value) }
    }

    /** 封面画质：只写偏好，界面状态只从 `preferencesRepository.coverQuality` 的 collect 来（单一真源）。 */
    override fun onCoverQualityChange(value: CoverQuality) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setCoverQuality(value) }
    }

    init {
        // 本地偏好原样映进状态；DataStore 写入后会回放新值（界面状态的唯一来源）。
        follow(failures, {
            merge(
                mirror(preferencesRepository.themePreferences) { copy(themePreferences = it) },
                mirror(preferencesRepository.themeMode) { copy(themeMode = it) },
                mirror(displayScaleRepository.percent) { copy(displayScale = it) },
                mirror(preferencesRepository.refreshRate) { copy(refreshRate = it) },
                mirror(preferencesRepository.experienceMode) { copy(experienceMode = it) },
                mirror(preferencesRepository.showFriendMarks) { copy(showFriendMarks = it) },
                mirror(preferencesRepository.showPublicRatings) { copy(showPublicRatings = it) },
                mirror(preferencesRepository.showCoverScores) { copy(showCoverScores = it) },
                mirror(preferencesRepository.toWatchLimit) { copy(toWatchLimit = it) },
                mirror(preferencesRepository.progressDefaultType) { copy(progressDefaultType = it) },
                mirror(preferencesRepository.readingTextSize) { copy(readingTextSize = it) },
                mirror(preferencesRepository.coverQuality) { copy(coverQuality = it) },
            ).failuresAsLoad()
        }) { apply -> updateState(apply) }
        // 桌面图标没有变化通知：进页时读一次，之后只在自己换过之后再读。
        showAppIcon()
    }

    /** 每个偏好只产出自己的状态变换；合并后的单一 follow 负责失败和全部偏好的重订阅。 */
    private fun <T> mirror(
        source: Flow<T>,
        apply: PreferenceSettingsUiState.(T) -> PreferenceSettingsUiState,
    ): Flow<Load<(PreferenceSettingsUiState) -> PreferenceSettingsUiState>> = source.map { value ->
        Load.Ready(value = { state: PreferenceSettingsUiState -> state.apply(value) })
    }
}
