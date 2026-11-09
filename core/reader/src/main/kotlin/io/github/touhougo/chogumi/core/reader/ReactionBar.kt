package io.github.touhougo.chogumi.core.reader

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.AppDropdownMenu
import io.github.touhougo.chogumi.core.ui.composables.AppMenuItem
import io.github.touhougo.chogumi.core.ui.composables.RollingText
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * 贴贴的界面（楼层与动态详情面板共用，只此一套）：已有贴贴的一排 chip（[ReactionBar]）、
 * 「添加贴贴」图标按钮、表情选择器（[ReactionPicker]）。贴什么、能贴哪些由调用方给（palette 来自 `reactionsOf` /
 * `TimelineReactionTarget.palette`），这里只管画与把点击换成「贴上 value / 取消（null）」。
 */

// 贴贴选择器与 chip 的几何——设计决定（not in M3）：表情排两行，12 个一行 6 格，格子 44 加左右 8 正好是
// M3 菜单的最大宽度 280dp（feedback.md Menus），一次点击就能贴，不用横滑或二级菜单。
private const val REACTION_PICKER_ROWS = 2
private val ReactionPickerCell = 44.dp
private val ReactionPickerSmiley = 24.dp

/** 已被占用的表情画多淡：取 Compose M3 组件不可用内容用的透明度（M3 规范里没写数值，我的设计决定）。 */
private const val TAKEN_ALPHA = 0.38f
private val ReactionChipHeight = 28.dp
private val ReactionChipSmiley = 16.dp
/** 贴上时 chip 弹一下的峰值缩放（设计决定，not in M3）。 */
private const val REACTION_POP_SCALE = 1.15f

/**
 * 一排 chip 的高度与 chip 里计数的尺寸过渡。两处都按动画途中的尺寸裁剪，尺寸弹簧一过冲，收矮时就把
 * 最后一行、计数变窄时把数字裁掉一截，所以取不回弹的 standard 方案（tokens.md：阻尼 0.9）。
 */
@Composable
private fun <T> reactionSizeSpec(): FiniteAnimationSpec<T> = LocalAppMotion.current.scheme.fastSpatialSpec()

/** 新出现的 chip 与重新出现的「添加贴贴」：缩放淡入。 */
@Composable
private fun reactionEnter(): EnterTransition =
    scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.fastEffectsSpec())

/**
 * 选择器开在哪：打开与位置分开存，关闭时位置保留，菜单退场动画仍在原处收起（合为一个可空值时，关闭瞬间位置归零，
 * 菜单闪到左侧）。位置是选择器挂上去的那块（[target]）：长按是手指那一点，「添加贴贴」是按钮本身；
 * 坐标系是锚点（挂了 [reactionPickerAnchor] 的那块，选择器就画在它里面）。
 * 长按打开的是整层楼的菜单（[withActions]：表情下面还有编辑、删除自己的楼）；「添加贴贴」只开表情。
 */
@Stable
internal class ReactionPickerState {
    var open by mutableStateOf(false)
        private set
    var target by mutableStateOf(Rect.Zero)
        private set
    var withActions by mutableStateOf(false)
        private set
    internal var anchor: LayoutCoordinates? = null

    /** 长按：从锚点里的 [position]（手指处）弹出整层楼的菜单。 */
    fun openAt(position: Offset) = openOn(Rect(position, Size.Zero), actions = true)

    /** 挂在 [chip]（「添加贴贴」按钮）上只弹出表情：下方放得下贴着它下沿，放不下贴着它上沿。 */
    fun openOn(chip: LayoutCoordinates) {
        val anchor = anchor ?: return
        openOn(anchor.localBoundingBoxOf(chip, clipBounds = false), actions = false)
    }

    private fun openOn(rect: Rect, actions: Boolean) {
        target = rect
        withActions = actions
        open = true
    }

    fun close() {
        open = false
    }
}

@Composable
internal fun rememberReactionPickerState() = remember { ReactionPickerState() }

/** 选择器的锚点：选择器（[ReactionPicker]）要放在挂了它的那个布局里面。 */
internal fun Modifier.reactionPickerAnchor(state: ReactionPickerState): Modifier = onPlaced { state.anchor = it }

