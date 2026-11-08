package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.round
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 只有图标的一排按钮说出自己的名字（feedback.md Tooltips：普通提示说明图标按钮是什么）：在 [TipHost] 里给按钮加
 * [LongPressTipScope.longPressTip]，长按就在按钮正上方出一行普通提示（[PlainTip]），手指离开 1.5 秒后消失（feedback.md：
 * 离开目标 1.5 秒后消失）。同一时刻只有一个提示：长按另一个，前一个立刻换掉（feedback.md：一次只显示一个提示）。
 * 提示由 [AnchoredTip] 开在自己的窗口里，横向不出宿主这一排的左右两边。
 */
@Composable
fun TipHost(modifier: Modifier = Modifier, content: @Composable LongPressTipScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    val tips = remember(scope) { LongPressTipScope(scope) }
    Box(modifier.onGloballyPositioned { tips.host = it }) {
        tips.content()
        tips.last?.let { placing ->
            AnchoredTip(visible = tips.shown != null, kind = TipKind.Plain, anchor = { placing.bounds }) { PlainTip(placing.text) }
        }
    }
}

/** [TipHost] 里的按钮用 [longPressTip] 报自己的名字。 */
class LongPressTipScope internal constructor(private val scope: CoroutineScope) {
    internal var host: LayoutCoordinates? = null

    /** 正在显示的提示；null = 没有。 */
    internal var shown: Tip? by mutableStateOf(null)
        private set

    /** 最后一个提示：退场的那一段仍按它摆放。 */
    internal var last: Tip? by mutableStateOf(null)
        private set

    private var hideJob: Job? = null

    internal class Tip(val text: String, val bounds: IntRect)

    /**
     * 长按这一块就出 [text]。只旁听手势、不消费：点按、长按后的松手照常归这一块自己的点击处理。
     * 每个按钮都是同一种修饰符，不随谁在显示提示而增减（按住途中改了修饰符链，这一下手势会被取消）。
     */
    fun Modifier.longPressTip(text: String): Modifier = this then LongPressTipElement(text, this@LongPressTipScope)

    internal fun show(text: String, target: LayoutCoordinates?) {
        val parent = host ?: return
        val self = target?.takeIf { it.isAttached } ?: return
        val topLeft = parent.localPositionOf(self, Offset.Zero).round()
        val tip = Tip(text, IntRect(topLeft, self.size))
        hideJob?.cancel()
        last = tip
        shown = tip
    }

    internal fun hideLater() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(TIP_LINGER_MILLIS)
            shown = null
        }
    }
}

/** 手指离开后提示停留多久（feedback.md Tooltips：离开目标 1.5 秒后消失）。 */
private const val TIP_LINGER_MILLIS = 1500L

private data class LongPressTipElement(val text: String, val tips: LongPressTipScope) : ModifierNodeElement<LongPressTipNode>() {
    override fun create() = LongPressTipNode(text, tips)

    override fun update(node: LongPressTipNode) {
        node.text = text
        node.tips = tips
    }
}

/** 记下自己在哪（提示指着它），旁听长按（Initial 阶段、不消费）。 */
private class LongPressTipNode(var text: String, var tips: LongPressTipScope) : DelegatingNode(), GlobalPositionAwareModifierNode {
    private var coordinates: LayoutCoordinates? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    init {
        delegate(
            SuspendingPointerInputModifierNode {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation(PointerEventPass.Initial)
                    }
                    if (released == null) {
                        tips.show(text, coordinates)
                        waitForUpOrCancellation(PointerEventPass.Initial)
                        tips.hideLater()
                    }
                }
            },
        )
    }
}
