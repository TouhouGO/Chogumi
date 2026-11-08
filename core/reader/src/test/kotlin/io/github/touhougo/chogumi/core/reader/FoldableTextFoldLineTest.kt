package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 折叠线必须落在行边界：「N 行」按视觉行跨段落累计，段落间距不能把第 N 行推到折叠线下面被拦腰截断。
 *
 * 每个段落是一个独立的文字行，所以 [BBCodeText] 经 [FoldProbe] 报出的折叠线必须正好等于第 3 段的底边；
 * 「要不要出按钮」走 [FoldableText] 本身验证。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FoldableTextFoldLineTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val expandLabel: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.detail_expand)

    @Test
    fun `多段落折叠线落在第三段底边`() = assertFoldsAtThirdParagraph(fontScale = 1f)

    @Test
    fun `两倍字号多段落折叠线落在第三段底边`() = assertFoldsAtThirdParagraph(fontScale = 2f)

    @Test
    fun `正好三段不出现展开按钮`() {
        setFoldable("一\n二\n三")
        assertTrue(composeRule.onAllNodesWithText(expandLabel).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `多出一段时出现展开按钮`() {
        setFoldable("一\n二\n三\n四")
        assertEquals(1, composeRule.onAllNodesWithText(expandLabel).fetchSemanticsNodes().size)
    }

    private fun assertFoldsAtThirdParagraph(fontScale: Float) {
        val probe = FoldProbe(lines = 3)
        var densityValue = 1f
        val ast = BBCodeParser.parse("一\n二\n三\n四")
        composeRule.setContent {
            val density = LocalDensity.current
            densityValue = density.density
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    BBCodeText(ast = ast, modifier = Modifier.width(360.dp), foldProbe = probe)
                }
            }
        }
        composeRule.waitForIdle()
        val third = composeRule.onNodeWithText("三", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val foldPx = probe.line
        assertNotNull("超出 3 行必须报出折叠线", foldPx)
        assertEquals("折叠线必须等于第 3 段底边", third.bottom.value, foldPx!! / densityValue, 0.5f)
    }

    private fun setFoldable(text: String) {
        val ast = BBCodeParser.parse(text)
        composeRule.setContent {
            ChogumiTheme {
                // 360dp 固定视口只是测试设计决定（not in M3）。
                Box(Modifier.width(360.dp)) {
                    FoldableText(
                        ast = ast,
                        expanded = false,
                        onToggle = {},
                        collapsedLines = 3,
                        expandLabel = R.string.detail_expand,
                        collapseLabel = R.string.detail_collapse,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }
}
