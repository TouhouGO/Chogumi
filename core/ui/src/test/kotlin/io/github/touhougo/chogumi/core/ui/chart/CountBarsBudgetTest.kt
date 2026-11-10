package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/** 长篇章节的柱与点按区也必须落在宽度预算内。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class CountBarsBudgetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `一百根柱每根点按区宽度为正且末根留在画布内`() {
        compose.setContent {
            ChogumiTheme {
                Box(Modifier.width(280.dp)) {
                    CountBars(List(100) { it + 1 }, List(100) { "${it + 1}" }, List(100) { "第 ${it + 1} 集" }, "长篇讨论", 110.dp)
                }
            }
        }
        val chart = compose.onNodeWithContentDescription("长篇讨论").fetchSemanticsNode().boundsInRoot
        for (i in 1..100) {
            val mark = compose.onNodeWithContentDescription("第 $i 集").fetchSemanticsNode().boundsInRoot
            assertTrue(mark.width > 0f, "第 $i 根点按区必须有正宽度")
            assertTrue(mark.left >= chart.left && mark.right <= chart.right + 1f, "第 $i 根点按区不能出画布")
        }
    }
}
