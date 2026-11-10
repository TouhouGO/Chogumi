package io.github.touhougo.chogumi.core.ui.composables

import android.content.res.Configuration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.rules.ExternalResource
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionProgressLabelFitTest {
    /** 整模块跑时，前面的测试早已让 UiAutomation 连上、Activity 才打开：先连上，单跑与整模块跑是同一个条件。 */
    @get:Rule(order = 0)
    val automationFirst = object : ExternalResource() {
        override fun before() {
            checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation)
        }
    }

    @get:Rule(order = 1)
    val compose = createComposeRule()
    private var clicks = 0

    @Test
    fun `真机字体度量下英文大字号退为完整数字且读屏只念完整句`() {
        compose.setContent {
            val context = LocalContext.current
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.ENGLISH) }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context.createConfigurationContext(configuration),
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, 2f),
            ) {
                ChogumiTheme {
                    Box(Modifier.width(380.dp)) {
                        CollectionListItem(
                            item = UserCollectionBo(
                                subjectId = 1,
                                subjectType = SubjectType.ANIME,
                                type = CollectionType.DOING,
                                subject = SubjectBo(id = 1, type = SubjectType.ANIME, name = "Example", nameCn = "", eps = 1155),
                            ),
                            onClick = { clicks++ },
                            onEdit = {},
                            progressCount = ProgressCount.Known(null, 438, 1155),
                        )
                    }
                }
            }
        }
        val results = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().flatMap { node ->
            mutableListOf<TextLayoutResult>().also { node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }
        }
        val progress = results.single { it.layoutInput.text.text.contains("438") }
        assertEquals("438 / 1155", progress.layoutInput.text.text)
        assertEquals(1, progress.lineCount)
        assertFalse("进度数字不得省略或裁切", progress.hasVisualOverflow)
        assertEquals(1, compose.onAllNodes(hasContentDescription("Watched 438 / 1155 eps")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasContentDescription("438 / 1155")).fetchSemanticsNodes().size)
        val row = compose.onNode(hasClickAction() and hasText("Example"))
        val config = row.fetchSemanticsNode().config
        assertEquals(listOf("Example"), config[SemanticsProperties.Text].map { it.text })
        assertEquals(listOf("Watched 438 / 1155 eps"), config[SemanticsProperties.ContentDescription])
        assertFalse(config.contains(SemanticsProperties.HideFromAccessibility))
        row.performClick()
        assertEquals(1, clicks)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(500, 5_000)
        val root = checkNotNull(automation.rootInActiveWindow) { "Android 无障碍窗口不可用" }
        val nodes = descendants(root).filter { it.isVisibleToUser && it.isImportantForAccessibility }
        val accessibleRows = nodes.filter { it.contentDescription?.toString()?.contains("Watched 438 / 1155 eps") == true }
        assertEquals("Android 无障碍树中完整句只出现一次", 1, accessibleRows.size)
        assertEquals("Watched 438 / 1155 eps", accessibleRows.single().contentDescription?.toString())
        assertEquals("标题在 Android 无障碍树中", 1, nodes.count { it.text?.toString() == "Example" })
        assertEquals("短数字不重复进入 Android 无障碍树", 0, nodes.count { it.text?.toString() == "438 / 1155" })
        assertEquals("进度条仍可访问", 1, nodes.count { it.className?.toString() == "android.widget.ProgressBar" })
        val clickableRow = nodes.single { it.isClickable }
        assertTrue("整行可点击", clickableRow.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitForIdle()
        assertEquals(2, clicks)
    }

    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(node) + (0 until node.childCount).mapNotNull { node.getChild(it) }.flatMap { descendants(it) }
}
