package io.github.touhougo.chogumi.core.ui.composables

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.res.R

/** 文字版的一格：一个选项，或组尾收纳放不下的选项的「更多」。 */
private sealed interface ChoiceSlot<out T> {
    data class Option<T>(val index: Int, val value: T) : ChoiceSlot<T>
    data object More : ChoiceSlot<Nothing>
}

/** [ConnectedChoiceGroup] 的文字版：按 [planTextChoices] 的结论排，见那里的 KDoc。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> TextChoiceGroup(
    options: List<T>,
    labels: List<String>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDeselect: (() -> Unit)?,
    badge: (T) -> Int,
    modifier: Modifier,
    enabled: (T) -> Boolean,
    visible: (T) -> Boolean,
    itemModifier: (T) -> Modifier,
) {
    val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val labelOf = { option: T -> labels[options.indexOf(option)] }
    val moreDescription = stringResource(R.string.choice_group_more)
    var menuOpen by remember { mutableStateOf(false) }
    // 点已选中的项：可以不选时收回选择，否则不做事
    val pick: (T) -> Unit = { option -> if (option == selected) onDeselect?.invoke() else onSelect(option) }
    BoxWithConstraints(modifier) {
        val visibleOptionIndices = options.indices.filter { visible(options[it]) }
        val selectedIndex = visibleOptionIndices.indexOfFirst { options[it] == selected }
        val fit = rememberTextChoiceFit(
            labels = visibleOptionIndices.map { labels[it] },
            badges = visibleOptionIndices.map { badge(options[it]) },
            maxWidth = constraints.maxWidth,
            selectedIndex = selectedIndex.takeIf { it >= 0 },
        )
        val shownVisibleIndices = if (fit is ChoiceFit.Overflow) fit.shownIndices else visibleOptionIndices.indices.toList()
        val shownOptionIndices = shownVisibleIndices.map { visibleOptionIndices[it] }
        val shownSet = shownOptionIndices.toSet()
        val shownOptions = shownOptionIndices.map(options::get)
        val overflowed = visibleOptionIndices.filterNot { it in shownSet }.map(options::get)
        val selectedOptionIndex = selected?.let { value -> options.indexOfFirst { it == value }.takeIf { it >= 0 } }
        val selectedSlot = selectedOptionIndex?.let { ChoiceSlot.Option(it, options[it]) }
        val optionSlots = options.indices.map { ChoiceSlot.Option(it, options[it]) }
        val slotVisible: (ChoiceSlot<T>) -> Boolean = { slot ->
            when (slot) {
                is ChoiceSlot.Option -> slot.index in shownSet
                ChoiceSlot.More -> overflowed.isNotEmpty()
            }
        }
        val shownSlotPositions = shownOptionIndices.withIndex().associate { (position, index) -> index to position }
        val contentPadding = PaddingValues(horizontal = with(LocalDensity.current) { fit.padding.toDp() })
        val moreWidth = maxOf(ButtonDefaults.SmallIconSize + NarrowChoicePadding * 2, ChoiceMinWidth)

        @Composable
        fun ChoiceRow(
            rowOptions: List<ChoiceSlot<T>>,
            rowModifier: Modifier,
            wrapLabel: Boolean,
            slotVisible: (ChoiceSlot<T>) -> Boolean,
            weightOf: (ChoiceSlot<T>) -> Float,
        ) {
            StyledButtonGroup(
                options = rowOptions,
                selected = selectedSlot,
                onSelect = { slot ->
                    when (slot) {
                        is ChoiceSlot.Option -> onSelect(slot.value)
                        ChoiceSlot.More -> menuOpen = true
                    }
                },
                modifier = rowModifier,
                visible = slotVisible,
                onReselect = { slot ->
                    when (slot) {
                        is ChoiceSlot.Option -> pick(slot.value)
                        ChoiceSlot.More -> menuOpen = true
                    }
                },
                enabled = { slot -> slot !is ChoiceSlot.Option || enabled(slot.value) },
                contentPadding = contentPadding,
                itemModifier = { slot ->
                    when (slot) {
                        is ChoiceSlot.Option -> itemModifier(slot.value)
                        ChoiceSlot.More -> Modifier.semantics { contentDescription = moreDescription }
                    }
                },
                selectedWeight = 1f,
                slotWeight = weightOf,
                style = ChoiceGroupStyle.SEGMENTED,
            ) { slot, _ ->
                when (slot) {
                    is ChoiceSlot.Option -> Row(
                        horizontalArrangement = Arrangement.spacedBy(ChoiceBadgeSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Crossfade(
                            targetState = labelOf(slot.value),
                            modifier = if (wrapLabel) Modifier.weight(1f, fill = false) else Modifier,
                            animationSpec = effectsSpec,
                            label = "ChoiceLabel",
                        ) {
                            // 常规排法已保证单行放得下；只有「文字 + 更多」连物理宽度都不够的两排回退才允许换行。
                            Text(
                                text = it,
                                maxLines = if (wrapLabel) Int.MAX_VALUE else 1,
                                softWrap = wrapLabel,
                                overflow = TextOverflow.Clip,
                            )
                        }
                        CountBadge(badge(slot.value))
                    }
                    ChoiceSlot.More -> {
                        Icon(painterResource(R.drawable.more_vert_24), contentDescription = null, modifier = Modifier.size(ButtonDefaults.SmallIconSize))
                        SelectableDropdownMenu(
                            open = menuOpen && overflowed.isNotEmpty(),
                            options = overflowed,
                            selected = selected.takeIf { it in overflowed },
                            label = { labelOf(it) },
                            onDismiss = { menuOpen = false },
                            onPick = pick,
                        )
                    }
                }
            }
        }

        if (fit is ChoiceFit.Overflow && fit.stacked) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                ChoiceRow(
                    rowOptions = optionSlots,
                    rowModifier = Modifier.fillMaxWidth(),
                    wrapLabel = true,
                    slotVisible = slotVisible,
                    weightOf = { 1f },
                )
                if (overflowed.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ChoiceRow(
                            rowOptions = listOf(ChoiceSlot.More),
                            rowModifier = Modifier.width(moreWidth),
                            wrapLabel = false,
                            slotVisible = { true },
                            weightOf = { 1f },
                        )
                    }
                }
            }
        } else {
            val slots = optionSlots +
                if (overflowed.isEmpty()) emptyList() else listOf(ChoiceSlot.More)
            ChoiceRow(
                rowOptions = slots,
                rowModifier = Modifier,
                wrapLabel = false,
                slotVisible = slotVisible,
                weightOf = { slot ->
                    when (slot) {
                        is ChoiceSlot.Option -> shownSlotPositions[slot.index]?.let(fit::weightOf) ?: 1f
                        ChoiceSlot.More -> if (overflowed.isEmpty()) 1f else fit.weightOf(shownOptions.size)
                    }
                },
            )
        }
    }
}


/**
 * 量出各格内容宽度（文字按按钮的字阶；有数字徽标的再加上徽标与间距）后交给 [planTextChoices]。
 * 徽标宽照 M3 Badge：标签文字左右各 4dp、至少 16dp（component-tokens.md Badge `LargeSize`）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberTextChoiceFit(
    labels: List<String>,
    badges: List<Int>,
    maxWidth: Int,
    selectedIndex: Int? = null,
): ChoiceFit {
    val measurer = rememberTextMeasurer()
    val style = ButtonDefaults.textStyleFor(ButtonDefaults.MinHeight)
    val badgeStyle = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    return remember(labels, badges, maxWidth, selectedIndex, style, badgeStyle, density) {
        with(density) {
            fun badgeWidth(count: Int): Int {
                val text = countBadgeText(count) ?: return 0
                val textWidth = measurer.measure(text, badgeStyle, maxLines = 1, softWrap = false).size.width
                return ChoiceBadgeSpacing.roundToPx() + maxOf(textWidth + 2 * BadgeLabelPadding.roundToPx(), BadgeMinSize.roundToPx())
            }
            planTextChoices(
                labelWidths = labels.mapIndexed { i, label ->
                    measurer.measure(label, style, maxLines = 1, softWrap = false).size.width + badgeWidth(badges[i])
                },
                available = maxWidth,
                gap = ButtonGroupDefaults.ConnectedSpaceBetween.roundToPx(),
                widePadding = ChoicePadding.roundToPx(),
                narrowPadding = NarrowChoicePadding.roundToPx(),
                minButton = ChoiceMinWidth.roundToPx(),
                moreButton = ButtonDefaults.SmallIconSize.roundToPx() + 2 * NarrowChoicePadding.roundToPx(),
                selectedIndex = selectedIndex,
            )
        }
    }
}

/**
 * 等分后的每一格是否都放得下「图标 + 间距 + 文字」（按静止宽度算，按压时的加宽 / 让位不改变结论，
 * 文字不会在动画中闪现闪没）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun labelsFitEverySlot(labels: List<String>, maxWidth: Int): Boolean {
    val measurer = rememberTextMeasurer()
    if (labels.isEmpty()) return true
    val style = ButtonDefaults.textStyleFor(ButtonDefaults.MinHeight)
    val density = LocalDensity.current
    return remember(labels, maxWidth, style, density) {
        with(density) {
            val slot = (maxWidth - ButtonGroupDefaults.ConnectedSpaceBetween.roundToPx() * (labels.size - 1)) / labels.size
            val room = slot - 2 * NarrowChoicePadding.roundToPx() - ButtonDefaults.SmallIconSize.roundToPx() - ButtonDefaults.IconSpacing.roundToPx()
            labels.all { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width <= room }
        }
    }
}

/**
 * 「全名放得下用全名，否则用短名，短名也放不下就不写字（null）」的**唯一判定**：选择组的整组标签和长按环的一格共用，
 * [fits] 回答某个候选放不放得下。
 */
