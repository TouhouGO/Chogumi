package io.github.touhougo.chogumi.shell

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.model.appFeatures
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import io.github.touhougo.chogumi.core.ui.common.navigation.TopLevelDestination
import io.github.touhougo.chogumi.core.ui.common.navigation.topLevelDestinationsFor
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 导航项的读屏语义：目的地名称念一遍（图标不另写描述，导航轨也不会念两遍），
 * 社区有未读且未选中时名称后面跟「N 条未读」，没有未读或已选中就不跟。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w800dp-h800dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopLevelNavigationSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val destinations = topLevelDestinationsFor(appFeatures(ExperienceMode.FULL, showPublicRatings = true, showCoverScores = true, showFriendMarks = true))
    private val communityIndex = destinations.indexOf(TopLevelDestination.COMMUNITY)

    private fun showBar(currentTab: Route, unread: Int) = composeRule.setContent {
        ChogumiTheme {
            TopLevelNavigationBar(currentTab = currentTab, destinations = destinations, isVisible = true, onSelect = {}, unreadCount = unread)
        }
    }

    private fun showRail(currentTab: Route, unread: Int) = composeRule.setContent {
        Rail(currentTab, unread)
    }

    @Composable
    private fun Rail(currentTab: Route, unread: Int) = ChogumiTheme {
        TopLevelNavigationRail(currentTab = currentTab, destinations = destinations, onSelect = {}, unreadCount = unread)
    }

    private fun community(tag: String): SemanticsNode = composeRule.onNodeWithTag("$tag$communityIndex").fetchSemanticsNode()

    /** 合并后的节点一共念出的文字与描述。 */
    private fun SemanticsNode.spoken(): List<String> =
        (config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())

    @Test
    fun `底栏：社区有未读时名称后跟条数，名称只念一遍`() {
        showBar(Route.Home, unread = 3)
        val node = community("TopLevelNavigationBarItem")
        assertEquals("3 条未读", node.config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(listOf("社区"), node.spoken())
    }

    @Test
    fun `底栏：没有未读时不加说明`() {
        showBar(Route.Home, unread = 0)
        assertNull(community("TopLevelNavigationBarItem").config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun `底栏：社区已选中时不加说明`() {
        showBar(Route.Community, unread = 3)
        assertNull(community("TopLevelNavigationBarItem").config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun `导航轨：社区有未读时名称后跟条数，名称只念一遍`() {
        showRail(Route.Home, unread = 3)
        val node = community("TopLevelNavigationRailItem")
        assertEquals("3 条未读", node.config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(listOf("社区"), node.spoken())
    }
}
