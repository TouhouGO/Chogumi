package io.github.touhougo.chogumi.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import io.github.touhougo.chogumi.core.model.ThemePreset

/**
 * 配色方案的整套配色：角色色是手工定的（设计决策，not in M3），只有没写在这里的角色（固定色 `*Fixed`、scrim）取主色按 M3 生成的那套。
 *
 * 设计规则：背景接近中性，只带一点那套的色调，浓的颜色留给控件；主色、次色容器、点缀色各取不同色系。
 * 每对前景与底色的对比度由 `PresetColorSchemeTest` 守：正文 7 以上，其余文字 4.5 以上，主色、点缀色压在背景上 4.5 以上。
 */
internal fun presetColorScheme(preset: ThemePreset, isDark: Boolean): ColorScheme {
    val roles = when (preset) {
        ThemePreset.MONO -> if (isDark) MonoDark else MonoLight
        ThemePreset.INK -> if (isDark) InkDark else InkLight
        ThemePreset.CINNABAR -> if (isDark) CinnabarDark else CinnabarLight
        ThemePreset.FUCHSIA -> if (isDark) FuchsiaDark else FuchsiaLight
        ThemePreset.VIOLET -> if (isDark) VioletDark else VioletLight
        ThemePreset.INDIGO -> if (isDark) IndigoDark else IndigoLight
        ThemePreset.AZURE -> if (isDark) AzureDark else AzureLight
        ThemePreset.PEACOCK -> if (isDark) PeacockDark else PeacockLight
        ThemePreset.PINE -> if (isDark) PineDark else PineLight
        ThemePreset.MOCHA -> if (isDark) MochaDark else MochaLight
    }
    return roles.toScheme(isDark)
}

/** 一套方案在浅色或深色下的角色色。background 等同 surface，surfaceVariant 等同 surfaceContainerHighest，surfaceTint 等同 primary。 */
private class PresetRoles(
    val primary: Long, val onPrimary: Long, val primaryContainer: Long, val onPrimaryContainer: Long,
    val secondary: Long, val onSecondary: Long, val secondaryContainer: Long, val onSecondaryContainer: Long,
    val tertiary: Long, val onTertiary: Long, val tertiaryContainer: Long, val onTertiaryContainer: Long,
    val surface: Long, val onSurface: Long, val onSurfaceVariant: Long,
    val surfaceDim: Long, val surfaceBright: Long, val surfaceContainerLowest: Long,
    val surfaceContainerLow: Long, val surfaceContainer: Long, val surfaceContainerHigh: Long, val surfaceContainerHighest: Long,
    val outline: Long, val outlineVariant: Long,
    val inverseSurface: Long, val inverseOnSurface: Long, val inversePrimary: Long,
    val error: Long, val onError: Long, val errorContainer: Long, val onErrorContainer: Long,
) {
    fun toScheme(isDark: Boolean): ColorScheme =
        dynamicColorScheme(seedColor = Color(primary), isDark = isDark, style = PaletteStyle.TonalSpot, specVersion = COLOR_SPEC).copy(
            primary = Color(primary), onPrimary = Color(onPrimary),
            primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
            inversePrimary = Color(inversePrimary),
            secondary = Color(secondary), onSecondary = Color(onSecondary),
            secondaryContainer = Color(secondaryContainer), onSecondaryContainer = Color(onSecondaryContainer),
            tertiary = Color(tertiary), onTertiary = Color(onTertiary),
            tertiaryContainer = Color(tertiaryContainer), onTertiaryContainer = Color(onTertiaryContainer),
            background = Color(surface), onBackground = Color(onSurface),
            surface = Color(surface), onSurface = Color(onSurface),
            surfaceVariant = Color(surfaceContainerHighest), onSurfaceVariant = Color(onSurfaceVariant),
            surfaceTint = Color(primary),
            inverseSurface = Color(inverseSurface), inverseOnSurface = Color(inverseOnSurface),
            error = Color(error), onError = Color(onError),
            errorContainer = Color(errorContainer), onErrorContainer = Color(onErrorContainer),
            outline = Color(outline), outlineVariant = Color(outlineVariant),
            surfaceBright = Color(surfaceBright), surfaceDim = Color(surfaceDim),
            surfaceContainer = Color(surfaceContainer), surfaceContainerHigh = Color(surfaceContainerHigh),
            surfaceContainerHighest = Color(surfaceContainerHighest), surfaceContainerLow = Color(surfaceContainerLow),
            surfaceContainerLowest = Color(surfaceContainerLowest),
        )
}

