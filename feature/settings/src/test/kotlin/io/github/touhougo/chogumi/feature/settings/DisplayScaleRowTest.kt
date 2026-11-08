package io.github.touhougo.chogumi.feature.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.DisplayScale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 界面大小一行：读数是这一档下的最小宽度，小屏上滑到最右是缩完不低于 320dp 的那一档，松手写入的就是读数那一档。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-sw360dp-w360dp-h640dp")
class DisplayScaleRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 小屏上滑到最右写入能放下的最大一档_读数是那一档的最小宽度() {
        val selected = mutableListOf<Int>()
        compose.setContent { DisplayScaleRow(index = 0, count = 1, stored = DisplayScale.SYSTEM) { selected += it } }
        compose.onNodeWithText("跟随系统").assertExists()

        val slider = compose.onNodeWithTag(DisplayScaleSliderTag)
        val last = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range.endInclusive
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(last) }

        assertEquals(listOf(110), selected)
        compose.onNodeWithText("327 dp").assertExists()
    }
}
