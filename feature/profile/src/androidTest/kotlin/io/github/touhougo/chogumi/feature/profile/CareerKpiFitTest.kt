package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 完整统计的大数字格在小屏、大字号下也不截字：380dp 的手机上内容宽 348dp，
 * 「≈265 小时」这一格在三等分里放不下，排法会换成按内容分宽、收窄留白或竖排（[KpiRowPlan]），每段字都完整画出。
 */
@RunWith(AndroidJUnit4::class)
class CareerKpiFitTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun assertNoKpiTextCut(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.width(SmallPhoneCardWidth)) {
                        CareerKpiRow(careerKpisOf(SubjectType.ANIME, ProfileSkeletonFixtures.careerStats, isSelf = true))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val texts = composeRule.onAllNodes(
            hasAnyAncestor(hasTestTag(CareerKpiTileTag)) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue("三格的数、单位与名称都在", texts.size >= 3 * 2)
        texts.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            // 不用 hasVisualOverflow：单行字的段落宽取的是最大宽度，没截也会报宽度溢出。
            // 截字只有两种：折到 maxLines 之外被丢掉，或一行画出了自己的宽度
            val text = layout.layoutInput.text
            assertFalse("「$text」在 fontScale=$fontScale 下折行被截", layout.multiParagraph.didExceedMaxLines)
            assertTrue(
                "「$text」在 fontScale=$fontScale 下超出自己的宽度",
                layout.getLineRight(layout.lineCount - 1) <= layout.size.width + 0.5f,
            )
        }
    }

    @Test
    fun 一倍字号下大数字格不截字() = assertNoKpiTextCut(fontScale = 1f)

    @Test
    fun 小屏常用的一点一倍字号下大数字格不截字() = assertNoKpiTextCut(fontScale = 1.1f)

    @Test
    fun 字体放大两倍时大数字格不截字() = assertNoKpiTextCut(fontScale = 2f)

    private companion object {
        /** 380dp 的手机减去左右各 16dp 的内边距。 */
        val SmallPhoneCardWidth = 348.dp
    }
}
