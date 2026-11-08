package io.github.touhougo.chogumi.feature.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.ThemeColorMode
import io.github.touhougo.chogumi.core.model.ThemePreferences
import io.github.touhougo.chogumi.core.model.ThemePreset
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.AdaptiveGridRows
import io.github.touhougo.chogumi.core.ui.composables.ConnectedChoiceGroup
import io.github.touhougo.chogumi.core.ui.composables.FormTextField
import io.github.touhougo.chogumi.core.ui.composables.GridCell
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.theme.ThemeColorSource
import io.github.touhougo.chogumi.core.ui.theme.appColorScheme
import io.github.touhougo.chogumi.core.ui.theme.isDarkThemeActive

/**
 * 配色一行：标题，来源的单选（配色方案、主题色、系统取色——一次只用一种），下面是这个来源可选的格子。
 * 换来源只改 [ThemePreferences.mode]，另外两种的选择留着，切回去还是原来那格。
 */
internal fun themeColorRows(value: ThemePreferences, onChange: (ThemePreferences) -> Unit): List<SettingsRow> = listOf(
    { index, count -> ThemeColorRow(index, count, value, onChange) },
)

@Composable
private fun ThemeColorRow(index: Int, count: Int, value: ThemePreferences, onChange: (ThemePreferences) -> Unit) {
    val sources = ColorSources
    // 系统取色存着、但这台系统给不了时，主题按种子色画（ChogumiTheme），这里也显示主题色
    val mode = value.mode.takeIf { it in sources } ?: ThemeColorMode.SEED
    SegmentedItem(
        index = index,
        count = count,
        onClick = null,
        modifier = Modifier.fillMaxWidth(),
        details = {
            Column {
                ConnectedChoiceGroup(
                    options = sources,
                    selected = mode,
                    label = { stringResource(it.label()) },
                    onSelect = { onChange(value.copy(mode = it)) },
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                )
                val grid = Modifier.padding(top = 16.dp, bottom = 4.dp)
                when (mode) {
                    ThemeColorMode.PRESET -> AdaptiveGridRows(ThemePreset.entries, GridCell.Swatch, grid, sideMargin = 0.dp) { preset ->
                        SwatchCell(
                            label = stringResource(preset.label()),
                            source = ThemeColorSource.Preset(preset),
                            selected = preset == value.preset,
                            onClick = { onChange(value.copy(preset = preset)) },
                        )
                    }
                    ThemeColorMode.SEED -> SeedGrid(value, onChange, grid)
                    ThemeColorMode.SYSTEM -> Text(
                        stringResource(R.string.theme_color_system_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
            }
        },
    ) { Text(stringResource(R.string.theme_color_source)) }
}

/** 系统取色只在能取壁纸颜色的系统（Android 12+）上出现。 */
private val ColorSources: List<ThemeColorMode> = listOfNotNull(
    ThemeColorMode.PRESET,
    ThemeColorMode.SEED,
    ThemeColorMode.SYSTEM.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S },
)

@StringRes
private fun ThemeColorMode.label(): Int = when (this) {
    ThemeColorMode.PRESET -> R.string.theme_color_preset
    ThemeColorMode.SEED -> R.string.theme_color
    ThemeColorMode.SYSTEM -> R.string.theme_color_system
}

@StringRes
private fun ThemePreset.label(): Int = when (this) {
    ThemePreset.MONO -> R.string.theme_preset_mono
    ThemePreset.INK -> R.string.theme_preset_ink
    ThemePreset.CINNABAR -> R.string.theme_preset_cinnabar
    ThemePreset.FUCHSIA -> R.string.theme_preset_fuchsia
    ThemePreset.VIOLET -> R.string.theme_preset_violet
    ThemePreset.INDIGO -> R.string.theme_preset_indigo
    ThemePreset.AZURE -> R.string.theme_preset_azure
    ThemePreset.PEACOCK -> R.string.theme_preset_peacock
    ThemePreset.PINE -> R.string.theme_preset_pine
    ThemePreset.MOCHA -> R.string.theme_preset_mocha
}

/** 主题色的格子：四个种子色，最后一格是自定义（选中时画用户输入的那个颜色）。 */
@Composable
private fun SeedGrid(value: ThemePreferences, onChange: (ThemePreferences) -> Unit, modifier: Modifier) {
    var custom by rememberSaveable { mutableStateOf(false) }
    var hex by rememberSaveable { mutableStateOf("") }
    val customSelected = SeedChoices.none { it.seedArgb == value.seedArgb }
    AdaptiveGridRows(SeedChoices + null, GridCell.Swatch, modifier, sideMargin = 0.dp) { choice ->
        if (choice != null) {
            SwatchCell(
                label = stringResource(choice.label),
                source = ThemeColorSource.Seed(Color(choice.seedArgb)),
                selected = choice.seedArgb == value.seedArgb,
                onClick = { onChange(value.copy(seedArgb = choice.seedArgb)) },
            )
        } else {
            SwatchCell(
                label = stringResource(R.string.theme_color_custom_short),
                source = ThemeColorSource.Seed(Color(value.seedArgb)).takeIf { customSelected },
                selected = customSelected,
                onClick = { hex = "#%06X".format(value.seedArgb and 0xFFFFFF); custom = true },
            )
        }
    }
    if (custom) {
        val digits = hex.removePrefix("#")
        val valid = digits.matches(Regex("[0-9a-fA-F]{6}"))
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text(stringResource(R.string.theme_color_custom)) },
            text = {
                FormTextField(
                    value = hex,
                    onValueChange = { hex = it.take(7) },
                    label = stringResource(R.string.theme_color_hex),
                    isError = !valid,
                    inDialog = true,
                    supportingText = { if (!valid) Text(stringResource(R.string.theme_color_invalid)) },
                )
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    onChange(value.copy(seedArgb = 0xFF000000L or digits.toLong(16)))
                    custom = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { custom = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** 一个种子色与它的名字。取值是设计决策（not in M3）。 */
private class SeedChoice(val seedArgb: Long, @StringRes val label: Int)

private val SeedChoices = listOf(
    SeedChoice(ThemePreferences.DEFAULT_SEED, R.string.theme_color_pink),
    SeedChoice(0xFF526CFDL, R.string.theme_color_blue),
    SeedChoice(0xFF9B72CFL, R.string.theme_color_purple),
    SeedChoice(0xFFE59952L, R.string.theme_color_orange),
)

/**
 * 一格配色：方块的底色是那套配色的背景，圆点上半是主色、左下次色容器、右下点缀色——不点开也能比出差别（我的设计决定，not in M3）。
 * 选中的那格方块变圆、外圈一道当前主色、中间一个勾，名字加粗；[source] 为 null 是还没选过的自定义格，画一个加号。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SwatchCell(label: String, source: ThemeColorSource?, selected: Boolean, onClick: () -> Unit) {
    val dark = isDarkThemeActive()
    val swatch = remember(source, dark) { source?.let { appColorScheme(it, dark) } }
    val corner by animateIntAsState(if (selected) 50 else 25, MaterialTheme.motionScheme.fastSpatialSpec())
    val shape = RoundedCornerShape(percent = corner)
    PressArea(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        role = Role.RadioButton,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                    .padding(4.dp)
                    .clip(shape)
                    .background(swatch?.surface ?: MaterialTheme.colorScheme.surfaceContainerLow)
                    .then(if (selected) Modifier else Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)),
                contentAlignment = Alignment.Center,
            ) {
                if (swatch != null) {
                    SwatchDot(swatch)
                    if (selected) SwatchCheck(swatch)
                } else {
                    Icon(
                        painterResource(R.drawable.add_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                label,
                style = if (selected) MaterialTheme.typography.labelMediumEmphasized else MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SwatchDot(swatch: ColorScheme) {
    Column(Modifier.fillMaxSize(0.62f).clip(CircleShape)) {
        Box(Modifier.weight(1f).fillMaxWidth().background(swatch.primary))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight().background(swatch.secondaryContainer))
            Box(Modifier.weight(1f).fillMaxHeight().background(swatch.tertiary))
        }
    }
}

@Composable
private fun SwatchCheck(swatch: ColorScheme) {
    Box(Modifier.fillMaxSize(0.44f).clip(CircleShape).background(swatch.primary), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(R.drawable.check_24),
            contentDescription = null,
            tint = swatch.onPrimary,
            modifier = Modifier.fillMaxSize(0.6f),
        )
    }
}