/**
 * 已有的贴贴：一排 chip（[CommentReactionsFlow]），整排高度变化有过渡（[animateRowHeight]）。
 * [onReact] 非 null 时 chip 可点：自己贴过的取消，别的换成它，不在 [palette] 里的只读。
 * 长按一颗 chip 看谁贴了（[onShowUsers]，参数是表情，名单面板见 [ReactionUsersSheet]）；chip 不能贴时点一下也是看名单。
 *
 * [onAdd] 非 null 时末尾一个「添加贴贴」，只在自己还没贴的时候出：贴上了（选择器里挑的，或点别人那颗 +1）它就收起，
 * 要换点别的 chip、要取消点自己那颗；取消之后它再出现。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReactionBar(
    reactions: List<CommentReactionBo>,
    palette: List<Int>,
    onReact: ((Int?) -> Unit)?,
    onShowUsers: (value: Int) -> Unit,
    modifier: Modifier = Modifier,
    onAdd: ((chip: LayoutCoordinates) -> Unit)? = null,
) {
    FlowRow(
        // 整排占满给它的宽度，只有高度（换行）做过渡：宽度不跟着 chip 增减变，末尾的「添加贴贴」挪位时不被动画中的宽度追着裁
        modifier = modifier
            .fillMaxWidth()
            .animateRowHeight(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        CommentReactionsFlow(
            reactions = reactions,
            onToggle = onReact?.let { react ->
                { reaction: CommentReactionBo ->
                    when {
                        reaction.selected -> react(null)
                        reaction.value in palette -> react(reaction.value)
                    }
                }
            },
            onShowUsers = onShowUsers,
        )
        if (onAdd != null) {
            // 收起是一下子的：贴上的那颗 chip 正好缩放淡入在它原来的位置
            AnimatedVisibility(visible = reactions.none { it.selected }, enter = reactionEnter(), exit = ExitTransition.None) {
                AddReactionButton(onAdd)
            }
        }
    }
}

/**
 * 整排高度（换行）的过渡。不用 `animateContentSize`：它一直按自身大小裁剪，chip 贴上时弹一下（[REACTION_POP_SCALE]）
 * 伸出这一排的那一圈被切掉，放大像是关在一个框里。这里只在高度变化的途中按动画中的下沿裁剪（不让新一行
 * 压到下面的内容上），左右与上沿从不裁，静止时整排不裁。
 */
@Composable
private fun Modifier.animateRowHeight(): Modifier {
    val scope = rememberCoroutineScope()
    val height = remember(scope) { RowHeight(scope) }
    height.spec = reactionSizeSpec()
    return drawWithContent {
        if (height.running) {
            clipRect(left = -size.width, top = -size.height, right = size.width * 2, bottom = size.height) {
                this@drawWithContent.drawContent()
            }
        } else {
            drawContent()
        }
    }.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, constraints.constrainHeight(height.heightFor(placeable.height))) { placeable.place(0, 0) }
    }
}

/** [animateRowHeight] 的高度：首次量到的直接用，之后每换一个目标从当前高度过渡过去。 */
private class RowHeight(private val scope: CoroutineScope) {
    lateinit var spec: FiniteAnimationSpec<Int>
    private var anim: Animatable<Int, AnimationVector1D>? = null
    private var target = 0

    val running: Boolean get() = anim?.isRunning == true

    fun heightFor(measured: Int): Int {
        val current = anim ?: return measured.also {
            anim = Animatable(it, Int.VectorConverter)
            target = it
        }
        if (measured != target) {
            target = measured
            scope.launch { current.animateTo(measured, spec) }
        }
        return current.value
    }
}

/** 「添加贴贴」：点了贴着它弹出贴贴选择器。 */
@Composable
private fun AddReactionButton(onClick: (chip: LayoutCoordinates) -> Unit) =
    ReactionRowButton(R.drawable.mood_24, R.string.floor_react_add, onClick)

/**
 * 贴贴那一排里的图标按钮（「添加贴贴」、动态的「回复」）：M3 extra small 描边图标按钮（component-tokens.md XSmallIconButton：
 * 容器 32、图标 20）。版面只占按钮本身（与 28 高的 chip 同排，按钮出没时一排高度几乎不变），48dp 触控区与 chip 一样由
 * Compose 对小目标的触控扩展给。[onClick] 带着按钮自己的位置（选择器贴着它弹出）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReactionRowButton(@DrawableRes icon: Int, @StringRes label: Int, onClick: (button: LayoutCoordinates) -> Unit) {
    // 只在点的时候读位置：滚动时每帧都会放置，不能把它放进会触发重组的状态
    val placed = remember { PlacedChip() }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        OutlinedIconButton(
            onClick = { placed.coordinates?.let(onClick) },
            modifier = Modifier
                .size(IconButtonDefaults.extraSmallContainerSize())
                .onPlaced { placed.coordinates = it },
        ) {
            Icon(
                painterResource(icon),
                contentDescription = stringResource(label),
                modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
            )
        }
    }
}

private class PlacedChip {
    var coordinates: LayoutCoordinates? = null
}

/** 楼层菜单里的一项（M3 菜单项：前置图标 + 文字）。 */
internal class FloorMenuAction(@StringRes val label: Int, @DrawableRes val icon: Int, val onClick: () -> Unit)