private val MonoLight = PresetRoles(
    primary = 0xFF000000, onPrimary = 0xFFE2E2E2, primaryContainer = 0xFF3B3B3B, onPrimaryContainer = 0xFFFFFFFF,
    secondary = 0xFF5E5E5E, onSecondary = 0xFFE2E2E2, secondaryContainer = 0xFFD4D4D4, onSecondaryContainer = 0xFF1B1B1B,
    tertiary = 0xFF3B3B3B, onTertiary = 0xFFFFFFFF, tertiaryContainer = 0xFF747474, onTertiaryContainer = 0xFFFFFFFF,
    surface = 0xFFF9F9F9, onSurface = 0xFF1B1B1B, onSurfaceVariant = 0xFF474747,
    surfaceDim = 0xFFD7D7D7, surfaceBright = 0xFFF9F9F9, surfaceContainerLowest = 0xFFFEFEFE,
    surfaceContainerLow = 0xFFF3F3F3, surfaceContainer = 0xFFEEEEEE, surfaceContainerHigh = 0xFFE8E8E8, surfaceContainerHighest = 0xFFE2E2E2,
    outline = 0xFF777777, outlineVariant = 0xFFC6C6C6,
    inverseSurface = 0xFF2A2A2A, inverseOnSurface = 0xFFEEEEEE, inversePrimary = 0xFFFFFFFF,
    error = 0xFFBA1A1A, onError = 0xFFFFFFFF, errorContainer = 0xFFFED7D1, onErrorContainer = 0xFF621512,
)

private val MonoDark = PresetRoles(
    primary = 0xFFFFFFFF, onPrimary = 0xFF1B1B1B, primaryContainer = 0xFFD4D4D4, onPrimaryContainer = 0xFF000000,
    secondary = 0xFFC6C6C6, onSecondary = 0xFF242424, secondaryContainer = 0xFF474747, onSecondaryContainer = 0xFFE2E2E2,
    tertiary = 0xFFE2E2E2, onTertiary = 0xFF131313, tertiaryContainer = 0xFF919191, onTertiaryContainer = 0xFF000000,
    surface = 0xFF131313, onSurface = 0xFFE2E2E2, onSurfaceVariant = 0xFFC6C6C6,
    surfaceDim = 0xFF131313, surfaceBright = 0xFF3E3E3E, surfaceContainerLowest = 0xFF0E0E0E,
    surfaceContainerLow = 0xFF1B1B1B, surfaceContainer = 0xFF1F1F1F, surfaceContainerHigh = 0xFF2A2A2A, surfaceContainerHighest = 0xFF353535,
    outline = 0xFF919191, outlineVariant = 0xFF474747,
    inverseSurface = 0xFFE2E2E2, inverseOnSurface = 0xFF2A2A2A, inversePrimary = 0xFF000000,
    error = 0xFFFFB4AB, onError = 0xFF4C100E, errorContainer = 0xFF68231F, onErrorContainer = 0xFFFED7D1,
)

private val InkLight = PresetRoles(
    primary = 0xFF283345, onPrimary = 0xFFFBFCFE, primaryContainer = 0xFFD3DAE4, onPrimaryContainer = 0xFF2E3643,
    secondary = 0xFF675C4B, onSecondary = 0xFFFBFCFE, secondaryContainer = 0xFFE4DDD2, onSecondaryContainer = 0xFF36322C,
    tertiary = 0xFFBC4A3C, onTertiary = 0xFFFFFFFF, tertiaryContainer = 0xFFFFCDC5, onTertiaryContainer = 0xFF601810,
    surface = 0xFFFAF6EF, onSurface = 0xFF262119, onSurfaceVariant = 0xFF5F574A,
    surfaceDim = 0xFFD9D4CC, surfaceBright = 0xFFFAF6EF, surfaceContainerLowest = 0xFFFEFBF7,
    surfaceContainerLow = 0xFFF4EFE8, surfaceContainer = 0xFFEFEAE3, surfaceContainerHigh = 0xFFEAE5DD, surfaceContainerHighest = 0xFFE4DFD7,
    outline = 0xFF8D8579, outlineVariant = 0xFFD3CDC2,
    inverseSurface = 0xFF25221E, inverseOnSurface = 0xFFEFEAE3, inversePrimary = 0xFFB4BECE,
    error = 0xFFC53732, onError = 0xFFFFFFFF, errorContainer = 0xFFFED7D1, onErrorContainer = 0xFF621513,
)

