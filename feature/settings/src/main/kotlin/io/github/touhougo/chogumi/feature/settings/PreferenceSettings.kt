package io.github.touhougo.chogumi.feature.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.model.CoverQuality
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.model.ReadingTextSize
import io.github.touhougo.chogumi.core.model.RefreshRate
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.ThemeMode
import io.github.touhougo.chogumi.core.model.ToWatchLimit
import io.github.touhougo.chogumi.core.model.appFeatures
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.navigation.SettingsSection
import io.github.touhougo.chogumi.core.ui.composables.ConnectedChoiceGroup
import io.github.touhougo.chogumi.core.ui.composables.labelOrAllRes
import io.github.touhougo.chogumi.core.ui.composables.subjectTypeTabsOf
import io.github.touhougo.chogumi.core.ui.theme.screenModes
import org.koin.compose.viewmodel.koinViewModel

/**
 * 外观、首页与进度、评分与社区三个分类页：同一个 [PreferenceSettingsViewModel]，[groups] 是这一页排哪几组。
 * 分类与它的组只在 [SettingsPageScreen] 里对应一次。
 */
@Composable
internal fun PreferenceSettingsScreen(
    section: SettingsSection,
    onBack: () -> Unit,
    groups: @Composable (PreferenceSettingsUiState, PreferenceSettingsActions) -> List<SettingsGroup>,
    modifier: Modifier = Modifier,
) {
    val viewModel: PreferenceSettingsViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val rows = groups(uiState, viewModel)
    SettingsListPage(
        title = stringResource(section.title),
        failures = viewModel.failures,
        onBack = onBack,
        modifier = modifier,
    ) { settingsGroups(rows) }
}

/**
 * 外观：主题、配色、应用图标一组；界面大小、阅读字号、刷新率（这块屏只有一种刷新率时没有这一行）、封面画质一组。
 */
@Composable
internal fun appearanceGroups(state: PreferenceSettingsUiState, actions: PreferenceSettingsActions): List<SettingsGroup> {
    val context = LocalContext.current
    val screen = remember(context) { context.screenModes() }
    val refreshRates = remember(screen) { RefreshRate.optionsFor(screen) }
    return listOf(
        SettingsGroup(
            key = "theme",
            title = null,
            rows = listOf<SettingsRow>(
                { index, count -> ThemeModeRow(index, count, state.themeMode, actions::onThemeModeChange) },
            ) + themeColorRows(state.themePreferences, actions::onThemePreferencesChange) + listOf<SettingsRow>(
                { index, count -> AppIconRow(index, count, state.appIcon, actions::onAppIconChange) },
            ),
        ),
        SettingsGroup(
            key = "size",
            title = null,
            rows = listOf<SettingsRow>(
                { index, count -> DisplayScaleRow(index, count, state.displayScale, actions::onDisplayScaleChange) },
                { index, count ->
                    ReadingTextSizeRow(index, count, state.readingTextSize, actions::onReadingTextSizeChange)
                },
            ) + if (refreshRates.size > 1) {
                listOf<SettingsRow>({ index, count ->
                    RefreshRateRow(
                        index, count, refreshRates, RefreshRate.effective(state.refreshRate, screen),
                        actions::onRefreshRateChange,
                    )
                })
            } else {
                emptyList()
            } + listOf<SettingsRow>(
                { index, count -> CoverQualityRow(index, count, state.coverQuality, actions::onCoverQualityChange) },
            ),
        ),
    )
}

/** 首页与进度：首页待看数量、进度页默认分类。 */
@Composable
internal fun homeProgressGroups(state: PreferenceSettingsUiState, actions: PreferenceSettingsActions): List<SettingsGroup> = listOf(
    SettingsGroup(
        key = "home",
        title = null,
        rows = listOf<SettingsRow>(
            { index, count -> ToWatchLimitRow(index, count, state.toWatchLimit, actions::onToWatchLimitChange) },
            { index, count ->
                ProgressDefaultTypeRow(index, count, state.progressDefaultType, actions::onProgressDefaultTypeChange)
            },
        ),
    ),
)

