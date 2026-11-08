package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 热门轮播停稳时，最前面的大项是一张完整的 3:4 封面：宽高比就是封面比例，宽度就是 [heroLargeItemWidth] 给的宽度
 * （官方排布没有为了排满再挪它）。窄屏、默认宽度、平板宽度各量一次。
 */
@RunWith(AndroidJUnit4::class)
class TrendingHeroCarouselTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var width by mutableStateOf(380.dp)

    private val items = (1..8).map { SubjectBo(id = it, type = SubjectType.ANIME, name = "作品$it", nameCn = "作品$it") }

    @Test
    fun 各宽度下停稳的大项都是完整的三比四封面() {
        composeRule.setContent {
            ChogumiTheme {
                Box(Modifier.requiredWidth(width)) {
                    TrendingHeroCarousel(items = items, onSubjectClick = {}, guide = null, coverDemo = false)
                }
            }
        }
        for (screenWidth in listOf(380.dp, 448.dp, 800.dp)) {
            composeRule.runOnIdle { width = screenWidth }
            composeRule.waitForIdle()
            val bounds = composeRule.onNodeWithTag(HeroItemTagPrefix + 0, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val expectedWidth = heroLargeItemWidth(screenWidth)
            assertNear("$screenWidth 宽时大项的宽", expectedWidth, bounds.right - bounds.left)
            assertNear("$screenWidth 宽时大项的高", expectedWidth / ChogumiDimens.CoverRatio, bounds.bottom - bounds.top)
        }
    }

    /** 像素取整之后，dp 上差不到 1。 */
    private fun assertNear(what: String, expected: Dp, actual: Dp) =
        assertEquals(what, expected.value, actual.value, 1f)
}
