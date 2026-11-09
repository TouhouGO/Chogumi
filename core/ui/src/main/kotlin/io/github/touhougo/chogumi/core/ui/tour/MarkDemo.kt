package io.github.touhougo.chogumi.core.ui.tour

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import io.github.touhougo.chogumi.core.model.COLLECTION_MARK_WINDOW_NANOS
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.PendingMark
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.commitClockNanos
import io.github.touhougo.chogumi.core.ui.collection.LiftElevation
import io.github.touhougo.chogumi.core.ui.collection.MarkBadges
import io.github.touhougo.chogumi.core.ui.collection.RingGeometry
import io.github.touhougo.chogumi.core.ui.collection.RingLayers
import io.github.touhougo.chogumi.core.ui.collection.RingLook
import io.github.touhougo.chogumi.core.ui.collection.RingSlot
import io.github.touhougo.chogumi.core.ui.collection.animatePosterCorner
import io.github.touhougo.chogumi.core.ui.collection.ringCenter
import io.github.touhougo.chogumi.core.ui.collection.ringSlotAt
import io.github.touhougo.chogumi.core.ui.collection.ringSlotCenter
import io.github.touhougo.chogumi.core.ui.composables.PosterCorner
import io.github.touhougo.chogumi.core.ui.composables.skeletonFill
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/** 演示封面的宽：海报卡在手机上大约这么宽（设计决定，not in M3）。 */
private val CoverWidth = 112.dp

/** 演示里的条目：动画，环上的叫法按动画取（看过、在看……）。 */
private val DemoSubjectType = SubjectType.ANIME

/** 演示一遍：按下之前停一会儿，环开着先看一眼再滑，瞄准后停一下再松手，标好的角标留一会儿。 */
private const val RestMillis = 700L
private const val LookMillis = 500L
private const val AimMillis = 450L
private const val CommittedMillis = 1600L

/** 演示环：真环画法（[RingLayers]）要的那几样，由演示脚本改。 */
@Stable
private class DemoRing(override val press: Offset, override val center: Offset) : RingLook {
    override val current: CollectionType? = null
    override val subjectType: SubjectType = DemoSubjectType
    override var open by mutableStateOf(true)
    override var hot by mutableStateOf<RingSlot?>(null)
    override var chosen by mutableStateOf<RingSlot?>(null)
}

/**
 * 第一页：按住封面，环展开，滑向「看过」松手，封面底部出现待提交胶囊，窗口过后右上角是看过的角标。
 * 环、遮罩、胶囊与角标是真环的那几层（[RingLayers]、[MarkBadges]），封面按住时的圆角是同一段动画（[animatePosterCorner]）。
 */
@Composable
internal fun MarkDemo(playing: Boolean, modifier: Modifier = Modifier) {
    TourStage(modifier) { stage ->
        val area = stage.size
        val density = LocalDensity.current
        val motion = MaterialTheme.motionScheme
        val longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
        val finger = remember { TourFinger() }
        var pressed by remember { mutableStateOf(false) }
        var ring by remember { mutableStateOf<DemoRing?>(null) }
        var pending by remember { mutableStateOf<PendingMark?>(null) }
        var marked by remember { mutableStateOf<CollectionType?>(null) }

        val coverSize = with(density) { Size(CoverWidth.toPx(), (CoverWidth / ChogumiDimens.CoverRatio).toPx()) }
        val cover = Rect(Offset((area.width - coverSize.width) / 2, (area.height - coverSize.height) / 2), coverSize)
        val corner by animatePosterCorner(charged = pressed || ring?.open == true, landing = ring?.open == false, restCorner = PosterCorner)

        LaunchedEffect(playing) {
            finger.reset()
            pressed = false
            ring = null
            pending = null
            marked = null
            if (!playing) return@LaunchedEffect
            val press = cover.center
            val extent = with(density) { (RingGeometry.Radius + RingGeometry.Push + RingGeometry.Button / 2 + RingGeometry.EdgeMargin).toPx() }
            val center = ringCenter(press, Rect(Offset.Zero, area), extent)
            val radius = with(density) { RingGeometry.Radius.toPx() }
            val deadZone = with(density) { RingGeometry.DeadZone.toPx() }
            while (true) {
                delay(RestMillis)
                finger.press(press)
                pressed = true
                delay(longPressMillis)
                val opened = DemoRing(press, center).also { ring = it }
                delay(LookMillis)
                finger.position.animateTo(ringSlotCenter(center, RingSlot.DONE, radius), motion.slowSpatialSpec()) {
                    val delta = value - press
                    opened.hot = ringSlotAt(delta.x, delta.y, deadZone)
                }
                delay(AimMillis)
                finger.lift()
                pressed = false
                opened.chosen = RingSlot.DONE
                opened.open = false
                pending = PendingMark(subjectId = 0, type = CollectionType.DONE, commitAtNanos = commitClockNanos() + COLLECTION_MARK_WINDOW_NANOS)
                delay(TimeUnit.NANOSECONDS.toMillis(COLLECTION_MARK_WINDOW_NANOS))
                pending = null
                marked = CollectionType.DONE
                delay(CommittedMillis)
                marked = null
            }
        }

        val coverModifier = Modifier
            .offset { cover.topLeft.round() }
            .size(with(density) { cover.width.toDp() }, with(density) { cover.height.toDp() })
        Box(coverModifier) {
            Box(Modifier.fillMaxSize().skeletonFill(RoundedCornerShape(corner)))
            MarkBadges(marked, pending, DemoSubjectType, onUndo = {})
        }
        ring?.let { shown ->
            key(shown) { DemoRingLayers(shown, coverModifier, corner, onClosed = { ring = null }) }
        }
        FingerDot(finger)
    }
}

/** 演示环的几层：真环的画法（[RingLayers]），浮起的是演示封面那一块色块。 */
@Composable
private fun DemoRingLayers(ring: DemoRing, coverModifier: Modifier, corner: Dp, onClosed: () -> Unit) {
    RingLayers(ring, onDismiss = {}, onChoose = {}, onClosed = onClosed, Modifier.fillMaxSize()) { liftScale ->
        Box(
            coverModifier
                .graphicsLayer {
                    scaleX = liftScale()
                    scaleY = liftScale()
                    shape = RoundedCornerShape(corner)
                    clip = true
                    shadowElevation = LiftElevation.toPx()
                }
                .skeletonFill(),
        )
    }
}