private val InkDark = PresetRoles(
    primary = 0xFFB4BECE, onPrimary = 0xFF202731, primaryContainer = 0xFF414853, onPrimaryContainer = 0xFFDDE2EA,
    secondary = 0xFFC9BCA9, onSecondary = 0xFF27231E, secondaryContainer = 0xFF3B3731, onSecondaryContainer = 0xFFE4E1DB,
    tertiary = 0xFFFAA495, onTertiary = 0xFF12100D, tertiaryContainer = 0xFF703229, onTertiaryContainer = 0xFFFFD7CF,
    surface = 0xFF12100D, onSurface = 0xFFEBE7E1, onSurfaceVariant = 0xFFBAB3A8,
    surfaceDim = 0xFF12100D, surfaceBright = 0xFF35332E, surfaceContainerLowest = 0xFF0D0B08,
    surfaceContainerLow = 0xFF181612, surfaceContainer = 0xFF1E1B17, surfaceContainerHigh = 0xFF25221E, surfaceContainerHighest = 0xFF2C2A25,
    outline = 0xFF8C857A, outlineVariant = 0xFF433F3A,
    inverseSurface = 0xFFE4DFD7, inverseOnSurface = 0xFF25221E, inversePrimary = 0xFF283345,
    error = 0xFFFA887D, onError = 0xFF4C100E, errorContainer = 0xFF68231F, onErrorContainer = 0xFFFED7D2,
)

private val CinnabarLight = PresetRoles(
    primary = 0xFFAB331F, onPrimary = 0xFFFDF9F4, primaryContainer = 0xFFFFBFB2, onPrimaryContainer = 0xFF5A0900,
    secondary = 0xFF476270, onSecondary = 0xFFFDF9F4, secondaryContainer = 0xFFC6DDEA, onSecondaryContainer = 0xFF103242,
    tertiary = 0xFF94680F, onTertiary = 0xFFFDF9F4, tertiaryContainer = 0xFFE7D1B0, onTertiaryContainer = 0xFF452E00,
    surface = 0xFFFCF7EF, onSurface = 0xFF231F19, onSurfaceVariant = 0xFF605A52,
    surfaceDim = 0xFFDED8CE, surfaceBright = 0xFFFCF7EF, surfaceContainerLowest = 0xFFFFFCF9,
    surfaceContainerLow = 0xFFF7F1EA, surfaceContainer = 0xFFF3EDE4, surfaceContainerHigh = 0xFFEFE8DF, surfaceContainerHighest = 0xFFEAE3D9,
    outline = 0xFF8D8881, outlineVariant = 0xFFD8D0C4,
    inverseSurface = 0xFF2F2625, inverseOnSurface = 0xFFF3EDE4, inversePrimary = 0xFFFEA28F,
    error = 0xFFB6322D, onError = 0xFFFFFFFF, errorContainer = 0xFFFED7D1, onErrorContainer = 0xFF621513,
)

private val CinnabarDark = PresetRoles(
    primary = 0xFFFEA28F, onPrimary = 0xFF440600, primaryContainer = 0xFF752E21, onPrimaryContainer = 0xFFFFD2C9,
    secondary = 0xFFA1C3D8, onSecondary = 0xFF102733, secondaryContainer = 0xFF2C4350, onSecondaryContainer = 0xFFCFE1EB,
    tertiary = 0xFFD5B17A, onTertiary = 0xFF2C1C01, tertiaryContainer = 0xFF5B431A, onTertiaryContainer = 0xFFECDBC2,
    surface = 0xFF1C1412, onSurface = 0xFFE9E4DD, onSurfaceVariant = 0xFFB6B0A9,
    surfaceDim = 0xFF1C1412, surfaceBright = 0xFF403735, surfaceContainerLowest = 0xFF160F0D,
    surfaceContainerLow = 0xFF221918, surfaceContainer = 0xFF281F1D, surfaceContainerHigh = 0xFF2F2625, surfaceContainerHighest = 0xFF372E2C,
    outline = 0xFF847F78, outlineVariant = 0xFF433F39,
    inverseSurface = 0xFFEAE3D9, inverseOnSurface = 0xFF2F2625, inversePrimary = 0xFFAB331F,
    error = 0xFFF6857A, onError = 0xFF4C100E, errorContainer = 0xFF68231F, onErrorContainer = 0xFFFED7D1,
)

