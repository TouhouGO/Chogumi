package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.material3.ShapeDefaults
import androidx.navigation3.runtime.NavKey
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 转场的种类由 [AppNavigator.transitionKind] 按两个场景判断，排法与外壳（`AppEntries`）相同：
 * 页面区包在共享转场里，切 tab 时外层不重组。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@OptIn(ExperimentalSharedTransitionApi::class)
class TransitionKindTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navigator: AppNavigator
    private val kinds = mutableListOf<PageTransitionKind>()
    private val contentKeys = HashMap<NavKey, Any>()

    @Composable
    private fun Shell(navigator: AppNavigator) {
        val page = LocalAppMotion.current.page
        val sheets = remember { PageSheetRegistry() }
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalPageExpandRegistry provides navigator.expand) {
                NavDisplay(
                    entries = navigator.entries({ key -> NavEntry(key) { Box(Modifier.fillMaxSize()) }.also { contentKeys[key] = it.contentKey } }, sheets),
                    transitionSpec = { chogumiTransition(navigator.transitionKind(back = false, targetState, initialState).also(kinds::add), page, 1) },
                    popTransitionSpec = { chogumiTransition(navigator.transitionKind(back = true, targetState, initialState).also(kinds::add), page, 1) },
                    onBack = navigator::goBack,
                )
            }
        }
    }

    private fun setContent() {
        composeRule.setContent {
            ChogumiTheme {
                navigator = rememberAppNavigator()
                Shell(navigator)
            }
        }
        composeRule.waitForIdle()
    }

    private fun act(action: AppNavigator.() -> Unit): PageTransitionKind {
        kinds.clear()
        composeRule.runOnIdle { navigator.action() }
        composeRule.waitForIdle()
        return kinds.last()
    }

    @Test
    fun 切到别的tab后第一次点封面_就是容器转换_返回也是() {
        setContent()
        assertEquals(PageTransitionKind.TopLevel, act { navigate(Route.Explore) })

        assertEquals("切 tab 后第一次点封面", PageTransitionKind.Container, act { expand.press("cover"); navigate(Route.SubjectDetails(1)) })
        assertEquals("从它返回", PageTransitionKind.Container, act { goBack() })
        assertEquals("没点封面压进的页", PageTransitionKind.Forward, act { navigate(Route.Settings) })
        assertEquals(PageTransitionKind.Back, act { goBack() })
    }

    @Test
    fun 不同作品循环进入返回后只保留栈内登记() {
        setContent()
        repeat(12) { index ->
            val token = "cover-$index"
            val route = Route.SubjectDetails(index + 1)
            act {
                expand.press(token)
                expand.origins[token] = OriginLook(ShapeDefaults.Medium, 60f)
                navigate(route)
            }
            val contentKey = contentKeys.getValue(route)
            act { goBack() }
            assertFalse("退出完成后不再挂起点标记", navigator.expand.isActive(token))
            assertNull("退出完成后不留旧路由", navigator.expand.routeOf(Route.Home, contentKey))
            assertEquals("退出完成后起点外观释放", 0, navigator.expand.origins.size)
        }
    }

    @Test
    fun 返回离场中保留起点与路由_离场后一起释放() {
        setContent()
        val route = Route.SubjectDetails(1)
        act { expand.press("cover"); navigate(route) }
        val contentKey = contentKeys.getValue(route)
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { navigator.goBack() }
        composeRule.mainClock.advanceTimeByFrame()
        assertTrue("弹栈时起点不能消失", navigator.expand.isActive("cover"))
        assertEquals("离场仍识别容器转换", route, navigator.expand.routeOf(Route.Home, contentKey))
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertFalse("离场完成再释放起点", navigator.expand.isActive("cover"))
        assertNull(navigator.expand.routeOf(Route.Home, contentKey))
    }

    @Test
    fun 清子栈不清其他tab的同作品认领() {
        setContent()
        val route = Route.SubjectDetails(1)
        act { expand.press("home-cover"); navigate(route) }
        val homeKey = contentKeys.getValue(route)
        act { navigate(Route.Settings) }
        act { navigate(Route.Explore) }
        act { expand.press("explore-cover"); navigate(route) }
        val exploreKey = contentKeys.getValue(route)
        act { navigate(Route.Home) }
        act { selectTab(Route.Home) }
        assertNull("清掉子栈中的作品登记", navigator.expand.routeOf(Route.Home, homeKey))
        assertFalse(navigator.expand.isActive("home-cover"))
        assertEquals("别的 tab 留在栈中", route, navigator.expand.routeOf(Route.Explore, exploreKey))
        assertTrue(navigator.expand.isActive("explore-cover"))
        act { navigate(Route.Explore) }
        act { goBack() }
        assertFalse(navigator.expand.isActive("explore-cover"))
    }

    @Test
    fun 切tab_无论两条栈多深_都是切tab() {
        setContent()
        act { navigate(Route.Settings) }
        assertEquals(PageTransitionKind.TopLevel, act { navigate(Route.Explore) })
        act { expand.press("cover"); navigate(Route.SubjectDetails(1)) }
        assertEquals(PageTransitionKind.TopLevel, act { navigate(Route.Home) })
        assertEquals(PageTransitionKind.TopLevel, act { navigate(Route.Explore) })
    }
}