/**
 * 贴贴选择器，也是一层楼的长按菜单：表情格子（[ReactionGrid]，两行铺开）里我贴着的（[reactions] 里 selected 的那个）高亮，
 * 再点它是取消。放在 [reactionPickerAnchor] 的那块里，挂在 [ReactionPickerState.target] 上弹出。
 *
 * 长按打开时（[ReactionPickerState.withActions]）表情下面接 [actionGroups]：每组一块，组间是 M3 菜单的 gap
 * （分组的 [AppDropdownMenu]；空的组不画）。一层楼只有一个长按菜单；没有贴贴的地方（[onPick] 为 null）只有这些项。
 * 「添加贴贴」打开时只有表情。
 */
@Composable
internal fun ReactionPicker(
    state: ReactionPickerState,
    palette: List<Int>,
    reactions: List<CommentReactionBo>,
    onPick: ((Int?) -> Unit)?,
    actionGroups: List<List<FloorMenuAction>> = emptyList(),
) {
    val selected = reactions.firstOrNull { it.selected }?.value
    val target = state.target
    val targetSize = with(LocalDensity.current) { DpSize(target.width.toDp(), target.height.toDp()) }
    val groups = buildList<@Composable ColumnScope.() -> Unit> {
        if (onPick != null && palette.isNotEmpty()) {
            add {
                ReactionGrid(palette, selected) { value ->
                    state.close()
                    onPick(value)
                }
            }
        }
        if (state.withActions) {
            actionGroups.filter { it.isNotEmpty() }.forEach { group ->
                add {
                    group.forEach { action ->
                        AppMenuItem(stringResource(action.label), onDismiss = state::close, icon = action.icon, onClick = action.onClick)
                    }
                }
            }
        }
    }
    // 菜单的锚点就是 target 那一块，下方放不下时菜单翻到它上沿之上。target 若当作整块锚点里的偏移传进去，
    // 翻转会按整块计算：楼层很高、按钮靠近屏幕底部时，菜单被甩到窗口顶上。
    Box(
        Modifier
            .offset { target.topLeft.round() }
            .size(targetSize),
    ) {
        AppDropdownMenu(expanded = state.open, onDismissRequest = state::close, groups = groups)
    }
}

/**
 * 贴贴的表情格子（楼层的贴贴选择器、编辑器里给投票选项挑表情共用）：排成 [REACTION_PICKER_ROWS] 行（12 个是 6×2，
 * 作品吐槽的 8 个是 4×2，不会有半行），[selected] 高亮，点了交出这个值（再点高亮的那个是取消，交出 null）；
 * [taken] 里的（别处已占用的）变淡、不能点。
 */
