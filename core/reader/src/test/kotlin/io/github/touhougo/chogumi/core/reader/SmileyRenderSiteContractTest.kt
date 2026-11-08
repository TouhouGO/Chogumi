package io.github.touhougo.chogumi.core.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 两条正文渲染路径都按家族给表情定尺寸（[SmileySizes.emOf]）：时间线正文（[TimelineBody]）与吐槽箱、楼层的
 * [BBCodeText]。任何一条另取尺寸，同一个表情在时间线与楼层里就大小不一；一刀切成小尺寸，大动态表情就被压成一个字大。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmileyRenderSiteContractTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `时间线正文里大小表情的占位按家族尺寸`() = assertFamilySizes { TimelineBody(BODY) }

    @Test
    fun `BBCode 正文里大小表情的占位按家族尺寸`() = assertFamilySizes { BBCodeText(BBCodeParser.parse(BODY)) }

    private fun assertFamilySizes(content: @Composable () -> Unit) {
        composeRule.setContent { ChogumiTheme { content() } }
        val layout = buildList<TextLayoutResult> {
            composeRule.onNodeWithText("小", substring = true, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(this) }
        }.single()
        val (small, large) = layout.placeholderRects.map { checkNotNull(it).height }
        val fontPx = with(layout.layoutInput.density) { layout.layoutInput.style.fontSize.toPx() }
        // 占位高取整到像素，按 em × 字号比，差不过 1 px
        assertEquals("小表情占位高", SmileySizes.emOf(SMALL).value * fontPx, small, 1f)
        assertEquals("大表情占位高", SmileySizes.emOf(LARGE).value * fontPx, large, 1f)
    }

    private companion object {
        const val SMALL = "bgm38"
        const val LARGE = "musume_15"
        const val BODY = "小($SMALL)大($LARGE)"
    }
}