private val FuchsiaLight = PresetRoles(
    primary = 0xFFA12F69, onPrimary = 0xFFFEF8FC, primaryContainer = 0xFFFCBCD5, onPrimaryContainer = 0xFF570033,
    secondary = 0xFF4E606F, onSecondary = 0xFFFEF8FC, secondaryContainer = 0xFFCCDCE9, onSecondaryContainer = 0xFF1B3040,
    tertiary = 0xFF9C6603, onTertiary = 0xFFFEF8FC, tertiaryContainer = 0xFFEECEA7, onTertiaryContainer = 0xFF472C02,
    surface = 0xFFFAF6F9, onSurface = 0xFF231D22, onSurfaceVariant = 0xFF60585E,
    surfaceDim = 0xFFDCD7DB, surfaceBright = 0xFFFAF6F9, surfaceContainerLowest = 0xFFFEFCFD,
    surfaceContainerLow = 0xFFF5F1F4, surfaceContainer = 0xFFF1ECF0, surfaceContainerHigh = 0xFFECE7EB, surfaceContainerHighest = 0xFFE7E2E6,
    outline = 0xFF8E878C, outlineVariant = 0xFFD5CFD3,
    inverseSurface = 0xFF2F2629, inverseOnSurface = 0xFFF1ECF0, inversePrimary = 0xFFFA9DC4,
    error = 0xFFB6322D, onError = 0xFFFFFFFF, errorContainer = 0xFFFED7D1, onErrorContainer = 0xFF621513,
)

private val FuchsiaDark = PresetRoles(
    primary = 0xFFFA9DC4, onPrimary = 0xFF410425, primaryContainer = 0xFF702C4C, onPrimaryContainer = 0xFFFDCFE1,
    secondary = 0xFFA9C1D6, onSecondary = 0xFF172632, secondaryContainer = 0xFF32424F, onSecondaryContainer = 0xFFD3E0EB,
    tertiary = 0xFFE1AC69, onTertiary = 0xFF2E1B01, tertiaryContainer = 0xFF633F03, onTertiaryContainer = 0xFFF3DABB,
    surface = 0xFF1B1316, onSurface = 0xFFE9E2E7, onSurfaceVariant = 0xFFB6AEB4,
    surfaceDim = 0xFF1B1316, surfaceBright = 0xFF3F363A, surfaceContainerLowest = 0xFF150E10,
    surfaceContainerLow = 0xFF21191C, surfaceContainer = 0xFF271F22, surfaceContainerHigh = 0xFF2F2629, surfaceContainerHighest = 0xFF362D31,
    outline = 0xFF857E83, outlineVariant = 0xFF423F41,
    inverseSurface = 0xFFE7E2E6, inverseOnSurface = 0xFF2F2629, inversePrimary = 0xFFA12F69,
    error = 0xFFF6857A, onError = 0xFF4C100E, errorContainer = 0xFF68231F, onErrorContainer = 0xFFFED7D1,
)

