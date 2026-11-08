package io.github.touhougo.chogumi.core.ui.composables

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.EP_COMMIT_DELAY_NANOS
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.EpPendingVisual
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.commitClockNanos
import io.github.touhougo.chogumi.core.model.commitRemainingFraction
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 倒计时细条按真实时间排空，与系统动画倍率无关：倍率为 0（无障碍「移除动画」）时不会一下排空，
 * 走到窗口一半的时刻细条剩一半左右——细条走完的那一刻就是真正提交的那一刻。
 *
 * 帧由测试手动推进，剩多少只看真实时间（[commitClockNanos]）；量的是画出来的像素，不是状态值。
 * 倒计时在首次组合画完之后才起算（宿主忙时首次组合本身就能用掉整个窗口），每次量都按这一帧前后两次读钟的区间断言：
 * 宿主卡多久，区间就宽多少，断言的始终是「画出来的 = 那一刻的真实剩余」。
 */
@RunWith(AndroidJUnit4::class)
class EpButtonDrainTest {
    @get:Rule val composeRule = createComposeRule(
        ComposeUiTestConfig(
            effectContext = object : MotionDurationScale {
                override val scaleFactor = 0f
            },
        ),
    )

    @Test
    fun 动画倍率为0时细条仍按真实时间排空() {
        var commitAt by mutableLongStateOf(commitClockNanos() + HOLD_NANOS)
        var bar = Color.Unspecified
        var container = Color.Unspecified
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ChogumiTheme {
                bar = MaterialTheme.colorScheme.onPrimaryContainer
                container = MaterialTheme.colorScheme.primaryContainer
                EpButton(
                    state = EpButtonState.Next(EpisodeBo(id = 5, subjectId = 1, type = 0, sort = 5.0, name = "", nameCn = ""), position = 5),
                    onNext = {},
                    onMarkFinished = {},
                    showTarget = true,
                    subjectType = SubjectType.ANIME,
                    modifier = Modifier.testTag(TAG),
                    pending = EpPendingVisual(count = 1, commitAtNanos = commitAt),
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()

        commitAt = commitClockNanos() + EP_COMMIT_DELAY_NANOS
        drawnAgainstClock(bar, container, commitAt).assertMatches("刚点时细条接近满格（动画倍率 0 也不能一下排空）")

        while (commitClockNanos() < commitAt - EP_COMMIT_DELAY_NANOS / 2) Thread.sleep(5)
        drawnAgainstClock(bar, container, commitAt).assertMatches("走到窗口一半时细条约剩一半")
    }

    /** 画出来的几成（[drawn]），与推一帧前后两次读钟时按真实时间应剩的几成（[least]..[most]）。 */
    private class Drawn(val drawn: Float, val least: Float, val most: Float) {
        fun assertMatches(what: String) =
            assertTrue("$what：画出 $drawn，这一帧前后按真实时间应剩 $least..$most", drawn in (least - PIXEL_TOLERANCE)..(most + PIXEL_TOLERANCE))
    }

    /** 推一帧并量细条；前后各读一次钟：这一帧的倒计时在两次读钟之间按时钟算，画出来的必在两者对应的剩余之间。 */
    private fun drawnAgainstClock(bar: Color, container: Color, commitAt: Long): Drawn {
        val before = commitClockNanos()
        composeRule.mainClock.advanceTimeByFrame()
        val drawn = barFraction(bar, container)
        val after = commitClockNanos()
        return Drawn(
            drawn = drawn,
            least = commitRemainingFraction(commitAt, after, EP_COMMIT_DELAY_NANOS),
            most = commitRemainingFraction(commitAt, before, EP_COMMIT_DELAY_NANOS),
        )
    }

    /**
     * 细条占容器宽度的几成：从容器底边往上第 2 到第 4 行像素（细条 3dp 高、避开抗锯齿的底边），
     * 细条色的像素数 / （细条色 + 容器色）。两端的圆角在同一行同样裁掉两种颜色，比例不受影响。
     */
    private fun barFraction(bar: Color, container: Color): Float {
        val bitmap = composeRule.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
        val bottom = (bitmap.height - 1 downTo 0).first { y -> rowCount(bitmap, y, bar) + rowCount(bitmap, y, container) >= MIN_PILL_PIXELS }
        val rows = (bottom - 4..bottom - 2)
        val barPixels = rows.sumOf { rowCount(bitmap, it, bar) }
        val containerPixels = rows.sumOf { rowCount(bitmap, it, container) }
        return barPixels.toFloat() / (barPixels + containerPixels)
    }

    private fun rowCount(bitmap: Bitmap, y: Int, color: Color): Int {
        val target = color.toArgb()
        return (0 until bitmap.width).count { x -> near(bitmap.getPixel(x, y), target) }
    }

    private fun near(a: Int, b: Int): Boolean =
        listOf(16, 8, 0).all { shift -> abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) <= COLOR_TOLERANCE }

    private companion object {
        const val TAG = "ep_button"
        const val COLOR_TOLERANCE = 12
        /** 一行里至少这么多像素是容器或细条色，才算到了按钮容器（排除徽标、抗锯齿边）。 */
        const val MIN_PILL_PIXELS = 40
        /** 量像素的误差（几成）：细条两端的抗锯齿与取整。 */
        const val PIXEL_TOLERANCE = 0.05f
        /** 首次组合期间的提交时刻：远在窗口之外，细条满格不动，倒计时还没开始。 */
        val HOLD_NANOS = EP_COMMIT_DELAY_NANOS * 1000
    }
}
