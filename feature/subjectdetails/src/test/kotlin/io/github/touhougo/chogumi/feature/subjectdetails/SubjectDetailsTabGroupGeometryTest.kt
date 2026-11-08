package io.github.touhougo.chogumi.feature.subjectdetails

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 条目详情的选项卡切换栏：选中格放得下文字才写，放不下就只留图标。
 * 窄屏、2 倍字号下选中格的内容要么是单独一个图标，要么装得下完整的文字，不能是被裁掉半截的文字。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubjectDetailsTabGroupGeometryTest {

    @get:Rule
    val compose = createComposeRule()

    private var selected by mutableStateOf(SubjectDetailsSection.OVERVIEW)

    private fun assertSelectedContentIsWhole(width: Dp, fontScale: Float) {
        val sections = SubjectDetailsSection.entries
        var textWidth = emptyMap<SubjectDetailsSection, Dp>()
        compose.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    val context = ApplicationProvider.getApplicationContext<Context>()
                    val measurer = rememberTextMeasurer()
                    val style = MaterialTheme.typography.labelLargeEmphasized
                    val density = LocalDensity.current
                    textWidth = sections.associateWith {
                        with(density) { measurer.measure(context.getString(it.titleRes), style, maxLines = 1, softWrap = false).size.width.toDp() }
                    }
                    Box(Modifier.width(width)) {
                        SubjectDetailsTabGroup(sections = sections, selected = selected, onSelect = {})
                    }
                }
            }
        }
        sections.forEach { section ->
            compose.runOnIdle { selected = section }
            compose.waitForIdle()
            val title = ApplicationProvider.getApplicationContext<Context>().getString(section.titleRes)
            // 选中格的内容是带完整标签的那一行；按钮本身也带，比它宽，取最窄的
            val matches = compose.onAllNodes(hasContentDescription(title), useUnmergedTree = true)
            val cell = matches.fetchSemanticsNodes().indices.minOf { matches[it].getUnclippedBoundsInRoot().width }
            val iconOnly = cell <= ICON + 0.5.dp
            val whole = cell >= ICON + ICON_GAP + textWidth.getValue(section) - 0.5.dp
            assertTrue("${width}、${fontScale}× 字号下选中「$title」的内容宽 $cell：既不是单独的图标，也没装下完整文字（${textWidth.getValue(section)}）", iconOnly || whole)
        }
    }

    @Test
    fun `320dp 默认字号 选中格不裁字`() = assertSelectedContentIsWhole(320.dp, 1f)

    @Test
    fun `320dp 两倍字号 选中格不裁字`() = assertSelectedContentIsWhole(320.dp, 2f)

    private companion object {
        val ICON = 20.dp
        val ICON_GAP = 6.dp
    }
}
