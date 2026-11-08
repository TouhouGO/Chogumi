package io.github.touhougo.chogumi.feature.explore

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.testing.CompositionWork
import io.github.touhougo.chogumi.core.ui.testing.CountingCompositions
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 热门大图跟着手指滑时，各项的宽度与文字透明度在绘制层里变，卡片不重组。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class TrendingHeroScrollTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 跟着手指滑_卡片不重组() {
        val work = CompositionWork()
        val items = (1..8).map { SubjectBo(id = it, type = SubjectType.ANIME, name = "作品$it", nameCn = "作品$it") }
        compose.setContent {
            CountingCompositions(work) {
                ChogumiTheme { TrendingHeroCarousel(items = items, onSubjectClick = {}, guide = null, coverDemo = false) }
            }
        }
        val first = compose.onNodeWithTag(HeroItemTagPrefix + 0)
        // 先越过触摸阈值、开始拖动，再数接下来一小段拖动
        first.performTouchInput { down(center); moveBy(Offset(-viewConfiguration.touchSlop - 10f, 0f)) }
        compose.waitForIdle()
        work.reset()
        first.performTouchInput { moveBy(Offset(-12f, 0f)) }
        compose.waitForIdle()
        val entered = work.entered
        first.performTouchInput { up() }

        assertEquals("拖动途中进入的重组作用域", 0, entered)
    }
}
