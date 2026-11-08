package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 连通按钮组里第 [index] 个可见按钮的三套官方形状（首 / 中 / 尾），全仓只此一处。
 * 不直接交给官方按钮，由 [connectedShapeOf] 取出此刻的那一套、[rememberMorphingShape] 连续过渡过去。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun connectedButtonShapesAt(index: Int, count: Int): ToggleButtonShapes = when (index) {
    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
    count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
    // 中间格，以及正在退场、已不在可见列表里的格（index = -1）
    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
}

/** 此刻该是哪一个官方形状：按下优先，其次选中，否则静止（与官方切换按钮同一优先级）。 */
private fun ToggleButtonShapes.connectedShapeOf(pressed: Boolean, checked: Boolean): CornerBasedShape = when {
    pressed -> pressedShape
    checked -> checkedShape
    else -> shape
} as CornerBasedShape

/** 单选连通按钮组的样子：整组一种（M3：连接按钮组里不混用配色）。 */
enum class ChoiceGroupStyle {
    /**
     * 分段（原型 `.cbg`）：横排，未选 `surfaceContainerHigh` / `onSurfaceVariant`，选中 `secondaryContainer` / `onSecondaryContainer`。
     * 不是 M3 四种内置配色之一（filled 选中是 primary）——我的设计决定（not in M3），取自原型；
     * M3 允许换色角色，条件是容器与文字对比 ≥ 3:1，这两组本就是 M3 配好的容器 / 内容对。
     */
    SEGMENTED,

    /**
     * 收藏状态（要给图标）：图标在上、文字在下（五格放不下「图标 + 文字」横排），按钮高取 M3 medium 按钮的容器高，
     * 文字用 label medium（选中的那格 emphasized）；选中由方变圆、填 primary（M3 filled 切换按钮）。
     * 未选是 `surfaceContainerHighest`：收藏面板底是 surface container low，M3 filled 的未选底色（surface container）
     * 在上面几乎看不出来。叠排、medium 高与未选底色都是我的设计决定（not in M3：M3 按钮的图标与文字横排）。
     */
    STATUS,
}

@Composable
private fun ChoiceGroupStyle.colors(): ToggleButtonColors = when (this) {
    ChoiceGroupStyle.SEGMENTED -> ToggleButtonDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    ChoiceGroupStyle.STATUS -> ToggleButtonDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        checkedContainerColor = MaterialTheme.colorScheme.primary,
        checkedContentColor = MaterialTheme.colorScheme.onPrimary,
    )
}

/** 按钮内左右留白：M3 ButtonSmall 的 LeadingSpace / TrailingSpace 16dp（component-tokens.md；连通按钮组 small 高 40dp）。 */
internal val ChoicePadding = 16.dp

/**
 * 收紧后的按钮内左右留白（原型 `.cbg button{padding:0 8px}`）：图标版一直用它；文字版放不下时才退到它。
 * 我的设计决定（not in M3）——M3 只说紧凑窗口里「考虑更窄的按钮让所有按钮放得下」（buttons.md 按钮组 Placement）。
 */
internal val NarrowChoicePadding = 8.dp

/** 图标版按钮内左右留白。 */
private val IconChoicePadding = PaddingValues(horizontal = NarrowChoicePadding)

/** 文字与数字徽标之间的间距（原型 `.fd{gap:4px}`，设计决定，not in M3）。 */
internal val ChoiceBadgeSpacing = 4.dp

/** M3 Badge 标签文字左右的留白（Compose Material3 `Badge` 的实现值）。 */
internal val BadgeLabelPadding = 4.dp

/** M3 large badge 的最小边长（component-tokens.md Badge `LargeSize`）。 */
internal val BadgeMinSize = 16.dp

/** 连通按钮组里每格的最小宽度：M3「extra small and small connected button groups have … a minimum width of 48dp」。 */
internal val ChoiceMinWidth = 48.dp