internal inline fun <S> fullThenShort(full: S, short: S?, fits: (S) -> Boolean): S? = when {
    fits(full) -> full
    short != null && fits(short) -> short
    else -> null
}

/**
 * 叠排时每一格是否都放得下文字（label medium emphasized：选中那格加粗，按最宽的算）。按静止宽度算，同 [labelsFitEverySlot]。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun stackedLabelsFit(labels: List<String>, maxWidth: Int): Boolean {
    val measurer = rememberTextMeasurer()
    if (labels.isEmpty()) return true
    val style = MaterialTheme.typography.labelMediumEmphasized
    val density = LocalDensity.current
    return remember(labels, maxWidth, style, density) {
        with(density) {
            val slot = (maxWidth - ButtonGroupDefaults.ConnectedSpaceBetween.roundToPx() * (labels.size - 1)) / labels.size
            val room = slot - 2 * NarrowChoicePadding.roundToPx()
            labels.all { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width <= room }
        }
    }
}

/** 叠排的一格：图标（M3 medium 按钮的图标尺寸）在上、可选文字在下；选中那格文字 emphasized。按钮自身的语义已带标签。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StackedIconLabel(@DrawableRes iconRes: Int, label: String?, checked: Boolean) {
    Column(
        modifier = Modifier.clearAndSetSemantics {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(StackedLabelSpacing),
    ) {
        Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(ButtonDefaults.MediumIconSize))
        if (label != null) {
            Text(
                text = label,
                style = if (checked) MaterialTheme.typography.labelMediumEmphasized else MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** 叠排时图标与文字的间距（设计稿 `.status button{gap:2px}`，not in M3）。 */
private val StackedLabelSpacing = 2.dp

/**
 * 图标 + 可选文字（[label] 为 null 时只画图标）。按钮自身的语义已带标签，这里不重复念。
 * 图标尺寸与间距取 M3 small 按钮（[ButtonDefaults.SmallIconSize] / [ButtonDefaults.IconSpacing]）。
 */
@Composable
internal fun IconWithLabel(@DrawableRes iconRes: Int, label: String?) {
    Row(
        modifier = Modifier.clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(ButtonDefaults.IconSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(ButtonDefaults.SmallIconSize))
        if (label != null) Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}
