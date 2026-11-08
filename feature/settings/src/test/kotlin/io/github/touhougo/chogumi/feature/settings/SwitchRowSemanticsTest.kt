package io.github.touhougo.chogumi.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 开关行是一个整体：读屏聚焦时念标题，点标题也能切换。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class SwitchRowSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 标题和开关是同一个可切换节点() {
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            Column { SwitchRow(index = 0, count = 1, title = "专注模式", checked = true, onCheckedChange = { changes += it }) }
        }

        compose.onNode(hasText("专注模式") and isToggleable()).performClick()

        assertEquals(listOf(false), changes)
    }
}
