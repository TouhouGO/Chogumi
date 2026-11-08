package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.SearchAirtime
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 年份滑块报出去的值，调用方的状态晚一帧才交回来（真机上就是这样）：拖动途中手柄不被晚到的旧值往回拽，
 * 手指一路向右时报出的起点年份不回退，抬手时手柄停在手指放开的地方。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AirtimeSliderLagTest {

    @get:Rule
    val compose = createComposeRule()

    private var airtime by mutableStateOf<SearchAirtime?>(null)
    private val reported = mutableListOf<SearchAirtime?>()

    @Test
    fun `状态晚一帧交回 拖动途中报出的起点年份不回退`() {
        compose.mainClock.autoAdvance = false
        var handedBack: SearchAirtime? = null
        compose.setContent {
            ChogumiTheme {
                Box(Modifier.width(360.dp)) {
                    AirtimeSection(airtime = airtime, currentYear = 2026, onChange = { reported += it; handedBack = it })
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        // 起点手柄在最左端：从那里按下，一路向右拖到约 60% 处，每走一小步过一帧，并把上一帧报出的值在这一帧交回
        compose.onNodeWithTag("SearchTimeSlider").performTouchInput {
            down(Offset(HANDLE_INSET_PX, centerY))
            val steps = 12
            repeat(steps) { step ->
                moveTo(Offset(HANDLE_INSET_PX + (width * 0.6f - HANDLE_INSET_PX) * (step + 1) / steps, centerY))
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle { airtime = handedBack }
                compose.mainClock.advanceTimeByFrame()
            }
            up()
        }
        compose.mainClock.advanceTimeByFrame()
        val starts = reported.mapNotNull { it?.startYear }
        assertTrue("拖动途中报出过起点年份：$reported", starts.isNotEmpty())
        assertEquals("一路向右时报出的起点年份不回退：$starts", starts.sorted(), starts)
        assertTrue("手指停在约 60% 处，起点年份 ${starts.last()} 应在 1990 年之后", starts.last() > 1990)
    }

    private companion object {
        /** 起点手柄中心离滑块左端的大致距离（像素）：落在手柄的触控范围内即可。 */
        const val HANDLE_INSET_PX = 8f
    }
}
