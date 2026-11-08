package io.github.touhougo.chogumi.core.ui.collection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.CollectionType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 海报长按环上的六格，**方向固定、全 App 一套**（原型 `poster-long-press.html`「为什么这样排」）：
 * 上半圈是常用的三个——正上看过、右上想看、左上在看；下半圈左下管理、正下搁置、右下抛弃（手遮得最多、拇指最难够）。
 * [angleDegrees] 是屏幕坐标里的方向（0° 正右、-90° 正上，y 向下）；[type] 为 null 的是「管理」。
 * 叫法随条目类型（[io.github.touhougo.chogumi.core.ui.composables.labelResFor]），方向不随页面、不随条目类型变。
 */
internal enum class RingSlot(val angleDegrees: Float, val type: CollectionType?) {
    DONE(-90f, CollectionType.DONE),
    WISH(-30f, CollectionType.WISH),
    DROPPED(30f, CollectionType.DROPPED),
    ON_HOLD(90f, CollectionType.ON_HOLD),
    MANAGE(150f, null),
    DOING(-150f, CollectionType.DOING),
}

/**
 * 环的几何（**设计决定，not in M3**：M3 没有环形菜单）。圆钮用 Medium icon button 的尺寸（component-tokens.md
 * MediumIconButton.ContainerHeight 56）；半径 76 让相邻圆钮之间留 20 的缝；瞄准时往外探 8；死区半径 24；
 * 环离页面边缘至少 8。
 */
internal object RingGeometry {
    val Radius = 76.dp
    val DeadZone = 24.dp
    val Push = 8.dp
    val Button = 56.dp
    val EdgeMargin = 8.dp
}

/**
 * 手指离按下点 ([dx], [dy]) 时指着哪一格（全 App 唯一的方向判定）：还在死区（半径 [deadZone]）里是 null；
 * 出了死区按角度取最近的一格——每格管 60° 的扇区、没有空隙，滑多远都行，不用落在圆钮上。
 */
internal fun ringSlotAt(dx: Float, dy: Float, deadZone: Float): RingSlot? {
    if (hypot(dx, dy) < deadZone) return null
    val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    return RingSlot.entries.minBy { angleBetween(angle, it.angleDegrees) }
}

/** 两个方向之间的夹角（0–180°）。 */
private fun angleBetween(a: Float, b: Float): Float {
    val d = ((a - b) % 360f + 540f) % 360f - 180f
    return abs(d)
}

/**
 * 环心：以按下点 [press] 为心；贴边放不下时整圈往 [area] 里平移到放得下为止（离中心 [extent] 以内都要在里面）。
 * 只挪环、不改方向：方向仍从按下点算（[ringSlotAt]），同样往左上滑还是那一格。区域比环还小时居中。
 */
internal fun ringCenter(press: Offset, area: Rect, extent: Float): Offset =
    Offset(clampInto(press.x, area.left + extent, area.right - extent), clampInto(press.y, area.top + extent, area.bottom - extent))

private fun clampInto(value: Float, low: Float, high: Float): Float = if (low > high) (low + high) / 2f else value.coerceIn(low, high)

/** [slot] 那一格的圆钮中心：离环心 [center] [radius] 远。 */
internal fun ringSlotCenter(center: Offset, slot: RingSlot, radius: Float): Offset {
    val radians = Math.toRadians(slot.angleDegrees.toDouble())
    return Offset(center.x + radius * cos(radians).toFloat(), center.y + radius * sin(radians).toFloat())
}

/**
 * 瞄准预演胶囊的顶边：浮在环的正上方（拇指挡不到），离环最外缘 [gap]；上面放不下（顶到 [area] 的 [gap] 以内）就放到环的正下方。
 * [extent] 是环心到环最外缘的距离，[height] 是胶囊高。
 */
internal fun ringPreviewTop(center: Offset, area: Rect, extent: Float, height: Float, gap: Float): Float {
    val above = center.y - extent - gap - height
    return if (above < area.top + gap) center.y + extent + gap else above
}
