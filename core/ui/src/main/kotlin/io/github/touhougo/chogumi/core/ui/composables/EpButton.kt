package io.github.touhougo.chogumi.core.ui.composables

import android.content.res.Resources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.EP_COMMIT_DELAY_NANOS
import io.github.touhougo.chogumi.core.model.EpPendingVisual
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import kotlinx.coroutines.launch

/** 倒计时环的直径：M3 圆形进度指示器的标准尺寸（`component-tokens.md` CircularProgressIndicator.Size），正好是容器高。 */
private val WaitingDialSize = 40.dp

/** 放进按钮当图标的倒计时环：M3 圆形进度指示器的最小尺寸（`components/feedback.md`：circular sizes range 24dp to 240dp）。 */
private val WaitingGlyphSize = 24.dp

/**
 * EP 按钮加载中的占位：M3 的 **skeleton loader**（`transitions.md`：提示内容将出现的位置、稳定布局，
 * 内容到了淡入覆盖）。不用 LoadingIndicator——它是 48dp 的形变动画，用于 200ms–5s 的整块等待，
 * 放进一行里会比按钮高、且每行一个同时转很吵。
 *
 * **占地**与真按钮完全一致（[epButtonFootprint]）；**视觉**取 ButtonSmall：
 * 容器高 40dp（[ButtonDefaults.MinHeight]）、全圆角（`ContainerShapeRound` = CornerFull）、
 * 宽：写目标时为 [ChogumiDimens.EpButtonMinWidth]（带字按钮的最窄宽度，**我的设计决定（不在 M3）**），
 * 紧凑形态为宽款图标按钮的宽，与 [EpButton] 的 [showTarget] 同一口径；在占地里居中。
 * 颜色与脉动沿用全 App 的 [SkeletonBox]。
 */
@Composable
fun EpButtonPlaceholder(showTarget: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.epButtonFootprint(showTarget), contentAlignment = Alignment.Center) {
        SkeletonBox(
            modifier = Modifier
                .width(if (showTarget) ChogumiDimens.EpButtonMinWidth else epCompactWidth)
                .height(ButtonDefaults.MinHeight),
            shape = CircleShape,
        )
    }
}

