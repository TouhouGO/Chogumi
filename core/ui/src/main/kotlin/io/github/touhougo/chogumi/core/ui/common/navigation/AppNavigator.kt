package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.result.rememberResultEventBusNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.navigation3.scene.Scene
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.savedstate.compose.serialization.serializers.MutableStateSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 全 App 唯一的导航状态与操作：每个顶级目的地（[TopLevelDestination]）一条返回栈，外加「当前 tab」。
 *
 * - [navigate]：顶级路由只切 tab（各 tab 的栈原样保留），其余路由压进当前 tab 的栈；
 * - [selectTab]：点底栏 / 导航轨的一项。别的 tab 切过去（栈原样）；再点当前 tab，不在第一层就回第一层，
 *   已在第一层就让这一层的列表滚回顶部（[LocalTabReselect]）；
 * - [goBack]：栈里还有页面就弹出；已在 tab 根部则回首页（首页根部的返回交给系统，退出 App）。
 *
 * 显示的只有当前 tab 的栈（[entries]）。其他 tab 根部的返回是切回首页 tab，不是退回上一页：
 * 拖动时没有翻页预览，松手才走切 tab 的淡出淡入（[entries] 里的返回处理）。
 */
@Stable
class AppNavigator internal constructor(
    private val home: Route,
    tab: MutableState<Route>,
    private val stacks: Map<Route, NavBackStack<NavKey>>,
) {
    /** 当前 tab：底栏 / 导航轨的选中项。 */
    var currentTab: Route by tab
        private set

    /** 停在当前 tab 的根部（不是压上去的子页）。 */
    val atTabRoot: Boolean get() = currentStack.size == 1

    private val currentStack: NavBackStack<NavKey> get() = stacks.getValue(currentTab)

    /** 「从哪张封面长出去」的起点登记（见 [PageExpandRegistry]）。 */
    val expand = PageExpandRegistry()

    /** 各 tab 第一层的「再点一次」：一次点击一个事件，没人收（页面还没起来）就丢掉，不攒到以后。 */
    private val reselects = stacks.keys.associateWith { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }

    fun navigate(route: Route) {
        if (route in stacks) {
            currentTab = route
        } else {
            expand.claim(currentTab, route, from = currentStack.last() as? Route)
            currentStack.add(route)
        }
    }

    /** 点了底栏 / 导航轨的 [tab]（手机底栏与平板导航轨共用这一个入口）。 */
    fun selectTab(tab: Route) {
        require(tab in stacks) { "不是顶级目的地：$tab" }
        when {
            tab != currentTab -> navigate(tab)
            !atTabRoot -> {
                // 一次清掉压上去的全部页面：只有一次返回转场，从最上面那页直接回第一层
                currentStack.subList(1, currentStack.size).clear()
            }
            else -> reselects.getValue(tab).tryEmit(Unit)
        }
    }

    fun goBack() {
        if (atTabRoot) currentTab = home else currentStack.removeAt(currentStack.lastIndex)
    }

    /**
     * 一次页面切换的种类，全 App 唯一的判断，只看切换的两个场景：栈底不同（两页属于不同的 tab）是切 tab；
     * 同一条栈里，进的时候看新页、返回的时候看离开的那页，从封面长出来的页走容器转换，其余是前进 / 后退。
     */
    fun transitionKind(back: Boolean, entering: Scene<NavKey>, leaving: Scene<NavKey>): PageTransitionKind = when {
        bottomOf(entering) != bottomOf(leaving) -> PageTransitionKind.TopLevel
        expand.expandsPage(currentTab, (if (back) leaving else entering).entries.last().contentKey) -> PageTransitionKind.Container
        back -> PageTransitionKind.Back
        else -> PageTransitionKind.Forward
    }

    /** [tab] 的栈里内容键为 [contentKey] 的这一页，是压在第一层上面的最上面一页（返回会退回它下面那页）。 */
    private fun isPushedTop(tab: Route, contentKey: Any): Boolean {
        val stack = stacks.getValue(tab)
        return stack.size > 1 && expand.routeOf(tab, contentKey) == stack.last()
    }

    /** 场景所在那条栈的栈底（tab 的第一层）。 */
    private fun bottomOf(scene: Scene<NavKey>): Any = (scene.previousEntries.firstOrNull() ?: scene.entries.first()).contentKey

    /** [tab] 第一层收到的「再点一次」（经 [LocalTabReselect] 交给页面）。 */
    internal fun tabReselects(tab: Route): Flow<Unit> = reselects.getValue(tab)

    /**
     * 交给 `NavDisplay` 的页面列表。每条栈各自一套装饰器（不透明的页面底色、可保存状态、ViewModel、结果总线、页面面板层），
     * 不在显示中的栈也照常保留。[sheets] 记哪些页面开着面板（手机底栏据此让位，见 [PageSheetRegistry]）。
     */
    @Composable
    fun entries(entryProvider: (NavKey) -> NavEntry<NavKey>, sheets: PageSheetRegistry): List<NavEntry<NavKey>> {
        val decorated = stacks.mapValues { (tab, stack) ->
            rememberDecoratedNavEntries(
                backStack = stack,
                entryDecorators = listOf(
                    // 最外层：容器转换里长大的是整张页，共享边界要包住页面底色
                    // 其次：整张页（页面与面板层）垫在不透明的底色上，预测性返回时下面的上一页透不上来
                    rememberPageExpandNavEntryDecorator(tab),
                    rememberPageSurfaceNavEntryDecorator(),
                    // 还在进场的页先接住返回，按普通返回从此刻的位置退回去
                    rememberEnteringBackNavEntryDecorator(isTopPage = { contentKey -> isPushedTop(tab, contentKey) }, goBack = ::goBack),
                    // 「再点当前 tab」只在这一 tab 停在第一层时发出，那时栈里只有第一层，收得到的只有它
                    rememberTabReselectNavEntryDecorator(tabReselects(tab)),
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                    rememberResultEventBusNavEntryDecorator(),
                    // 页面面板层排在可保存状态之后：面板里的可保存状态随页面一起保存
                    rememberPageSheetNavEntryDecorator(sheets),
                ),
                entryProvider = { key ->
                    entryProvider(key).also { entry -> (key as? Route)?.let { expand.register(tab, entry.contentKey, it) } }
                },
            )
        }
        // 其他 tab 根部：显示的栈只有一页，NavDisplay 不接返回，由这里接住，松手后切回首页
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = currentTab != home && atTabRoot,
            onBackCompleted = ::goBack,
        )
        return decorated.getValue(currentTab)
    }
}

/** 记住导航状态：当前 tab 与各 tab 的返回栈都跨配置变更与进程死亡保存。 */
@Composable
fun rememberAppNavigator(): AppNavigator {
    val home = TopLevelDestination.HOME.route
    val tab = rememberSerializable(serializer = MutableStateSerializer(NavKeySerializer<Route>())) {
        mutableStateOf(home)
    }
    val stacks = TopLevelDestination.routes.associateWith { rememberNavBackStack(it) }
    return remember { AppNavigator(home, tab, stacks) }
}
