package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.FactRow
import io.github.touhougo.chogumi.core.ui.common.navigation.PageSheetRegistry
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FactRowsSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `重复资料键与带后缀的资料键都显示各自的值`() {
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost(PageSheetRegistry()) {
                    FactRowsSheet(
                        title = "资料",
                        rows = listOf(FactRow("a", "第一项"), FactRow("a", "第二项"), FactRow("a#2", "第三项")),
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("第一项", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("第二项", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("第三项", useUnmergedTree = true).assertExists()
    }
}

@VisualCapture
@RunWith(AndroidJUnit4::class)
class FactRowsSheetScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `保存重复资料键与带后缀资料键的合成面板`() {
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost(PageSheetRegistry()) {
                    FactRowsSheet(
                        title = "资料键碰撞",
                        rows = listOf(FactRow("a", "第一项"), FactRow("a", "第二项"), FactRow("a#2", "第三项")),
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()
        saveScreenshot("fact-rows", "collision", composeRule.onRoot().captureToImage().asAndroidBitmap())
    }
}
