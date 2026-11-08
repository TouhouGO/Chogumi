package io.github.touhougo.chogumi.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.glass.glassSupported
import io.github.touhougo.chogumi.core.model.ThemePreferences
import io.github.touhougo.chogumi.core.res.R

/**
 * 外观里的「玻璃效果」（core:glass，默认关）：底栏、作品页的顶栏、切换栏与收藏按钮换成玻璃。
 * 系统画不了玻璃（Android 11 及以下没有模糊）时不出这一行。
 */
internal fun glassRows(preferences: ThemePreferences, onChange: (ThemePreferences) -> Unit): List<SettingsRow> =
    if (glassSupported()) listOf({ index, count -> GlassEffectRow(index, count, preferences, onChange) }) else emptyList()

@Composable
private fun GlassEffectRow(index: Int, count: Int, preferences: ThemePreferences, onChange: (ThemePreferences) -> Unit) {
    SwitchRow(
        index = index,
        count = count,
        title = stringResource(R.string.settings_glass),
        checked = preferences.glass,
        onCheckedChange = { onChange(preferences.copy(glass = it)) },
    )
}
