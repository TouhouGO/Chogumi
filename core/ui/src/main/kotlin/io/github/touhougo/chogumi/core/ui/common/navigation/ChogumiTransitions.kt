package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigationevent.NavigationEvent
import io.github.touhougo.chogumi.core.ui.theme.PageMotion
import kotlin.math.roundToInt

/** 一次页面切换的性质，决定两页怎么动（M3 `transitions.md` 的转场模式）。 */
enum class PageTransitionKind {
    /** 压进一页（forward and backward 的视差式）：新页不透明地从行尾一侧整屏滑进来盖住旧页，旧页同时慢慢往行首退一小段。 */
    Forward,

    /** 返回、或再点当前 tab 回第一层：同一动作倒过来，当前页整屏滑回行尾一侧，下面的页从行首一侧慢慢回位。 */
    Back,

    /** 顶级目的地之间互切（top level，即 fade through）：旧页先淡出，新页再淡入，不位移。 */
    TopLevel,

    /** 作品封面到作品详情（container transform）：封面长成整页，返回时缩回；两页本身不位移不淡化，离开的页留到转换结束。 */
    Container,
}

/**
 * 全站页面转场的**唯一定义处**（红线：单一真源）。`NavDisplay` 的三个 spec 都调这里，
 * 调用侧不得再自己写 `fadeIn/fadeOut/slide*` 或各写一份时长，时长与缓动全部来自 [PageMotion]。
 *
 * 守 `transitions.md` 的几条：**任何一帧都不许两屏半透明叠在一起**、转场不用弹簧、
 * 预测性返回拖动时两页都不透明、容器转换只给封面到作品详情这一处。
 *
 * - [PageTransitionKind.Forward] / [PageTransitionKind.Back]：`transitions.md` 前进后退的视差式（背景比前景慢）；这是设计决定，不在 M3 的 Android 默认里（Android 默认是边滑边淡，视差是 iOS 的默认）。两页都不透明、不淡化，
 *   上面那页（前进时的新页、返回时离开的页）整屏横移，下面那页同一条曲线只走 [PageMotion.parallax]；每张页自己垫了底色，
 *   盖住的部分看不见。前进时新页在上，返回时离开的页留在上面滑走（`NavDisplay` 按返回栈排上下层）。
 * - [PageTransitionKind.TopLevel]：只淡出再淡入。
 * - [PageTransitionKind.Container]：由共享边界（`PageExpand.kt`）把封面长成页面，这里两页都不动；离开的页必须留到转换结束。
 *   这段停留也是整个转换的时长：两页都没有动画时转换长度为 0，新页第一次组合卡几帧之后它当帧结束，
 *   旧页消失露出黑底，容器直接跳成整页。
 * - 系统关掉动画时所有规格都是瞬时的，页面直接换，不需要另写分支。
 *
 * [direction] 是排版方向（从左到右为 1，从右到左为 -1）：前进时新页从行尾一侧进来。它与预测性返回的预览位移无关。
 */
fun AnimatedContentTransitionScope<*>.chogumiTransition(
    kind: PageTransitionKind,
    page: PageMotion,
    direction: Int,
): ContentTransform = when (kind) {
    PageTransitionKind.Forward ->
        slideInHorizontally(page.slide) { width -> width * direction } togetherWith
            slideOutHorizontally(page.slide) { width -> -page.behind(width) * direction }
    PageTransitionKind.Back ->
        slideInHorizontally(page.slide) { width -> -page.behind(width) * direction } togetherWith
            slideOutHorizontally(page.slide) { width -> width * direction }
    PageTransitionKind.TopLevel -> fadeIn(page.enterFade) togetherWith fadeOut(page.exitFade)
    PageTransitionKind.Container ->
        (EnterTransition.None togetherWith fadeOut(page.containerHold)).apply { targetContentZIndex = 1f }
}

/**
 * `predictivePopTransitionSpec` 唯一该调的入口：离开的页跟着手势缩小、四角变圆（[rememberPageSurfaceNavEntryDecorator]），
 * 并向手势方向滑一点，下面的页原地不动、不透明；松手后离开的页接着缩完并淡出（`PageMotion.previewFade`）。从封面长出来的页返回时由共享边界跟着手势缩回封面。
 * [shiftPx] 是预览位移的像素数，已按排版方向带正负。tab 根部的返回不在这里：`AppNavigator` 把它当成切 tab，没有拖动预览。
 *
 * 不是手指拖出来的返回（[followsFinger] 为假：三键导航点返回键时，系统自己在约 0.3 秒里把进度推完）没有可跟的手指，
 * 走普通返回的转场（[chogumiTransition]），和松手前就已提交的返回一样。
 */
fun AnimatedContentTransitionScope<Scene<NavKey>>.chogumiPredictivePopTransition(
    kind: PageTransitionKind,
    page: PageMotion,
    direction: Int,
    shiftPx: Int,
    swipeEdge: Int,
): ContentTransform {
    if (kind == PageTransitionKind.Container || !followsFinger(swipeEdge)) return chogumiTransition(kind, page, direction)
    return (EnterTransition.None togetherWith
        (slideOutHorizontally(page.previewShift) { shiftPx } + scaleOut(page.previewShrink, targetScale = page.previewScale) + fadeOut(page.previewFade)))
        .apply { targetContentZIndex = -1f }
}

/**
 * 这次返回是不是手指从屏幕边上拖出来的（返回预览跟着它走），全应用唯一的判定。
 * 返回键（三键导航）、键盘等触发的返回事件不带边，是 [NavigationEvent.EDGE_NONE]。
 */
fun followsFinger(swipeEdge: Int): Boolean = swipeEdge != NavigationEvent.EDGE_NONE

/** 视差里下面那页走的像素数。 */
private fun PageMotion.behind(width: Int): Int = (width * parallax).roundToInt()
