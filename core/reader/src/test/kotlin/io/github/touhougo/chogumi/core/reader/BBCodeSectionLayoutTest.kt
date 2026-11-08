package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.assertTrue

/** 段落容器在栏里的位置：居中、靠右、浮动贴边、缩进，量的是文字在 300dp 栏里的实际边界。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w360dp-h640dp")
class BBCodeSectionLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val column = 300.dp

    private fun show(source: String) {
        composeRule.setContent {
            ChogumiTheme { BBCodeText(BBCodeParser.parse(source), Modifier.width(column)) }
        }
    }

    private fun boundsOf(text: String) = composeRule.onNodeWithText(text, substring = true).getBoundsInRoot()

    private fun assertNear(expected: Dp, actual: Dp, what: String) =
        assertTrue(abs((expected - actual).value) <= 1f, "$what：期望 $expected，实际 $actual")

    @Test
    fun 居中的一行落在栏的正中() {
        show("[center]短句[/center]")
        val bounds = boundsOf("短句")
        assertNear(column / 2, (bounds.left + bounds.right) / 2, "居中文字的中线")
    }

    @Test
    fun 靠右的一行贴栏的右边() {
        show("[right]短句[/right]")
        assertNear(column, boundsOf("短句").right, "靠右文字的右边")
    }

    @Test
    fun 右浮动的盒子贴栏的右边() {
        show("[float=right]短句[/float]")
        assertNear(column, boundsOf("短句").right, "右浮动盒的右边")
    }

    @Test
    fun 缩进的段落从缩进处开始() {
        show("[indent]短句[/indent]")
        assertNear(IndentWidth, boundsOf("短句").left, "缩进段落的左边")
    }
}