/**
 * 单选连通按钮组的骨架（原型 `.cbg`：ConnectedButtonGroup small，高 40dp、间距 2dp），
 * **全仓的单选分段都走这里**：形状、配色、单选语义、按压弹性、选项增减动画只此一处。
 * 调用方只决定「有哪些选项、格里画什么」，不得自己拼 `ToggleButton` + 首 / 中 / 尾形状。
 *
 * 按压时被按的那格用 M3 [ButtonGroup] 的 `animateWidth` 以弹簧加宽、相邻格让位，松手回弹。
 * M3 规范里这是 standard 组的行为，connected 组只变形不挤邻居——这里给连通组也加上，
 * 是我的设计决定（not in M3）：单纯的形变不够有弹性（Albert 反馈）。
 *
 * [visible] 为 false 的选项不占位；在可见与不可见之间切换时，该格宽度与间距随出现比例收放、
 * 同时淡入淡出，其余格平滑重新分配宽度（搜索排序随条件增减可用项）。宽度是几何变化用 spatial，
 * 透明度用 effects（`motion.md`），都取 `MaterialTheme.motionScheme` 的 fast 档（按钮属小组件）。
 *
 * [itemModifier] 挂在每格按钮上（测试标签、额外语义等）；[content] 画格内内容。
 *
 * [selectedWeight] 是选中格相对其余格的宽度倍数（默认 1 = 等宽）。大于 1 时选中格伸展、
 * 切换选中项时宽度以 spatial 弹簧过渡（原型 `.cbg button[aria-pressed]{flex-grow:2.2}`，详情页选项卡用；
 * 我的设计决定，not in M3）。
 *
 * 配色是分段的（[ChoiceGroupStyle.SEGMENTED]）；别的样子只经 [ConnectedChoiceGroup] 的 style 给。
 */
@Composable
fun <T> ConnectedButtonGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    visible: (T) -> Boolean = { true },
    enabled: (T) -> Boolean = { true },
    contentPadding: PaddingValues = PaddingValues(horizontal = ChoicePadding),
    itemModifier: (T) -> Modifier = { Modifier },
    selectedWeight: Float = 1f,
    content: @Composable (option: T, checked: Boolean) -> Unit,
) {
    StyledButtonGroup(
        options, selected, onSelect, modifier, visible, enabled, contentPadding, itemModifier, selectedWeight,
        slotWeight = { 1f }, onReselect = {}, style = ChoiceGroupStyle.SEGMENTED, content = content,
    )
}

