package io.github.touhougo.chogumi.shell

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.model.appFeatures
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import io.github.touhougo.chogumi.core.ui.common.navigation.TopLevelDestination
import io.github.touhougo.chogumi.core.ui.common.navigation.topLevelDestinationsFor
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 底栏内容区根节点选择器 */
private const val ContentTag = "TopLevelNavigationBar"

/** 底栏条目选择器前缀 */
private const val ItemTagPrefix = "TopLevelNavigationBarItem"

/**
 * 定制底栏契约测试：
 * destination 数量与顺序由 `topLevelDestinationsFor(features)` 决定、当前项唯一选中、
 * 点击未选中项导航恰好一次、根内容高度满足触控规范、每项语义触控区不小于 48dp、
 * isVisible=false 时滑出屏幕且不在无障碍树里、显隐不改变页面区的底部留白。
 */
@RunWith(AndroidJUnit4::class)
class TopLevelNavigationBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 可选中节点（tab 语义）匹配器 */
    private val selectableMatcher = SemanticsMatcher("isSelectable") {
        it.config.getOrNull(SemanticsProperties.Selected) != null
    }

    /** 处于选中态的节点匹配器 */
    private val selectedMatcher = SemanticsMatcher("isSelected") {
        it.config.getOrNull(SemanticsProperties.Selected) == true
    }

    private val context: Context by lazy {
        ApplicationProvider.getApplicationContext<Context>()
    }

    private val fullFeatures = appFeatures(ExperienceMode.FULL, showPublicRatings = true, showCoverScores = true, showFriendMarks = true)
    private val focusFeatures = appFeatures(ExperienceMode.FOCUS, showPublicRatings = true, showCoverScores = true, showFriendMarks = true)

    private fun setContentWithBar(
        currentTab: Route = Route.Home,
        isVisible: Boolean = true,
        destinations: List<TopLevelDestination> = topLevelDestinationsFor(fullFeatures),
        onSelect: (Route) -> Unit = {},
    ) {
        composeRule.setContent {
            ChogumiTheme {
                // 与外壳一样放在 Scaffold 的底栏槽：收起时才会移到屏幕底边以下
                Scaffold(
                    bottomBar = {
                        TopLevelNavigationBar(
                            currentTab = currentTab,
                            destinations = destinations,
                            isVisible = isVisible,
                            onSelect = onSelect,
                        )
                    },
                ) { padding -> Box(Modifier.padding(padding)) }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `isVisible为false时底栏滑出屏幕且不在无障碍树里`() {
        setContentWithBar(isVisible = false)
        composeRule.onNodeWithTag(ContentTag).assertIsNotDisplayed()
        composeRule.onNodeWithTag(ContentTag).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    }

    @Test
    fun `收起与显示时页面区的底部留白相同`() {
        var visible by mutableStateOf(true)
        var bottomPadding: Dp? = null
        composeRule.setContent {
            ChogumiTheme {
                Scaffold(
                    bottomBar = {
                        TopLevelNavigationBar(
                            currentTab = Route.Home,
                            destinations = topLevelDestinationsFor(fullFeatures),
                            isVisible = visible,
                            onSelect = {},
                        )
                    },
                ) { padding ->
                    bottomPadding = padding.calculateBottomPadding()
                    Box(Modifier)
                }
            }
        }
        composeRule.waitForIdle()
        val shown = checkNotNull(bottomPadding)
        assertTrue("底栏显示时页面区要让出它的高度，实测 $shown", shown.value > 0f)

        visible = false
        composeRule.waitForIdle()
        assertEquals("底栏收起时页面区的底部留白不变", shown, bottomPadding)

        visible = true
        composeRule.waitForIdle()
        assertEquals("底栏再显示时页面区的底部留白不变", shown, bottomPadding)
    }

    @Test
    fun `专注模式底栏渲染全部派生目的地`() {
        val destinations = topLevelDestinationsFor(focusFeatures)
        setContentWithBar(destinations = destinations)

        composeRule.onAllNodes(selectableMatcher).assertCountEquals(destinations.size)
        destinations.forEachIndexed { index, destination ->
            composeRule.onNodeWithTag("$ItemTagPrefix$index").assertExists()
            composeRule.onNodeWithText(context.getString(destination.title)).assertExists()
        }
    }

    @Test
    fun `完整模式底栏渲染全部派生目的地`() {
        val destinations = topLevelDestinationsFor(fullFeatures)
        setContentWithBar(destinations = destinations)

        composeRule.onAllNodes(selectableMatcher).assertCountEquals(destinations.size)
        destinations.forEachIndexed { index, destination ->
            composeRule.onNodeWithTag("$ItemTagPrefix$index").assertExists()
            composeRule.onNodeWithText(context.getString(destination.title)).assertExists()
        }
    }

    @Test
    fun `专注模式当前项唯一选中`() {
        val destinations = topLevelDestinationsFor(focusFeatures)
        val targetRoute = Route.Progress
        val targetIndex = destinations.indexOfFirst { it.route == targetRoute }
        setContentWithBar(currentTab = targetRoute, destinations = destinations)

        composeRule.onAllNodes(selectedMatcher).assertCountEquals(1)
        composeRule.onNodeWithTag("$ItemTagPrefix$targetIndex").assertIsSelected()
    }

    @Test
    fun `完整模式当前项唯一选中`() {
        val destinations = topLevelDestinationsFor(fullFeatures)
        val targetRoute = Route.Progress
        val targetIndex = destinations.indexOfFirst { it.route == targetRoute }
        setContentWithBar(currentTab = targetRoute, destinations = destinations)

        composeRule.onAllNodes(selectedMatcher).assertCountEquals(1)
        composeRule.onNodeWithTag("$ItemTagPrefix$targetIndex").assertIsSelected()
    }

    @Test
    fun `点击未选中项导航一次到该目的地`() {
        val destinations = topLevelDestinationsFor(fullFeatures)
        val routes = mutableListOf<Route>()
        setContentWithBar(destinations = destinations, onSelect = { routes += it })

        composeRule.onNodeWithTag("${ItemTagPrefix}1").performClick()
        assertEquals(listOf(destinations[1].route), routes)
    }

    @Test
    fun `点击当前选中项也交给导航层`() {
        val routes = mutableListOf<Route>()
        setContentWithBar(currentTab = Route.Home, onSelect = { routes += it })

        composeRule.onNodeWithTag("${ItemTagPrefix}0").performClick()
        assertEquals("再点当前项由 AppNavigator.selectTab 决定做什么", listOf<Route>(Route.Home), routes)
    }

    @Test
    fun `专注模式每项触控区不小于48dp`() {
        val destinations = topLevelDestinationsFor(focusFeatures)
        assertTouchTargetCompliance(destinations)
    }

    @Test
    fun `完整模式每项触控区不小于48dp`() {
        val destinations = topLevelDestinationsFor(fullFeatures)
        assertTouchTargetCompliance(destinations)
    }

    private fun assertTouchTargetCompliance(destinations: List<TopLevelDestination>) {
        setContentWithBar(destinations = destinations)
        val minPx = with(composeRule.density) { ChogumiDimens.MinTouchTarget.toPx() }
        destinations.indices.forEach { index ->
            val item = composeRule.onNodeWithTag("$ItemTagPrefix$index")
            val touchBounds = item.fetchSemanticsNode().touchBoundsInRoot
            assertTrue(
                "条目 $index 语义触控区 ${touchBounds.width}x${touchBounds.height}px 应不小于 ${minPx}px",
                touchBounds.width >= minPx && touchBounds.height >= minPx,
            )
            val layoutBounds = item.getUnclippedBoundsInRoot()
            assertTrue(
                "条目 $index 布局尺寸 ${layoutBounds.width}x${layoutBounds.height} 应不小于 ${ChogumiDimens.MinTouchTarget}",
                layoutBounds.width >= ChogumiDimens.MinTouchTarget &&
                    layoutBounds.height >= ChogumiDimens.MinTouchTarget,
            )
        }
    }
}