/**
 * EP 按钮：进度写入入口在界面上的样子（首页、进度页共用）。
 *
 * 两种形态：
 * - **紧凑**（[showTarget] 为 false，列表行：行里已写「看过 N / M 集」）：不写字，所有状态都是 M3 小号**宽款图标按钮**的宽
 *   （[epCompactWidth]）——可点是「+」，完结是实心的对勾按钮，等待是倒计时环。列表右侧一列因此对齐，状态变了标题列也不变宽窄；
 * - **带字**（[showTarget] 为 true，首页待看卡片：卡片上没有进度文字）：写「+ 第 12 集 / 第 20 话 / 第 3 卷」，
 *   放不下（[maxWidth]，大字号）时只写集号；等待写「3 天后」，完结写「标为看过」。
 *
 * 读屏都念完整的文案（「第 12 集」「第 9 集 尚未播出（3 天后）」「标为看过」）。
 *
 * - **可点态**：点一下前进一步，图标是「+」（推进一集；对勾只留给「标为看过」，在 M3 里对勾是「已选中 / 完成」）；
 * - **点一下**：「+」当场转四分之一圈（只回应这一次点击，回到页面不重放）；点击立刻乐观写进本地，[state] 当即前进，
 *   字里只有集号翻一格（[RollingText]，标完已播的最后一集就整个翻成「3 天后」）；
 * - **待提交**（[pending]）：容器变方（M3 选中态的 square 形状）并换成 primaryContainer，右上角骑一枚 `+N` 徽标，
 *   容器底边的细条朝提交时刻排空。提交时刻由协调器给出（[EpPendingVisual.commitAtNanos]），首页与进度页
 *   看的是同一个时刻：离开再回来细条接着走，走完的那一刻正好提交，徽标随之退场。
 *   进度已乐观写进本地真源，提交不阻塞界面，因此**没有「提交中」态**；
 * - **加载中**（[EpButtonState.Loading]）：章节请求还没返回，画与按钮同占地的骨架占位，不可点；
 * - **等待态**：不可点（只在下一集未播出时出现）。图形是**倒计时环**——M3 圆形进度指示器，随播出日临近从空到满
 *   （上一集播出到这一集播出的间隔，见 [EpButtonState.Waiting.elapsedFraction]）。紧凑形态是 40dp 的环、环里写「3天」；
 *   带字形态是 M3 outlined 样式的胶囊（无填充、outline variant 描边），24dp 的环当图标，后面写「3 天后」；
 * - **完结态**「标为看过」：点击走收藏状态写入（[onMarkFinished]）。
 *
 * 读屏文案一律经 [episodeProgressLabel] / [bookProgressLabel]（全 App 唯一）；集号由领域层给出的正片序号显示。
 *
 * 带字的形态取 M3 **ButtonSmall**（`component-tokens.md`）：容器高 40dp、左右 16dp、图标 20dp、图标与文字间距 8dp。
 * 宽度随内容，下限 [ChogumiDimens.EpButtonMinWidth]，且文字里的数字先按三位的宽度占位（[EP_LABEL_RESERVED_DIGITS]）：
 * **连点（1→12、99→100）不改变按钮的宽度**，所在行的其他列不会被挤压；超出这个范围或切到别的形态时以
 * fast spatial 弹簧过渡，不跳变。
 *
 * **画的一切都在自己的占地里**（[epButtonFootprint]）：细条画在容器里、被容器的形状裁切；徽标落在徽标带里。
 * 所以不依赖任何外层「别裁我」。
 *
 * 状态切换用 M3 Expressive 弹簧：文字翻动见 [RollingText]，形状变化由官方 [Button] 的形状动画完成，
 * `+N` 徽标缩放淡入，容器色以 effects 弹簧过渡（数值取 `MaterialTheme.motionScheme`，
 * 对应 `references/tokens.md` 的 Motion springs 表）。
 *
 * 按压反馈用**官方 [Button]**：10% 状态层 + ripple，`ButtonDefaults.shapes()` 按下变方。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EpButton(
    state: EpButtonState,
    onNext: () -> Unit,
    onMarkFinished: () -> Unit,
    /** 按钮上写不写目标（「第 12 集」）：所在处已有进度文字（列表行）时不写，只画「+」。 */
    showTarget: Boolean,
    /** 条目类型：看完时写「标为看过 / 读过 …」（[markDoneLabel]）。 */
    subjectType: SubjectType,
    modifier: Modifier = Modifier,
    /** 写目标时按钮最多多宽（所在容器会裁切时给出）；放不下完整的「第 12 集」就只写集号。 */
    maxWidth: Dp = Dp.Infinity,
    /** 待提交的连点累计；null 表示没有待提交的点击。 */
    pending: EpPendingVisual? = null,
    /**
     * 交互源：默认自建。测试可注入自己的并播 [androidx.compose.foundation.interaction.PressInteraction]
     * 来取确定性的**按下帧**（测试规则不会 hold 手势，performTouchInput 按下即弹回）。
     */
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    // 「画什么」由状态自己说了算：加载中画占位（占地与按钮一致），没有进度动作则什么都不画。
    when (state) {
        EpButtonState.Loading -> { EpButtonPlaceholder(showTarget, modifier); return }
        EpButtonState.Empty -> return
        else -> Unit
    }

    val visual = when {
        pending != null -> EpButtonVisual.PENDING
        state is EpButtonState.Waiting -> EpButtonVisual.WAITING
        state is EpButtonState.Finished -> EpButtonVisual.FINISHED
        else -> EpButtonVisual.NEXT
    }
    val clickable = state !is EpButtonState.Waiting
    val advancing = state is EpButtonState.Next || state is EpButtonState.BookNext
    // 所在处已有进度文字（列表行）：不写字，所有状态同宽
    val compact = !showTarget

    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Color>()
    val containerColor by animateColorAsState(
        when (visual) {
            EpButtonVisual.FINISHED -> MaterialTheme.colorScheme.primary
            // M3 outlined 样式：容器无填充，只有描边
            EpButtonVisual.WAITING -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0f)
            EpButtonVisual.PENDING -> MaterialTheme.colorScheme.primaryContainer
            EpButtonVisual.NEXT -> MaterialTheme.colorScheme.secondaryContainer
        },
        animationSpec = effects,
        label = "EpButtonContainer",
    )
    val contentColor by animateColorAsState(
        when (visual) {
            EpButtonVisual.FINISHED -> MaterialTheme.colorScheme.onPrimary
            EpButtonVisual.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
            EpButtonVisual.PENDING -> MaterialTheme.colorScheme.onPrimaryContainer
            EpButtonVisual.NEXT -> MaterialTheme.colorScheme.onSecondaryContainer
        },
        animationSpec = effects,
        label = "EpButtonContent",
    )

    // 底边细条：离协调器给的提交时刻还剩几成，每帧按真实时间现算（rememberCommitRemaining）
    val drain = rememberCommitRemaining(pending?.commitAtNanos, EP_COMMIT_DELAY_NANOS)
    val labelStyle = MaterialTheme.typography.labelLargeEmphasized
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // 徽标退场期间 pending 已为 null：沿用最后一次的数字，避免退场时闪成「+0」
    var lastCount by remember { mutableIntStateOf(0) }
    if (pending != null) lastCount = pending.count

    // 读屏描述：带单位的完整文案，跟着下一步走（不看 pending：点击已乐观写进本地，state 当即前进）。
    // 次序 [step]：字往哪边翻（[RollingText]），推进一步就往上翻；完结排在一切集号之后。
    val description: String
    val number: String?
    val step: Int
    when (state) {
        is EpButtonState.Next -> {
            // 集号是「本条目第几集」（正片序号），不是系列绝对集号 sort——
            // 与列表行「看过 N / M 集」同一个坐标系，见 EpisodeTracking.mainEpisodePosition。
            description = episodeProgressLabel(state.position.toDouble())
            number = state.position.toString()
            step = state.position
        }
        is EpButtonState.BookNext -> {
            description = bookProgressLabel(state.unit, state.current + 1)
            number = (state.current + 1).toString()
            step = state.current + 1
        }
        is EpButtonState.Waiting -> {
            description = stringResource(
                R.string.ep_button_waiting_a11y,
                episodeProgressLabel(state.position.toDouble()),
                dayOffsetLabel(state.daysUntil),
            )
            number = null
            step = state.position
        }
        else -> {
            description = markDoneLabel(subjectType)
            number = null
            step = Int.MAX_VALUE
        }
    }

    // 按钮上的字：推进一集写「第 12 集」（放不下 [maxWidth] 就只写集号），等待写「3 天后」，完结写「标为看过 / 读过」。
    // 推进一集的数字先按三位占位：1→12、99→100 只在格内变化，按钮不变宽，所在行的其余列不会随连点抖动。
    // 格宽按当前字号实测，字体放大时同样成立。
    val waitingLabel = (state as? EpButtonState.Waiting)?.let { dayOffsetLabel(it.daysUntil) }
    val finishedLabel = markDoneLabel(subjectType)
    val (label, reservedLabelWidth) = remember(description, number, waitingLabel, maxWidth, labelStyle, textMeasurer, density) {
        fun reservedWidth(text: String) = with(density) {
            val shape = text.replace(Regex("\\d+")) { "0".repeat(maxOf(EP_LABEL_RESERVED_DIGITS, it.value.length)) }
            textMeasurer.measure(shape, labelStyle, maxLines = 1, softWrap = false).size.width.toDp()
        }
        when {
            number == null -> (waitingLabel ?: finishedLabel) to 0.dp
            else -> {
                val full = reservedWidth(description)
                val chrome = ChogumiDimens.EpButtonHorizontalPadding * 2 + ButtonDefaults.SmallIconSize + ButtonDefaults.IconSpacing
                if (full + chrome <= maxWidth) description to full else number to reservedWidth(number)
            }
        }
    }

    // 「+」每点一次转四分之一圈（十字对称，停下时与原来一样）：只回应手指的这一次点击，
    // 不跟着待提交状态走——否则换个页面回来，有待提交的按钮会再转一遍。
    // 用默认（不是快速）空间弹簧：快速弹簧约 100ms 就转完，看不出来
    val spin = remember { Animatable(0f) }
    val spinSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val scope = rememberCoroutineScope()

    // 容器形状：静止全圆，按下变成官方的按下形状，待提交变方（M3 ButtonSmall 的 PressedContainerShape /
    // SelectedContainerShapeSquare）。官方 Button 的形状动画按 shapes 对象分组，只在同一套 shapes 的「静止 ↔ 按下」之间过渡；
    // 待提交时换一套 shapes 会直接从新形状起步，圆 ↔ 方跳一帧。所以这里自己用空间弹簧动画「离全圆有多远」与目标圆角，
    // 交给 Button 的始终是同一个形状（[EpContainerShape]），按下与待提交走同一条曲线。
    // 按下沿用官方的 fast spatial；松手后变方（待提交）/ 回圆用 default spatial——快速弹簧两三帧就变完，看不出过渡。
    // 用 Animatable 而不是 animateFloatAsState：点一下时按下与待提交目标相同（都是方），后者目标不变就不会换弹簧，
    // 整段变方都跑在快速弹簧上；这里每次按下 / 松手 / 待提交变化都从当前位置与速度接着跑，换弹簧不跳。
    val pressed by interactionSource.collectIsPressedAsState()
    val squareness = remember { Animatable(0f) }
    val pressSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val settleSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(pressed, pending != null) {
        squareness.animateTo(if (pressed || pending != null) 1f else 0f, if (pressed) pressSpec else settleSpec)
    }
    val corner by animateDpAsState(
        targetValue = (if (pressed) ButtonDefaults.pressedShape else ButtonDefaults.squareShape).cornerRadius(),
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "EpButtonCorner",
    )
    val containerShape = EpContainerShape(corner, squareness.value)

    val touchInset = epTouchInset()
    val band = epBadgeBand()
    // 紧凑形态的等待态、又没有待提交：不是按钮，是一枚倒计时环（没有容器，也就没有容器上的待提交视觉）。
    // 待提交期间仍画按钮：变方、换色、细条排空，提交之后才落成环。
    val dial = compact && state is EpButtonState.Waiting && pending == null
    // 占地 = 触控区 + 上下徽标带（与 [epButtonFootprint] 同一口径）；徽标骑在容器上沿，右缘与容器右缘对齐。
    // 徽标是叠加层：出现、消失、位数变化都不改变占地，所在行的其余列不会被挤压。语义合并回按钮这一个节点。
    Layout(
        content = {
            Crossfade(targetState = dial, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "EpButtonForm") { showDial ->
                if (showDial && state is EpButtonState.Waiting) {
                    EpWaitingDial(state, description, Modifier.width(epCompactWidth).heightIn(min = epTouchHeight()))
                    return@Crossfade
                }
                Button(
                    onClick = {
                        if (advancing) scope.launch { spin.animateTo(spin.targetValue + 90f, spinSpec) }
                        if (state is EpButtonState.Finished && pending == null) onMarkFinished() else onNext()
                    },
                    // 紧凑形态把容器钉在宽款图标按钮的宽（官方 Button 内部的最窄宽度比它宽）
                    modifier = Modifier
                        .then(if (compact) Modifier.width(epCompactWidth) else Modifier)
                        .semantics { contentDescription = description },
                    enabled = clickable,
                    interactionSource = interactionSource,
                    // 静止与按下都交同一个形状：过渡由上面的弹簧完成，不走官方按 shapes 分组的那套
                    shapes = ButtonShapes(shape = containerShape, pressedShape = containerShape),
                    // 等待态是 M3 outlined 样式：描边取官方 outlined 按钮的（outline variant）；它不可点，但不按禁用灰掉
                    border = if (visual == EpButtonVisual.WAITING) ButtonDefaults.outlinedButtonBorder(enabled = true) else null,
                    // 标准内边距由下方 Row 承载，放进宽度动画的裁剪边界内；总内边距不变。
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = containerColor,
                        contentColor = contentColor,
                        // 禁用（等待态）的基色保持与容器一致，避免 M3 默认灰化把状态色改掉
                        disabledContainerColor = containerColor,
                        disabledContentColor = contentColor,
                    ),
                ) {
                    // 官方 Button 会把内容色设成默认值，这里按状态覆写回自己的角色色
                    CompositionLocalProvider(LocalContentColor provides contentColor) {
                        Row(
                            // 这一行就是整个容器（宽高下限都在这里），细条画在它底边、被容器的形状裁切。
                            // 内容变宽/变窄（超过三位的集号、切到「标为看过」/「3 天后」）时以弹簧过渡；
                            // animateContentSize 自带矩形裁剪，把原有的标准内边距一同动画，
                            // 回缩过冲消耗的是留白，数字不会紧贴内部裁剪右缘。
                            modifier = Modifier
                                .then(if (compact) Modifier.width(epCompactWidth) else Modifier.widthIn(min = ChogumiDimens.EpButtonMinWidth))
                                .heightIn(min = ButtonDefaults.MinHeight)
                                .animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())
                                .commitCountdownBar(drain.takeIf { pending != null }, contentColor)
                                .then(
                                    // 紧凑形态图标在容器里居中；带字时左右是 ButtonSmall 的内边距
                                    if (compact) Modifier else Modifier.padding(horizontal = ChogumiDimens.EpButtonHorizontalPadding),
                                ),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state is EpButtonState.Waiting) {
                                // 等待态的图形就是倒计时环（与紧凑形态的环同一种 M3 圆形进度指示器、同一份进度）
                                CircularProgressIndicator(progress = { state.elapsedFraction }, modifier = Modifier.size(WaitingGlyphSize))
                            } else {
                                Icon(
                                    painter = painterResource(if (state is EpButtonState.Finished) R.drawable.check_24 else R.drawable.add_24),
                                    contentDescription = null,
                                    tint = contentColor,
                                    modifier = Modifier
                                        .size(if (compact) IconButtonDefaults.smallIconSize else ButtonDefaults.SmallIconSize)
                                        .graphicsLayer { rotationZ = if (advancing) spin.value else 0f },
                                )
                            }
                            if (!compact) Box(
                                modifier = Modifier
                                    .padding(start = ButtonDefaults.IconSpacing)
                                    .widthIn(min = reservedLabelWidth),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                RollingText(text = label, order = step, style = labelStyle, color = contentColor)
                            }
                        }
                    }
                }
            }
            // 包一层常驻的 Box：徽标隐藏时 AnimatedVisibility 不产出节点，测量时仍要有第二个子项
            Box { EpPendingBadge(visible = pending != null, count = lastCount) }
        },
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) { (button, badge), constraints ->
        val bandPx = band.roundToPx()
        val buttonPlaceable = button.measure(constraints.offset(vertical = -2 * bandPx))
        val badgePlaceable = badge.measure(Constraints())
        layout(buttonPlaceable.width, buttonPlaceable.height + 2 * bandPx) {
            buttonPlaceable.placeRelative(0, bandPx)
            // 徽标中线对齐容器顶边（容器在触控区里垂直居中）；徽标带保证它不出占地
            val containerTop = bandPx + touchInset.roundToPx()
            badgePlaceable.placeRelative(
                x = buttonPlaceable.width - badgePlaceable.width,
                y = (containerTop - badgePlaceable.height / 2).coerceAtLeast(0),
            )
        }
    }
}

