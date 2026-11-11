package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.statistics.ratingControversyOf
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class CampsHitTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `点两派比例条按实际占比显示_上方文字仍独立可点`() {
        // 100票：1分80票、5分10票、10分10票；底部条形左80%都是1至4分组。
        val value = requireNotNull(ratingControversyOf(listOf(80,0,0,0,10,0,0,0,0,10)))
        val tips = listOf("低分组80票", "中间组10票", "高分组10票")
        compose.setContent { ChogumiTheme { Box(Modifier.width(300.dp)) { ControversyCamps(value,tips) } } }
        compose.onNodeWithContentDescription(tips.joinToString()).performTouchInput {
            click(Offset(width * 0.5f, height * 78f / 92f))
        }
        compose.onNodeWithText("低分组80票").assertExists()
        compose.onNodeWithContentDescription(tips.joinToString()).performTouchInput {
            click(Offset(width * 0.5f, height * 30f / 92f))
        }
        compose.onNodeWithText("中间组10票").assertExists()
    }
    @Test fun `条形进场和零占比的命中几何与绘图相同`() {
        val bounds = campSegmentBounds(listOf(0f, 0.01f, 0.99f), 300f, 70f, 16f, 3f, listOf(0.5f, 0.5f, 0.5f))
        org.junit.Assert.assertEquals(0f, bounds[0].width)
        org.junit.Assert.assertEquals(0f, bounds[1].width)
        val last = bounds[2]
        org.junit.Assert.assertEquals(3f, last.left)
        org.junit.Assert.assertEquals(147f, last.width)
        val marks = bounds.mapIndexedNotNull { index, rect -> rect.takeIf { it.width > 0f }?.let { ChartMark(it, "$index") } }
        org.junit.Assert.assertEquals("2", markAt(marks, Offset(80f, 78f), 0f)?.label)
        org.junit.Assert.assertNull(markAt(marks, Offset(280f, 78f), 0f))
    }

}
