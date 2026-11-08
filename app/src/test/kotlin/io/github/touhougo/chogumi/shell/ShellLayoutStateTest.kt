package io.github.touhougo.chogumi.shell

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.common.navigation.PageSheetRegistry
import io.github.touhougo.chogumi.core.ui.common.navigation.rememberAppNavigator
import io.github.touhougo.chogumi.core.ui.composables.PageModalSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 窗口宽度跨过 compact / medium（底栏 ↔ 导航轨）时，页面里的可保存状态（页内标签、开着的面板、面板里的状态）原样保留。
 *
 * 宽度变了 Activity 会重建：可保存状态按组合位置存取，导航形态一换、页面区在组合里换了位置，存下的状态就找不回来。
 * 这里用真实的导航状态与导航项装饰器（[rememberAppNavigator]），在页面里放一个计数和一个开着的页面面板，
 * 切换导航形态后模拟 Activity 重建（[StateRestorationTester]），也覆盖不重建、当场切换的情形。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ShellLayoutStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `底栏切到导航轨并重建后页面与面板状态都在`() = assertStateSurvives(
        from = TopLevelNavigationKind.BOTTOM_BAR,
        to = TopLevelNavigationKind.RAIL,
        recreate = true,
    )

    @Test
    fun `导航轨切到底栏并重建后页面与面板状态都在`() = assertStateSurvives(
        from = TopLevelNavigationKind.RAIL,
        to = TopLevelNavigationKind.BOTTOM_BAR,
        recreate = true,
    )

    @Test
    fun `不重建当场切换导航形态页面与面板状态都在`() = assertStateSurvives(
        from = TopLevelNavigationKind.BOTTOM_BAR,
        to = TopLevelNavigationKind.RAIL,
        recreate = false,
    )

    private fun assertStateSurvives(from: TopLevelNavigationKind, to: TopLevelNavigationKind, recreate: Boolean) {
        var navigation by mutableStateOf(from)
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            val navigator = rememberAppNavigator()
            val sheets = remember { PageSheetRegistry() }
            ShellLayout(navigation = navigation, glass = null, bottomBar = {}, rail = {}) {
                NavDisplay(
                    entries = navigator.entries({ key -> NavEntry(key) { PageWithSheet() } }, sheets),
                    onBack = navigator::goBack,
                )
            }
        }
        counter(PAGE_COUNTER).performClick().performClick()
        composeRule.onNodeWithTag(OPEN_SHEET).performClick()
        counter(SHEET_COUNTER).performClick()

        navigation = to
        if (recreate) restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        counter(PAGE_COUNTER).assertTextEquals("2")
        counter(SHEET_COUNTER).assertTextEquals("1")
    }

    /** 面板开着时页面对读屏隐藏（模态），计数要到未合并的语义树里找。 */
    private fun counter(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true)

    @Composable
    private fun PageWithSheet() {
        var sheetOpen by rememberSaveable { mutableStateOf(false) }
        Column {
            Counter(PAGE_COUNTER)
            Button(onClick = { sheetOpen = true }, modifier = Modifier.testTag(OPEN_SHEET)) { Text("open") }
        }
        if (sheetOpen) {
            PageModalSheet(onDismiss = { sheetOpen = false }, fullHeight = false) {
                Counter(SHEET_COUNTER)
            }
        }
    }

    @Composable
    private fun Counter(tag: String) {
        var count by rememberSaveable { mutableIntStateOf(0) }
        Button(onClick = { count++ }) { Text("$count", Modifier.testTag(tag)) }
    }

    private companion object {
        const val PAGE_COUNTER = "pageCounter"
        const val OPEN_SHEET = "openSheet"
        const val SHEET_COUNTER = "sheetCounter"
    }
}