private val VioletLight = PresetRoles(
    primary = 0xFF5D3CA4, onPrimary = 0xFFFBF9FF, primaryContainer = 0xFFD3C8FF, onPrimaryContainer = 0xFF341866,
    secondary = 0xFF4D6459, onSecondary = 0xFFFBF9FF, secondaryContainer = 0xFFCBDFD5, onSecondaryContainer = 0xFF193428,
    tertiary = 0xFF9C6603, onTertiary = 0xFFFBF9FF, tertiaryContainer = 0xFFEECEA7, onTertiaryContainer = 0xFF472C02,
    surface = 0xFFF8F6FB, onSurface = 0xFF201E24, onSurfaceVariant = 0xFF5C5961,
    surfaceDim = 0xFFDAD8DC, surfaceBright = 0xFFF8F6FB, surfaceContainerLowest = 0xFFFDFBFE,
    surfaceContainerLow = 0xFFF3F1F6, surfaceContainer = 0xFFEFEDF2, surfaceContainerHigh = 0xFFEAE8ED, surfaceContainerHighest = 0xFFE5E3E8,
    outline = 0xFF8B878F, outlineVariant = 0xFFD2D0D6,
    inverseSurface = 0xFF29282F, inverseOnSurface = 0xFFEFEDF2, inversePrimary = 0xFFC1B0FE,
    error = 0xFFB6322D, onError = 0xFFFFFFFF, errorContainer = 0xFFFED7D1, onErrorContainer = 0xFF621513,
)

private val VioletDark = PresetRoles(
    primary = 0xFFC1B0FE, onPrimary = 0xFF26134A, primaryContainer = 0xFF4B3A7A, onPrimaryContainer = 0xFFDFD8FE,
    secondary = 0xFFA8C6B6, onSecondary = 0xFF162920, secondaryContainer = 0xFF31453B, onSecondaryContainer = 0xFFD3E2DA,
    tertiary = 0xFFE1AC69, onTertiary = 0xFF2E1B01, tertiaryContainer = 0xFF633F03, onTertiaryContainer = 0xFFF3DABB,
    surface = 0xFF16151C, onSurface = 0xFFE6E3EA, onSurfaceVariant = 0xFFB3AFB7,
    surfaceDim = 0xFF16151C, surfaceBright = 0xFF393840, surfaceContainerLowest = 0xFF100F16,
    surfaceContainerLow = 0xFF1C1B22, surfaceContainer = 0xFF222028, surfaceContainerHigh = 0xFF29282F, surfaceContainerHighest = 0xFF302F37,
    outline = 0xFF827F86, outlineVariant = 0xFF403F42,
    inverseSurface = 0xFFE5E3E8, inverseOnSurface = 0xFF29282F, inversePrimary = 0xFF5D3CA4,
    error = 0xFFF6857A, onError = 0xFF4C100E, errorContainer = 0xFF68231F, onErrorContainer = 0xFFFED7D1,
)

private val IndigoLight = PresetRoles(
    primary = 0xFF273A6C, onPrimary = 0xFFF7FAFE, primaryContainer = 0xFFC3D1EF, onPrimaryContainer = 0xFF1D2C52,
    secondary = 0xFF6B5C36, onSecondary = 0xFFF7FAFE, secondaryContainer = 0xFFE5D8B8, onSecondaryContainer = 0xFF3A2C01,
    tertiary = 0xFF95344A, onTertiary = 0xFFF7FAFE, tertiaryContainer = 0xFFF8C5CB, onTertiaryContainer = 0xFF5A1727,
    surface = 0xFFF4F8FC, onSurface = 0xFF1B2025, onSurfaceVariant = 0xFF555C63,
    surfaceDim = 0xFFD4DADE, surfaceBright = 0xFFF4F8FC, surfaceContainerLowest = 0xFFFBFDFE,
    surfaceContainerLow = 0xFFEFF3F8, surfaceContainer = 0xFFEAEEF3, surfaceContainerHigh = 0xFFE5EAEF, surfaceContainerHighest = 0xFFDFE5EA,
    outline = 0xFF848A90, outlineVariant = 0xFFCCD2D8,
    inverseSurface = 0xFF262930, inverseOnSurface = 0xFFEAEEF3, inversePrimary = 0xFFA9BDEA,
    error = 0xFFB6322D, onError = 0xFFFFFFFF, errorContainer = 0xFFFED7D1, onErrorContainer = 0xFF621513,
)

