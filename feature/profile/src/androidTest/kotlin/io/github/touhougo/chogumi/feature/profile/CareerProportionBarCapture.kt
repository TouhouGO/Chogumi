package io.github.touhougo.chogumi.feature.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** 真实生涯比例图区块在当前设备宽度下的浅深色与放大字号捕获；图像只用于人工查看。 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class CareerProportionBarCapture {

    @get:Rule
    val composeRule = createComposeRule()

    private var fontScale by mutableFloatStateOf(1f)
    private var darkTheme by mutableStateOf(false)
    private var rankTitle = ""

    @Test
    fun 捕获长尾比例图与图例() {
        composeRule.setContent {
            CareerProportionHost(fontScale, darkTheme) {
                rankTitle = stringResource(R.string.career_sub_rank)
                CareerProportionSections()
            }
        }
        listOf(1f, 1.1f, 2f).forEach { font ->
            listOf(false, true).forEach { dark ->
                composeRule.runOnIdle {
                    fontScale = font
                    darkTheme = dark
                }
                composeRule.waitForIdle()
                val width = composeRule.onRoot().fetchSemanticsNode().size.width / composeRule.density.density
                val name = "width-${width.toInt()}-font-$font-${if (dark) "dark" else "light"}"
                composeRule.onNode(hasContentDescription(rankTitle)).performScrollTo()
                saveScreenshot(Folder, name, composeRule.onRoot().captureToImage().asAndroidBitmap())
            }
        }
    }

    private companion object {
        const val Folder = "career-proportion"
    }
}