/**
 * 评分与社区：评分、社区两组。互相置灰的开关（封面评分跟着公共评分，好友标识跟着专注模式）在同一组；
 * 子开关能不能点读 [appFeatures] 里它所属的那一项，不在这里再判一次模式。
 */
@Composable
internal fun ratingsCommunityGroups(state: PreferenceSettingsUiState, actions: PreferenceSettingsActions): List<SettingsGroup> {
    val features = appFeatures(state.experienceMode, state.showPublicRatings, state.showCoverScores, state.showFriendMarks)
    return listOf(
        SettingsGroup(
            key = "ratings",
            title = stringResource(R.string.settings_ratings),
            rows = listOf<SettingsRow>(
                { index, count ->
                    PublicRatingsRow(index, count, state.showPublicRatings, actions::onShowPublicRatingsChange)
                },
                { index, count ->
                    CoverScoresRow(
                        index, count, state.showCoverScores, features.publicRatings, actions::onShowCoverScoresChange,
                    )
                },
            ),
        ),
        SettingsGroup(
            key = "community",
            title = stringResource(R.string.settings_community),
            rows = listOf<SettingsRow>(
                { index, count ->
                    ExperienceModeRow(index, count, state.experienceMode, actions::onExperienceModeChange)
                },
                { index, count ->
                    FriendMarksRow(
                        index, count, state.showFriendMarks, features.community,
                        actions::onShowFriendMarksChange,
                    )
                },
            ),
        ),
    )
}

@Composable
private fun ThemeModeRow(index: Int, count: Int, mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    ChoiceRow(
        index = index,
        count = count,
        title = stringResource(R.string.settings_theme),
        options = ThemeMode.entries,
        selected = mode,
        label = { option ->
            stringResource(
                when (option) {
                    ThemeMode.FOLLOW_SYSTEM -> R.string.theme_follow_system
                    ThemeMode.LIGHT -> R.string.theme_light
                    ThemeMode.DARK -> R.string.theme_dark
                },
            )
        },
        onSelect = onSelect,
    )
}

/**
 * 体验模式开关（完整 / 专注）。设置行的标题下面一般不放说明，这一行是唯一的例外：「专注模式」四个字说不出
 * 打开后少了什么，标题下一行写明隐藏的东西（与 core:model `AppFeatures.community` 一致），两种状态都显示。
 */
@Composable
private fun ExperienceModeRow(index: Int, count: Int, mode: ExperienceMode, onCheckedChange: (ExperienceMode) -> Unit) {
    SwitchRow(
        index = index,
        count = count,
        title = stringResource(R.string.settings_experience_mode),
        checked = mode == ExperienceMode.FOCUS,
        onCheckedChange = { onCheckedChange(if (it) ExperienceMode.FOCUS else ExperienceMode.FULL) },
        details = { Text(stringResource(R.string.settings_experience_mode_details)) },
    )
}

/**
 * 好友标识开关：名字旁、头像底边的「好友」小牌画不画。小牌属于社区功能，所以紧跟专注模式；
 * 专注模式下本来就不画，这一行置灰（开关值保留，回到完整模式时照旧生效）。
 */
@Composable
private fun FriendMarksRow(
    index: Int,
    count: Int,
    showFriendMarks: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchRow(
        index = index,
        count = count,
        title = stringResource(R.string.settings_friend_marks),
        checked = showFriendMarks,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
    )
}

/** 显示公共评分开关（显示 / 隐藏）。 */
@Composable
private fun PublicRatingsRow(index: Int, count: Int, showPublicRatings: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchRow(
        index = index,
        count = count,
        title = stringResource(R.string.settings_public_ratings),
        checked = showPublicRatings,
        onCheckedChange = onCheckedChange,
    )
}

/**
 * 封面显示评分开关：只管海报封面右下角的分数角标。它是公共评分的一处，所以和公共评分放在同一组；
 * 公共评分关掉时封面本来就不画分数，这一行置灰（开关值保留，公共评分再打开时照旧生效）。
 */
