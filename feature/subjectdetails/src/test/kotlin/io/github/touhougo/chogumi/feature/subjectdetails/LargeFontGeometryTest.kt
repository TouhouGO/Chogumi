package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.RelatedPersonBo
import io.github.touhougo.chogumi.core.model.StaffPositionRow
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.HeaderChip
import io.github.touhougo.chogumi.core.ui.composables.numberColumnWidth
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 定高的小标与定宽的曲序列，在 2× 字号、可滚动（高度无界）的容器里仍装得下自己的字。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeFontGeometryTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 头部小标的高度装得下2倍字号的字() {
        setLarge { HeaderChip(icon = R.drawable.info_24, onClick = null, modifier = Modifier.testTag(CHIP)) { Text("信息", Modifier.testTag(TEXT)) } }
        assertFitsIn(CHIP, onTag(TEXT))
    }

    @Test
    fun 排名小标的高度装得下2倍字号的字() {
        setLarge { Box(Modifier.testTag(CHIP)) { RankChip(12) } }
        assertFitsIn(CHIP, compose.onNode(hasText("#12"), useUnmergedTree = true))
    }

    @Test
    fun 曲序列放得下两位数的曲序() {
        setLarge {
            TrackRow(
                index = 0,
                count = 1,
                numberWidth = numberColumnWidth(2, MaterialTheme.typography.labelLarge, 28.dp),
                number = { numberModifier -> Text("10", numberModifier.testTag(TEXT)) },
                title = { Text("曲名") },
            )
        }
        val number = compose.onNodeWithTag(TEXT, useUnmergedTree = true).getUnclippedBoundsInRoot()
        // 2× 字号下一行字高约 42dp；折成两行则翻倍
        assertTrue("曲序 $number 没有折行", number.height < 60.dp)
    }

    @Test
    fun 职位名与第一排人名的基线对齐() {
        setLarge {
            StaffRow(
                row = StaffPositionRow("监督", listOf(RelatedPersonBo(id = 1, name = "山田", type = 1))),
                index = 0,
                count = 1,
                open = false,
                onTogglePosition = {},
                onPersonClick = {},
            )
        }
        fun baselineY(text: String): Float {
            val node = compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            return node.boundsInRoot.top + layouts.first().firstBaseline
        }
        assertEquals("人名基线 ${baselineY("山田")}，职位名基线 ${baselineY("监督")}", baselineY("山田"), baselineY("监督"), 0.5f)
    }

    @Test
    fun 职位名折行时整行跟着长高_不切字() {
        setLarge {
            Box(Modifier.testTag(CHIP)) {
                StaffRow(
                    row = StaffPositionRow("系列构成设定", listOf(RelatedPersonBo(id = 1, name = "山田", type = 1))),
                    index = 0,
                    count = 1,
                    open = false,
                    onTogglePosition = {},
                    onPersonClick = {},
                )
            }
        }
        assertFitsIn(CHIP, compose.onNode(hasText("系列构成设定"), useUnmergedTree = true))
    }

    private fun onTag(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    /** 文字排版后的自然高度不超过容器高度：被夹进更矮的约束里的文字，量出来的框看着没事，字却被切。 */
    private fun assertFitsIn(container: String, text: SemanticsNodeInteraction) {
        val box = compose.onNodeWithTag(container).getUnclippedBoundsInRoot()
        val layout = mutableListOf<TextLayoutResult>()
        text.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
        val natural = with(compose.density) { layout.single().multiParagraph.height.toDp() }
        assertTrue("文字自然高度 $natural 超出容器 ${box.height}", natural <= box.height)
    }

    private fun setLarge(content: @Composable () -> Unit) {
        compose.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) { content() }
                }
            }
        }
    }

    private companion object {
        const val CHIP = "chip"
        const val TEXT = "text"
    }
}
