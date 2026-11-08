package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.testing.CompositionWork
import io.github.touhougo.chogumi.core.ui.testing.CountingCompositions
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 骨架换内容的淡入在绘制层里走：淡入途中的每一帧不重组。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SkeletonCrossfadeRecompositionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 淡入途中的帧不重组() {
        val work = CompositionWork()
        var loading by mutableStateOf(true)
        composeRule.setContent {
            CountingCompositions(work) {
                ChogumiTheme {
                    SkeletonCrossfade(
                        loading = loading,
                        skeleton = { Box(Modifier.size(40.dp)) },
                        content = { Box(Modifier.size(40.dp)) },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        loading = false
        composeRule.waitForIdle()
        // 内容进场、淡入开始的两帧照常重组
        repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
        work.reset()
        // 淡入途中的三帧（effects 规格约 150 ms，比这几帧长）
        repeat(3) { composeRule.mainClock.advanceTimeByFrame() }

        assertEquals("淡入途中进入的重组作用域", 0, work.entered)
    }
}
