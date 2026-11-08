package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.CountBadgedIcon

/** 顶级目的地：手机底栏与平板导航轨共用这一组，顺序即显示顺序；第一个是首页（[AppNavigator] 的起点）。 */
enum class TopLevelDestination(
    val route: Route,
    @param:StringRes val title: Int,
    @param:DrawableRes private val icon: Int,
    @param:DrawableRes private val selectedIcon: Int,
) {
    HOME(Route.Home, R.string.home, R.drawable.home_24, R.drawable.home_filled_24),
    EXPLORE(Route.Explore, R.string.explore, R.drawable.explore_24, R.drawable.explore_filled_24),
    PROGRESS(Route.Progress, R.string.progress, R.drawable.list_alt_24, R.drawable.list_alt_filled_24),
    COMMUNITY(Route.Community, R.string.community, R.drawable.forum_24, R.drawable.forum_filled_24),
    PROFILE(Route.Profile, R.string.profile, R.drawable.person_24, R.drawable.person_filled_24),
    ;

    /**
     * 底栏与导航轨共用的图标（唯一实现）：只有 [COMMUNITY] 在有未读（通知与短信）、且没有选中时叠数字 Badge（[badgeCountOf]）。
     * [unreadCount] 来自全 App 唯一的未读数流。
     */
    @Composable
    fun Icon(selected: Boolean, unreadCount: Int) {
        // 名字与未读条数由导航项自己念（[unreadSemantics]）；图标与徽标数字不再各念一遍（栏已清掉图标槽位的语义，轨道要自己清）
        CountBadgedIcon(badgeCountOf(selected, unreadCount), Modifier.clearAndSetSemantics {}) {
            Icon(painterResource(if (selected) selectedIcon else icon), contentDescription = null)
        }
    }

    /**
     * 导航项自己的读屏附加说明：图标上有数字徽标（[badgeCountOf]）时念「N 条未读」，跟在目的地名称后面；没有徽标不加。
     * 底栏与导航轨共用，徽标的数字栏会清掉，所以放在导航项的语义上。
     */
    @Composable
    fun Modifier.unreadSemantics(selected: Boolean, unreadCount: Int): Modifier {
        val count = badgeCountOf(selected, unreadCount)
        if (count <= 0) return this
        val description = pluralStringResource(R.plurals.nav_unread_count, count, count)
        return semantics { stateDescription = description }
    }

    /**
     * 这个目的地图标上的数字（唯一判定点）：只有社区有，选中后不再画（M3：用户已经在那里，页头铃铛的圆点仍提示未读）。
     */
    fun badgeCountOf(selected: Boolean, unreadCount: Int): Int = if (this == COMMUNITY && !selected) unreadCount else 0

    companion object {
        /**
         * 全部顶级路由：导航状态按它建栈，与体验模式无关——
         * 若随模式收缩，在设置页拨开关会把设置页自己从栈里抹掉。
         */
        val routes: List<Route> = entries.map { it.route }
    }
}

/**
 * 功能可见性 → 显示的顶级目的地（唯一判定点）。
 *
 * [AppFeatures.community] 为 false（专注模式）时去掉「社区」：5 个变 4 个
 * （M3 `navigation.md:14`：导航栏承载 3–5 个同等重要的目的地）。界面只遍历本函数的结果，不得再叠一层筛除。
 * 本列表只管显示，不决定导航状态（见 [TopLevelDestination.routes]）。
 */
fun topLevelDestinationsFor(features: AppFeatures): List<TopLevelDestination> =
    if (features.community) TopLevelDestination.entries
    else TopLevelDestination.entries.filter { it != TopLevelDestination.COMMUNITY }
