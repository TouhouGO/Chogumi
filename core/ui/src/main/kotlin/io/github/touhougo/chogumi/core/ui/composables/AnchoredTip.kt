package io.github.touhougo.chogumi.core.ui.composables

import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/*
 * 指着某一块的提示（图表气泡、章节方格的未播出与大跳确认）：开在自己的窗口里，浮在版面之上，不占版面的位置，
 * 也不被所在的卡片、分页裁掉。放在哪只由 [tipPosition] 定。
 */

/** 提示的两种（feedback.md Tooltips）。 */
internal enum class TipKind {
    /** 普通提示：一行字，开在锚点正上方、对着它居中。 */
    Plain,

    /** 富提示：开在锚点右下，左缘对着锚点中线。 */
    Rich,
}

/**
 * 在宿主（放 [AnchoredTip] 的那个版面）上方开一个提示，指着宿主里的 [anchor]（宿主坐标，像素；参数是宿主的尺寸，
 * 锚点要按宿主宽度算的调用方用得上）。[visible] 变化时提示缩放加淡入淡出（同官方提示：从 0.8 倍起，缩放走
 * fastSpatial、淡入淡出走 fastEffects），退场的那一段仍按原来的锚点摆放；退场完窗口就关掉。
 */
@Composable
internal fun AnchoredTip(
    visible: Boolean,
    kind: TipKind,
    anchor: (hostSize: IntSize) -> IntRect,
    content: @Composable () -> Unit,
) {
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = visible
    if (!shown.currentState && !shown.targetState) return
    val density = LocalDensity.current
    val spacing = with(density) { TipSpacing.roundToPx() }
    val step = with(density) { TipStep.roundToPx() }
    val room = with(density) { TipShadowRoom.roundToPx() }
    val motion = MaterialTheme.motionScheme
    Popup(
        popupPositionProvider = AnchoredTipPosition(kind, anchor, spacing, step, room),
        properties = TipWindow,
    ) {
        AnimatedVisibility(
            visibleState = shown,
            enter = scaleIn(motion.fastSpatialSpec(), initialScale = TIP_FROM_SCALE) + fadeIn(motion.fastEffectsSpec()),
            exit = scaleOut(motion.fastSpatialSpec(), targetScale = TIP_FROM_SCALE) + fadeOut(motion.fastEffectsSpec()),
        ) {
            // 四周留出阴影的位置：淡入淡出时整个提示画在离屏层里，阴影出了边界会被裁掉
            Box(Modifier.padding(TipShadowRoom)) { content() }
        }
    }
}

/**
 * 普通提示本身：外形取官方 TooltipDefaults，配色与 [JumpConfirmTooltip] 一样用 rich 提示的 surfaceContainer / onSurfaceVariant
 * （不用 plain 的反色深块：提示只有一种外观）；bodySmall，最小 40×24dp，内边距 8 / 4dp，一行字。出现时读屏念一遍。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlainTip(text: String) {
    Surface(
        shape = TooltipDefaults.plainTooltipContainerShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Box(
            Modifier
                .sizeIn(minWidth = 40.dp, maxWidth = TooltipDefaults.plainTooltipMaxWidth, minHeight = 24.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 富提示的正文：长说明允许折行，形状、最大宽度与内边距沿用 M3 富提示，出现时读屏念一遍。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RichTip(text: String) {
    Surface(
        shape = TooltipDefaults.richTooltipContainerShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Box(
            Modifier
                .sizeIn(maxWidth = TooltipDefaults.richTooltipMaxWidth)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * 富提示卡（feedback.md / component-tokens.md RichTooltip：surfaceContainer 底、Medium 圆角、Level2 海拔；内边距上 12、下 8、左右 16），
 * 里面是小标题、说明与可选的文字按钮，由调用方排。章节方格的大跳确认与第一次进来的引导都是它；最宽 [maxWidth]。出现时读屏念一遍。
 */
@Composable
internal fun RichTipCard(maxWidth: Dp, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shadowElevation = RichTooltipElevation,
        modifier = modifier
            .widthIn(max = maxWidth)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(RichTooltipPadding), content = content)
    }
}

