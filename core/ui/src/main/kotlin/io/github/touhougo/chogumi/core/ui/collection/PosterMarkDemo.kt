package io.github.touhougo.chogumi.core.ui.collection

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalViewConfiguration
import io.github.touhougo.chogumi.core.model.COLLECTION_MARK_WINDOW_NANOS
import io.github.touhougo.chogumi.core.model.CollectionType
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/*
 * 引导里演示长按环（探索页第一次进来）：在真的那一层（[PosterMarkLayer]）上放一遍「按住封面 → 环展开 → 朝看过滑过去 → 松手 →
 * 4 秒可撤销的胶囊 → 角标」，一遍一遍地放。用的是真的环、真的胶囊与角标，选了也不写收藏，停下就撤掉。
 *
 * 节奏取原型 `explore-focus-hint.html` 的时间点（设计决策，not in M3）；按住多久才开环取系统的长按时长，胶囊留多久取标记的可撤销窗口，与真的长按一样。
 */

/** 每一遍开始前、手指按下前的空档。 */
private const val DemoRestMillis = 500L

/** 环展开后、手指开始滑之前停多久（让人看清一圈有哪几格）。 */
private const val DemoLookMillis = 800L

/** 指着一格、预演胶囊出来之后停多久才松手。 */
private const val DemoAimMillis = 1000L

/** 提交后角标留多久，再从头放。 */
private const val DemoCommittedMillis = 1600L

/**
 * [playing] 时在 [mark] 这张封面上一遍遍放长按环的演示；[playing] 变 false 或离开组合就停，收起环、撤掉演示画的标记。
 * [mark] 为 null（不在 [PosterMarkHost] 里）时什么也不做。
 */
@Composable
fun PosterMarkDemo(mark: PosterMark?, playing: Boolean) {
    mark ?: return
    val longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
    val move = MaterialTheme.motionScheme.defaultSpatialSpec<Offset>()
    LaunchedEffect(mark, playing) {
        if (playing) mark.layer.playRingDemo(mark, longPressMillis, move)
    }
}

/**
 * 一遍遍放演示，直到被取消；取消时收起演示的环、抬起手指、撤掉演示画的标记。手指移动用 [move]（空间动效），
 * 移动途中按真的方向判定（[ringSlotAt]）换指着的那一格，与手指滑过去时一样。
 */
internal suspend fun PosterMarkLayer.playRingDemo(mark: PosterMark, longPressMillis: Long, move: AnimationSpec<Offset>) {
    try {
        while (true) {
            delay(DemoRestMillis)
            val press = coverRectsOf(mark)?.second?.center ?: continue
            demoFinger = press
            delay(longPressMillis)
            val ring = open(mark, pressInBlock = null, demo = true)
            if (ring == null) {
                demoFinger = null
                continue
            }
            delay(DemoLookMillis)
            // 演示标成看过；已经看过的标成想看，免得选中当前状态什么也不出
            val slot = if (ring.current == CollectionType.DONE) RingSlot.WISH else RingSlot.DONE
            val deadZone = with(density) { RingGeometry.DeadZone.toPx() }
            val finger = Animatable(ring.press, Offset.VectorConverter)
            finger.animateTo(ringSlotCenter(ring.press, slot, with(density) { RingGeometry.Radius.toPx() }), move) {
                demoFinger = value
                val delta = value - ring.press
                ring.hot = ringSlotAt(delta.x, delta.y, deadZone)
            }
            delay(DemoAimMillis)
            demoFinger = null
            choose(slot)
            delay(TimeUnit.NANOSECONDS.toMillis(COLLECTION_MARK_WINDOW_NANOS))
            showDemoMark(mark.subjectId, SubjectMark.of(marks, mark.subjectId).copy(type = slot.type, pending = null))
            delay(DemoCommittedMillis)
            showDemoMark(mark.subjectId, null)
        }
    } finally {
        demoFinger = null
        session?.takeIf { it.demo }?.open = false
        showDemoMark(mark.subjectId, null)
    }
}

/**
 * 演示里的手指：环中心那个死区圈填实（同一个半径与白色），按下时淡入、抬起时淡出（effects 动效）。画在环的浮层之上。
 */
@Composable
internal fun DemoFinger(layer: PosterMarkLayer, modifier: Modifier) {
    val down by remember(layer) { derivedStateOf { layer.demoFinger != null } }
    val alpha by animateFloatAsState(if (down) 1f else 0f, MaterialTheme.motionScheme.fastEffectsSpec(), label = "demo_finger")
    // 抬起后淡出时还画在最后按着的地方：只在绘制里读写，不是界面状态
    val last = remember { FingerAt() }
    if (alpha == 0f && !down) return
    Canvas(modifier) {
        val at = layer.demoFinger?.also { last.at = it } ?: last.at
        drawCircle(
            color = Color.White.copy(alpha = HubStrokeAlpha),
            radius = RingGeometry.DeadZone.toPx(),
            center = at,
            alpha = HubAlpha * alpha,
        )
    }
}

private class FingerAt {
    var at: Offset = Offset.Zero
}
