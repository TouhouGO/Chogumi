package io.github.touhougo.chogumi.core.ui.composables

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.navigation.PageSheetRegistry
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import io.github.touhougo.chogumi.core.ui.common.navigation.PageTransitionKind
import io.github.touhougo.chogumi.core.ui.common.navigation.chogumiTransition
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import io.github.touhougo.chogumi.core.ui.common.navigation.rememberPageSheetNavEntryDecorator
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 页面面板收起途中跳去子页、再返回：面板不能自己又弹出来。
 *
 * 面板一开始收起，下面的页面就能点（不等滑完），所以「点遮罩关面板，紧接着点页头的另一个入口进子页」在设备上很常见
 * （社区页：铃铛连点两下，第二下落在遮罩上，再点「我的帖子」）。所在页这时离开组合，面板没滑完、关闭回调没来，
 * 调用处的「开着」还是 true；返回时面板若照存下的状态恢复，就从底下又滑上来了。
 * 转场与 App 相同（前进后退）；时钟手动推进，收起只走两帧就跳走，每一步都是确定的帧。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PageModalSheetReturnTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val backStack = mutableStateListOf<NavKey>(Route.Community)
    private val registry = PageSheetRegistry()
    private var dismissed by mutableIntStateOf(0)
    private var sheetOpen = false
    private val closeLabel by lazy { ApplicationProvider.getApplicationContext<Context>().getString(R.string.close) }

    @Test
    fun 收起途中进子页_返回后面板不再弹出() {
        setContent()
        composeRule.onNodeWithTag(OPEN).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SHEET).assertExists()

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription(closeLabel).performSemanticsAction(SemanticsActions.OnClick)
        repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.onNodeWithTag(SHEET).assertExists()
        // 收起中下面的页面已经能点：进子页
        composeRule.onNodeWithTag(SUBPAGE).performClick()
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        backStack.removeAt(backStack.lastIndex)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SHEET).assertDoesNotExist()
        assertEquals("收起的那个面板关闭回调恰好一次", 1, dismissed)
        assertFalse("调用处的「开着」已复位", sheetOpen)
        assertFalse("页面不再算开着面板（底栏据此回来）", registry.anyOpen)
    }

    private fun setContent() {
        composeRule.setContent {
            ChogumiTheme {
                val page = LocalAppMotion.current.page
                NavDisplay(
                    backStack = backStack,
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberPageSheetNavEntryDecorator(registry),
                    ),
                    onBack = { backStack.removeAt(backStack.lastIndex) },
                    // 与 App 同一套转场：前进后退，面板跟着所在页一起离开组合
                    transitionSpec = { chogumiTransition(PageTransitionKind.Forward, page, 1) },
                    popTransitionSpec = { chogumiTransition(PageTransitionKind.Back, page, 1) },
                    entryProvider = { key ->
                        NavEntry(key) {
                            if (key == Route.Community) PageWithSheet() else Text("subpage")
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun PageWithSheet() {
        var open by rememberSaveable { mutableStateOf(false) }
        sheetOpen = open
        Column(Modifier.fillMaxSize()) {
            Button(onClick = { open = true }, modifier = Modifier.testTag(OPEN)) { Text("open") }
            Button(onClick = { backStack.add(Route.MyTopics) }, modifier = Modifier.testTag(SUBPAGE)) { Text("subpage") }
        }
        if (open) {
            PageModalSheet(
                onDismiss = {
                    dismissed++
                    open = false
                },
                fullHeight = true,
            ) { Text("sheet", Modifier.testTag(SHEET)) }
        }
    }

    private companion object {
        const val OPEN = "open"
        const val SUBPAGE = "subpage"
        const val SHEET = "sheet"
    }
}
