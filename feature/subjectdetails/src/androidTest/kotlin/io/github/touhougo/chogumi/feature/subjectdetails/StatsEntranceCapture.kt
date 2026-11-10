package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.touhougo.chogumi.core.model.StatsData
import io.github.touhougo.chogumi.core.model.StatsDrawing
import io.github.touhougo.chogumi.core.model.StatsModule
import io.github.touhougo.chogumi.core.ui.chart.ChartEntrance
import io.github.touhougo.chogumi.core.ui.chart.LocalChartEntrance
import io.github.touhougo.chogumi.core.ui.theme.AppMotion
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test

/** 进场逐帧看柱生长、真实线逐段、针落位；规格与页面同一个ChartEntrance。 */
@VisualCapture
class StatsEntranceCapture {
    @get:Rule val compose = createComposeRule()
    private var entrance by mutableStateOf(ChartEntrance())
    private var module by mutableStateOf(StatsModule("motion", StatsData.RATING, StatsDrawing.RATING_BARS))
    private lateinit var scope: CoroutineScope
    private lateinit var motion: AppMotion

    @Test
    fun 统计进场逐帧() {
        val tab = StatsCaptureFixtures.tab()
        compose.setContent {
            ChogumiTheme {
                scope = rememberCoroutineScope()
                motion = LocalAppMotion.current
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalChartEntrance provides entrance) {
                        Column { StatsModuleBlock(module, tab, {}, {}, {}, {}, {}, {}) }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        for (draw in listOf(StatsDrawing.RATING_BARS, StatsDrawing.CONTROVERSY_RIDGE, StatsDrawing.CONTROVERSY_DIAL)) {
            compose.runOnIdle {
                val data = StatsData.entries.first { draw in it.drawings }
                module = StatsModule("motion", data, draw)
                entrance = ChartEntrance(elements = module.entranceElements(tab))
            }
            compose.waitForIdle()
            compose.runOnIdle { scope.launch { entrance.play(motion, module.drawing.entranceStagger()) } }
            for (frame in 0..45) {
                compose.mainClock.advanceTimeByFrame()
                saveScreenshot("stats-motion", "${draw.name}-${frame.toString().padStart(3, '0')}", compose.onRoot().captureToImage().asAndroidBitmap())
            }
        }
        compose.mainClock.autoAdvance = true
    }
}
