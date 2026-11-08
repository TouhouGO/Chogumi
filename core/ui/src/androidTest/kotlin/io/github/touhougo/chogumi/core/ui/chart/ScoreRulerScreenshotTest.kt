package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.ui.composables.colors
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 同中心与两端标签的设备捕获：画布宽度及字号随设备配置，浅深色各一张。 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class ScoreRulerScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `分数尺拥挤标签与两端标签的浅深色画面`() {
        val dark = mutableStateOf(false)
        compose.setContent {
            ChogumiTheme(darkTheme = dark.value) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        val color = CollectionType.DONE.colors().color
                        ScoreRuler(
                            listOf("看过 8.50", "在看 8.50", "搁置 8.50", "抛弃 8.50", "想看 8.50").map { ScoreMark(it, 8.5, own = false, color) },
                            "同中心均分",
                        )
                        ScoreRuler(
                            listOf("Completed collection average 8.50", "Watching collection average 8.50", "On hold collection average 8.50")
                                .map { ScoreMark(it, 8.5, own = false, color) },
                            "长英文均分",
                        )
                        ScoreRuler(
                            listOf(ScoreMark("看过 1.00", 1.0, own = false, color), ScoreMark("在看 10.00", 10.0, own = false, color)),
                            "两端均分",
                        )
                    }
                }
            }
        }
        listOf(false, true).forEach { night ->
            compose.runOnIdle { dark.value = night }
            val image = compose.onRoot().captureToImage()
            val width = with(compose.density) { image.width.toDp().value.toInt() }
            saveScreenshot("score-ruler", "$width-${if (night) "dark" else "light"}", image.asAndroidBitmap())
        }
    }
}