private val IndigoDark = PresetRoles(
    primary = 0xFFA9BDEA, onPrimary = 0xFF15203B, primaryContainer = 0xFF394768, onPrimaryContainer = 0xFFD4DEF4,
    secondary = 0xFFD1BC8A, onSecondary = 0xFF2E2202, secondaryContainer = 0xFF4C3E1C, onSecondaryContainer = 0xFFE8DDC4,
    tertiary = 0xFFEE9DA9, onTertiary = 0xFF380F18, tertiaryContainer = 0xFF6B343E, onTertiaryContainer = 0xFFFAD2D7,
    surface = 0xFF13161D, onSurface = 0xFFE0E5EB, onSurfaceVariant = 0xFFACB2B9,
    surfaceDim = 0xFF13161D, surfaceBright = 0xFF363941, surfaceContainerLowest = 0xFF0E1017,
    surfaceContainerLow = 0xFF191C22, surfaceContainer = 0xFF1E2229, surfaceContainerHigh = 0xFF262930, surfaceContainerHighest = 0xFF2D3038,
    outline = 0xFF7B8187, outlineVariant = 0xFF3D4043,
    inverseSurface = 0xFFDFE5EA, inverseOnSurface = 0xFF262930, inversePrimary = 0xFF273A6C,
    error = 0xFFF6857A, onError = 0xFF4C100E, errorContainer = 0xFF68231F, onErrorContainer = 0xFFFED7D1,
)

private val AzureLight = PresetRoles(
    primary = 0xFF385BBD, onPrimary = 0xFFF8FAFE, primaryContainer = 0xFFBCD1FF, onPrimaryContainer = 0xFF10246D,
    secondary = 0xFF605A6E, onSecondary = 0xFFF8FAFE, secondaryContainer = 0xFFDBD6E8, onSecondaryContainer = 0xFF31293F,
    tertiary = 0xFFBE4C04, onTertiary = 0xFFF8FAFE, tertiaryContainer = 0xFFFEC6AE, onTertiaryContainer = 0xFF581E00,
    surface = 0xFFF5F8FC, onSurface = 0xFF1C1F25, onSurfaceVariant = 0xFF565B63,
    surfaceDim = 0xFFD5D9DF, surfaceBright = 0xFFF5F8FC, surfaceContainerLowest = 0xFFFCFDFE,
    surfaceContainerLow = 0xFFEFF3F8, surfaceContainer = 0xFFEBEEF4, surfaceContainerHigh = 0xFFE6E9EF, surfaceContainerHighest = 0xFFE0E4EB,
    outline = 0xFF858991, outlineVariant = 0xFFCDD1D9,
    inverseSurface = 0xFF262930, inverseOnSurface = 0xFFEBEEF4, inversePrimary = 0xFFA0BCFF,
    error = 0xFFB6322D, onError = 0xFFFFFFFF, errorContainer = 0xFFFED7D1, onErrorContainer = 0xFF621513,
)

private val AzureDark = PresetRoles(
    primary = 0xFFA0BCFF, onPrimary = 0xFF0C1C4F, primaryContainer = 0xFF2D447F, onPrimaryContainer = 0xFFCFDEFF,
    secondary = 0xFFC1B8D5, onSecondary = 0xFF262131, secondaryContainer = 0xFF423C4E, onSecondaryContainer = 0xFFE0DBEA,
    tertiary = 0xFFFB9B72, onTertiary = 0xFF391101, tertiaryContainer = 0xFF733212, onTertiaryContainer = 0xFFFED3C2,
    surface = 0xFF13161D, onSurface = 0xFFE1E5EB, onSurfaceVariant = 0xFFADB1B9,
    surfaceDim = 0xFF13161D, surfaceBright = 0xFF363941, surfaceContainerLowest = 0xFF0E1017,
    surfaceContainerLow = 0xFF191C22, surfaceContainer = 0xFF1E2229, surfaceContainerHigh = 0xFF262930, surfaceContainerHighest = 0xFF2D3038,
    outline = 0xFF7C8088, outlineVariant = 0xFF3E4044,
    inverseSurface = 0xFFE0E4EB, inverseOnSurface = 0xFF262930, inversePrimary = 0xFF385BBD,
    error = 0xFFF6857A, onError = 0xFF4C100E, errorContainer = 0xFF68231F, onErrorContainer = 0xFFFED7D1,
)

