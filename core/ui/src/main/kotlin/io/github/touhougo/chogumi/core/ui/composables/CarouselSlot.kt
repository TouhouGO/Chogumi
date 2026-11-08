package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import io.github.touhougo.chogumi.core.ui.common.navigation.LocalVisibleArea
import io.github.touhougo.chogumi.core.ui.common.navigation.VisibleArea

/**
 * 官方轮播条目里的一块（全 App 唯一的轮播槽位）：按 M3 的遮罩裁切（[CarouselItemScope.maskClip]，收窄时仍是完整圆角），
 * 并把遮罩后看得见的那一块告诉里面的内容（[LocalVisibleArea] 与 [LocalItemMask] 是同一份）。槽位里的封面长成作品详情页、
 * 缩回来时，都落在这一块上，而不是被遮住的整张；封面上的收藏角标、待提交胶囊与长按浮起的封面也排在这一块里。
 */
@Composable
fun CarouselItemScope.CarouselSlot(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val drawInfo = carouselItemDrawInfo
    val visible = remember(drawInfo) { VisibleArea { drawInfo.maskRect } }
    Box(modifier.onPlaced { visible.coordinates = it }.maskClip(shape)) {
        CompositionLocalProvider(LocalVisibleArea provides visible, LocalItemMask provides visible) { content() }
    }
}

/**
 * 轮播把条目收窄成的那一块（只由 [CarouselSlot] 提供）：条目照整张排版，遮罩只露出中间一段，压在条目上、要跟着收窄的东西
 * 排进这一块，不然两头被遮罩切掉。和 [LocalVisibleArea] 不同，顶栏盖住一截不算收窄：滚到顶栏下的海报，角标不挪。
 */
internal val LocalItemMask = compositionLocalOf<VisibleArea?> { null }

/**
 * 把这一层（占满它的父级）排进 [LocalItemMask] 那一块：量成露出来的那一块的大小、摆在它的位置；不在轮播里原样占满。
 * 遮罩随滑动每帧变，只有这一层跟着重排。这一层在槽位里的位置排一次才知道，变了再排一次。
 */
@Composable
internal fun Modifier.withinItemMask(): Modifier {
    val mask = LocalItemMask.current ?: return this
    val inSlot = remember(mask) { mutableStateOf(Offset.Zero) }
    return layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val whole = IntRect(0, 0, constraints.maxWidth, constraints.maxHeight)
        val shown = mask.within(whole, inSlot.value)
        val placeable = measurable.measure(Constraints.fixed(shown.width, shown.height))
        layout(whole.width, whole.height) {
            if (!isLookingAhead) coordinates?.let { here -> mask.offsetOf(here)?.let { inSlot.value = it } }
            placeable.place(shown.left, shown.top)
        }
    }
}