/**
 * [ConnectedButtonGroup] 的实现，[ConnectedChoiceGroup] 直接用它：
 * [slotWeight] 是每格的宽度权重（文字版放不下时按文字宽度分）；[onReselect] 在点已选中的格时调用（「更多」格要再次打开菜单）；
 * 配色按 [style]（叠排的收藏状态要 primary）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> StyledButtonGroup(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier,
    visible: (T) -> Boolean,
    enabled: (T) -> Boolean,
    contentPadding: PaddingValues,
    itemModifier: (T) -> Modifier,
    selectedWeight: Float,
    slotWeight: (T) -> Float,
    onReselect: (T) -> Unit,
    style: ChoiceGroupStyle,
    content: @Composable (option: T, checked: Boolean) -> Unit,
) {
    val colors = style.colors()
    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val shown = options.filter(visible)
    val slots = options.map { option ->
        key(option) {
            val transition = updateTransition(visible(option), label = "ConnectedButtonGroupSlot")
            ConnectedSlot(
                option = option,
                // 退场动画结束（起止都是不可见）才移出组合：不可见项不留节点、不留间距。
                present = transition.currentState || transition.targetState,
                // spatial 会轻微超调，比例可略超 1；用作 weight 时下限只为满足 weight > 0。
                presence = transition.animateFloat(transitionSpec = { spatialSpec }) { if (it) 1f else 0f },
                alpha = transition.animateFloat(transitionSpec = { effectsSpec }) { if (it) 1f else 0f },
            )
        }
    }
    ButtonGroup(
        overflowIndicator = {},
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        // 组内间距由每格的左内边距随出现比例给出（Arrangement.spacedBy 会给退场中的格也留满间距）。
        horizontalArrangement = Arrangement.Start,
    ) {
        slots.filter { it.present }.forEach { slot ->
            customItem(
                buttonGroupContent = {
                    // ButtonGroup 按位置组合各格：不按选项分组，增减项后第 2 格会沿用原第 2 格（别的选项）的
                    // 状态——格内动画从旧文案过渡到新文案、按压状态串格。按选项分组，状态只属于自己那一项。
                    key(slot.option) {
                        val option = slot.option
                        val checked = option == selected
                        val position = shown.indexOf(option)
                        val presence = slot.presence.value
                        val stretch by animateFloatAsState(
                            targetValue = if (checked) selectedWeight else 1f,
                            animationSpec = spatialSpec,
                            label = "ConnectedButtonGroupStretch",
                        )
                        val interactionSource = remember { MutableInteractionSource() }
                        val pressed by interactionSource.collectIsPressedAsState()
                        val shape = rememberMorphingShape(
                            target = connectedButtonShapesAt(position, shown.size).connectedShapeOf(pressed, checked),
                            spec = spatialSpec,
                        )
                        ToggleButton(
                            checked = checked,
                            onCheckedChange = { if (checked) onReselect(option) else onSelect(option) },
                            enabled = enabled(option) && visible(option),
                            interactionSource = interactionSource,
                            modifier = Modifier
                                .weight((presence * stretch * slotWeight(option)).coerceAtLeast(MIN_SLOT_WEIGHT))
                                .animateWidth(interactionSource)
                                .padding(start = if (position == 0) 0.dp else ButtonGroupDefaults.ConnectedSpaceBetween * presence.coerceIn(0f, 1f))
                                .graphicsLayer { alpha = slot.alpha.value }
                                .semantics {
                                    role = Role.RadioButton
                                    this.selected = checked
                                }
                                .then(itemModifier(option)),
                            shapes = ToggleButtonShapes(shape, shape, shape),
                            colors = colors,
                            contentPadding = contentPadding,
                        ) {
                            content(option, checked)
                        }
                    }
                },
                menuContent = {},
            )
        }
    }
}

/** 按钮组里一格的出现状态（见 [ConnectedButtonGroup] 的 visible）。 */
private class ConnectedSlot<T>(
    val option: T,
    val present: Boolean,
    val presence: State<Float>,
    val alpha: State<Float>,
)

/**
 * 连通按钮组的占位（骨架用，全仓唯一：详情页切换栏、目录页页签共用）：每格一个胶囊，
 * 高为官方按钮容器高（[ButtonDefaults.MinHeight]），在官方 `minimumInteractiveComponentSize()` 的 48dp 触控区里居中——
 * 真按钮的布局高度含这 48dp，占位只占 40dp 的话按钮组一出现下面的内容就被顶下 8dp。格间距同 [ConnectedButtonGroup]。
 *
 * @param weights 每格的宽度权重（等宽传全 1；详情页切换栏选中格伸展）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectedButtonGroupSkeleton(weights: List<Float>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        weights.forEach { weight ->
            Box(Modifier.weight(weight).minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
                SkeletonBox(Modifier.fillMaxWidth().height(ButtonDefaults.MinHeight), shape = CircleShape)
            }
        }
    }
}

/** weight 必须大于 0；退场尾段的占位宽度，肉眼不可见。 */
private const val MIN_SLOT_WEIGHT = 0.001f

