package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.SubjectCountUnit
import io.github.touhougo.chogumi.core.model.SubjectMetaPart
import io.github.touhougo.chogumi.core.model.SubjectYearMonth
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 头部元信息行在封面右侧的窄宽度里：放不下的段从末尾起整段去掉，年月不被截成半截。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MetaLineLargeFontTest {

    @get:Rule
    val compose = createComposeRule()

    private val parts = listOf(
        SubjectMetaPart.Text("TV"),
        SubjectMetaPart.Date(SubjectYearMonth(2026, 10)),
        SubjectMetaPart.Count(SubjectCountUnit.EPISODE, 12),
        SubjectMetaPart.InfoboxValue("パッショーネ"),
    )

    private fun show(fontScale: Float, width: () -> Dp = { HEADER_TEXT_WIDTH }) {
        compose.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                    Column(Modifier.width(width())) { MetaLine(SlotFill.Real(parts)) }
                }
            }
        }
    }

    private fun shownText(): String {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText("TV", substring = true), useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.single()
        assertFalse("元信息行被截断", layout.hasVisualOverflow)
        return layout.layoutInput.text.text
    }

    @Test
    fun 一点一倍字号下资料表段不留下只剩省略号的一段() {
        var width by mutableStateOf(HEADER_TEXT_WIDTH)
        show(1.1f) { width }
        assertTrue(shownText().contains("12 话"))
        val seen = (300 downTo 180 step 2).map { value ->
            compose.runOnIdle { width = value.dp }
            shownText().also { text -> assertFalse("宽度 $value：$text", text.trimEnd().endsWith(" …")) }
        }
        assertTrue("宽时资料表段确实出现", seen.any { it.endsWith("パッショーネ") })
        assertTrue("收窄后资料表段被去掉", seen.any { it.endsWith("12 话") })
    }

    @Test
    fun 两倍字号下放不下的段从末尾起整段去掉_不截年月() {
        show(2f)
        val text = shownText()
        assertEquals("TV 2026 年 10 月", text)
    }

    private companion object {
        // 条目头部元信息行在 380dp 屏上、封面右侧的可用宽度
        val HEADER_TEXT_WIDTH = 230.dp
    }
}
