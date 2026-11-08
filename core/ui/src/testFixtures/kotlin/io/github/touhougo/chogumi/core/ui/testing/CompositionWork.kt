package io.github.touhougo.chogumi.core.ui.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.setObserver

/**
 * 一次状态变化让多少段组合重跑：数 [CountingCompositions] 所在的组合及其下全部子组合（列表项、翻页的页）进入的重组作用域。
 * 内容装进 [CountingCompositions]，变化前 [reset]，等界面空闲后读 [entered]。
 *
 * 绝对数随组件实现变，不拿来断言；断言比的是两份内容各自同一拍的数（「这一拍的工作量与列表里有多少条无关」）。
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
class CompositionWork {
    /** 上次 [reset] 之后进入的重组作用域数。 */
    var entered: Int = 0
        private set

    fun reset() {
        entered = 0
    }

    internal val counter = object : CompositionObserver {
        override fun onBeginComposition(composition: ObservableComposition) = Unit
        override fun onScopeEnter(scope: RecomposeScope) {
            entered++
        }
        override fun onReadInScope(scope: RecomposeScope, value: Any) = Unit
        override fun onScopeExit(scope: RecomposeScope) = Unit
        override fun onEndComposition(composition: ObservableComposition) = Unit
        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) = Unit
        override fun onScopeDisposed(scope: RecomposeScope) = Unit
    }
}

/**
 * 把 [content] 所在的组合交给 [work] 计数。子组合不单独登记：它们向父组合取观察者，挂在这一层就都数到了。
 * 从第二帧起才数（观察者在第一帧之后挂上），断言的那一拍本来就在 [CompositionWork.reset] 之后。
 */
@OptIn(ExperimentalComposeRuntimeApi::class, InternalComposeApi::class)
@Composable
fun CountingCompositions(work: CompositionWork, content: @Composable () -> Unit) {
    val composition = currentComposer.composition
    DisposableEffect(composition) {
        val handle = composition.setObserver(work.counter)
        onDispose { handle?.dispose() }
    }
    content()
}
