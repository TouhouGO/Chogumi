package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.StatsData
import io.github.touhougo.chogumi.core.model.StatsDrawing
import io.github.touhougo.chogumi.core.model.StatsModule
import io.github.touhougo.chogumi.core.model.statistics.EpisodeHeatCard
import io.github.touhougo.chogumi.core.model.statistics.EpisodeHeatCell
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test

/** 长篇柱的单独捕获，避免全画法矩阵的截图负载盖住这条验收。 */
@VisualCapture
class LongStatsCapture {
    @get:Rule val compose = createComposeRule()
    private var dark by mutableStateOf(false)

    @Test
    fun 百集讨论柱两色() {
        val heat = EpisodeHeatCard(List(100) { EpisodeHeatCell(it + 1, (it + 1).toDouble(), it + 1, false) })
        val tab = StatsCaptureFixtures.tab().copy(episodeHeat = Load.Ready(heat))
        compose.setContent {
            ChogumiTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize()) {
                    Column { StatsModuleBlock(StatsModule("long", StatsData.EPISODE_DISCUSSION, StatsDrawing.EPISODE_BARS), tab, {}, {}, {}, {}, {}, {}) }
                }
            }
        }
        val width = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp
        for (value in listOf(false, true)) {
            compose.runOnIdle { dark = value }
            compose.waitForIdle()
            saveScreenshot("stats-long", "$width-${if (value) "dark" else "light"}", compose.onRoot().captureToImage().asAndroidBitmap())
        }
    }
}