/**
 * 紧凑形态的等待态：一枚 40dp 的倒计时环（M3 圆形进度指示器，进度见 [EpButtonState.Waiting.elapsedFraction]），
 * 环里写还有几天——明天写「明天」，其余写「3天」；环里放不下（天数多、字号大）就只写数字，读屏仍念完整的 [description]。
 * 字是 outlined 样式的内容色（on surface variant），与带字形态的等待胶囊一致。
 */
@Composable
private fun EpWaitingDial(state: EpButtonState.Waiting, description: String, modifier: Modifier) {
    val style = MaterialTheme.typography.labelMediumEmphasized
    val full = if (state.daysUntil == 1L) dayOffsetLabel(1L) else stringResource(R.string.ep_button_waiting_days_compact, state.daysUntil)
    val bare = state.daysUntil.toString()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val label = remember(full, bare, style, measurer, density) {
        // 字离环的内缘至少各留半个描边：可用宽 = 直径 − 3 个描边宽
        val room = WaitingDialSize - ProgressIndicatorDefaults.CircularStrokeWidth * 3
        val width = with(density) { measurer.measure(full, style, maxLines = 1, softWrap = false).size.width.toDp() }
        if (width <= room) full else bare
    }
    Box(modifier.semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = { state.elapsedFraction }, modifier = Modifier.size(WaitingDialSize))
        Text(text = label, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** 推进一集的文字里，数字预留的位数：一到三位集号的按钮宽度一致。 */
private const val EP_LABEL_RESERVED_DIGITS = 3

/**
 * 连点累计徽标 `+N`：官方 M3 [Badge]（大号徽标：高 16dp、全圆角、左右 4dp，字为 label small），
 * 由 [EpButton] 摆在容器右上角、骑在容器上沿。数量增多时宽度向起始侧扩展、高度与位置不变（`components/feedback.md`
 * Badges：large badge width expands, height and placement stay the same）。
 * 颜色取 primary / onPrimary（**我的设计决定（不在 M3）**：M3 默认是 error，这里表示的是「待提交」而不是警示）。
 */
@Composable
private fun EpPendingBadge(visible: Boolean, count: Int) {
    val fadeSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(fadeSpec),
        exit = scaleOut(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(fadeSpec),
        label = "EpButtonBadge",
    ) {
        Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
            Text(text = stringResource(R.string.ep_button_pending_count, count), style = epBadgeTextStyle(), maxLines = 1)
        }
    }
}

/** EP 按钮的视觉形态：状态 + 待提交共同投影出它，界面不另做集号判定。 */
private enum class EpButtonVisual { NEXT, PENDING, WAITING, FINISHED }

/**
 * 相对天数文案**唯一映射**：`0` → 今天、`1` → 明天、其余 → `N 天后`。
 *
 * 追更时间表的分组名与 EP 按钮等待态共用。等待态的 `daysUntil` 恒 `>= 1`
 * （今天播出算已播出、可标记，见 [io.github.touhougo.chogumi.core.model.EpisodeTracking.airState]）。
 */
fun dayOffsetLabel(resources: Resources, daysUntil: Long): String = when (daysUntil) {
    0L -> resources.getString(R.string.day_offset_today)
    1L -> resources.getString(R.string.day_offset_tomorrow)
    else -> resources.getString(R.string.day_offset_days, daysUntil)
}

/** 组合里的 [dayOffsetLabel]：资源取自 [LocalResources]。 */
@Composable
fun dayOffsetLabel(daysUntil: Long): String = dayOffsetLabel(LocalResources.current, daysUntil)
