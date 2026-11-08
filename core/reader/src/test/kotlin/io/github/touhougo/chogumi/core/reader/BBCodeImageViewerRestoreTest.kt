package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.ui.image.ImageViewerPageTag
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 正文里点开的全屏大图跟着这段正文的图片走：页面重建（转屏、切深浅色）后还开着、停在原来那张；
 * 离开期间图片换了（帖子改过），回来时不拿旧下标去开新的图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-w400dp-h900dp")
class BBCodeImageViewerRestoreTest {
    @get:Rule
    val compose = createComposeRule()

    private val opener = SemanticsMatcher("点开大图") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "查看图片" }

    private fun openSecondImage() {
        compose.onAllNodes(opener)[1].performClick()
        compose.waitForIdle()
        assertEquals(1, viewerPages(), "大图已打开")
        compose.onNodeWithText("2 / 2").assertExists()
    }

    private fun viewerPages() = compose.onAllNodesWithTag(ImageViewerPageTag).fetchSemanticsNodes().size

    @Test
    fun `页面重建后大图还开着且停在第二张`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ChogumiTheme { BBCodeText(remember { BBCodeParser.parse(TWO_IMAGES) }) }
        }
        openSecondImage()

        restoration.emulateSavedInstanceStateRestore()

        assertEquals(1, viewerPages(), "重建后大图还开着")
        compose.onNodeWithText("2 / 2").assertExists()
    }

    @Test
    fun `离开期间图片换了_回来时大图不开`() {
        var source by mutableStateOf(TWO_IMAGES)
        var shown by mutableStateOf(true)
        compose.setContent {
            ChogumiTheme {
                val holder = rememberSaveableStateHolder()
                Box {
                    if (shown) holder.SaveableStateProvider("body") {
                        BBCodeText(remember(source) { BBCodeParser.parse(source) })
                    }
                }
            }
        }
        openSecondImage()
        compose.runOnIdle { shown = false }
        compose.runOnIdle { source = "[img]https://example.invalid/3.png[/img]" }
        compose.runOnIdle { shown = true }

        assertEquals(0, viewerPages(), "存下的是另两张图的第二张，不拿来开这段只有一张的正文")
    }

    private companion object {
        const val TWO_IMAGES = "[img]https://example.invalid/1.png[/img][img]https://example.invalid/2.png[/img]"
    }
}
