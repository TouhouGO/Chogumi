package io.github.touhougo.chogumi.core.reader

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.bbcode.SmileyCatalog
import io.github.touhougo.chogumi.core.model.SmileyFiles
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * 按需下载的表情还没到时，占位与图同大：图到了贴贴 chip、投票卡不变宽变高，所在的列表项不在出现之后再变尺寸
 * （docs/code-standards.md「懒加载行回来时还是离开时的大小」）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w360dp-h640dp")
class SmileyLoadingPlaceholderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 下载中的表情占位与图同大() {
        val neverArrives = SmileyFiles { _, _ -> awaitCancellation() }
        val code = "bgm01"
        composeRule.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalSmileyFiles provides neverArrives) {
                    SmileyImage(code, size = SIDE, modifier = Modifier.testTag("smiley"))
                }
            }
        }

        val bounds = composeRule.onNodeWithTag("smiley").getUnclippedBoundsInRoot()
        assertEquals(SIDE * SmileyCatalog.widthRatioOf(code), bounds.width, "占位宽 = 图宽")
        assertEquals(SIDE, bounds.height, "占位高 = 图高")
    }

    private companion object {
        val SIDE = 24.dp
    }
}