private val PeacockLight = PresetRoles(
    primary = 0xFF065D6B, onPrimary = 0xFFF4FCFD, primaryContainer = 0xFFB2D9E1, onPrimaryContainer = 0xFF00343D,
    secondary = 0xFF765354, onSecondary = 0xFFF4FCFD, secondaryContainer = 0xFFF0D0D0, onSecondaryContainer = 0xFF462123,
    tertiary = 0xFF9B641A, onTertiary = 0xFFF4FCFD, tertiaryContainer = 0xFFEBCFB2, onTertiaryContainer = 0xFF4A2B00,
    surface = 0xFFF2F9FA, onSurface = 0xFF192123, onSurfaceVariant = 0xFF525D60,
    surfaceDim = 0xFFD2DBDC, surfaceBright = 0xFFF2F9FA, surfaceContainerLowest = 0xFFF9FDFE,
    surfaceContainerLow = 0xFFEDF4F6, surfaceContainer = 0xFFE8EFF1, surfaceContainerHigh = 0xFFE3EBED, surfaceContainerHighest = 0xFFDDE6E8,
    outline = 0xFF818B8E, outlineVariant = 0xFFC9D3D5,
    inverseSurface = 0xFF212B2D, inverseOnSurface = 0xFFE8EFF1, inversePrimary = 0xFF8DC9D6,
    error = 0xFFB6322D, onError = 0xFFFFFFFF, errorContainer = 0xFFFED7D1, onErrorContainer = 0xFF621513,
)

private val PeacockDark = PresetRoles(
    primary = 0xFF8DC9D6, onPrimary = 0xFF01272E, primaryContainer = 0xFF1D5059, onPrimaryContainer = 0xFFC9E4EA,
    secondary = 0xFFDFB0B0, onSecondary = 0xFF361B1C, secondaryContainer = 0xFF543737, onSecondaryContainer = 0xFFF0D7D7,
    tertiary = 0xFFDBAE7E, onTertiary = 0xFF2F1A00, tertiaryContainer = 0xFF5F401D, onTertiaryContainer = 0xFFF0DAC3,
    surface = 0xFF0F181A, onSurface = 0xFFDEE6E9, onSurfaceVariant = 0xFFA9B3B6,
    surfaceDim = 0xFF0F181A, surfaceBright = 0xFF313B3D, surfaceContainerLowest = 0xFF0A1214,
    surfaceContainerLow = 0xFF141E20, surfaceContainer = 0xFF1A2426, surfaceContainerHigh = 0xFF212B2D, surfaceContainerHighest = 0xFF283234,
    outline = 0xFF798285, outlineVariant = 0xFF3C4142,
    inverseSurface = 0xFFDDE6E8, inverseOnSurface = 0xFF212B2D, inversePrimary = 0xFF065D6B,
    error = 0xFFF6857A, onError = 0xFF4C100E, errorContainer = 0xFF68231F, onErrorContainer = 0xFFFED7D1,
)

private val PineLight = PresetRoles(
    primary = 0xFF0E4F37, onPrimary = 0xFFFDFAF4, primaryContainer = 0xFFBCD9CA, onPrimaryContainer = 0xFF063725,
    secondary = 0xFF71583D, onSecondary = 0xFFFDFAF4, secondaryContainer = 0xFFEBD5BD, onSecondaryContainer = 0xFF422701,
    tertiary = 0xFFB23A23, onTertiary = 0xFFFDFAF4, tertiaryContainer = 0xFFFFC4B7, onTertiaryContainer = 0xFF610E00,
    surface = 0xFFFAF7F0, onSurface = 0xFF221F19, onSurfaceVariant = 0xFF5E5A52,
    surfaceDim = 0xFFDCD8CF, surfaceBright = 0xFFFAF7F0, surfaceContainerLowest = 0xFFFEFCF8,
    surfaceContainerLow = 0xFFF6F2EA, surfaceContainer = 0xFFF1EDE5, surfaceContainerHigh = 0xFFEDE9E0, surfaceContainerHighest = 0xFFE8E3DA,
    outline = 0xFF8C8981, outlineVariant = 0xFFD6D0C6,
    inverseSurface = 0xFF232B27, inverseOnSurface = 0xFFF1EDE5, inversePrimary = 0xFF9DCAB3,
    error = 0xFFB6322D, onError = 0xFFFFFFFF, errorContainer = 0xFFFED7D1, onErrorContainer = 0xFF621513,
)