/**
 * 单选连通按钮组（文字或「图标 + 文字」）：主页分区、主页页内小类、生涯类型、动态页「好友 / 全站」、搜索排序、设置项共用。
 * **全仓文字分段都走这里，不许直接给 [ConnectedButtonGroup] 塞 `Text`**：这里保证任何屏宽、任何字号下文字都不被截成「…」。
 *
 * - 没给 [icon] 时只画文字，排法按顺序取第一个放得下的（判定见 [planTextChoices]）：
 *   等宽（M3 small 按钮留白 16）→ 按文字宽度分宽（M3 按钮组「Flexible」：按钮宽度随内容增减）→
 *   留白收到 8 → 组尾的几项收进「更多」菜单（M3：「buttons at the trailing edge of the group can collapse into an
 *   overflow menu」，菜单放在组的尾端）。当前选中项优先留在组里，尾部被收纳时仍显示该项文字。
 *   文字变化（如标签浏览下「收藏」改叫「标注数」）淡入淡出。
 * - 给了 [icon] 时画「图标 + 文字」，按钮内左右 8dp（M3 小按钮是 16dp——我的设计决定，not in M3：
 *   一行五段时 16dp 放不下「图标 + 两字」）。整组只要有一格放不下文字，**整组**改画 [shortLabel]
 *   （给了且每格都放得下时，如作品类型筛选的「图标 + 计数」），否则只留图标；
 *   不会出现个别格掉字、也不出现「…」；读屏始终念完整标签（同为我的设计决定）。
 * - [style] 为 [ChoiceGroupStyle.STATUS] 时图标在上、文字在下（见那里）；放不放得下、整组换 [shortLabel] 还是只留图标，规则同上。
 * - [enabled] 为 false 的项不可点（如未登录时的「好友」）。
 * - [visible] 为 false 的项不占位，出现 / 消失走 [ConnectedButtonGroup] 的收放动画（同搜索排序的可用项增减）；
 *   放不放得下文字只按可见项算。
 * - [itemModifier] 挂在每个选项的按钮上（测试标签等）。
 * - [onDeselect] 给了时可以一个都不选（[selected] 为 null）：再点已选中的那格（或在「更多」菜单里再点它）就调它，
 *   用于「点一下展开、再点收起」（进度页筛选面板的分类维度）。不给时是普通单选，再点已选中的格不做事。
 * - [badge] 是文字版每格文字后的数字（[CountBadge]，0 不画），排法把它的宽度算进去，照样不出「…」。
 *
 * 四周不留白：左右与上下间距由所在页面给出。
 */
@Composable
fun <T> ConnectedChoiceGroup(
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ((option: T, selected: Boolean) -> Int)? = null,
    shortLabel: (@Composable (T) -> String)? = null,
    enabled: (T) -> Boolean = { true },
    visible: (T) -> Boolean = { true },
    itemModifier: (T) -> Modifier = { Modifier },
    style: ChoiceGroupStyle = ChoiceGroupStyle.SEGMENTED,
    onDeselect: (() -> Unit)? = null,
    badge: (T) -> Int = { 0 },
) {
    val stacked = style == ChoiceGroupStyle.STATUS
    val labels = options.map { label(it) }
    if (icon == null) {
        TextChoiceGroup(options, labels, selected, onSelect, onDeselect, badge, modifier, enabled, visible, itemModifier)
        return
    }
    val shortLabels = shortLabel?.let { short -> options.map { short(it) } }
    BoxWithConstraints(modifier) {
        val visibleIndices = options.indices.filter { visible(options[it]) }
        val fit: @Composable (List<String>) -> Boolean = { texts ->
            if (stacked) stackedLabelsFit(texts, maxWidth = constraints.maxWidth) else labelsFitEverySlot(texts, maxWidth = constraints.maxWidth)
        }
        val shown = fullThenShort(labels, shortLabels) { fit(visibleIndices.map(it::get)) }
        StyledButtonGroup(
            options = options,
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier,
            visible = visible,
            enabled = enabled,
            contentPadding = IconChoicePadding,
            // 文字可能被省掉，标签挂在按钮上
            itemModifier = { option ->
                Modifier
                    .then(if (stacked) Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight) else Modifier)
                    .semantics { contentDescription = labels[options.indexOf(option)] }
                    .then(itemModifier(option))
            },
            selectedWeight = 1f,
            slotWeight = { 1f },
            onReselect = { onDeselect?.invoke() },
            style = style,
        ) { option, checked ->
            if (stacked) {
                StackedIconLabel(iconRes = icon(option, checked), label = shown?.get(options.indexOf(option)), checked = checked)
            } else {
                IconWithLabel(iconRes = icon(option, checked), label = shown?.get(options.indexOf(option)))
            }
        }
    }
}
