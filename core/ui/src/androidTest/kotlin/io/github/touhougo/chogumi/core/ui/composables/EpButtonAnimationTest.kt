package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.EpPendingVisual
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 固定动画时钟检查整个回缩过程，不能用 waitForIdle 后的稳态证明中间帧没有裁切。 */
@RunWith(AndroidJUnit4::class)
class EpButtonAnimationTest {
    @get:Rule val composeRule = createComposeRule()

    private var position by mutableStateOf(97)
    private var pending by mutableStateOf<EpPendingVisual?>(null)

    @Test fun 双位与三位数字回缩时右缘始终完整() = assertRetraction(fontScale = 1f)

    @Test fun 放大字体与跨位数回缩时右缘始终完整() = assertRetraction(fontScale = 2f)

    private fun assertRetraction(fontScale: Float) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    EpButton(
                        state = EpButtonState.Next(
                            EpisodeBo(id = position, subjectId = 1, type = 0, sort = position.toDouble(), name = "", nameCn = ""),
                            position = position,
                        ),
                        onNext = {}, onMarkFinished = {}, showTarget = true, subjectType = SubjectType.ANIME, pending = pending,
                    )
                }
            }
        }
        for (target in listOf(98, 100, 1000)) {
            composeRule.runOnIdle {
                position = target - 1
                pending = EpPendingVisual(count = 12, commitAtNanos = 0L)
            }
            composeRule.mainClock.advanceTimeBy(2_000)
            composeRule.runOnIdle { position = target; pending = null }
            // 每帧都断言，不轮询等待一瞬间碰巧完整；覆盖标签滑入及宽度弹簧过冲。
            repeat(64) { frame ->
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
                val text = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.progress_episode, target.toString())
                val label = composeRule.onNodeWithText(text, useUnmergedTree = true)
                val full = label.getUnclippedBoundsInRoot()
                val visible = label.fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "数字=$target 字体=$fontScale 帧=$frame 右缘被裁切：完整=$full 可见=$visible",
                    visible.right >= full.right.value * composeRule.density.density - 1f,
                )
            }
        }
    }
}
