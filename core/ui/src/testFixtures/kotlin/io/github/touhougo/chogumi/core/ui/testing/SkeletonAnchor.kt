package io.github.touhougo.chogumi.core.ui.testing

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.DpRect
import kotlin.math.abs
import org.junit.Assert.assertTrue

/**
 * 全仓 androidTest 共用的「骨架框 ↔ 真实框」比对工具，唯一一份：core:ui、profile、
 * subjectdetails 与其余测试都用它，不各写一份（各写一份很快就会分叉）。
 *
 * 语义（不许放松）：
 * - 容差 0.5dp（来自像素取整）；
 * - 默认比上、下、左三边；[SkeletonAnchor.compareLeft] / [compareTop] 可按锚点关掉，[compareRight] 可按锚点打开；
 * - [assertSkeletonMatchesReal] 的 [allowUpwardMovement] 把上/下缘换成「只许上移或不动」；横向始终严格比较；
 * - 某个锚点在任一态里找不到，算一条不一致（骨架漏画了真实存在的区块）。
 */
class SkeletonAnchor(
    val name: String,
    val skeleton: () -> SemanticsNodeInteraction,
    val real: () -> SemanticsNodeInteraction,
    /**
     * 默认 true；只有横向位置本就由数据决定的锚点才关掉——个人页人物卡的名字长度会改变头像在列内的
     * 居中位置，短名字与长名字各是一条用例、骨架却是同一份，无法同时严格对齐，与统计页右对齐的评分药丸同理。
     */
    val compareLeft: Boolean = true,
    /**
     * 默认 true；只有「在固定高度容器里垂直居中、且高度随内容变」的元素才关掉——高度收缩时上缘会随之下移
     * （如下缘略上移），既非内容下移也非整卡长高，纵向漂移由整卡锚点兜住。
     */
    val compareTop: Boolean = true,
    /** 默认 false；轮播里的空 Surface 若不撑满槽位会塌成零宽、左缘照旧而右缘贴到左缘，那类锚点才打开。 */
    val compareRight: Boolean = false,
)

/** 比较容差：来自像素取整。 */
private const val ToleranceDp = 0.5f

/**
 * 骨架 → 真实内容的几何同源断言：先读骨架里所有锚点的框，再调 [flipToReal] 换成真实内容，
 * 读同一批锚点逐一比较。
 *
 * 所有不一致攒到最后一次性报告，红的时候一眼看出差在哪几条边、差多少。
 */
fun assertSkeletonMatchesReal(
    anchors: List<SkeletonAnchor>,
    flipToReal: () -> Unit,
    allowUpwardMovement: Boolean = false,
) {
    val skeletonBounds = anchors.map { anchor -> runCatching { anchor.skeleton().getUnclippedBoundsInRoot() }.getOrNull() }
    flipToReal()
    val failures = mutableListOf<String>()
    anchors.forEachIndexed { index, anchor ->
        val skeleton = skeletonBounds[index]
        val real = runCatching { anchor.real().getUnclippedBoundsInRoot() }.getOrNull()
        when {
            skeleton == null -> failures += "${anchor.name}：骨架里没有这个锚点"
            real == null -> failures += "${anchor.name}：真实内容里没有这个锚点"
            else -> compareEdges(anchor, skeleton, real, failures, allowUpwardMovement)
        }
    }
    assertTrue("骨架与真实内容的几何不一致：\n" + failures.joinToString("\n"), failures.isEmpty())
}

private fun compareEdges(
    anchor: SkeletonAnchor,
    skeleton: DpRect,
    real: DpRect,
    failures: MutableList<String>,
    allowUpwardMovement: Boolean,
) {
    val name = anchor.name
    fun check(edge: String, s: Float, r: Float) {
        val mismatch = if (allowUpwardMovement) r > s + ToleranceDp else abs(s - r) > ToleranceDp
        if (mismatch) failures += "$name.$edge 骨架=${s}dp 真实=${r}dp（差 ${r - s}dp）"
    }
    if (anchor.compareTop) check("top", skeleton.top.value, real.top.value)
    check("bottom", skeleton.bottom.value, real.bottom.value)
    // 横向两边不受 allowUpwardMovement 影响：只有右对齐的评分药丸宽度由文案决定，靠 compareLeft 关掉左缘。
    if (anchor.compareLeft && abs(skeleton.left.value - real.left.value) > ToleranceDp) {
        failures += "$name.left 骨架=${skeleton.left} 真实=${real.left}"
    }
    if (anchor.compareRight && abs(skeleton.right.value - real.right.value) > ToleranceDp) {
        failures += "$name.right 骨架=${skeleton.right} 真实=${real.right}"
    }
}
