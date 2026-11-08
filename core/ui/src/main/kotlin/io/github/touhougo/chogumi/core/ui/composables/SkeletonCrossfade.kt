package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 骨架 → 内容的**唯一过渡组件**：内容就绪后在骨架之上快速淡入，**绝不硬切**。
 *
 * M3 `transitions.md:43`：内容加载好后，在骨架之上**快速淡入**；`:59`/`:60`：避免内容瞬间蹦出来、
 * 避免跳切。`if (loading) 骨架 else 内容` 会在一帧之内硬切，所以骨架换内容一律走这里的交叉淡入。
 *
 * ## 语义
 * - [loading] 为 true：只画 [skeleton]。
 * - [loading] 变 false：立刻画 [content] 并把它的不透明度从 0 提到 1（effects 弹簧，不透明度不做过冲），
 *   骨架同步以互补的不透明度（1 − 内容）淡出；淡入结束后骨架移出组合，省掉底下骨架的开销。
 *   内容本身没有底色（列表、透明区块），骨架若在底下保持不透明，淡入期间会从内容的空隙里透出来，
 *   与位置不同的真实区块叠成两层（进作品详情瞬间标签行、切换栏会各出现两份）。
 *
 * ## 高度
 * 容器高度取在场子项的最大值。骨架只画「必然存在」的元素（见各骨架组件与审计表），因此内容通常
 * ≥ 骨架高，过渡结束后高度稳定在内容高，不会因骨架离场而回缩。骨架与内容**同位保留**的部分
 * （头像、名字、tab 等）由各骨架几何测试保证同源，淡入时不会错位。
 *
 * 淡入时长/缓动只取自 `MaterialTheme.motionScheme`（`motion.md`：自定义组件也走同一个 motion scheme），
 * 不硬编码 tween。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SkeletonCrossfade(
    loading: Boolean,
    modifier: Modifier = Modifier,
    skeleton: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val fadeSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    // 首帧就按 loading 定好：先画骨架时内容为 0、骨架为 1，不会闪一帧空白。
    val alpha = remember { Animatable(if (loading) 0f else 1f) }
    var showSkeleton by remember { mutableStateOf(loading) }

    LaunchedEffect(loading) {
        if (loading) {
            alpha.snapTo(0f)
            showSkeleton = true
        } else {
            // 从当前值（首次为 0）冲到 1；到 1 后骨架离场。不透明属 effects，弹簧不做过冲。
            alpha.animateTo(1f, fadeSpec)
            showSkeleton = false
        }
    }

    // 不透明度在绘制层里读：淡入途中的每一帧只重画，不重组
    Box(modifier) {
        if (showSkeleton || loading) {
            Box(Modifier.graphicsLayer { this.alpha = 1f - alpha.value }) { skeleton() }
        }
        if (!loading) {
            Box(Modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
        }
    }
}
