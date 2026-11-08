package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真设备上的封面容器退出：返回途中保留起点，退出完成才释放登记。 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalSharedTransitionApi::class)
class PageExpandLifecycleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `循环封面进入返回保留离场起点并回收历史`() {
        lateinit var navigator: AppNavigator
        var nextId = 1
        compose.setContent {
            ChogumiTheme {
                navigator = rememberAppNavigator()
                val sheets = remember { PageSheetRegistry() }
                val page = LocalAppMotion.current.page
                SharedTransitionLayout(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalSharedTransitionScope provides this, LocalPageExpandRegistry provides navigator.expand) {
                        NavDisplay(
                            entries = navigator.entries({ route ->
                                NavEntry(route) {
                                    Box(Modifier.fillMaxSize()) {
                                        if (route == Route.Home) {
                                            SubjectPoster(
                                                url = null,
                                                modifier = Modifier.padding(64.dp).size(80.dp, 108.dp).testTag("cover"),
                                                onClick = { navigator.navigate(Route.SubjectDetails(nextId)) },
                                            )
                                        }
                                    }
                                }
                            }, sheets),
                            onBack = navigator::goBack,
                            transitionSpec = { chogumiTransition(navigator.transitionKind(false, targetState, initialState), page, 1) },
                            popTransitionSpec = { chogumiTransition(navigator.transitionKind(true, targetState, initialState), page, 1) },
                        )
                    }
                }
            }
        }
        repeat(3) { index ->
            nextId = index + 1
            compose.onNodeWithTag("cover").performTouchInput { click() }
            compose.waitForIdle()
            val token = navigator.expand.origins.keys.single()
            assertTrue(navigator.expand.isActive(token))
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { navigator.goBack() }
            compose.mainClock.advanceTimeByFrame()
            assertTrue("退出途中起点仍有效", navigator.expand.isActive(token))
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertFalse("退出完成不留认领", navigator.expand.isActive(token))
            assertEquals("退出完成不留起点外观", 0, navigator.expand.origins.size)
        }
    }
}
