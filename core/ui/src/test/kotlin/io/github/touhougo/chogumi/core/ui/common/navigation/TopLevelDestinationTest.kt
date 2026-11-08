package io.github.touhougo.chogumi.core.ui.common.navigation

import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.model.appFeatures
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 顶级目的地的**两种模式全表断言**（`topLevelDestinationsFor` 是唯一判定点）。
 *
 * 断言的是**列表本身**（顺序、个数），不是某个调用方渲染出来的样子：底栏与导航轨都从这一个函数取值。
 */
class TopLevelDestinationTest {

    private val full = appFeatures(ExperienceMode.FULL, showPublicRatings = true, showCoverScores = true, showFriendMarks = true)
    private val focus = appFeatures(ExperienceMode.FOCUS, showPublicRatings = true, showCoverScores = true, showFriendMarks = true)

    @Test
    fun `完整模式五个目的地按底栏顺序`() {
        assertEquals(
            listOf(
                TopLevelDestination.HOME,
                TopLevelDestination.EXPLORE,
                TopLevelDestination.PROGRESS,
                TopLevelDestination.COMMUNITY,
                TopLevelDestination.PROFILE,
            ),
            topLevelDestinationsFor(full),
        )
    }

    @Test
    fun `专注模式只去掉社区_其余目的地与顺序不动`() {
        assertEquals(
            listOf(
                TopLevelDestination.HOME,
                TopLevelDestination.EXPLORE,
                TopLevelDestination.PROGRESS,
                TopLevelDestination.PROFILE,
            ),
            topLevelDestinationsFor(focus),
        )
    }

    @Test
    fun `导航状态固定用全部顶级路由_顺序即显示顺序_首页在前`() {
        // 导航状态不随模式重建（否则在设置页拨开关会把设置页自己从栈里抹掉），
        // 故顶级路由恒为全部目的地；这条守的是"别把它改成随模式收缩"。
        assertEquals(
            listOf(Route.Home, Route.Explore, Route.Progress, Route.Community, Route.Profile),
            TopLevelDestination.routes,
        )
    }

    @Test
    fun `未读数只画在没选中的社区图标上`() {
        assertEquals(5, TopLevelDestination.COMMUNITY.badgeCountOf(selected = false, unreadCount = 5))
        assertEquals(0, TopLevelDestination.COMMUNITY.badgeCountOf(selected = true, unreadCount = 5))
        TopLevelDestination.entries.filter { it != TopLevelDestination.COMMUNITY }.forEach {
            assertEquals(0, it.badgeCountOf(selected = false, unreadCount = 5))
        }
    }
}
