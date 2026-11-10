package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.touhougo.chogumi.core.ui.theme.AppMotion
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 首次可见事件持有的进度，存在懒列表之上；同一模块换画法与返回时不重播。 */
class ChartEntrance(val elements: Int = 100, finished: Boolean = false) {
    val spatial = List(elements) { Animatable(if (finished) 1f else 0f) }
    val effects = Animatable(if (finished) 1f else 0f)
    suspend fun play(motion: AppMotion, staggerMillis: Long) = coroutineScope {
        launch { effects.animateTo(1f, motion.scheme.defaultEffectsSpec()) }
        spatial.forEachIndexed { index, value ->
            launch {
                delay(motion.stagger(index * staggerMillis))
                value.animateTo(1f, motion.scheme.defaultSpatialSpec())
            }
        }
    }
}

val LocalChartEntrance = staticCompositionLocalOf<ChartEntrance?> { null }

/** 缩略图与其他图表没有首次可见事件，直接画终态。 */
@Composable
fun chartFractions(): List<Float> = if (LocalAppMotion.current.stagger(1) == 0L) emptyList()
    else LocalChartEntrance.current?.spatial?.map { it.value } ?: emptyList()

fun List<Float>.chartFraction(index: Int): Float = getOrElse(index) { 1f }.coerceIn(0f, 1f)

/** 效果属性在系统移除动画时直接最终态。 */
@Composable
fun chartOpacity(): Float = if (LocalAppMotion.current.stagger(1) == 0L) 1f else LocalChartEntrance.current?.effects?.value ?: 1f
