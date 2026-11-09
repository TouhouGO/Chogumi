package io.github.touhougo.chogumi.core.ui.tour

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.round
import io.github.touhougo.chogumi.core.ui.collection.HubAlpha
import io.github.touhougo.chogumi.core.ui.collection.HubStrokeAlpha
import io.github.touhougo.chogumi.core.ui.collection.RingGeometry
import io.github.touhougo.chogumi.core.ui.collection.SlotElevation
import io.github.touhougo.chogumi.core.ui.composables.SkeletonSketch
import kotlin.math.roundToInt

/*
 * 手势引导的演示台：每页一块，里面用色块按真实的 dp 排一小段界面，再整块缩小画出来，像一张会动的截图。
 * 色块不是真组件（不接数据、不能点），几何、文案与颜色从组件自己的常量取：长按环与封面角标就是真环的那几层
 * （RingLayers、MarkBadges），格子取 TileStyle 的样子与 EpisodeTileRows 的排法，字与封面是骨架的示意图画法（SkeletonSketch）。
 */

/**
 * 演示台里的界面按真实 dp 排，画的时候缩到这么大：手机宽的演示台里放得下整个长按环、一整张楼层菜单
 * （设计决定，not in M3）。
 */
private const val StageScale = 0.8f

/** 演示台里按真实 dp 排版的那块区域：多大（px），以及台上某一块的中心在哪（手指按的位置），都在演示台自己的坐标里。 */
internal class StageSpace(val size: Size) {
    internal var root: LayoutCoordinates? = null

    /** [target] 的中心在演示台坐标里的位置；演示台还没放置时是原点。 */
    fun centerOf(target: LayoutCoordinates): Offset =
        root?.takeIf { it.isAttached && target.isAttached }?.localPositionOf(target, Offset(target.size.width / 2f, target.size.height / 2f))
            ?: Offset.Zero
}

/**
 * 演示台：`extraLarge` 圆角的一块 `surfaceContainer`，正方形；[content] 拿到按真实 dp 排版的区域（[StageSpace]），
 * 里面的字条与色块是骨架的示意图画法（[SkeletonSketch]）。整块对读屏隐藏、不接点按：每页的标题与说明已经说了它在演示什么。
 */
@Composable
internal fun TourStage(modifier: Modifier = Modifier, content: @Composable BoxScope.(stage: StageSpace) -> Unit) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clearAndSetSemantics {}
            .layout { measurable, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val placeable = measurable.measure(Constraints.fixed((width / StageScale).roundToInt(), (height / StageScale).roundToInt()))
                layout(width, height) {
                    placeable.placeWithLayer(0, 0) {
                        scaleX = StageScale
                        scaleY = StageScale
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                }
            },
    ) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val stage = remember(width, height) { StageSpace(Size(width.toFloat(), height.toFloat())) }
        SkeletonSketch {
            Box(Modifier.fillMaxSize().onPlaced { stage.root = it }) { content(stage) }
        }
        // 演示里有真组件（菜单项、按钮、搜索入口），盖一层只收不消费的触摸层：它们收不到点按，左右滑照样交给翻页
        Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
    }
}

/** 演示里的那根手指：在哪（演示台坐标，px）、按着没有。 */
@Stable
internal class TourFinger {
    val position = Animatable(Offset.Zero, Offset.VectorConverter)
    var down by mutableStateOf(false)
        private set

    /** 在 [at] 按下：先到位再出现，不从上一次抬起的地方滑过来。 */
    suspend fun press(at: Offset) {
        position.snapTo(at)
        down = true
    }

    fun lift() {
        down = false
    }

    /** 回到没按着的样子（演示从头再来、翻走了）。 */
    suspend fun reset() {
        down = false
        position.snapTo(Offset.Zero)
    }
}

/**
 * 手指：长按环中心那个死区圈填实（同一个半径、同一种白），带环上圆钮那一档阴影，在浅色的演示台上也看得见；
 * 按下时淡入、抬起时淡出（effects 动效），抬起后在原地淡出。放在演示台的最上面。
 */
@Composable
internal fun FingerDot(finger: TourFinger) {
    val alpha by animateFloatAsState(if (finger.down) 1f else 0f, MaterialTheme.motionScheme.fastEffectsSpec(), label = "tour_finger")
    Box(
        Modifier
            .offset {
                val radius = RingGeometry.DeadZone.toPx()
                (finger.position.value - Offset(radius, radius)).round()
            }
            .size(RingGeometry.DeadZone * 2)
            .graphicsLayer {
                this.alpha = HubAlpha * alpha
                shape = CircleShape
                shadowElevation = SlotElevation.toPx()
            }
            .background(Color.White.copy(alpha = HubStrokeAlpha), CircleShape),
    )
}
