package io.github.touhougo.chogumi.core.reader

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 帖子里宫格中的图片可点：读屏要念出「查看图片」（单张图的加载成功态用同一个标签，Robolectric 里没有网络，图总是加载失败，测不到）。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w360dp-h640dp")
class BBCodeImageSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val opensImage = SemanticsMatcher("点击标签是「查看图片」") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "查看图片"
    }

    @Test
    fun 宫格里的每张图片带点击标签() {
        composeRule.setContent {
            ChogumiTheme { BBCodeText(BBCodeParser.parse("[img]https://a/1.png[/img][img]https://a/2.png[/img]")) }
        }

        composeRule.onAllNodes(opensImage).assertCountEquals(2)
    }
}
