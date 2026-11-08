package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 非满高面板的顶边固定：内容变高时面板在里面滚，不往上长；只有说了跟着内容走的面板才长。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PageModalSheetHeightTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var rows by mutableIntStateOf(2)

    @Test
    fun 内容变高时顶边不动() {
        setSheet(growsWithContent = false)
        val before = titleTop()
        rows = 8
        composeRule.waitForIdle()
        assertEquals("顶边应停在原处", before, titleTop(), 0.5f)
    }

    @Test
    fun 跟着内容走的面板内容变高时往上长() {
        setSheet(growsWithContent = true)
        val before = titleTop()
        rows = 8
        composeRule.waitForIdle()
        assertTrue("跟着内容走的面板应往上长：之前 $before，之后 ${titleTop()}", titleTop() < before - 100f)
    }

    private fun setSheet(growsWithContent: Boolean) {
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    PageModalSheet(onDismiss = {}, fullHeight = false, growsWithContent = growsWithContent) {
                        Text("title", Modifier.testTag(TITLE))
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            repeat(rows) { Text("row $it", Modifier.height(48.dp)) }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun titleTop(): Float = composeRule.onNodeWithTag(TITLE).getUnclippedBoundsInRoot().top.value

    private companion object {
        const val TITLE = "title"
    }
}