@Composable
private fun CoverScoresRow(
    index: Int,
    count: Int,
    showCoverScores: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchRow(
        index = index,
        count = count,
        title = stringResource(R.string.settings_cover_scores),
        checked = showCoverScores,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
    )
}

/** 首页「待看」条数（4 档互斥单选，connected button group；放不下时末尾几项收进 overflow 菜单，见 M3 buttons.md）。 */
@Composable
private fun ToWatchLimitRow(index: Int, count: Int, limit: Int, onSelect: (Int) -> Unit) {
    ChoiceRow(
        index = index,
        count = count,
        title = stringResource(R.string.settings_to_watch_limit),
        options = ToWatchLimit.OPTIONS,
        selected = limit,
        label = { it.toString() },
        onSelect = onSelect,
    )
}

/**
 * 进度页启动时打开的分类：选项与进度页分类栏同一份（[subjectTypeTabsOf]），文字也同一份（[labelOrAllRes]）。
 * 「全部」是 null，而连通按钮组拿 null 当「一个都没选」，所以按分类栏里的位置选。
 * 六项放不下时由 [ConnectedChoiceGroup] 把末尾几项收进「更多」菜单（M3 button groups 的 overflow）。
 */
@Composable
private fun ProgressDefaultTypeRow(index: Int, count: Int, type: SubjectType?, onSelect: (SubjectType?) -> Unit) {
    val types = remember { subjectTypeTabsOf(includeAll = true) }
    ChoiceRow(
        index = index,
        count = count,
        title = stringResource(R.string.settings_progress_default_type),
        options = types.indices.toList(),
        selected = types.indexOf(type),
        label = { stringResource(types[it].labelOrAllRes()) },
        onSelect = { onSelect(types[it]) },
    )
}

/**
 * 刷新率：跟随系统加这块屏有的几档（[options] 来自 [RefreshRate.optionsFor]），[selected] 是存值在这块屏上的那一档。
 * 只有一种刷新率的屏不显示这一行，由调用方判。
 */
@Composable
private fun RefreshRateRow(index: Int, count: Int, options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    ChoiceRow(
        index = index,
        count = count,
        title = stringResource(R.string.settings_refresh_rate),
        options = options,
        selected = selected,
        label = { option ->
            if (option == RefreshRate.SYSTEM) {
                stringResource(R.string.theme_follow_system)
            } else {
                stringResource(R.string.settings_refresh_rate_hz, option)
            }
        },
        onSelect = onSelect,
    )
}

/** 阅读字号（3 档互斥单选）。档位 → 字阶的映射在 core:ui 的 `ReaderTypography`，这里只写档位名。 */
@Composable
private fun ReadingTextSizeRow(index: Int, count: Int, size: ReadingTextSize, onSelect: (ReadingTextSize) -> Unit) {
    ChoiceRow(
        index = index,
        count = count,
        title = stringResource(R.string.settings_reading_text_size),
        options = ReadingTextSize.entries,
        selected = size,
        label = { option ->
            stringResource(
                when (option) {
                    ReadingTextSize.SMALL -> R.string.settings_reading_text_size_small
                    ReadingTextSize.STANDARD -> R.string.settings_reading_text_size_standard
                    ReadingTextSize.LARGE -> R.string.settings_reading_text_size_large
                },
            )
        },
        onSelect = onSelect,
    )
}

/** 封面画质（3 档互斥单选）。档位 → 取哪张图的规则在 core:ui 的 `coverUrlFor`，这里只写档位名。 */
@Composable
private fun CoverQualityRow(index: Int, count: Int, quality: CoverQuality, onSelect: (CoverQuality) -> Unit) {
    ChoiceRow(
        index = index,
        count = count,
        title = stringResource(R.string.settings_cover_quality),
        options = CoverQuality.entries,
        selected = quality,
        label = { option ->
            stringResource(
                when (option) {
                    CoverQuality.AUTO -> R.string.settings_cover_quality_auto
                    CoverQuality.LARGE -> R.string.settings_cover_quality_large
                    CoverQuality.ORIGINAL -> R.string.settings_cover_quality_original
                },
            )
        },
        onSelect = onSelect,
    )
}
