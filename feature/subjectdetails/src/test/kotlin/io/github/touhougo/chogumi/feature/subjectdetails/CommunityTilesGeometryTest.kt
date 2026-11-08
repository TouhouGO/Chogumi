package io.github.touhougo.chogumi.feature.subjectdetails

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 社区页头的三块入口在 380dp、2 倍字号下：放不下并排就三块一起改竖排，竖排再放不下就折行，标题和数量一个字都不被截。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w380dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommunityTilesGeometryTest {

    @get:Rule
    val compose = createComposeRule()

    private fun assertTilesIntact(fontScale: Float, overview: CommunityOverview) {
        compose.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Box(Modifier.width(380.dp)) {
                        CommunityEntryTiles(
                            overview = overview,
                            loading = false,
                            onOpenReviews = {},
                            onOpenTopics = {},
                            onOpenIndexes = {},
                        )
                    }
                }
            }
        }
        val tiles = compose.onAllNodes(hasClickAction())
        val tileCount = tiles.fetchSemanticsNodes().size
        assertEquals("三块入口", 3, tileCount)

        // 竖排时标题在图标下面（离块顶 10 + 图标 20 + 间距 4 以上），并排时标题与图标同一高度（离块顶不过一两行字）：三块必须一致
        val titleOffsets = listOf(R.string.community_reviews, R.string.community_board, R.string.community_indexes).mapIndexed { index, titleRes ->
            val title = ApplicationProvider.getApplicationContext<Context>().getString(titleRes)
            val tile = tiles[index].getUnclippedBoundsInRoot()
            compose.onNode(hasText(title), useUnmergedTree = true).getUnclippedBoundsInRoot().top - tile.top
        }
        val stacked = titleOffsets.map { it >= STACKED_TITLE_OFFSET }
        assertTrue("${fontScale}× 字号三块不是一起竖排或一起并排：标题离块顶 $titleOffsets", stacked.distinct().size == 1)

        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        val nodes = texts.fetchSemanticsNodes()
        assertTrue("有文字可查", nodes.isNotEmpty())
        nodes.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            layouts.forEach { layout ->
                assertFalse("${fontScale}× 字号下「$text」被省略号截断", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            }
        }
    }

    @Test
    fun `默认字号三块入口的字不被切`() = assertTilesIntact(1f, CommunityOverview(reviews = 1234, topics = 5678, indexes = 999))

    @Test
    fun `两倍字号三块入口的字不被切`() = assertTilesIntact(2f, CommunityOverview(reviews = 1234, topics = 5678, indexes = 999))

    private companion object {
        /** 竖排时标题至少离块顶这么远：上内边距 10 + 图标 20 + 图标与标题的间距 4。 */
        val STACKED_TITLE_OFFSET = 34.dp
    }
}