/**
 * 提示放在哪（窗口坐标，像素）。不盖住锚点（feedback.md：提示不该盖住它说明的元素），放不下时换边
 * （feedback.md：提示会调整位置以免出屏）：
 * - 纵向：普通提示开在锚点上方（feedback.md：普通提示默认在正上方）；富提示开在下方，锚点在窗口下半时开在上方——
 *   屏幕底部有收藏按钮与手势条，朝下开会被挡住。那一边在窗口里放不下就换另一边，两边都放不下时放在下方、收进窗口（盖住锚点，卡上的按钮仍点得到）。
 * - 横向不出宿主 [host] 的左右两边：普通提示对着锚点居中；富提示左缘对着锚点中线、往右开，右边放不下就按 [step]
 *   （8dp）一档往左挪（feedback.md：富提示默认在右下，动态定位按 8dp 一档调整）。
 */
internal fun tipPosition(
    anchor: IntRect,
    tip: IntSize,
    host: IntRect,
    windowHeight: Int,
    spacing: Int,
    step: Int,
    kind: TipKind,
): IntOffset {
    val preferAbove = kind == TipKind.Plain || anchor.center.y > windowHeight / 2
    val below = anchor.bottom + spacing
    val above = anchor.top - spacing - tip.height
    val fitsBelow = below + tip.height <= windowHeight
    val fitsAbove = above >= 0
    val y = when {
        preferAbove && fitsAbove -> above
        !preferAbove && fitsBelow -> below
        fitsAbove -> above
        // 两边都放不下（横屏、分屏的矮窗口）：收进窗口，宁可盖住锚点也不让卡上的按钮出屏
        else -> below.coerceIn(0, maxOf(0, windowHeight - tip.height))
    }
    val x = when (kind) {
        TipKind.Rich -> {
            val start = anchor.center.x
            val overflow = start + tip.width - host.right
            val shift = if (overflow > 0) (overflow + step - 1) / step * step else 0
            (start - shift).coerceAtLeast(host.left)
        }
        TipKind.Plain -> (anchor.center.x - tip.width / 2).coerceIn(host.left, maxOf(host.left, host.right - tip.width))
    }
    return IntOffset(x, y)
}

/** 把宿主坐标的锚点换到窗口坐标交给 [tipPosition]；窗口四周多出的阴影位置 [room] 不算进提示的大小。 */
private class AnchoredTipPosition(
    private val kind: TipKind,
    private val anchor: (hostSize: IntSize) -> IntRect,
    private val spacing: Int,
    private val step: Int,
    private val room: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val target = anchor(anchorBounds.size).translate(anchorBounds.topLeft)
        val position = tipPosition(
            anchor = target,
            tip = IntSize(popupContentSize.width - room * 2, popupContentSize.height - room * 2),
            host = anchorBounds,
            windowHeight = windowSize.height,
            spacing = spacing,
            step = step,
            kind = kind,
        )
        return IntOffset(position.x - room, position.y - room)
    }
}

/**
 * 提示窗口：不可点、不抢焦点、不按窗口边界裁剪。不可点是关键：提示常常正盖着锚点旁边那块，要是能点，
 * 手指落在提示上就点不到它下面的那块了（官方 TooltipBox 的弹出层可点，且不开放窗口参数，所以这里自己开窗口）。
 */
private val TipWindow = PopupProperties(
    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    dismissOnBackPress = false,
    dismissOnClickOutside = false,
)

/** 富提示：海拔 Level2（component-tokens.md RichTooltip，tokens.md：3dp）；上 12、下 8、左右 16（feedback.md）。 */
private val RichTooltipElevation = 3.dp
private val RichTooltipPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 8.dp)

/** 提示从多大缩放进出：同官方提示（0.8）。 */
internal const val TIP_FROM_SCALE = 0.8f

/** 提示与锚点的距离：有可见边界时 4dp（feedback.md 普通提示的放置；富提示沿用同一距离）。 */
internal val TipSpacing = 4.dp

/** 富提示放不下时每次挪 8dp（feedback.md：动态定位按 8dp 一档）。 */
internal val TipStep = 8.dp

/** 提示四周给阴影留的位置：Level2 的阴影不出这一圈（我的设计决定，not in M3）。 */
private val TipShadowRoom = 6.dp
