package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import io.github.touhougo.chogumi.core.ui.common.navigation.CoverPressBlock

/**
 * 可点的一整块（封面 + 下方文字的卡片、头像 + 名字等）——全 App 唯一做法：整块可点，按下的水波纹按 [shape] 圆角，
 * **只裁水波纹、不裁内容**。[shape] 传该块的圆角（封面卡传封面的圆角，水波纹与封面同一个角）。[color]、[contentColor] 与
 * [border] 画容器外观；都不给时块是透明的，只有点击层。
 *
 * 各处自己处理时出过两种错：给整块 `clip(圆角).clickable`（关联作品、出演作品、货架、网格、人物横排），
 * 标题、名字落在左下角被圆角切掉；只 `clickable`（日历、首页时间表、在看、好友在看），水波纹是直角。
 * 别再自己写「`clip` + `clickable` 包住带文字的一整块」或第二套「`Surface(onClick)` / `Surface(checked)` + 文字」，
 * 门禁见 tools/guards/check_press_area.py。
 * [onClick] 为 null 不可点且没有点击语义。画了容器（[color] 或 [border]）的块按 M3 `Surface` 排版：块的最小尺寸交给内容
 * （定了宽高的块，内容就铺满这个宽高），可点时至少 48dp 触控区；透明块只是一层 `Box`，内容按自己的大小排。
 * 水波纹与内容在同一层，铺满整块，不随内容缩小。
 * 圆角要随按下变形时，把 [interactionSource] 也交给 [pressedCorner]。
 * [onClickLabel] 与 [role] 交给读屏：点了做什么（「输入数字」）、这一块算什么控件。
 */
@Composable
fun PressArea(
    onClick: (() -> Unit)?,
    shape: Shape,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    contentColor: Color = contentColorFor(color),
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onClickLabel: String? = null,
    role: Role? = null,
    content: @Composable () -> Unit,
) {
    if (onClick == null) {
        PressAreaContainer(modifier, shape, color, contentColor, border) { content() }
        return
    }
    PressableArea(
        press = Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        ),
        interactionSource = interactionSource,
        shape = shape,
        modifier = modifier,
        color = color,
        contentColor = contentColor,
        border = border,
        content = content,
    )
}

/**
 * 能选上、再点取消的一整块（多选格，与 M3 `Surface(checked, onCheckedChange)` 同）：排版、容器与只裁水波纹都和上面的
 * [PressArea] 一样，读屏读出开或关。[checked] 是现在选没选，[onCheckedChange] 收到点了之后该是什么。
 */
@Composable
fun PressArea(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
    color: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    PressableArea(
        press = Modifier.toggleable(
            value = checked,
            interactionSource = interactionSource,
            indication = null,
            onValueChange = onCheckedChange,
        ),
        interactionSource = interactionSource,
        shape = shape,
        modifier = modifier,
        color = color,
        contentColor = contentColor,
        border = null,
        content = content,
    )
}

/** 可点的块：[press] 是点击或开关的那层修饰符，水波纹铺满整块、按 [shape] 裁。 */
@Composable
private fun PressableArea(
    press: Modifier,
    interactionSource: MutableInteractionSource,
    shape: Shape,
    modifier: Modifier,
    color: Color,
    contentColor: Color,
    border: BorderStroke?,
    content: @Composable () -> Unit,
) {
    // 块里有封面时，点块里任何地方都由封面长出详情页
    CoverPressBlock { presses ->
        val pressModifier = modifier.then(presses).let {
            if (color != Color.Unspecified || border != null) it.minimumInteractiveComponentSize() else it
        }
        PressAreaContainer(
            modifier = pressModifier.then(press),
            shape = shape,
            color = color,
            contentColor = contentColor,
            border = border,
        ) {
            content()
            Box(Modifier.matchParentSize().clip(shape).indication(interactionSource, ripple()))
        }
    }
}

/**
 * 容器外观由 Box 自绘；shape 只用于底色和边框，正文与图片从不裁在里面。
 * 画了容器时内容拿到块的最小尺寸（`propagateMinConstraints`，与 M3 `Surface` 同）：定了宽高、里面靠右下对齐的块
 * （探索页的合集方块）内容铺满整块，右下的东西落在块的右下，不缩到左上角压住字。
 */
@Composable
private fun PressAreaContainer(
    modifier: Modifier,
    shape: Shape,
    color: Color,
    contentColor: Color,
    border: BorderStroke?,
    content: @Composable BoxScope.() -> Unit,
) {
    if (color == Color.Unspecified && border == null) {
        Box(modifier, content = content)
    } else {
        val painted = modifier.let { base ->
            val withColor = if (color == Color.Unspecified) base else base.background(color, shape)
            if (border == null) withColor else withColor.border(border, shape)
        }
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Box(painted, propagateMinConstraints = true, content = content)
        }
    }
}

/**
 * 按下时变形的圆角（全 App 唯一）：跟着 [interactionSource] 的按下状态在 [resting] 与 [pressed] 之间走，
 * 弹簧取主题的 fast spatial（M3 standard）。
 * 探索页的入口方块、货架方块与资料页数字行共用；圆角取值各处是设计决定（not in M3）。
 */
@Composable
fun pressedCorner(interactionSource: InteractionSource, resting: Dp, pressed: Dp): State<Dp> {
    val isPressed by interactionSource.collectIsPressedAsState()
    return animateDpAsState(
        targetValue = if (isPressed) pressed else resting,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "press_corner",
    )
}
