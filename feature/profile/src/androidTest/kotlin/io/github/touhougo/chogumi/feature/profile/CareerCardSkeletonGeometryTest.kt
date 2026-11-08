package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.testing.SkeletonAnchor
import io.github.touhougo.chogumi.core.ui.testing.assertSkeletonMatchesReal
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 生涯卡的骨架与真实卡同一个外框：数据到了，卡片原位换成真的，不变高、不把下面顶下去；放大字号时卡片随字长高，字不出卡。
 *
 * 量的地方可纵向滚动（不限高），与个人页一样：放大字号时整卡比屏幕还高，限高的话量到的不是它本来的高度。
 */
@RunWith(AndroidJUnit4::class)
class CareerCardSkeletonGeometryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var showReal by mutableStateOf(false)

    private val real = CareerState(isSelf = true, ownStats = ProfileSkeletonFixtures.careerStatistics)

    private fun setContent(width: Dp, fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.width(width).verticalScroll(rememberScrollState())) {
                        val state = if (showReal) real else CareerState(isSelf = true)
                        CareerCard(content = state.cardContent(publicRatings = true), title = "我的生涯", onOpenFull = {}, onRetry = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun card() = composeRule.onAllNodesWithTag(CareerCardTag, useUnmergedTree = true).let { it[it.fetchSemanticsNodes().size - 1] }

    private fun assertSameFrame(width: Dp) {
        setContent(width, fontScale = 1f)
        assertSkeletonMatchesReal(
            anchors = listOf(SkeletonAnchor("整卡", { card() }, { card() })),
            flipToReal = {
                composeRule.runOnIdle { showReal = true }
                composeRule.waitForIdle()
            },
        )
    }

    @Test
    fun 小屏上骨架与真实卡同一个外框() = assertSameFrame(SmallPhoneCardWidth)

    @Test
    fun 常见宽度上骨架与真实卡同一个外框() = assertSameFrame(400.dp)

    @Test
    fun 字体放大两倍时卡片随字长高_每行字都在卡内() {
        showReal = true
        setContent(SmallPhoneCardWidth, fontScale = 2f)
        val cardBounds = composeRule.onNodeWithTag(CareerCardTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val texts = composeRule.onAllNodes(
            hasAnyAncestor(hasTestTag(CareerCardTag)) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        )
        val count = texts.fetchSemanticsNodes().size
        assertTrue("卡上的字都画出来了：刊头、三行数与说明、两类、称号、最爱", count >= 1 + 3 * 2 + 2 * 2 + 2)
        (0 until count).forEach { index ->
            val bounds = texts[index].getUnclippedBoundsInRoot()
            assertTrue("第 $index 行字的下沿 ${bounds.bottom} 超出卡片下沿 ${cardBounds.bottom}", bounds.bottom <= cardBounds.bottom + 0.5.dp)
            assertTrue("第 $index 行字的右沿 ${bounds.right} 超出卡片右沿 ${cardBounds.right}", bounds.right <= cardBounds.right + 0.5.dp)
        }
    }

    private companion object {
        /** 380dp 的手机减去个人页左右各 16dp 的网格内边距。 */
        val SmallPhoneCardWidth = 348.dp
    }
}
