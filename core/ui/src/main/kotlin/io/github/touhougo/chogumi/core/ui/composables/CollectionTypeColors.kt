package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.ui.theme.isDark
import com.materialkolor.ktx.from
import com.materialkolor.ktx.harmonizeWithPrimary
import com.materialkolor.ktx.toneColor
import com.materialkolor.palettes.TonalPalette

/**
 * 一种收藏状态的四个颜色角色（同 M3 的 primary / onPrimary / primaryContainer / onPrimaryContainer）。
 * [color] 是实色（图表的格子与条、环上瞄准的那一格、瞄准线），[container] 是浅底色（环上平时的格子、预演胶囊、
 * 待提交胶囊、「我」的状态角标）。
 */
@Immutable
data class CollectionTypeColors(
    val color: Color,
    val onColor: Color,
    val container: Color,
    val onContainer: Color,
)

/**
 * 收藏状态 → 颜色，**全 App 唯一出处**（长按环、两种胶囊、状态角标、生涯与条目统计图都只读它）。
 *
 * 做法是 M3 的 static colors（color-schemes.md「Define static colors」，旧称 custom colors）：每个状态一个源色，
 * 向当前配色的 primary 协调（harmonize，色相最多偏 15°；用户换主题色或跟随系统时随之微调，绿还是绿），再从它的色调板取色调：
 * 浅色主题 40 / 100 / 90 / 30，深色 80 / 20 / 30 / 90，与 M3 给 primary 四件套取的色调一致。
 * 抛弃的源色是配色里的 error（M3：error 就是现成的语义红，不必另配），同样从它的色调板按上面的色调取，不直接用 errorContainer：
 * 新版配色规范里 errorContainer 色调更深更艳，和另外四种的浅底不在一档。[CollectionType.UNKNOWN] 用中性色（[neutralMarkColors]）。
 * 源色的分配是设计决定（not in M3），按最常见的直觉：在看是「进行中」的绿，想看是「待办」的蓝，看过是「完成」的紫
 * （和蓝、绿拉开色相，又不像红黄那样带警示意味），搁置是「暂停」的琥珀，抛弃是红。
 */
fun collectionTypeColors(type: CollectionType, scheme: ColorScheme): CollectionTypeColors {
    val source = when (type) {
        CollectionType.DONE -> scheme.harmonizeWithPrimary(DoneSource)
        CollectionType.DOING -> scheme.harmonizeWithPrimary(DoingSource)
        CollectionType.WISH -> scheme.harmonizeWithPrimary(WishSource)
        CollectionType.ON_HOLD -> scheme.harmonizeWithPrimary(OnHoldSource)
        // 配色里的 error 本身就是语义红，不再协调；但同样从它的色调板取色调，深浅档与另外四种一致
        CollectionType.DROPPED -> scheme.error
        CollectionType.UNKNOWN -> return neutralMarkColors(scheme)
    }
    val palette = TonalPalette.from(source)
    return if (scheme.isDark()) {
        CollectionTypeColors(palette.toneColor(80), palette.toneColor(20), palette.toneColor(30), palette.toneColor(90))
    } else {
        CollectionTypeColors(palette.toneColor(40), palette.toneColor(100), palette.toneColor(90), palette.toneColor(30))
    }
}

/**
 * 不用状态色时的中性配色：长按环上的「管理」（进收藏面板的入口，不是状态）、未知状态，以及别人的收藏状态角标
 * （[CollectionTypeBadge] 的 `tinted = false`）。
 * 平时 surfaceContainerHighest / onSurface，瞄准时 onSurfaceVariant 实色、其上用 surface（M3 保证这一对的对比度）。
 */
fun neutralMarkColors(scheme: ColorScheme): CollectionTypeColors =
    CollectionTypeColors(scheme.onSurfaceVariant, scheme.surface, scheme.surfaceContainerHighest, scheme.onSurface)

/** 当前主题下这种状态的颜色；配色变了（换主题色、深浅切换、跟随系统）才重算。 */
@Composable
fun CollectionType.colors(): CollectionTypeColors {
    val scheme = MaterialTheme.colorScheme
    return remember(this, scheme) { collectionTypeColors(this, scheme) }
}

private val DoneSource = Color(0xFF8B5CF6)
private val DoingSource = Color(0xFF3DA35D)
private val WishSource = Color(0xFF0EA5E9)
private val OnHoldSource = Color(0xFFE3B600)
