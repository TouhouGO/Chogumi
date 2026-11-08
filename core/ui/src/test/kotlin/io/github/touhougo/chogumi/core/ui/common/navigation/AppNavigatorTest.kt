package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 多返回栈的进退规则：顶级路由只切 tab，其余压栈；tab 根部返回回首页；再点当前 tab 回第一层、已在第一层就回顶。 */
class AppNavigatorTest {

    private val stacks = TopLevelDestination.routes.associateWith { NavBackStack<NavKey>(it) }
    private val navigator = AppNavigator(Route.Home, mutableStateOf(Route.Home), stacks)

    @Test
    fun `子页压进当前tab的栈_返回弹出`() {
        navigator.navigate(Route.SubjectDetails(1))
        assertFalse(navigator.atTabRoot)
        assertEquals(listOf<NavKey>(Route.Home, Route.SubjectDetails(1)), stacks.getValue(Route.Home).toList())

        navigator.goBack()
        assertTrue(navigator.atTabRoot)
        assertEquals(listOf<NavKey>(Route.Home), stacks.getValue(Route.Home).toList())
    }

    @Test
    fun `切tab保留各自的栈`() {
        navigator.navigate(Route.Explore)
        navigator.navigate(Route.Search())
        navigator.navigate(Route.Profile)
        assertEquals(Route.Profile, navigator.currentTab)
        assertTrue(navigator.atTabRoot)

        navigator.navigate(Route.Explore)
        assertEquals(listOf<NavKey>(Route.Explore, Route.Search()), stacks.getValue(Route.Explore).toList())
        assertFalse(navigator.atTabRoot)
    }

    @Test
    fun `其他tab根部返回回首页_首页栈原样`() {
        navigator.navigate(Route.SubjectDetails(1))
        navigator.navigate(Route.Community)
        navigator.goBack()
        assertEquals(Route.Home, navigator.currentTab)
        assertEquals(listOf<NavKey>(Route.Home, Route.SubjectDetails(1)), stacks.getValue(Route.Home).toList())
    }

    @Test
    fun `再点当前tab_不在第一层时一次回到第一层_别的tab不动`() {
        navigator.navigate(Route.SubjectDetails(1))
        navigator.navigate(Route.Explore)
        navigator.navigate(Route.Search())
        navigator.navigate(Route.SubjectDetails(2))

        navigator.selectTab(Route.Explore)

        assertEquals(Route.Explore, navigator.currentTab)
        assertEquals(listOf<NavKey>(Route.Explore), stacks.getValue(Route.Explore).toList())
        assertEquals(listOf<NavKey>(Route.Home, Route.SubjectDetails(1)), stacks.getValue(Route.Home).toList())
    }

    @Test
    fun `再点当前tab_已在第一层时发出回顶事件_栈不变`() = runBlocking {
        navigator.navigate(Route.Community)
        val reselected = async(start = CoroutineStart.UNDISPATCHED) { navigator.tabReselects(Route.Community).first() }

        navigator.selectTab(Route.Community)

        withTimeout(1_000) { reselected.await() }
        assertEquals(listOf<NavKey>(Route.Community), stacks.getValue(Route.Community).toList())
    }

    @Test
    fun `点别的tab只切过去_各栈原样_不发回顶事件`() = runTest {
        navigator.navigate(Route.Explore)
        navigator.navigate(Route.Search())
        navigator.navigate(Route.Home)
        val reselected = async(start = CoroutineStart.UNDISPATCHED) { navigator.tabReselects(Route.Explore).first() }

        navigator.selectTab(Route.Explore)
        testScheduler.runCurrent()

        assertEquals(Route.Explore, navigator.currentTab)
        assertEquals(listOf<NavKey>(Route.Explore, Route.Search()), stacks.getValue(Route.Explore).toList())
        assertTrue(reselected.isActive, "切 tab 不是再点一次")
        reselected.cancel()
    }
}
