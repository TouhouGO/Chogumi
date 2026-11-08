package io.github.touhougo.chogumi.feature.monodetails

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import io.github.touhougo.chogumi.core.model.MonoWorkBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.feature.monodetails.ui.WorkMeta
import io.github.touhougo.chogumi.feature.monodetails.ui.WorkTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 职位小牌与作品行的标签行：高度装得下 2× 字号的字，职位多时换行而不越出行宽（容器可滚动，高度无界）。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkTagGeometryTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 小标的高度装得下2倍字号的字() {
        compose.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                        Box(Modifier.testTag("tag")) { WorkTag("主角", primary = true) }
                    }
                }
            }
        }
        val tag = compose.onNodeWithTag("tag").getUnclippedBoundsInRoot()
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText("主角"), useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
        val natural = with(compose.density) { layout.single().multiParagraph.height.toDp() }
        // 被夹进更矮约束里的文字，量出来的框看着没事，字却被切：比的是文字自然高度
        assertTrue("文字自然高度 $natural 超出小标 ${tag.height}", natural <= tag.height)
    }

    @Test
    fun 职位很多时标签换行且不越出行宽() {
        val positions = listOf("导演", "脚本", "分镜", "演出", "原画", "作画监督", "总作画监督", "美术监督")
        val work = MonoWorkBo(id = 1, name = "w", nameCn = "作品", image = null, subjectType = SubjectType.ANIME, positions = positions)
        compose.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    Column(Modifier.width(RowWidth).testTag("meta").verticalScroll(rememberScrollState())) { WorkMeta(work) }
                }
            }
        }
        val row = compose.onNodeWithTag("meta").getUnclippedBoundsInRoot()
        val tops = positions.map { position ->
            val bounds = compose.onNode(hasText(position), useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("「$position」右缘 ${bounds.right} 越出行宽 ${row.right}", bounds.right <= row.right)
            bounds.top
        }
        assertTrue("八个职位排在 ${tops.distinct().size} 行，没有换行", tops.distinct().size > 1)
    }

    private companion object {
        val RowWidth = 240.dp
    }
}