private val PineDark = PresetRoles(
    primary = 0xFF9DCAB3, onPrimary = 0xFF08281B, primaryContainer = 0xFF2E5040, onPrimaryContainer = 0xFFCFE4D9,
    secondary = 0xFFD8B793, onSecondary = 0xFF331F07, secondaryContainer = 0xFF503B23, onSecondaryContainer = 0xFFECDBC8,
    tertiary = 0xFFFA9A85, onTertiary = 0xFF3D0B03, tertiaryContainer = 0xFF723124, onTertiaryContainer = 0xFFFFD2C8,
    surface = 0xFF101814, onSurface = 0xFFE8E4DD, onSurfaceVariant = 0xFFB4B1A9,
    surfaceDim = 0xFF101814, surfaceBright = 0xFF333C37, surfaceContainerLowest = 0xFF0B120F,
    surfaceContainerLow = 0xFF161E1A, surfaceContainer = 0xFF1C2420, surfaceContainerHigh = 0xFF232B27, surfaceContainerHighest = 0xFF2A332E,
    outline = 0xFF838078, outlineVariant = 0xFF423F3A,
    inverseSurface = 0xFFE8E3DA, inverseOnSurface = 0xFF232B27, inversePrimary = 0xFF0E4F37,
    error = 0xFFF6857A, onError = 0xFF4C100E, errorContainer = 0xFF68231F, onErrorContainer = 0xFFFED7D1,
)

private val MochaLight = PresetRoles(
    primary = 0xFF5B3E31, onPrimary = 0xFFFEF9F4, primaryContainer = 0xFFDFCDC5, onPrimaryContainer = 0xFF3D281E,
    secondary = 0xFF57624F, onSecondary = 0xFFFEF9F4, secondaryContainer = 0xFFD3DDCC, onSecondaryContainer = 0xFF26321D,
    tertiary = 0xFF926854, onTertiary = 0xFFFEF9F4, tertiaryContainer = 0xFFE5CFC5, onTertiaryContainer = 0xFF472B1E,
    surface = 0xFFFCF6EF, onSurface = 0xFF231E19, onSurfaceVariant = 0xFF605A52,
    surfaceDim = 0xFFDFD8CE, surfaceBright = 0xFFFCF6EF, surfaceContainerLowest = 0xFFFFFBF7,
    surfaceContainerLow = 0xFFF8F1EA, surfaceContainer = 0xFFF4EDE4, surfaceContainerHigh = 0xFFEFE8DF, surfaceContainerHighest = 0xFFEBE3D9,
    outline = 0xFF8E8881, outlineVariant = 0xFFD8CFC5,
    inverseSurface = 0xFF2F2723, inverseOnSurface = 0xFFF4EDE4, inversePrimary = 0xFFD2B7AC,
    error = 0xFFB6322D, onError = 0xFFFFFFFF, errorContainer = 0xFFFED7D1, onErrorContainer = 0xFF621513,
)

private val MochaDark = PresetRoles(
    primary = 0xFFD2B7AC, onPrimary = 0xFF2D1D17, primaryContainer = 0xFF56433A, onPrimaryContainer = 0xFFE8DBD5,
    secondary = 0xFFB5C3AA, onSecondary = 0xFF1F2718, secondaryContainer = 0xFF3A4333, onSecondaryContainer = 0xFFD9E1D4,
    tertiary = 0xFFD2AF9F, onTertiary = 0xFF2C1A12, tertiaryContainer = 0xFF594137, onTertiaryContainer = 0xFFEBDAD2,
    surface = 0xFF1C1411, onSurface = 0xFFE9E4DD, onSurfaceVariant = 0xFFB6B0A9,
    surfaceDim = 0xFF1C1411, surfaceBright = 0xFF403734, surfaceContainerLowest = 0xFF160F0C,
    surfaceContainerLow = 0xFF221A16, surfaceContainer = 0xFF28201C, surfaceContainerHigh = 0xFF2F2723, surfaceContainerHighest = 0xFF372E2B,
    outline = 0xFF857F79, outlineVariant = 0xFF433F39,
    inverseSurface = 0xFFEBE3D9, inverseOnSurface = 0xFF2F2723, inversePrimary = 0xFF5B3E31,
    error = 0xFFF6857A, onError = 0xFF4C100E, errorContainer = 0xFF68231F, onErrorContainer = 0xFFFED7D1,
)
