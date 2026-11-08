package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** 元信息各段之间不画分隔点，只靠空白分段：这段空白约一个字宽，一眼就比段内的词间空格（「CV 坂田将吾」）宽得多。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MetaTextGapTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `段间空白约一个字宽 远宽于词间空格`() {
        var gap = 0
        var space = 0
        var em = 0f
        composeRule.setContent {
            ChogumiTheme {
                val measurer = rememberTextMeasurer()
                val style = MaterialTheme.typography.bodySmall
                fun widthOf(text: AnnotatedString) = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
                val tight = widthOf(AnnotatedString("主角CV"))
                gap = widthOf(metaLine(listOf(AnnotatedString("主角"), AnnotatedString("CV")))) - tight
                space = widthOf(AnnotatedString("主角 CV")) - tight
                em = with(LocalDensity.current) { style.fontSize.toPx() }
            }
        }
        composeRule.waitForIdle()
        assertTrue("词间空格 $space px 有宽度", space > 0)
        assertTrue("段间空白 $gap px 至少是词间空格 $space px 的三倍", gap >= space * 3)
        assertTrue("段间空白 $gap px 约一个字宽（$em px）", gap in (em * 0.8f).roundToInt()..(em * 1.3f).roundToInt())
    }

    @Test
    fun `metaGapWidth 就是 MetaText 画出的段间空白 并随字号一起变宽`() {
        var fontScale by mutableStateOf(1f)
        val widths = mutableMapOf<Float, Pair<Dp, Dp>>()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                ChogumiTheme {
                    val measurer = rememberTextMeasurer()
                    val density = LocalDensity.current
                    val style = MaterialTheme.typography.labelMedium
                    fun widthOf(text: AnnotatedString) = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
                    val drawn = with(density) {
                        (widthOf(metaLine(listOf(AnnotatedString("a"), AnnotatedString("a")))) - widthOf(AnnotatedString("aa"))).toDp()
                    }
                    widths[density.fontScale] = metaGapWidth(style) to drawn
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { fontScale = 2f }
        composeRule.waitForIdle()
        assertEquals(setOf(1f, 2f), widths.keys)
        widths.forEach { (scale, pair) -> assertEquals("${scale}× 字号：metaGapWidth 与画出的空白同宽", pair.second.value, pair.first.value, 0.01f) }
        val normal = widths.getValue(1f).first
        val large = widths.getValue(2f).first
        assertTrue("2× 字号的段间空白 $large 至少是 1× 的 $normal 的 1.5 倍", large.value >= normal.value * 1.5f)
    }
}
