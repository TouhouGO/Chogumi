package io.github.touhougo.chogumi.core.ui.composables

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.BookUnit
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectionProgressLabelFitTest {
    @get:Rule val compose = createComposeRule()
    private var clicks = 0

    @Test
    fun `英文大字号进度数字完整显示且读屏保留完整句`() {
        setRow(width = 380.dp, fontScale = 2f, count = ProgressCount.Known(null, 438, 1155))
        assertProgressText(visual = "438 / 1155", spoken = "Watched 438 / 1155 eps", current = 438, total = 1155)
        assertMergedRow("Watched 438 / 1155 eps")
    }

    @Test
    fun `普通字号放得下时仍显示原完整句`() {
        setRow(width = 380.dp, fontScale = 1.1f, count = ProgressCount.Known(null, 1, 12))
        assertProgressText(visual = "Watched 1 / 12 eps", spoken = "Watched 1 / 12 eps", current = 1, total = 12)
        assertMergedRow("Watched 1 / 12 eps")
    }

    @Test
    fun `未知总数在窄宽度只显示当前数且不冒充总数`() {
        setRow(width = 380.dp, fontScale = 2f, count = ProgressCount.Known(null, 438, 0))
        assertProgressText(visual = "438", spoken = "Watched 438 eps", current = 438, total = 0)
    }

    @Test
    fun `书籍单位只留在完整读屏句`() {
        setRow(width = 380.dp, fontScale = 2f, count = ProgressCount.Known(BookUnit.VOL, 438, 1155))
        assertProgressText(visual = "438 / 1155", spoken = "Read 438 / 1155 vol.", current = 438, total = 1155)
    }

    @Test
    fun `从右到左布局仍完整显示数字并只念完整句`() {
        setRow(
            width = 380.dp,
            fontScale = 2f,
            count = ProgressCount.Known(null, 438, 1155),
            layoutDirection = LayoutDirection.Rtl,
        )
        assertProgressText(visual = "438 / 1155", spoken = "Watched 438 / 1155 eps", current = 438, total = 1155)
    }

    private fun setRow(
        width: Dp,
        fontScale: Float,
        count: ProgressCount.Known,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        compose.setContent {
            val context = LocalContext.current
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.ENGLISH) }
            val englishContext = context.createConfigurationContext(configuration)
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides englishContext,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                ChogumiTheme {
                    Box(Modifier.width(width)) {
                        CollectionListItem(
                            item = UserCollectionBo(
                                subjectId = 1,
                                subjectType = if (count.unit == null) SubjectType.ANIME else SubjectType.BOOK,
                                type = CollectionType.DOING,
                                subject = SubjectBo(
                                    id = 1,
                                    type = if (count.unit == null) SubjectType.ANIME else SubjectType.BOOK,
                                    name = "Example",
                                    nameCn = "",
                                    eps = count.total,
                                ),
                            ),
                            onClick = { clicks++ },
                            onEdit = {},
                            progressCount = count,
                        )
                    }
                }
            }
        }
    }

    private fun assertMergedRow(spoken: String) {
        val row = compose.onNode(hasClickAction() and hasText("Example"))
        val node = row.fetchSemanticsNode()
        val text = node.config[SemanticsProperties.Text].map { it.text }
        val descriptions = node.config[SemanticsProperties.ContentDescription]
        assertEquals("合并行只包含标题文字", listOf("Example"), text)
        assertEquals("合并行只包含一次完整进度句", listOf(spoken), descriptions)
        assertFalse("合并行不能被无障碍隐藏", node.config.contains(SemanticsProperties.HideFromAccessibility))
        row.performClick()
        assertEquals("合并行仍可点击", 1, clicks)
    }

    private fun assertProgressText(visual: String, spoken: String, current: Int, total: Int) {
        val progress = textLayouts().single { it.layoutInput.text.text.contains(current.toString()) }
        assertEquals(visual, progress.layoutInput.text.text)
        assertEquals(1, progress.lineCount)
        assertFalse("进度文字不得被省略或裁切", progress.hasVisualOverflow)
        assertEquals("读屏只保留一份完整句", 1, compose.onAllNodes(hasContentDescription(spoken)).fetchSemanticsNodes().size)
        if (visual != spoken) {
            assertTrue("读屏不应重复念短数字", compose.onAllNodes(hasContentDescription(visual)).fetchSemanticsNodes().isEmpty())
        }
        val indicators = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        if (total > 0) {
            assertEquals("进度条语义仍只有一份", 1, indicators.size)
            val range = indicators.single().config[SemanticsProperties.ProgressBarRangeInfo]
            val fraction = current.toFloat() / total
            assertEquals(fraction, range.current, 0.001f)
            assertEquals(ProgressBarRangeInfo(fraction, 0f..1f), range)
        } else {
            assertTrue("总数未知时没有伪进度条", indicators.isEmpty())
        }
    }

    private fun textLayouts(): List<TextLayoutResult> = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().flatMap { node ->
        mutableListOf<TextLayoutResult>().also { node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }
    }
}
