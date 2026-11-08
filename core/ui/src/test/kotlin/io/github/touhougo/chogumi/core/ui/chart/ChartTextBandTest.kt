package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 图里文字带的高度随字号走：1 倍字号取下限，2 倍字号取这个字阶放大后的行高，字不压住上一行。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class ChartTextBandTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun bandAt(fontScale: Float): Pair<Dp, Dp> {
        var band = 0.dp
        var lineHeight = 0.dp
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                ChogumiTheme {
                    val style = MaterialTheme.typography.labelSmall
                    band = textBand(style, MIN_BAND)
                    lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
                }
            }
        }
        composeRule.waitForIdle()
        return band to lineHeight
    }

    @Test
    fun `默认字号行高不超过下限时取下限`() {
        val (band, lineHeight) = bandAt(1f)
        assertTrue("字阶行高 $lineHeight 应不超过下限 $MIN_BAND", lineHeight <= MIN_BAND)
        assertEquals(MIN_BAND.value, band.value, 0.01f)
    }

    @Test
    fun `两倍字号带高等于放大后的行高`() {
        val (band, lineHeight) = bandAt(2f)
        assertTrue("放大后的行高 $lineHeight 应超过下限 $MIN_BAND", lineHeight > MIN_BAND)
        assertEquals(lineHeight.value, band.value, 0.01f)
    }

    private companion object {
        val MIN_BAND = 16.dp
    }
}
