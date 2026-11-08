package io.github.touhougo.chogumi.core.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.SelectionFabMenu
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 顶级页把 FAB 放进 [AboveGlassBar]：FAB 抬到玻璃底栏上面，FAB 之外的页面照样点得到。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class AboveGlassBarTest {

    @get:Rule
    val compose = createComposeRule()

    private var contentClicks = 0

    private fun page(bottom: Int) {
        compose.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalGlassContentBottom provides bottom.dp) {
                    Box(Modifier.size(360.dp, 640.dp).testTag("page")) {
                        Box(Modifier.fillMaxSize().testTag("content").clickable { contentClicks++ })
                        AboveGlassBar {
                            SelectionFabMenu(
                                expanded = false,
                                onExpandedChange = {},
                                options = listOf(1, 2),
                                selected = 1,
                                iconRes = { R.drawable.home_24 },
                                label = { "$it" },
                                onSelect = {},
                                collapsedDescription = "筛选",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun FAB之外的页面点得到() {
        page(bottom = 96)
        compose.onNodeWithTag("content").performClick()
        compose.waitForIdle()
        assertEquals(1, contentClicks)
    }

    @Test
    fun FAB抬到玻璃底栏上面() {
        page(bottom = 96)
        val pageBottom = compose.onNodeWithTag("page").getBoundsInRoot().bottom
        val fabBottom = compose.onNodeWithTag("SelectionFab").getBoundsInRoot().bottom
        assertTrue("FAB 底边 $fabBottom 应在页面底边 $pageBottom 往上 96dp 之外", fabBottom <= pageBottom - 96.dp)
    }

    @Test
    fun 玻璃关着时FAB贴着页面底边() {
        page(bottom = 0)
        val pageBottom = compose.onNodeWithTag("page").getBoundsInRoot().bottom
        val fabBottom = compose.onNodeWithTag("SelectionFab").getBoundsInRoot().bottom
        assertTrue("FAB 底边 $fabBottom 应在页面底边 $pageBottom 往上 96dp 以内", fabBottom > pageBottom - 96.dp)
    }
}