@Composable
internal fun ReactionGrid(palette: List<Int>, selected: Int?, taken: Set<Int> = emptySet(), onPick: (Int?) -> Unit) {
    val columns = (palette.size + REACTION_PICKER_ROWS - 1) / REACTION_PICKER_ROWS
    Column(Modifier.padding(horizontal = 8.dp)) {
        palette.chunked(columns.coerceAtLeast(1)).forEach { row ->
            Row {
                row.forEach { value ->
                    val isSelected = value == selected
                    val isTaken = value in taken
                    Box(
                        Modifier
                            .size(ReactionPickerCell)
                            .clip(MaterialTheme.shapes.medium)
                            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .selectable(selected = isSelected, enabled = !isTaken, role = Role.Button) { onPick(value.takeUnless { isSelected }) }
                            .alpha(if (isTaken) TAKEN_ALPHA else 1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        ReactionGlyph(reactionDisplayOf(value), ReactionPickerSmiley)
                    }
                }
            }
        }
    }
}

/**
 * 贴贴 chip（原型 `.react`）：高 28、左右 10、全圆角、表情 16；我贴过的用 `primaryContainer`。[onToggle] 非 null 时可点。
 *
 * 动画（贴贴不能只是机械地换色加数）：
 * - 计数变化：新数字从下方滑入（+1）或上方滑入（−1），旧数字反向滑出；
 * - 选中态：底色与文字色渐变，贴上的那一下 chip 弹一下（[REACTION_POP_SCALE]，弹簧回落）；
 * - 新出现的 chip 缩放淡入（首次显示的不播，滑进屏幕的楼层不会每层都弹一遍）。
 * 时长与弹簧取 `MaterialTheme.motionScheme`（M3 standard）。
 */
@Composable
private fun CommentReactionsFlow(
    reactions: List<CommentReactionBo>,
    onToggle: ((CommentReactionBo) -> Unit)?,
    onShowUsers: (value: Int) -> Unit,
) {
    val initialValues = remember { reactions.map { it.value }.toSet() }
    reactions.forEach { reaction ->
        key(reaction.value) {
            val visible = remember { MutableTransitionState(reaction.value in initialValues).apply { targetState = true } }
            AnimatedVisibility(visibleState = visible, enter = reactionEnter()) {
                ReactionChip(reaction, onToggle, onShowUsers)
            }
        }
    }
}

@Composable
private fun ReactionChip(reaction: CommentReactionBo, onToggle: ((CommentReactionBo) -> Unit)?, onShowUsers: (value: Int) -> Unit) {
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val container by animateColorAsState(
        if (reaction.selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        effects,
        label = "reactionContainer",
    )
    val content by animateColorAsState(
        if (reaction.selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        effects,
        label = "reactionContent",
    )
    val pop = remember { Animatable(1f) }
    val popUp = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val settle = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val scope = rememberCoroutineScope()
    // 弹一下属于这一次点按（事件），不挂在 selected 上：别处改了（刷新、另一台设备）不弹；连点时新一段顶掉旧一段
    val popOnSelect = {
        scope.launch {
            pop.animateTo(REACTION_POP_SCALE, popUp)
            pop.animateTo(1f, settle)
        }
        Unit
    }
    val display = reactionDisplayOf(reaction.value)
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = Modifier.graphicsLayer {
            scaleX = pop.value
            scaleY = pop.value
        },
    ) {
        Row(
            modifier = Modifier
                .reactionChipGestures(
                    selected = reaction.selected,
                    onToggle = onToggle?.let { toggle ->
                        {
                            if (!reaction.selected) popOnSelect()
                            toggle(reaction)
                        }
                    },
                    onShowUsers = { onShowUsers(reaction.value) },
                    usersLabel = stringResource(R.string.reaction_users_open),
                )
                .heightIn(min = ReactionChipHeight)
                .padding(horizontal = 10.dp)
                .animateContentSize(reactionSizeSpec()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReactionGlyph(display, ReactionChipSmiley)
            // 只翻变了的那几位：100 → 101 只有末位翻，「10」不动（全 App 的数字变化都走 RollingText）
            RollingText(text = "${reaction.count}", order = reaction.count, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * 一颗 chip 的手势：点 = 贴上或取消（开关语义，读屏读出选中与否），长按 = 看谁贴了。不能贴时（[onToggle] 为 null）点与长按都是看名单。
 * 长按由 chip 自己吃掉，不会再弹出楼层的长按菜单。
 */
private fun Modifier.reactionChipGestures(
    selected: Boolean,
    onToggle: (() -> Unit)?,
    onShowUsers: () -> Unit,
    usersLabel: String,
): Modifier =
    if (onToggle != null) {
        semantics { toggleableState = ToggleableState(selected) }
            .combinedClickable(role = Role.Checkbox, onLongClickLabel = usersLabel, onLongClick = onShowUsers, onClick = onToggle)
    } else {
        combinedClickable(onClickLabel = usersLabel, onLongClickLabel = usersLabel, onLongClick = onShowUsers, onClick = onShowUsers)
    }

/** 一个贴贴表情：内置 / 远程表情画图，未知值画数字。楼层的 chip、选择器与动态卡片共用。 */
@Composable
internal fun ReactionGlyph(display: ReactionDisplay, size: Dp) {
    when (display) {
        is ReactionDisplay.Smiley -> SmileyImage(display.code, size = size)
        is ReactionDisplay.Unknown -> Text(display.value.toString(), style = MaterialTheme.typography.labelMedium)
    }
}
