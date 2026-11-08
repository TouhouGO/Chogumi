package io.github.touhougo.chogumi.core.reader

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实绘制的链接描述在遮罩内不可见，首次点击揭开，再次点击才打开链接。 */
@RunWith(AndroidJUnit4::class)
class BBCodeMaskedLinkTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun 链接描述被遮住_揭开后才能打开链接() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        val ast = BBCodeParser.parse("[mask][url=https://example.com]链接描述[/url][/mask]")
        compose.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalUriHandler provides handler) { BBCodeText(ast) }
            }
        }
        val text = compose.onNodeWithText("链接描述")
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val bounds = layouts.single().getBoundingBox(0)
        val pixels = text.captureToImage().toPixelMap()
        // 首字中央区域包含真实字形；字色与背景同色时整块只有遮罩色。
        for (y in (bounds.top + bounds.height / 4).toInt() until (bounds.bottom - bounds.height / 4).toInt()) {
            for (x in bounds.left.toInt() + 1 until bounds.right.toInt() - 1) {
                assertEquals("遮罩内不应露出链接字形 ($x, $y)", 0xFF555555.toInt(), pixels[x, y].toArgb())
            }
        }
        text.performTouchInput { click(center) }
        compose.runOnIdle { assertTrue("第一次点击只揭开遮罩", opened.isEmpty()) }
        text.performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf("https://example.com"), opened) }
    }
}
